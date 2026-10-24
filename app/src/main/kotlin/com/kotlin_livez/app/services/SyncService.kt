package com.kotlin_livez.app.services

import android.os.Build
import com.kotlin_livez.app.app.Log
import com.kotlin_livez.app.di.ServiceContainer
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.UUID
import kotlin.concurrent.thread

/**
 * 局域网同步服务 —— 对应 Dart `SyncService`
 * （lib/services/sync_service.dart, 645 行）。
 * UDP 广播发现（23235）+ HTTP 服务端（23234，NanoHTTPD 替代 shelf）。
 * 本机作为「接收方」接收对端推送的 follow/tag/history/blocked_word/profile/账号数据。
 */
object SyncService {

    const val udpPort = 23235
    const val httpPort = 23234

    val scanClients = MutableStateFlow<List<SyncClient>>(emptyList())
    val ipAddress = MutableStateFlow("")
    val httpRunning = MutableStateFlow(false)
    val httpErrorMsg = MutableStateFlow("")
    val udpRunning = MutableStateFlow(false)
    val udpErrorMsg = MutableStateFlow("")

    var deviceId: String = UUID.randomUUID().toString().split("-").first()
        private set

    private var udpSocket: DatagramSocket? = null
    private var httpServer: SyncHttpServer? = null

    /**
     * 接收侧 UI 事件流（对应 Dart SyncProgressDialog 的 show/update/dismiss + SmartDialog.showToast）。
     * NanoHTTPD 服务线程以 [emitEvent] 发射；全局宿主 `ui/components/SyncProgressHost.kt` 收集渲染，
     * 使任意页面被推送时都可见。
     */
    private val _syncUiEvents = MutableSharedFlow<SyncUiEvent>(extraBufferCapacity = 64)
    val syncUiEvents: SharedFlow<SyncUiEvent> = _syncUiEvents

    fun emitEvent(event: SyncUiEvent) {
        _syncUiEvents.tryEmit(event)
    }

    /** 服务启动（对应 Dart onInit）。 */
    fun install() {
        deviceId = UUID.randomUUID().toString().split("-").first()
        listenUDP()
        initServer()
    }

    val lanErrorMsg: String
        get() {
            val messages = mutableListOf<String>()
            if (httpErrorMsg.value.trim().isNotEmpty()) messages.add(httpErrorMsg.value)
            if (udpErrorMsg.value.trim().isNotEmpty()) messages.add(udpErrorMsg.value)
            return messages.joinToString("；")
        }

    /* ---------- UDP 发现 ---------- */

    private fun listenUDP() {
        try {
            val socket = DatagramSocket(null)
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(udpPort))
            udpSocket = socket
            udpRunning.value = true
            udpErrorMsg.value = ""
            thread(name = "sync-udp-listener", isDaemon = true) {
                val buffer = ByteArray(4096)
                while (!socket.isClosed) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val str = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        listenUdp(str, packet.address.hostAddress ?: "")
                    } catch (e: Exception) {
                        if (socket.isClosed) return@thread
                        udpRunning.value = false
                        udpErrorMsg.value = formatPortError(e, udpPort, "UDP发现服务")
                        Log.logPrint(e)
                        return@thread
                    }
                }
            }
        } catch (e: Exception) {
            udpRunning.value = false
            udpErrorMsg.value = formatPortError(e, udpPort, "UDP发现服务")
            Log.logPrint(e)
        }
    }

    private fun listenUdp(str: String, fromAddress: String) {
        if (!str.startsWith("{") || !str.endsWith("}")) return
        // 诊断日志（对齐 Dart Log.i）：抓取日志时能确认收没收到对端广播
        Log.i("UDP 收到：$str from $fromAddress")
        try {
            val data = Json.parseToJsonElement(str).jsonObject
            // 自己的广播不处理
            if (data["id"]?.strOrEmpty() == deviceId) return
            // hello 广播：HTTP 服务已启动则回复自身信息
            if (data["type"]?.strOrEmpty() == "hello") {
                if (httpRunning.value) {
                    sendInfo()
                }
                return
            }
            // 其他端广播：以 datagram 地址入库（能收到即连通）
            val address = fromAddress
            val existing = scanClients.value.any { it.address == address }
            if (!existing) {
                scanClients.value = scanClients.value + SyncClient(
                    id = data["id"]?.strOrEmpty() ?: "",
                    name = data["name"]?.strOrEmpty() ?: "",
                    address = address,
                    port = httpPort,
                    type = data["type"]?.strOrEmpty() ?: "",
                )
            }
        } catch (e: Exception) {
            Log.logPrint(e)
        }
    }

    /** 广播 hello（对应 Dart sendHello）。
     *  ⚠ socket.send 必须在后台线程：主线程发 UDP 会触发 BlockGuard 的
     *  NetworkOnMainThreadException（真机实证 2026-10-25：广播从未发出、设备发现全挂）。 */
    fun sendHello() {
        sendBroadcast(
            buildJsonObject {
                put("id", deviceId)
                put("type", "hello")
            }.toString(),
        ) { "send udp: hello" }
    }

    /** 广播自身信息（对应 Dart sendInfo）。同样须后台线程发送（见 [sendHello]）。 */
    fun sendInfo() {
        sendBroadcast(
            buildJsonObject {
                put("id", deviceId)
                put("type", "android")
                put("name", getDeviceName())
            }.toString(),
        ) { "send udp info" }
    }

    /** 统一的 UDP 广播出口：组包在调用线程、发送在独立守护线程（见 sendHello 的主线程限制说明），
     *  成功后回传日志文案由 [successLog] 提供。 */
    private fun sendBroadcast(payloadJson: String, successLog: () -> String) {
        val socket = udpSocket ?: return
        if (!udpRunning.value) return
        val payload = payloadJson.toByteArray(Charsets.UTF_8)
        thread(isDaemon = true, name = "sync-udp-send") {
            try {
                socket.send(DatagramPacket(payload, payload.size, broadcastAddress(), udpPort))
                Log.d(successLog())
            } catch (e: Exception) {
                Log.logPrint(e)
            }
        }
    }

    private fun broadcastAddress(): InetAddress = InetAddress.getByName("255.255.255.255")

    /** 刷新客户端列表（对应 Dart refreshClients）。 */
    fun refreshClients() {
        scanClients.value = emptyList()
        sendHello()
    }

    fun getDeviceName(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Build.MODEL
    } else {
        Build.MODEL
    }

    /** 读取本机 IPv4 地址（对应 Dart getLocalIP），多地址用 ; 分隔。 */
    fun getLocalIP(): String {
        val ipList = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addresses = intf.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress &&
                        !addr.isMulticastAddress &&
                        addr is java.net.Inet4Address
                    ) {
                        val text = addr.hostAddress ?: continue
                        if (!text.startsWith("127")) {
                            ipList.add(text)
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.logPrint(e)
        }
        return ipList.joinToString(";")
    }

    /* ---------- HTTP 服务端 ---------- */

    private fun initServer() {
        try {
            val server = SyncHttpServer(httpPort)
            httpServer = server
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            httpRunning.value = true
            httpErrorMsg.value = ""
            ipAddress.value = getLocalIP()
            Log.d("Serving at http://${ipAddress.value}:$httpPort")
        } catch (e: Exception) {
            httpRunning.value = false
            httpErrorMsg.value = formatPortError(e, httpPort, "HTTP同步服务")
            Log.logPrint(e)
        }
    }

    fun stop() {
        Log.d("SyncService close")
        try {
            udpSocket?.close()
        } catch (_: Exception) {
        }
        udpRunning.value = false
        try {
            httpServer?.stop()
        } catch (_: Exception) {
        }
        httpRunning.value = false
    }

    private fun formatPortError(error: Throwable, port: Int, serviceName: String): String {
        val text = error.toString().lowercase()
        if (text.contains("10048") ||
            text.contains("address already in use") ||
            text.contains("failed to create server socket") ||
            text.contains("only one usage of each socket address")
        ) {
            return "$port 端口已被占用，请关闭其他 Simple Live / TV-Windows 窗口后重试"
        }
        return "${serviceName}启动失败：$error"
    }

    private fun JsonElement?.strOrEmpty(): String =
        (this as? JsonPrimitive)?.content?.takeIf { it != "null" } ?: ""
}

/** 同步客户端（对应 Dart SyncClinet）。 */
class SyncClient(
    val id: String,
    val name: String,
    val address: String,
    val port: Int,
    val type: String,
)

/** 接收侧同步 UI 事件（对应 Dart SyncProgressDialog 系列 + SmartDialog.showToast 的时机语义）。 */
sealed interface SyncUiEvent {
    /** 收到推送块，弹出进度框。 */
    data class Show(val progress: SyncProgress) : SyncUiEvent

    /** 更新进度框内容。 */
    data class Update(val progress: SyncProgress) : SyncUiEvent

    /** 关闭进度框（末块或异常）。 */
    data object Dismiss : SyncUiEvent

    /** 结果 toast（「已同步…」等，由全局宿主弹出）。 */
    data class Toast(val message: String) : SyncUiEvent
}

/** 同步分段信息（对应 Dart _SyncChunk）。 */
class SyncChunk(
    val chunkIndex: Int = 1,
    val chunkTotal: Int = 1,
    val itemStart: Int = 0,
    val itemEnd: Int = 0,
    val itemTotal: Int = 0,
) {
    val isLastChunk: Boolean get() = chunkIndex >= chunkTotal
}

/** HTTP 同步服务端（对应 Dart shelf Router；NanoHTTPD 实现）。 */
class SyncHttpServer(port: Int) : NanoHTTPD(port) {

    private val json = Json { ignoreUnknownKeys = true }

    override fun serve(session: IHTTPSession): Response {
        return try {
            handle(session)
        } catch (e: Exception) {
            toJsonResponse(buildJsonObject {
                put("status", false)
                put("message", e.toString())
            })
        }
    }

    private fun handle(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        // 诊断日志：接收侧收到的每个 HTTP 请求（uri 不含 query；query 另行打印）
        Log.i("HTTP 请求：$method $uri${session.queryParameterString?.takeIf { it.isNotBlank() }?.let { "?$it" } ?: ""}")
        val parms = session.parms ?: emptyMap()
        val service = SyncService

        return when {
            method == Method.GET && uri == "/" -> toJsonResponse(buildJsonObject {
                put("status", true)
                put("message", "http server is running...")
                put("version", "Simple Live android v${com.kotlin_livez.app.BuildConfig.VERSION_NAME}")
                put("app", "Simple Live")
                put("type", "android")
                put("platform", "android")
            })
            method == Method.GET && uri == "/info" -> toJsonResponse(buildJsonObject {
                put("id", service.deviceId)
                put("type", "android")
                put("name", service.getDeviceName())
                put("version", com.kotlin_livez.app.BuildConfig.VERSION_NAME)
                put("address", service.ipAddress.value)
                put("port", service.httpPort)
            })
            method == Method.POST && uri.startsWith("/sync/") ->
                handleSync(uri.removePrefix("/sync/"), session, parms)
            else -> toJsonResponse(buildJsonObject {
                put("status", false)
                put("message", "not found: $method $uri")
            })
        }
    }

    private fun handleSync(
        path: String,
        session: IHTTPSession,
        parms: Map<String, String>,
    ): Response {
        // ⚠ 读取 body 必须走 parseBody（按 content-length 精确读取）：
        // session.inputStream 是 keep-alive 的原始 socket 流，readBytes() 读到 body 末尾后
        // 会继续等下一包直到 5s socket 超时（真机实测 2026-10-25：所有 POST 同步必超时）。
        // 非表单 content-type 的 body 由 parseBody 整体存入 files["postData"]。
        val files = HashMap<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""
        val overlay = (parms["overlay"] ?: "0").toIntOrNull() ?: 0
        val chunk = SyncChunk(
            chunkIndex = (parms["chunkIndex"] ?: "").toIntOrNull() ?: 1,
            chunkTotal = (parms["chunkTotal"] ?: "").toIntOrNull() ?: 1,
            itemStart = (parms["itemStart"] ?: "").toIntOrNull() ?: 0,
            itemEnd = (parms["itemEnd"] ?: "").toIntOrNull() ?: 0,
            itemTotal = (parms["itemTotal"] ?: "").toIntOrNull() ?: 0,
        )
        val db = ServiceContainer.dbService
        val localStorage = ServiceContainer.localStorage
        val appSettings = ServiceContainer.appSettings
        return try {
            when (path) {
                "follow" -> {
                    emitShow("接收关注", chunk)
                    val list = parseArray(body, "关注列表格式不是数组")
                    val result = runBlocking(Dispatchers.IO) {
                        BulkDataImportService.importFollowUsers(
                            list, overwrite = overlay == 1,
                            onProgress = wrapChunkProgress(chunk),
                        )
                    }
                    Log.d("本地同步关注完成：${result.logSummary}")
                    if (chunk.isLastChunk) {
                        com.kotlin_livez.app.app.EventBus.emit(com.kotlin_livez.app.app.Constant.kUpdateFollow, 0)
                        emitDone("已同步关注用户列表（${chunkCount(chunk, result.imported)} 条）")
                    }
                    successResponse()
                }
                "tag" -> {
                    emitShow("接收标签", chunk)
                    val list = parseArray(body, "标签列表格式不是数组")
                    val result = runBlocking(Dispatchers.IO) {
                        BulkDataImportService.importFollowTags(
                            list, overwrite = overlay == 1,
                            onProgress = wrapChunkProgress(chunk),
                        )
                    }
                    Log.d("本地同步标签完成：${result.logSummary}")
                    if (chunk.isLastChunk) {
                        com.kotlin_livez.app.app.EventBus.emit(com.kotlin_livez.app.app.Constant.kUpdateFollow, 0)
                        emitDone("已同步标签列表（${chunkCount(chunk, result.imported)} 条）")
                    }
                    successResponse()
                }
                "history" -> {
                    emitShow("接收历史", chunk)
                    val list = parseArray(body, "历史记录格式不是数组")
                    val result = runBlocking(Dispatchers.IO) {
                        BulkDataImportService.importHistories(
                            list, overwrite = overlay == 1,
                            onProgress = wrapChunkProgress(chunk),
                        )
                    }
                    Log.d("本地同步历史完成：${result.logSummary}")
                    if (chunk.isLastChunk) {
                        com.kotlin_livez.app.app.EventBus.emit(com.kotlin_livez.app.app.Constant.kUpdateHistory, 0)
                        emitDone("已同步观看记录（${chunkCount(chunk, result.imported)} 条）")
                    }
                    successResponse()
                }
                "blocked_word" -> {
                    emitShow("接收屏蔽词", chunk)
                    val list = parseStringArray(body, "屏蔽词格式不是数组")
                    val result = runBlocking(Dispatchers.IO) {
                        BulkDataImportService.importShieldValues(
                            list, overwrite = overlay == 1,
                            onProgress = wrapChunkProgress(chunk),
                        )
                    }
                    Log.d("本地同步屏蔽词完成：${result.logSummary}")
                    if (chunk.isLastChunk) {
                        emitDone("已同步弹幕屏蔽词（${chunkCount(chunk, result.imported)} 条）")
                    }
                    successResponse()
                }
                "profile" -> {
                    emitEvent(SyncUiEvent.Show(SyncProgress(stage = "接收配置包")))
                    val summary = runBlocking(Dispatchers.IO) {
                        ProfileBackupService.importProfileJson(
                            body,
                            overwrite = overlay == 1,
                            onProgress = { progress -> emitEvent(SyncUiEvent.Update(progress)) },
                        )
                    }
                    Log.d("已同步配置包：${summary.message}")
                    emitEvent(SyncUiEvent.Toast("已同步配置包"))
                    emitEvent(SyncUiEvent.Dismiss)
                    toJsonResponse(buildJsonObject {
                        put("status", true)
                        put("message", summary.message)
                    })
                }
                "account/bilibili" -> {
                    val cookie = parseAccountCookie(body)
                    ServiceContainer.biliBiliAccount.setCookie(cookie)
                    // 对齐 Dart 接收端：落 Cookie 后立即拉用户信息（失败不影响同步结果）
                    runCatching { runBlocking(Dispatchers.IO) { ServiceContainer.biliBiliAccount.loadUserInfo() } }
                        .onFailure { Log.logPrint(it) }
                    Log.d("已同步哔哩哔哩账号")
                    emitEvent(SyncUiEvent.Toast("已同步哔哩哔哩账号"))
                    successResponse()
                }
                "account/douyin" -> {
                    val cookie = parseAccountCookie(body)
                    ServiceContainer.douyinAccount.setCookie(cookie)
                    Log.d("已同步抖音账号")
                    emitEvent(SyncUiEvent.Toast("已同步抖音账号"))
                    successResponse()
                }
                "account/douyu" -> {
                    val account = parseDouyuAccountBody(json, body)
                    // 斗鱼允许空 cookie（语义 = 清除对端账号）；凭据缺失传 null →
                    // setCookie 内部走 Cookie 反解并保留旧值（对齐 Dart）
                    ServiceContainer.douyuAccount.setCookie(account.cookie, account.ltp0, account.dyDid)
                    Log.d(if (account.cookie.isEmpty()) "已清除斗鱼账号" else "已同步斗鱼账号")
                    emitEvent(
                        SyncUiEvent.Toast(if (account.cookie.isEmpty()) "已清除斗鱼账号" else "已同步斗鱼账号"),
                    )
                    successResponse()
                }
                else -> toJsonResponse(buildJsonObject {
                    put("status", false)
                    put("message", "unknown endpoint: $path")
                })
            }
        } catch (e: Exception) {
            // 接收失败：收起进度框即可，错误详情经 HTTP status=false 由发送方 toast（对齐 Dart）
            emitEvent(SyncUiEvent.Dismiss)
            toJsonResponse(buildJsonObject {
                put("status", false)
                put("message", e.toString())
            })
        }
    }

    /** 收到推送块：弹进度框并给出分段阶段进度（对应 Dart _stageProgress）。 */
    private fun emitShow(stage: String, chunk: SyncChunk) {
        val total = if (chunk.itemTotal > 0) chunk.itemTotal else chunk.chunkTotal
        val current = if (chunk.itemTotal > 0) chunk.itemEnd else chunk.chunkIndex
        emitEvent(
            SyncUiEvent.Show(
                SyncProgress(
                    stage = stage,
                    current = current,
                    total = total,
                    message = if (chunk.chunkTotal > 1) "接收第 ${chunk.chunkIndex}/${chunk.chunkTotal} 段" else stage,
                ),
            ),
        )
    }

    /** 末块收尾：结果 toast + 关闭进度框（对应 Dart SyncProgressDialog.dismiss + showToast）。 */
    private fun emitDone(toastMessage: String) {
        emitEvent(SyncUiEvent.Toast(toastMessage))
        emitEvent(SyncUiEvent.Dismiss)
    }

    /** 末块结果条数：优先分段声明的 itemTotal，缺省回落实际导入数（对应 Dart）。 */
    private fun chunkCount(chunk: SyncChunk, imported: Int): Int =
        if (chunk.itemTotal > 0) chunk.itemTotal else imported

    private fun emitEvent(event: SyncUiEvent) {
        SyncService.emitEvent(event)
    }

    private fun parseArray(body: String, errMsg: String): List<JsonElement> {
        val element = json.parseToJsonElement(body)
        if (element !is JsonArray) throw IllegalArgumentException(errMsg)
        return element.toList()
    }

    private fun parseStringArray(body: String, errMsg: String): List<String> {
        val element = json.parseToJsonElement(body)
        val array = element as? JsonArray ?: throw IllegalArgumentException(errMsg)
        return array.mapNotNull { (it as? JsonPrimitive)?.content }
    }

    private fun parseAccountCookie(body: String): String {
        val obj = json.parseToJsonElement(body).jsonObject
        val cookie = obj["cookie"].strOrNull() ?: ""
        if (cookie.isEmpty()) throw IllegalArgumentException("账号 Cookie 为空")
        return cookie
    }

    /**
     * 分块导入进度 → UI 事件（对应 Dart _wrapChunkProgress）：
     * itemTotal<=0（无分段声明）直传；否则以 itemStart 为基准偏移，并夹取到 itemTotal。
     */
    private fun wrapChunkProgress(chunk: SyncChunk): SyncProgressCallback {
        return SyncProgressCallback { progress ->
            emitEvent(SyncUiEvent.Update(chunkProgress(progress, chunk)))
        }
    }

    private fun successResponse(): Response = toJsonResponse(buildJsonObject {
        put("status", true)
        put("message", "success")
    })

    private fun toJsonResponse(data: JsonObject): Response {
        val str = json.encodeToString(JsonObject.serializer(), data)
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", str)
    }

    private fun JsonElement?.strOrEmpty(): String =
        (this as? JsonPrimitive)?.content?.takeIf { it != "null" } ?: ""
}

/** 取可空字符串字段：缺失 / JsonNull → null（区别于「存在但为空串」）。 */
private fun JsonElement?.strOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

/** /sync/account/douyu 请求体：cookie（允许空 = 清除账号）+ 可选续期凭据。 */
internal data class DouyuAccountBody(
    val cookie: String,
    val ltp0: String?,
    val dyDid: String?,
)

/** 解析 /sync/account/douyu 请求体（纯函数，供单测复用）。 */
internal fun parseDouyuAccountBody(json: Json, body: String): DouyuAccountBody {
    val obj = json.parseToJsonElement(body).jsonObject
    return DouyuAccountBody(
        cookie = obj["cookie"].strOrNull() ?: "",
        ltp0 = obj["ltp0"].strOrNull(),
        dyDid = obj["dyDid"].strOrNull(),
    )
}

/**
 * 分块导入进度的偏移换算（对应 Dart `_wrapChunkProgress` 公式，纯函数）：
 * itemTotal<=0（无分段声明）原样返回；否则 current 以 itemStart 为基准偏移并夹取到 itemTotal，
 * total 统一为 itemTotal、message 带 `当前/总数`。
 */
internal fun chunkProgress(progress: SyncProgress, chunk: SyncChunk): SyncProgress {
    if (chunk.itemTotal <= 0) return progress
    val current = (chunk.itemStart + progress.current).coerceIn(0, chunk.itemTotal)
    return SyncProgress(
        stage = progress.stage,
        current = current,
        total = chunk.itemTotal,
        message = "${progress.stage} $current/${chunk.itemTotal}",
    )
}