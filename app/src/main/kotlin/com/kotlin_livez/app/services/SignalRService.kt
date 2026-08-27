package com.kotlin_livez.app.services

import com.kotlin_livez.app.app.Log
import com.kotlin_livez.app.di.ServiceContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 远程房间同步服务 —— 对应 Dart `SignalRService`
 * （lib/services/signalr_service.dart, 695 行）。
 * 私有 WebSocket JSON RPC（非标准 SignalR hub 帧）：{type, requestId, roomId?, payload?}，
 * 服务端按 requestId 回包（roomCreated/roomJoined/ack/pong/...）或广播推送
 * （favoriteReceived/historyReceived/shieldWordReceived/biliAccountReceived/roomDestroyed/userUpdated）。
 */
class SignalRService {

    companion object {
        const val kRoomIdLength = 6
        const val kDefaultUrl = "wss://sync.furry.mo.cn/sync"
        const val kCloudflareUrl = "wss://simple-live-sync.3439394104.workers.dev/sync"
        const val kDefaultServerOption = "自建服务器（默认）"
        const val kCloudflareServerOption = "Cloudflare Worker（备用）"
        const val kCustomServerOption = "自定义地址"
        val kServerOptions = listOf(kDefaultServerOption, kCloudflareServerOption, kCustomServerOption)
        const val kDefaultLocalProxy = "51888"
        const val kDirectProxyValue = "direct"

        val configuredValue: String
            get() = ServiceContainer.localStorage.getValue(LocalStorageService.kSyncServerUrl, "").trim()

        val configuredUrl: String
            get() = configuredValue.ifEmpty { kDefaultUrl }

        val configuredServerOption: String
            get() = when (configuredUrl) {
                kDefaultUrl -> kDefaultServerOption
                kCloudflareUrl -> kCloudflareServerOption
                else -> kCustomServerOption
            }

        fun isValidServerUrl(value: String): Boolean {
            val uri = runCatching { java.net.URI(value.trim()) }.getOrNull() ?: return false
            return (uri.scheme == "wss" || uri.scheme == "ws") && !uri.host.isNullOrEmpty()
        }

        /** 探测服务器连通性（对应 Dart probeServer）。 */
        suspend fun probeServer(
            value: String,
            forceDirect: Boolean = false,
        ): SyncServerProbeResult {
            val url = value.trim()
            if (!isValidServerUrl(url)) {
                return SyncServerProbeResult.notConfigured()
            }
            val client = buildClient(forceDirect = forceDirect, probe = true)
            return try {
                val start = System.currentTimeMillis()
                val result = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                    val requestId = "connectivity_probe"
                    val ws = client.newWebSocket(
                        Request.Builder().url(url).build(),
                        object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                webSocket.send(
                                    buildJsonObject {
                                        put("type", "ping")
                                        put("requestId", requestId)
                                    }.toString(),
                                )
                            }

                            override fun onMessage(webSocket: WebSocket, text: String) {
                                try {
                                    val obj = Json.parseToJsonElement(text).jsonObject
                                    if (obj["type"]?.strTrim() == "pong" &&
                                        obj["requestId"]?.strTrim() == requestId
                                    ) {
                                        if (!cont.isCancelled) cont.resume(Unit)
                                    }
                                } catch (_: Exception) {
                                }
                            }

                            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                                if (!cont.isCancelled) cont.resumeWith(Result.failure(t))
                            }
                        },
                    )
                    cont.invokeOnCancellation { ws.close(1000, "cancel") }
                }
                val latency = System.currentTimeMillis() - start
                SyncServerProbeResult.reachable(latency)
            } catch (_: Exception) {
                SyncServerProbeResult.unreachable()
            } finally {
                client.dispatcher.executorService.shutdown()
            }
        }

        fun setConfiguredUrl(value: String) {
            ServiceContainer.localStorage.setValue(LocalStorageService.kSyncServerUrl, value.trim())
        }

        val configuredProxyUrl: String
            get() {
                val stored = ServiceContainer.localStorage.getValue(LocalStorageService.kSyncProxyUrl, "").toString()
                return normalizeProxyPort(stored) ?: ""
            }

        val proxyDisplayName: String
            get() {
                val value = configuredProxyUrl
                return when {
                    value.isEmpty() || value.lowercase() == kDirectProxyValue -> "直连"
                    else -> "本地代理端口 $value"
                }
            }

        fun setConfiguredProxyUrl(value: String) {
            ServiceContainer.localStorage.setValue(
                LocalStorageService.kSyncProxyUrl,
                normalizeProxyPort(value) ?: "",
            )
        }

        fun isValidProxyConfig(value: String): Boolean {
            val text = value.trim()
            if (text.isEmpty() || text.lowercase() == kDirectProxyValue) return true
            val port = text.toIntOrNull()
            return port != null && port in 1..65535
        }

        private fun normalizeProxyPort(value: String): String? {
            var text = value.trim()
            if (text.isEmpty() || text.lowercase() == kDirectProxyValue) return null
            val directPort = text.toIntOrNull()
            if (directPort != null && directPort in 1..65535) {
                return directPort.toString()
            }
            // 迁移旧 host:port / URL 格式为纯端口
            if (!text.contains("://")) {
                val parts = text.split(":")
                if (parts.size == 2) {
                    val port = parts[1].toIntOrNull()
                    if (port != null && port in 1..65535) return port.toString()
                }
                return null
            }
            val uri = runCatching { java.net.URI(text) }.getOrNull() ?: return null
            if (uri.scheme != "http" && uri.scheme != "https") return null
            if (uri.host.isNullOrEmpty() || uri.port == -1) return null
            return uri.port.toString()
        }

        /** 构建 WebSocket OkHttpClient（本地代理端口支持）。 */
        internal fun buildClient(forceDirect: Boolean = false, probe: Boolean = false): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .pingInterval(if (probe) 4 else 20, TimeUnit.SECONDS)
                .connectTimeout(if (probe) 8 else 15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
            if (forceDirect) {
                builder.proxy(Proxy.NO_PROXY)
            } else {
                val port = configuredProxyUrl.toIntOrNull()
                if (port != null && port in 1..65535) {
                    Log.d("远程同步使用代理: 127.0.0.1:$port")
                    builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
                }
            }
            return builder.build()
        }

        /** JsonPrimitive 文本取值（companion 内可见，类体同样可见）。 */
        private fun JsonElement?.strTrim(): String? =
            (this as? JsonPrimitive)?.content?.trim()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(SignalRConnectionState.disconnected)
    val state: StateFlow<SignalRConnectionState> = _state

    private val _onFavorite = MutableSharedFlow<RoomSyncPayload>(extraBufferCapacity = 64)
    val onFavorite: SharedFlow<RoomSyncPayload> = _onFavorite

    private val _onHistory = MutableSharedFlow<RoomSyncPayload>(extraBufferCapacity = 64)
    val onHistory: SharedFlow<RoomSyncPayload> = _onHistory

    private val _onShieldWord = MutableSharedFlow<RoomSyncPayload>(extraBufferCapacity = 64)
    val onShieldWord: SharedFlow<RoomSyncPayload> = _onShieldWord

    private val _onBiliAccount = MutableSharedFlow<RoomSyncPayload>(extraBufferCapacity = 64)
    val onBiliAccount: SharedFlow<RoomSyncPayload> = _onBiliAccount

    private val _onRoomDestroyed = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val onRoomDestroyed: SharedFlow<String> = _onRoomDestroyed

    private val _onRoomUserUpdated = MutableSharedFlow<List<RoomUser>>(extraBufferCapacity = 64)
    val onRoomUserUpdated: SharedFlow<List<RoomUser>> = _onRoomUserUpdated

    val hubConnection = SignalRConnectionInfo()

    /** UI 层注入：toast 与同步进度（对应 Dart SmartDialog.showToast / SyncProgressDialog）。 */
    var onToast: ((String) -> Unit)? = null
    var onProgress: ((SyncProgress) -> Unit)? = null

    private var webSocket: WebSocket? = null
    private var httpClient: OkHttpClient? = null
    private var requestIdCounter = 0
    private var currentRoomId = ""
    private var heartbeatJob: kotlinx.coroutines.Job? = null
    private val pendingMutex = Mutex()
    private val pendingRequests = HashMap<String, PendingRequest>()

    /** 连接（对应 Dart connect）。 */
    suspend fun connect() {
        disconnect()
        _state.value = SignalRConnectionState.connecting
        val client = buildClient()
        httpClient = client
        try {
            val result = kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                val ws = client.newWebSocket(
                    Request.Builder().url(configuredUrl).build(),
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            if (!cont.isCancelled) cont.resume(Unit)
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            handleMessage(text)
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            if (!cont.isCancelled) cont.resumeWith(Result.failure(t))
                            else handleSocketError(t)
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            handleSocketDone()
                        }
                    },
                )
                webSocket = ws
                cont.invokeOnCancellation { runCatching { ws.close(1000, "cancel") } }
            }
            result
            startHeartbeat()
            _state.value = SignalRConnectionState.connected
        } catch (e: Exception) {
            Log.logPrint(e)
            cleanupSocket()
            _state.value = SignalRConnectionState.disconnected
            throw Exception(formatConnectionError(e))
        }
    }

    fun disconnect() {
        cleanupSocket()
        _state.value = SignalRConnectionState.disconnected
    }

    private fun cleanupSocket() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        runCatching { webSocket?.close(1000, "bye") }
        webSocket = null
        runCatching { httpClient?.dispatcher?.executorService?.shutdown() }
        httpClient = null
    }

    /** 创建房间（对应 Dart createRoom）。 */
    suspend fun createRoom(): Resp<String> {
        val resp = sendRequest<String>(
            type = "createRoom",
            payload = clientInfo(),
            successTypes = setOf("roomCreated"),
            dataReader = { it["roomId"]?.strTrim() },
        )
        if (resp.isSuccess && resp.data?.isNotEmpty() == true) {
            currentRoomId = resp.data ?: ""
        }
        return resp
    }

    /** 加入房间（对应 Dart joinRoom）。 */
    suspend fun joinRoom(roomId: String): Resp<String> {
        val safeRoomId = roomId.trim().uppercase()
        val resp = sendRequest<String>(
            type = "joinRoom",
            roomId = safeRoomId,
            payload = clientInfo(),
            successTypes = setOf("roomJoined"),
        )
        if (resp.isSuccess) {
            currentRoomId = safeRoomId
        }
        return resp
    }

    /** 发送内容（对应 Dart sendContent）。 */
    suspend fun sendContent(
        roomName: String,
        action: String,
        overlay: Boolean,
        content: String,
        extraPayload: Map<String, Any> = emptyMap(),
    ): Resp<String> {
        return sendRequest(
            type = mapSendAction(action),
            roomId = if (roomName.trim().isEmpty()) currentRoomId else roomName.trim(),
            payload = buildJsonObject {
                put("overlay", overlay)
                put("content", content)
                for ((k, v) in extraPayload) {
                    when (v) {
                        is String -> put(k, v)
                        is Int -> put(k, v)
                        is Long -> put(k, v)
                        is Boolean -> put(k, v)
                    }
                }
            },
            successTypes = setOf("ack"),
        )
    }

    fun dispose() {
        pendingMutexRun {
            for (pending in pendingRequests.values) {
                pending.completeError("连接已关闭")
            }
            pendingRequests.clear()
        }
        heartbeatJob?.cancel()
        runCatching { webSocket?.close(1000, "bye") }
        webSocket = null
    }

    /* ---------- 请求-响应 ---------- */

    private suspend fun <T> sendRequest(
        type: String,
        roomId: String? = null,
        payload: JsonObject? = null,
        successTypes: Set<String>,
        dataReader: ((JsonObject) -> T?)? = null,
    ): Resp<T> {
        if (_state.value != SignalRConnectionState.connected || webSocket == null) {
            throw Exception("not connected")
        }
        val requestId = (++requestIdCounter).toString()
        val message = buildJsonObject {
            put("type", type)
            put("requestId", requestId)
            if (!roomId.isNullOrEmpty()) put("roomId", roomId)
            if (payload != null) put("payload", payload)
        }
        val completion = PendingRequest(successTypes)
        pendingMutexRun {
            pendingRequests[requestId] = completion
        }
        val sent = webSocket?.send(message.toString()) ?: false
        if (!sent) {
            pendingMutexRun { pendingRequests.remove(requestId) }
            throw Exception("websocket send failed")
        }
        val data = withTimeoutOrNull(15_000) { completion.await() }
            ?: run {
                pendingMutexRun { pendingRequests.remove(requestId) }
                Resp(false, "同步服务响应超时", null as JsonObject?)
            }
        if (!data.isSuccess) {
            return Resp(false, data.message, null)
        }
        val msg = data.data
        val typeName = msg?.get("type")?.strTrim() ?: ""
        if (!successTypes.contains(typeName)) {
            return Resp(false, "同步服务返回异常：$typeName", null)
        }
        val result = msg?.let { dataReader?.invoke(it) }
        return Resp(true, "", result)
    }

    private fun handleMessage(text: String) {
        try {
            val message = json.parseToJsonElement(text) as? JsonObject ?: return
            val type = message["type"]?.strTrim() ?: ""
            val requestId = message["requestId"]?.strTrim() ?: ""
            if (requestId.isNotEmpty()) {
                val pending = pendingMutexRun {
                    val p = pendingRequests.remove(requestId)
                    p
                }
                if (pending != null) {
                    if (type == "error") {
                        pending.completeError(readErrorMessage(message))
                    } else {
                        pending.completeSuccess(message)
                    }
                    return
                }
            }
            when (type) {
                "favoriteReceived" -> emitBoolString(message, _onFavorite)
                "historyReceived" -> emitBoolString(message, _onHistory)
                "shieldWordReceived" -> emitBoolString(message, _onShieldWord)
                "biliAccountReceived" -> emitBoolString(message, _onBiliAccount)
                "roomDestroyed" -> scope.launch { _onRoomDestroyed.emit(message["reason"]?.strTrim() ?: "") }
                "userUpdated" -> {
                    val users = message["users"]
                    val roomUsers = if (users is kotlinx.serialization.json.JsonArray) {
                        users.map { RoomUser.fromObject(it) }
                    } else {
                        emptyList()
                    }
                    for (user in roomUsers) {
                        if (user.isSelf) {
                            hubConnection.connectionId = user.connectionId
                            break
                        }
                    }
                    scope.launch { _onRoomUserUpdated.emit(roomUsers) }
                }
            }
        } catch (e: Exception) {
            Log.logPrint(e)
        }
    }

    private fun emitBoolString(
        message: JsonObject,
        flow: MutableSharedFlow<RoomSyncPayload>,
    ) {
        val payload = message["payload"] as? JsonObject ?: return
        scope.launch { flow.emit(RoomSyncPayload.fromObject(payload)) }
    }

    private fun handleSocketError(error: Throwable) {
        Log.logPrint(error)
        completePendingWithError("同步服务连接失败：${formatConnectionError(error)}")
        _state.value = SignalRConnectionState.disconnected
    }

    private fun handleSocketDone() {
        completePendingWithError("同步服务连接已断开")
        _state.value = SignalRConnectionState.disconnected
    }

    private fun completePendingWithError(message: String) {
        pendingMutexRun {
            for (pending in pendingRequests.values) {
                pending.completeError(message)
            }
            pendingRequests.clear()
        }
    }

    /* ---------- 心跳 ---------- */

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (_state.value == SignalRConnectionState.connected) {
                delay(20_000)
                val ws = webSocket
                if (_state.value == SignalRConnectionState.connected && ws != null) {
                    ws.send(
                        buildJsonObject {
                            put("type", "ping")
                            put("requestId", "ping_${System.currentTimeMillis()}")
                        }.toString(),
                    )
                }
            }
        }
    }

    /* ---------- 工具 ---------- */

    private fun clientInfo(): JsonObject = buildJsonObject {
        put("app", "Simple Live")
        put("platform", "android")
        put("version", com.kotlin_livez.app.BuildConfig.VERSION_NAME)
    }

    private fun mapSendAction(action: String): String = when (action) {
        "SendFavorite" -> "sendFavorite"
        "SendHistory" -> "sendHistory"
        "SendShieldWord" -> "sendShieldWord"
        "SendBiliAccount" -> "sendBiliAccount"
        else -> action
    }

    private fun readErrorMessage(message: JsonObject): String {
        val error = message["error"]
        val obj = error as? JsonObject
        return obj?.get("message")?.strTrim()
            ?: obj?.get("code")?.strTrim()
            ?: error?.let { (it as? JsonPrimitive)?.content }
            ?: "未知错误"
    }

    private fun formatConnectionError(error: Throwable): String {
        val text = error.toString()
        if (text.contains("Timeout") || error is java.util.concurrent.TimeoutException) {
            return "同步服务连接超时，请检查网络或同步服务地址。" +
                "两台设备必须选择相同的同步服务；Cloudflare Worker 在部分网络下可能需要代理。"
        }
        if (text.contains("Socket") || text.contains("ConnectException") || text.contains("UnknownHost")) {
            return "无法连接同步服务，请检查网络或同步服务地址"
        }
        return text.removePrefix("java.net.ConnectException: ").trim()
    }

    /** 同步加锁操作 pendingRequests（避免并发修改）。 */
    private fun <T> pendingMutexRun(block: () -> T): T {
        return kotlinx.coroutines.runBlocking {
            pendingMutex.withLock { block() }
        }
    }

    private fun JsonElement?.strTrim(): String? = (this as? JsonPrimitive)?.content?.trim()
}

/** 连接状态（对应 Dart SignalRConnectionState）。 */
enum class SignalRConnectionState {
    connecting,
    connected,
    disconnected,
}

/** 服务器探测结果（对应 Dart SyncServerProbeResult）。 */
class SyncServerProbeResult private constructor(
    val isReachable: Boolean,
    val label: String,
    val latencyMs: Long?,
) {
    companion object {
        fun reachable(latencyMs: Long) =
            SyncServerProbeResult(true, "$latencyMs ms", latencyMs)

        fun unreachable() = SyncServerProbeResult(false, "不可用", null)

        fun notConfigured() = SyncServerProbeResult(false, "未配置", null)
    }
}

/** 连接信息（对应 Dart SignalRConnectionInfo）。 */
class SignalRConnectionInfo {
    var connectionId: String? = null
}

/** 通用响应（对应 Dart Resp）。 */
class Resp<T>(
    val isSuccess: Boolean,
    val message: String,
    val data: T?,
)

/** 房间同步负载（对应 Dart RoomSyncPayload）。 */
class RoomSyncPayload(
    val overlay: Boolean,
    val content: String,
    val chunkIndex: Int = 1,
    val chunkTotal: Int = 1,
    val itemStart: Int = 0,
    val itemEnd: Int = 0,
    val itemTotal: Int = 0,
) {
    val isLastChunk: Boolean get() = chunkIndex >= chunkTotal

    companion object {
        fun fromObject(obj: JsonObject): RoomSyncPayload {
            fun readInt(key: String, fallback: Int): Int {
                val value = obj[key]
                val primitive = value as? JsonPrimitive
                return primitive?.content?.toIntOrNull()
                    ?: primitive?.content?.toDoubleOrNull()?.toInt()
                    ?: fallback
            }

            return RoomSyncPayload(
                overlay = (obj["overlay"] as? JsonPrimitive)?.content == "true",
                content = obj["content"]?.let { (it as? JsonPrimitive)?.content } ?: "",
                chunkIndex = readInt("chunkIndex", 1),
                chunkTotal = readInt("chunkTotal", 1),
                itemStart = readInt("itemStart", 0),
                itemEnd = readInt("itemEnd", 0),
                itemTotal = readInt("itemTotal", 0),
            )
        }
    }
}

/** 房间成员（对应 Dart RoomUser）。 */
class RoomUser(
    val connectionId: String,
    val shortId: String,
    val platform: String,
    val version: String,
    val app: String,
    val isCreator: Boolean = false,
    val isSelf: Boolean = false,
) {
    companion object {
        fun fromObject(obj: JsonElement): RoomUser {
            val json = obj as? JsonObject
            fun str(key: String): String = json?.get(key)?.let { (it as? JsonPrimitive)?.content } ?: ""
            fun bool(key: String): Boolean = json?.get(key)?.let { (it as? JsonPrimitive)?.content == "true" } ?: false
            return RoomUser(
                connectionId = str("connectionId"),
                shortId = str("shortId"),
                platform = str("platform"),
                version = str("version"),
                app = str("app"),
                isCreator = bool("isCreator"),
                isSelf = bool("isSelf"),
            )
        }
    }
}

/** 挂起请求（包装 pendingRequests 条目；响应为原始 JsonObject，由调用方解析）。 */
private class PendingRequest(
    val successTypes: Set<String>,
) {
    private val deferred = kotlinx.coroutines.CompletableDeferred<Resp<JsonObject?>>()

    suspend fun await(): Resp<JsonObject?> = deferred.await()

    fun completeSuccess(message: JsonObject) {
        deferred.complete(Resp(true, "", message))
    }

    fun completeError(message: String) {
        deferred.complete(Resp(false, message, null))
    }
}