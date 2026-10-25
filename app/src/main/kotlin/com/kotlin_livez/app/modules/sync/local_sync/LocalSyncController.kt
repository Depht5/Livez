package com.kotlin_livez.app.modules.sync.local_sync

import com.kotlin_livez.app.app.Log
import com.kotlin_livez.app.app.controller.BaseController
import com.kotlin_livez.app.models.SyncClientInfoModel
import com.kotlin_livez.app.services.SyncClient
import com.kotlin_livez.app.services.SyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 局域网同步首页控制器 —— 对应 Dart `local_sync_controller.dart`（194 行）。
 * 地址输入 / 解析 / 连接对端；扫码、二维码展示、底部弹层与路由由 UI 层负责。
 */
class LocalSyncController(
    address: String? = null,
) : BaseController() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val addressText = MutableStateFlow("")
    /** 自动发现到的设备列表（对应 Dart SyncService.scanClients）。 */
    val scanClients = MutableStateFlow<List<SyncClient>>(emptyList())

    private val request = SyncClientRequest()

    init {
        SyncService.refreshClients()
        scope.launch {
            SyncService.scanClients.collect { scanClients.value = it }
        }
        if (!address.isNullOrEmpty()) {
            addressText.value = address
            connect(
                onSuccess = { _, _ -> },
                onError = { Log.d("Toast: $it") },
            )
        }
    }

    /** 解析地址（对应 Dart parseAddress；纯函数，供单测复用）。 */
    fun parseAddress(rawAddress: String): Pair<String, Int>? = parseAddressStatic(rawAddress)

    /** 连接指定地址（对应 Dart connect：解析输入框文本 → 构造手动输入设备 → connectClient）。 */
    fun connect(
        onSuccess: (SyncClient, SyncClientInfoModel) -> Unit,
        onError: (String) -> Unit,
        manualAddress: String? = null,
    ) {
        val text = manualAddress ?: addressText.value
        val parsed = parseAddress(text)
        if (parsed == null) {
            onError("请输入地址")
            return
        }
        val client = SyncClient(
            id = "manual",
            address = parsed.first,
            port = parsed.second,
            name = "手动输入",
            type = "android",
        )
        connectClient(client, onSuccess, onError)
    }

    /** 连接指定设备（对应 Dart connectClient：GET /info 成功即进设备页，失败弹长文案）。 */
    fun connectClient(
        client: SyncClient,
        onSuccess: (SyncClient, SyncClientInfoModel) -> Unit,
        onError: (String) -> Unit,
    ) {
        scope.launch {
            try {
                val info = withContext(Dispatchers.IO) { request.getClientInfo(client) }
                onSuccess(client, info)
            } catch (e: Exception) {
                Log.logPrint(e)
                onError(
                    "局域网同步只支持两台设备在同一 Wi-Fi/局域网内互相访问。" +
                        "如果两台设备不在同一局域网，需要先配置 VPN、端口转发或内网穿透；" +
                        "否则请改用 WebDAV。\n\n错误详情：${e.message}",
                )
            }
        }
    }

    /** 本机信息（对应 Dart showInfo 的数据部分）。 */
    fun localInfo(): String {
        val addresses = SyncService.ipAddress.value
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { "$it:${SyncService.httpPort}" }
            .joinToString(";")
        return addresses
    }

    /** 取消协程（对齐 Dart onClose；页面出栈时由 Screen 的 DisposableEffect 调用）。 */
    fun dispose() {
        scope.coroutineContext[Job]?.cancel()
    }

    companion object {
        /** 地址解析（对应 Dart parseAddress）：补 http:// 前缀、默认端口 23234、取 host。 */
        fun parseAddressStatic(rawAddress: String): Pair<String, Int>? {
            var address = rawAddress.trim()
            if (address.isEmpty()) return null
            if (!address.startsWith("http://") && !address.startsWith("https://")) {
                address = "http://$address"
            }
            val uri = runCatching { java.net.URI(address) }.getOrNull() ?: return null
            val host = uri.host
            if (host.isNullOrEmpty()) return null
            val port = if (uri.port != -1) uri.port else SyncService.httpPort
            return host to port
        }

        /**
         * 扫码结果拆分（对应 Dart toScanQr 的 `;` 拆分）：返回去空白后的地址列表。
         * ≥2 个地址由 UI 弹选择；单地址直接回填（6 位房间号分支已随远程同步移除）。
         */
        fun splitScanAddresses(content: String): List<String> =
            content.split(";").map { it.trim() }.filter { it.isNotEmpty() }
    }
}