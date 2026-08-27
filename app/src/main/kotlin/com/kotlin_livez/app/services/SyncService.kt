package com.kotlin_livez.app.services

import android.os.Build
import com.kotlin_livez.app.app.Log
import com.kotlin_livez.app.di.ServiceContainer
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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

    /** 同步进度通知（对应 Dart SyncProgressDialog 系列；UI 层注入）。 */
    var onSyncProgress: ((SyncProgress) -> Unit)? = null

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

    /** 广播 hello（对应 Dart sendHello）。 */
    fun sendHello() {
        val socket = udpSocket ?: return
        if (!udpRunning.value) return
        try {
            val payload = buildJsonObject {
                put("id", deviceId)
                put("type", "hello")
            }.toString().toByteArray(Charsets.UTF_8)
            socket.send(DatagramPacket(payload, payload.size, broadcastAddress(), udpPort))
            Log.d("send udp: hello")
        } catch (e: Exception) {
            Log.logPrint(e)
        }
    }

    /** 广播自身信息（对应 Dart sendInfo）。 */
    fun sendInfo() {
        val socket = udpSocket ?: return
        if (!udpRunning.value) return
        try {
            val payload = buildJsonObject {
                put("id", deviceId)
                put("type", "android")
                put("name", getDeviceName())
            }.toString().toByteArray(Charsets.UTF_8)
            socket.send(DatagramPacket(payload, payload.size, broadcastAddress(), udpPort))
            Log.d("send udp info")
        } catch (e: Exception) {
            Log.logPrint(e)
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
            Log.d("Serving at http://$ipAddress:$httpPort")
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
        val body = session.inputStream.readBytes().toString(Charsets.UTF_8)
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
                    }
                    successResponse()
                }
                "tag" -> {
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
                    }
                    successResponse()
                }
                "history" -> {
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
                    }
                    successResponse()
                }
                "blocked_word" -> {
                    val list = parseStringArray(body, "屏蔽词格式不是数组")
                    val result = runBlocking(Dispatchers.IO) {
                        BulkDataImportService.importShieldValues(
                            list, overwrite = overlay == 1,
                            onProgress = wrapChunkProgress(chunk),
                        )
                    }
                    Log.d("本地同步屏蔽词完成：${result.logSummary}")
                    successResponse()
                }
                "profile" -> {
                    val summary = runBlocking(Dispatchers.IO) {
                        ProfileBackupService.importProfileJson(
                            body,
                            overwrite = overlay == 1,
                            onProgress = { SyncService.onSyncProgress?.invoke(it) },
                        )
                    }
                    Log.d("已同步配置包：${summary.message}")
                    toJsonResponse(buildJsonObject {
                        put("status", true)
                        put("message", summary.message)
                    })
                }
                "account/bilibili" -> {
                    val cookie = parseAccountCookie(body)
                    ServiceContainer.biliBiliAccount.setCookie(cookie)
                    Log.d("已同步哔哩哔哩账号")
                    successResponse()
                }
                "account/douyin" -> {
                    val cookie = parseAccountCookie(body)
                    ServiceContainer.douyinAccount.setCookie(cookie)
                    Log.d("已同步抖音账号")
                    successResponse()
                }
                else -> toJsonResponse(buildJsonObject {
                    put("status", false)
                    put("message", "unknown endpoint: $path")
                })
            }
        } catch (e: Exception) {
            toJsonResponse(buildJsonObject {
                put("status", false)
                put("message", e.toString())
            })
        }
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
        val cookie = obj["cookie"]?.strOrEmpty() ?: ""
        if (cookie.isEmpty()) throw IllegalArgumentException("账号 Cookie 为空")
        return cookie
    }

    private fun wrapChunkProgress(chunk: SyncChunk): SyncProgressCallback {
        return SyncProgressCallback { progress ->
            SyncService.onSyncProgress?.invoke(progress)
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