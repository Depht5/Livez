package com.kotlin_livez.core.common

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** WebSocket 状态。 */
enum class SocketStatus { connected, failed, closed }

/**
 * WebSocket 工具，支持多候选地址、心跳、业务健康检查和自动重连。
 *
 * 每个底层 socket 都有独立 [Attempt] 身份。迟到的旧 listener、watchdog 或重连任务
 * 只能结束自己的 attempt，不能清空、标记健康或复活一条新连接。实例 close 后不可复用。
 */
class WebSocketUtil(
    val url: String,
    val heartBeatTime: Long,
    val backupUrl: String? = null,
    val backupUrls: List<String> = emptyList(),
    val onMessage: ((String) -> Unit)? = null,
    val onByteMessage: ((ByteArray) -> Unit)? = null,
    val onClose: ((String) -> Unit)? = null,
    val onReconnect: (() -> Unit)? = null,
    val onReady: (() -> Unit)? = null,
    val onHeartBeat: (() -> Unit)? = null,
    var headers: Map<String, String>? = null,
    val connectTimeoutMs: Long = 10_000,
    val shuffleUrls: Boolean = false,
    val maxConnectAttempts: Int? = null,
    val reconnectDelayMs: Long = 5_000,
    var maxReconnectTime: Int = 5,
    val pingIntervalMs: Long = 30_000,
    /**
     * 是否协商 RFC 7692 permessage-deflate。
     *
     * OkHttp 4.x 会无条件为 WebSocket 握手添加 `Sec-WebSocket-Extensions: permessage-deflate`，
     * 且公开 API 只能关闭出站压缩，不能关闭入站解压。部分服务端会返回使 Inflater 无法推进的
     * 压缩帧；此时 reader 线程会永久占满 CPU，连 cancel/切房也无法及时结束。关闭时在真正
     * 发往网络前移除该握手头，让服务端发送未压缩帧。
     */
    val webSocketCompressionEnabled: Boolean = true,
    /** 建连后首条业务下行的等待上限；0 表示禁用。 */
    val firstMessageTimeoutMs: Long = 0,
    /** 已收到业务下行后允许的最长静默时间；0 表示禁用。 */
    val messageIdleTimeoutMs: Long = 0,
    /** 是否把任意原始帧视为业务健康；有控制帧的平台应关闭并显式标记。 */
    val autoMarkHealthyOnMessage: Boolean = true,
) {
    private class Attempt(val id: Long) {
        val established = AtomicBoolean(false)
        val terminal = AtomicBoolean(false)
        val healthy = AtomicBoolean(false)
        val lastBusinessAtNanos = AtomicLong(0)

        @Volatile
        var socket: WebSocket? = null
    }

    @Volatile
    var status: SocketStatus = SocketStatus.closed
        private set

    private val stateLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .apply {
            if (pingIntervalMs > 0) pingInterval(pingIntervalMs, TimeUnit.MILLISECONDS)
            if (!webSocketCompressionEnabled) {
                // RealWebSocket 在创建 Call 之前注入扩展头。应用拦截器位于该 Call 的最外层，
                // 可以在 Retry/Bridge/Connect 写出请求头之前可靠移除；WebSocket 升级不会走
                // 普通的 network interceptor 链，因此不能在这里使用 addNetworkInterceptor。
                addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .removeHeader("Sec-WebSocket-Extensions")
                        .build()
                    chain.proceed(request)
                }
            }
        }
        .build()
    private val started = AtomicBoolean(false)
    private val disposed = AtomicBoolean(false)
    private val manualClosed = AtomicBoolean(true)
    private val nextAttemptId = AtomicLong(0)
    private val callbackAttempt = ThreadLocal<Attempt?>()

    @Volatile
    private var activeAttempt: Attempt? = null
    private var heartBeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var connectionJob: Job? = null
    private var firstMessageJob: Job? = null
    private var messageIdleJob: Job? = null

    @Volatile
    var reconnectTime: Int = 0
        private set

    /** 去重后的候选地址（诊断/测试用）。 */
    val connectUrls: List<String>
        get() = buildList {
            add(url)
            if (!backupUrl.isNullOrEmpty()) add(backupUrl)
            addAll(backupUrls.filter { it.isNotEmpty() })
        }.distinct()

    /** 开始连接。本工具是 one-shot；close 后的 connect 会被忽略。 */
    fun connect(retry: Boolean = false) {
        if (disposed.get() || !started.compareAndSet(false, true)) return
        manualClosed.set(false)
        startConnectCycle(retry)
    }

    private fun startConnectCycle(retry: Boolean) {
        if (disposed.get() || manualClosed.get()) return
        lateinit var newJob: Job
        newJob = scope.launch(start = CoroutineStart.LAZY) { runConnectCycle(retry) }
        val shouldStart = synchronized(stateLock) {
            if (disposed.get() || manualClosed.get()) {
                false
            } else {
                connectionJob?.cancel()
                connectionJob = newJob
                true
            }
        }
        if (!shouldStart) {
            newJob.cancel()
            return
        }
        newJob.invokeOnCompletion {
            synchronized(stateLock) {
                if (connectionJob === newJob) connectionJob = null
            }
        }
        newJob.start()
    }

    private suspend fun runConnectCycle(retry: Boolean) {
        val urls = connectUrls.toMutableList()
        if (shuffleUrls) urls.shuffle()
        val candidates = if (retry && urls.size > 1) urls.drop(1) else urls
        val limited = maxConnectAttempts?.let { candidates.take(it.coerceAtLeast(1)) } ?: candidates
        var lastError = "WebSocket connection failed"
        var lastAttempt: Attempt? = null

        for (wsUrl in limited) {
            if (!currentCoroutineContext().isActive || disposed.get() || manualClosed.get()) return
            val attempt = installAttempt() ?: return
            lastAttempt = attempt
            CoreLog.d("WS 连接：$wsUrl (重连第 $reconnectTime 次, attempt=${attempt.id})")
            try {
                val requestBuilder = Request.Builder().url(wsUrl)
                headers?.forEach { (key, value) -> requestBuilder.header(key, value) }
                openSocket(requestBuilder.build(), attempt)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message ?: "WebSocket connection failed"
                if (!isCurrent(attempt)) return
            }
        }

        lastAttempt?.let { onConnectionLost(it, null, lastError) }
    }

    private fun installAttempt(): Attempt? {
        val next = Attempt(nextAttemptId.incrementAndGet())
        var oldSocket: WebSocket? = null
        synchronized(stateLock) {
            if (disposed.get() || manualClosed.get()) return null
            activeAttempt?.let { old ->
                old.terminal.set(true)
                oldSocket = old.socket
                old.socket = null
            }
            activeAttempt = next
            status = SocketStatus.closed
            cancelTransportJobsLocked()
        }
        oldSocket?.cancel()
        return next
    }

    private suspend fun openSocket(request: Request, attempt: Attempt): WebSocket =
        suspendCancellableCoroutine { continuation ->
            val preOpenTerminal = AtomicBoolean(false)
            val listener = object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    if (!activateOpenedAttempt(attempt, ws)) {
                        ws.cancel()
                        if (continuation.isActive) {
                            continuation.resumeWithException(IOException("stale WebSocket attempt"))
                        }
                        return
                    }
                    ready(attempt)
                    if (continuation.isActive) continuation.resume(ws)
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    if (!isCurrent(attempt, ws)) return
                    try {
                        withCallbackAttempt(attempt) {
                            if (autoMarkHealthyOnMessage) markHealthy(attempt)
                            onMessage?.invoke(text)
                        }
                    } catch (e: Exception) {
                        CoreLog.e("WS 文本回调失败: attempt=${attempt.id}", e)
                        onConnectionLost(attempt, ws, "message callback failed: ${e.message}")
                    }
                }

                override fun onMessage(ws: WebSocket, bytes: okio.ByteString) {
                    if (!isCurrent(attempt, ws)) return
                    try {
                        withCallbackAttempt(attempt) {
                            if (autoMarkHealthyOnMessage) markHealthy(attempt)
                            onByteMessage?.invoke(bytes.toByteArray())
                        }
                    } catch (e: Exception) {
                        CoreLog.e("WS 二进制回调失败: attempt=${attempt.id}", e)
                        onConnectionLost(attempt, ws, "binary callback failed: ${e.message}")
                    }
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    CoreLog.d("WS onClosing: code=$code reason=$reason attempt=${attempt.id}")
                    ws.close(code, reason)
                    handleTerminal(attempt, ws, "closing($code): $reason", preOpenTerminal, continuation)
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    CoreLog.d("WS onClosed: code=$code reason=$reason attempt=${attempt.id}")
                    handleTerminal(attempt, ws, "closed($code): $reason", preOpenTerminal, continuation)
                }

                override fun onFailure(ws: WebSocket, failure: Throwable, response: Response?) {
                    CoreLog.w("WS onFailure: ${failure.message} attempt=${attempt.id}")
                    handleTerminal(
                        attempt,
                        ws,
                        failure.message ?: "WebSocket failure",
                        preOpenTerminal,
                        continuation,
                    )
                }
            }
            val socket = client.newWebSocket(request, listener)
            continuation.invokeOnCancellation { socket.cancel() }
        }

    private fun handleTerminal(
        attempt: Attempt,
        socket: WebSocket,
        reason: String,
        preOpenTerminal: AtomicBoolean,
        continuation: CancellableContinuation<WebSocket>,
    ) {
        if (attempt.established.get()) {
            onConnectionLost(attempt, socket, reason)
        } else if (preOpenTerminal.compareAndSet(false, true) && continuation.isActive) {
            continuation.resumeWithException(IOException(reason))
        }
    }

    private fun activateOpenedAttempt(attempt: Attempt, socket: WebSocket): Boolean =
        synchronized(stateLock) {
            if (disposed.get() || manualClosed.get() || activeAttempt !== attempt || attempt.terminal.get()) {
                false
            } else {
                attempt.socket = socket
                attempt.established.set(true)
                status = SocketStatus.connected
                true
            }
        }

    /** onOpen 内直接 ready，避免首帧在初始化 watchdog 之前到达的竞态。 */
    private fun ready(attempt: Attempt) {
        if (!isCurrent(attempt, attempt.socket)) return
        CoreLog.d("WS 已连接：$url attempt=${attempt.id}")
        startHeartBeat(attempt)
        startFirstMessageWatchdog(attempt)
        if (!isCurrent(attempt, attempt.socket)) return
        try {
            withCallbackAttempt(attempt) { onReady?.invoke() }
        } catch (e: Exception) {
            CoreLog.e("WS ready 回调失败: attempt=${attempt.id}", e)
            onConnectionLost(attempt, attempt.socket, "ready callback failed: ${e.message}")
        }
    }

    private fun startHeartBeat(attempt: Attempt) {
        if (heartBeatTime <= 0) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            while (currentCoroutineContext().isActive) {
                delay(heartBeatTime)
                if (!isCurrent(attempt, attempt.socket)) return@launch
                CoreLog.d("WS 心跳发送 attempt=${attempt.id}")
                try {
                    withCallbackAttempt(attempt) { onHeartBeat?.invoke() }
                } catch (e: Exception) {
                    CoreLog.e("WS 心跳回调失败: attempt=${attempt.id}", e)
                    onConnectionLost(attempt, attempt.socket, "heartbeat callback failed: ${e.message}")
                    return@launch
                }
            }
        }
        val shouldStart = synchronized(stateLock) {
            if (!isCurrentLocked(attempt, attempt.socket)) {
                false
            } else {
                heartBeatJob?.cancel()
                heartBeatJob = job
                true
            }
        }
        if (shouldStart) job.start() else job.cancel()
    }

    /**
     * 标记当前回调所属 attempt 的真正业务消息到达。
     * 虎牙 type 2 注册响应和 type 21 心跳 ACK 在进房参数错误时也会到达，
     * 因此不能在原始帧层把它们当成订阅成功。
     */
    fun markMessageHealthy() {
        // 只允许正在 listener 回调中的 attempt 标记自己；迟到的异步旧回调
        // 在 ThreadLocal 离开后不能把已经更换的新 socket 误标为健康。
        val attempt = callbackAttempt.get() ?: return
        markHealthy(attempt)
    }

    private fun markHealthy(attempt: Attempt) {
        var firstJob: Job? = null
        synchronized(stateLock) {
            if (!isCurrentLocked(attempt, attempt.socket)) return
            reconnectTime = 0
            attempt.lastBusinessAtNanos.set(System.nanoTime())
            if (attempt.healthy.compareAndSet(false, true)) {
                firstJob = firstMessageJob
                firstMessageJob = null
                CoreLog.d("WS 首条业务消息到达 attempt=${attempt.id}")
            }
        }
        firstJob?.cancel()
        ensureMessageIdleWatchdog(attempt)
    }

    private fun startFirstMessageWatchdog(attempt: Attempt) {
        if (firstMessageTimeoutMs <= 0 || attempt.healthy.get()) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(firstMessageTimeoutMs)
            if (!attempt.healthy.get() && isCurrent(attempt, attempt.socket)) {
                CoreLog.w("WS 首条业务消息等待超时：${firstMessageTimeoutMs}ms attempt=${attempt.id}")
                onConnectionLost(attempt, attempt.socket, "first business message timeout")
            }
        }
        val shouldStart = synchronized(stateLock) {
            if (!isCurrentLocked(attempt, attempt.socket) || attempt.healthy.get()) {
                false
            } else {
                firstMessageJob?.cancel()
                firstMessageJob = job
                true
            }
        }
        if (shouldStart) job.start() else job.cancel()
    }

    /** 每个 attempt 只起一个滚动检查任务，避免高流量房间每帧 cancel/launch Job。 */
    private fun ensureMessageIdleWatchdog(attempt: Attempt) {
        if (messageIdleTimeoutMs <= 0) return
        val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(messageIdleTimeoutMs)
        val job = synchronized(stateLock) {
            if (!isCurrentLocked(attempt, attempt.socket) || messageIdleJob != null) return
            scope.launch(start = CoroutineStart.LAZY) {
                while (currentCoroutineContext().isActive && isCurrent(attempt, attempt.socket)) {
                    val elapsed = System.nanoTime() - attempt.lastBusinessAtNanos.get()
                    val remainingNanos = timeoutNanos - elapsed
                    if (remainingNanos <= 0L) {
                        CoreLog.w("WS 业务消息静默超时：${messageIdleTimeoutMs}ms attempt=${attempt.id}")
                        onConnectionLost(attempt, attempt.socket, "business message idle timeout")
                        return@launch
                    }
                    delay(TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L))
                }
            }.also { messageIdleJob = it }
        }
        job.start()
    }

    fun sendMessage(message: String): Boolean {
        val attempt = callbackAttempt.get() ?: activeAttempt ?: return false
        return isCurrent(attempt, attempt.socket) && attempt.socket?.send(message) == true
    }

    fun sendBytes(bytes: ByteArray): Boolean {
        val attempt = callbackAttempt.get() ?: activeAttempt ?: return false
        return isCurrent(attempt, attempt.socket) &&
            attempt.socket?.send(okio.ByteString.of(*bytes)) == true
    }

    /** 主动淘汰当前回调所属的「传输已打开、业务不可用」连接。 */
    fun forceReconnect(reason: String) {
        val attempt = callbackAttempt.get() ?: return
        onConnectionLost(attempt, attempt.socket, reason)
    }

    private fun onConnectionLost(attempt: Attempt, source: WebSocket?, reason: String) {
        var socketToCancel: WebSocket? = null
        val accepted = synchronized(stateLock) {
            if (disposed.get() || manualClosed.get() || activeAttempt !== attempt ||
                (source != null && attempt.socket != null && attempt.socket !== source)
            ) {
                false
            } else if (!attempt.terminal.compareAndSet(false, true)) {
                false
            } else {
                socketToCancel = attempt.socket ?: source
                attempt.socket = null
                status = SocketStatus.failed
                cancelTransportJobsLocked()
                true
            }
        }
        if (!accepted) return

        socketToCancel?.cancel()
        CoreLog.w("WS 连接失效：$reason attempt=${attempt.id}")
        runCatching { onReconnect?.invoke() }
            .onFailure { CoreLog.e("WS reconnect 回调失败", it) }
        scheduleReconnect(attempt)
    }

    private fun scheduleReconnect(failedAttempt: Attempt) {
        var exhausted = false
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            delay(reconnectDelayMs)
            val mayReconnect = synchronized(stateLock) {
                if (disposed.get() || manualClosed.get() || activeAttempt !== failedAttempt ||
                    !failedAttempt.terminal.get() || reconnectJob !== job
                ) {
                    false
                } else {
                    reconnectJob = null
                    true
                }
            }
            if (mayReconnect) startConnectCycle(retry = false)
        }

        val shouldStart = synchronized(stateLock) {
            if (disposed.get() || manualClosed.get() || activeAttempt !== failedAttempt || reconnectJob != null) {
                false
            } else if (reconnectTime < maxReconnectTime) {
                reconnectTime++
                status = SocketStatus.closed
                reconnectJob = job
                true
            } else {
                exhausted = true
                false
            }
        }

        if (shouldStart) {
            job.start()
        } else {
            job.cancel()
            if (exhausted) {
                CoreLog.w("WS 重连超过 $maxReconnectTime 次，与服务器断开连接")
                runCatching { onClose?.invoke("重连超过最大次数，与服务器断开连接") }
                    .onFailure { CoreLog.e("WS close 回调失败", it) }
                close()
            }
        }
    }

    /** 兼容旧入口：将当前 attempt 立即置为失效并走统一重连流程。 */
    fun reconnect() {
        val attempt = activeAttempt ?: return
        onConnectionLost(attempt, attempt.socket, "manual reconnect")
    }

    fun onDone() {
        val attempt = callbackAttempt.get() ?: activeAttempt ?: return
        onConnectionLost(attempt, attempt.socket, "connection closed")
    }

    fun close() {
        if (!disposed.compareAndSet(false, true)) return
        manualClosed.set(true)
        var socket: WebSocket? = null
        var jobs: List<Job> = emptyList()
        synchronized(stateLock) {
            activeAttempt?.terminal?.set(true)
            socket = activeAttempt?.socket
            activeAttempt?.socket = null
            activeAttempt = null
            status = SocketStatus.closed
            jobs = listOfNotNull(
                reconnectJob,
                connectionJob,
                heartBeatJob,
                firstMessageJob,
                messageIdleJob,
            )
            reconnectJob = null
            connectionJob = null
            heartBeatJob = null
            firstMessageJob = null
            messageIdleJob = null
        }
        jobs.forEach { it.cancel() }
        socket?.cancel()
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        scope.cancel()
    }

    private fun cancelTransportJobsLocked() {
        heartBeatJob?.cancel()
        heartBeatJob = null
        firstMessageJob?.cancel()
        firstMessageJob = null
        messageIdleJob?.cancel()
        messageIdleJob = null
    }

    private fun isCurrent(attempt: Attempt, socket: WebSocket? = null): Boolean =
        synchronized(stateLock) { isCurrentLocked(attempt, socket) }

    private fun isCurrentLocked(attempt: Attempt, socket: WebSocket?): Boolean {
        if (disposed.get() || manualClosed.get() || activeAttempt !== attempt || attempt.terminal.get()) {
            return false
        }
        val activeSocket = attempt.socket
        return socket == null || activeSocket == null || activeSocket === socket
    }

    private inline fun <T> withCallbackAttempt(attempt: Attempt, block: () -> T): T {
        val previous = callbackAttempt.get()
        callbackAttempt.set(attempt)
        return try {
            block()
        } finally {
            if (previous == null) callbackAttempt.remove() else callbackAttempt.set(previous)
        }
    }
}
