package com.kotlin_livez.core.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * WebSocket 状态。
 * 对应 Dart `SocketStatus`（lib/src/common/web_socket_util.dart）。
 */
enum class SocketStatus { connected, failed, closed }

/**
 * WebSocket 工具，支持多候选地址、心跳、自动重连。
 * 对应 Dart `WebScoketUtils`（lib/src/common/web_socket_util.dart，含拼写 "WebScoket"）。
 * 本类名按 Kotlin 惯例命名 `WebSocketUtil`；功能一一对应。
 */
class WebSocketUtil(
    /** 链接 */
    val url: String,
    /** 心跳时间（毫秒） */
    val heartBeatTime: Long,
    /** 备用链接 */
    val backupUrl: String? = null,
    /** 备用链接列表 */
    val backupUrls: List<String> = emptyList(),
    /** 收到文本消息 */
    val onMessage: ((String) -> Unit)? = null,
    /** 收到二进制消息 */
    val onByteMessage: ((ByteArray) -> Unit)? = null,
    /** 连接关闭 */
    val onClose: ((String) -> Unit)? = null,
    /** 尝试重连 */
    val onReconnect: (() -> Unit)? = null,
    /** 准备就绪 */
    val onReady: (() -> Unit)? = null,
    /** 心跳 */
    val onHeartBeat: (() -> Unit)? = null,
    /** 请求头 */
    var headers: Map<String, String>? = null,
    /** 单个候选连接超时 */
    val connectTimeoutMs: Long = 10_000,
    /** 是否随机化地址顺序 */
    val shuffleUrls: Boolean = false,
    /** 每轮最多尝试数量，null=全部 */
    val maxConnectAttempts: Int? = null,
    /** 重连间隔 */
    val reconnectDelayMs: Long = 5_000,
    /** 最大重连次数 */
    var maxReconnectTime: Int = 5,
) {
    var status: SocketStatus = SocketStatus.closed
        private set

    private val scope = CoroutineScope(Dispatchers.Default)
    private var webSocket: WebSocket? = null
    private var heartBeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var connectionJob: Job? = null

    /** 重连次数 */
    var reconnectTime: Int = 0
        private set

    private val _manualClosed = AtomicBoolean(true)
    private val _connecting = AtomicBoolean(false)

    /** 去重后的候选地址（诊断/测试用）。 */
    val connectUrls: List<String>
        get() {
            val urls = mutableListOf(url)
            if (backupUrl != null && backupUrl.isNotEmpty()) urls.add(backupUrl)
            urls.addAll(backupUrls.filter { it.isNotEmpty() })
            return urls.distinct()
        }

    fun connect(retry: Boolean = false) {
        if (_connecting.get()) return
        _manualClosed.set(false)
        reconnectJob?.cancel()
        reconnectJob = null
        _connecting.set(true)
        webSocket?.close(1000, null)
        webSocket = null
        heartBeatJob?.cancel()
        heartBeatJob = null

        val urls = _connectUrls.toMutableList()
        if (shuffleUrls) urls.shuffle()
        val candidates = if (retry && urls.size > 1) urls.drop(1) else urls
        val limited = maxConnectAttempts?.let { candidates.take(it) } ?: candidates

        val job = scope.launch {
            var lastError: String? = null
            for (wsUrl in limited) {
                if (_manualClosed.get()) return@launch
                try {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                        .build()
                    val requestBuilder = Request.Builder().url(wsUrl)
                    headers?.forEach { (k, v) -> requestBuilder.header(k, v) }
                    val opened = openSocket(client, requestBuilder.build())
                    if (_manualClosed.get()) {
                        opened?.close(1000, null)
                        return@launch
                    }
                    webSocket = opened
                    ready()
                    return@launch
                } catch (e: Exception) {
                    lastError = e.message ?: "WebSocket connection failed"
                }
            }
            if (!_manualClosed.get()) {
                onError(lastError ?: "WebSocket connection failed")
            }
        }
        connectionJob = job
        job.invokeOnCompletion { _connecting.set(false) }
    }

    private suspend fun openSocket(client: OkHttpClient, request: Request): WebSocket =
        suspendCancellableCoroutine { cont ->
            var established = false
            val attemptListener = object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    if (!established) {
                        established = true
                        if (cont.isActive) cont.resume(ws)
                    }
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    reconnectTime = 0
                    onMessage?.invoke(text)
                }

                override fun onMessage(ws: WebSocket, bytes: okio.ByteString) {
                    reconnectTime = 0
                    onByteMessage?.invoke(bytes.toByteArray())
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    if (established) onDone()
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    if (established) {
                        onError(t.message ?: "WebSocket failure")
                    } else if (cont.isActive) {
                        cont.resumeWithException(t)
                    }
                }
            }
            val socket = client.newWebSocket(request, attemptListener)
            cont.invokeOnCancellation { socket.cancel() }
        }

    /** 连接完成 */
    private fun ready() {
        status = SocketStatus.connected
        heartBeatJob?.cancel()
        startHeartBeat()
        onReady?.invoke()
    }

    private fun startHeartBeat() {
        if (heartBeatTime <= 0) return
        heartBeatJob = scope.launch {
            while (true) {
                delay(heartBeatTime)
                onHeartBeat?.invoke()
            }
        }
    }

    fun sendMessage(message: String) {
        if (status == SocketStatus.connected) {
            webSocket?.send(message)
        }
    }

    fun sendBytes(bytes: ByteArray) {
        if (status == SocketStatus.connected) {
            webSocket?.send(okio.ByteString.of(*bytes))
        }
    }

    fun close() {
        _manualClosed.set(true)
        status = SocketStatus.closed
        reconnectJob?.cancel()
        reconnectJob = null
        connectionJob?.cancel()
        webSocket?.close(1000, null)
        webSocket = null
        heartBeatJob?.cancel()
        heartBeatJob = null
    }

    private fun onError(e: String) {
        status = SocketStatus.failed
        onClose?.invoke(e)
    }

    fun onDone() {
        if (status == SocketStatus.closed) return
        onReconnect?.invoke()
        reconnect()
    }

    fun reconnect() {
        if (_manualClosed.get() || reconnectJob != null) return
        status = SocketStatus.closed
        if (reconnectTime < maxReconnectTime) {
            reconnectTime++
            reconnectJob = scope.launch {
                delay(reconnectDelayMs)
                reconnectJob = null
                connect()
            }
        } else {
            onClose?.invoke("重连超过最大次数，与服务器断开连接")
            reconnectJob?.cancel()
            reconnectJob = null
            close()
        }
    }

    private val _connectUrls: List<String> get() = connectUrls
}
