package com.kotlin_livez.app.modules.mine.account.bilibili

import com.kotlin_livez.app.app.Log
import com.kotlin_livez.app.di.ServiceContainer
import com.kotlin_livez.app.requests.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 二维码登录状态（对应 Dart QRStatus）。 */
enum class QRStatus {
    loading,
    unscanned,
    scanned,
    expired,
    failed,
}

/**
 * B 站二维码登录控制器 —— 对应 Dart `qr_login_controller.dart`（111 行）。
 * 生成二维码 + 每 3s 轮询登录态；扫码成功读取 set-cookie 并保存账号。
 * 二维码图片渲染与页面返回由 UI 层负责（onExit 回调）。
 */
class BiliBiliQRLoginController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val http = HttpClient.INSTANCE
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private var qrcodeKey = ""
    private var pollJob: Job? = null

    val qrcodeUrl = MutableStateFlow("")
    val qrStatus = MutableStateFlow(QRStatus.loading)

    /** UI 层注入：toast 与登录成功后的页面返回。 */
    var onToast: ((String) -> Unit)? = null
    var onExit: (() -> Unit)? = null

    /** 加载二维码（对应 Dart loadQRCode，onInit 调用）。 */
    fun loadQRCode() {
        scope.launch {
            try {
                qrStatus.value = QRStatus.loading
                val result = withContext(Dispatchers.IO) {
                    http.getJson("https://passport.bilibili.com/x/passport-login/web/qrcode/generate")
                } ?: throw IllegalArgumentException("无响应")
                val obj = result as? JsonObject ?: throw IllegalArgumentException("响应格式错误")
                if (obj["code"]?.strAsInt() != 0) {
                    throw IllegalArgumentException(obj["message"]?.strTrim() ?: "未知错误")
                }
                val data = obj["data"] as? JsonObject
                qrcodeKey = data?.get("qrcode_key")?.strTrim() ?: ""
                qrcodeUrl.value = data?.get("url")?.strTrim() ?: ""
                qrStatus.value = QRStatus.unscanned
                startPoll()
            } catch (e: Exception) {
                Log.logPrint(e)
                onToast?.invoke(e.toString())
                qrStatus.value = QRStatus.failed
            }
        }
    }

    /** 开始轮询（对应 Dart startPoll；每 3 秒）。 */
    private fun startPoll() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                delay(3_000)
                pollQRStatus()
                if (qrStatus.value == QRStatus.expired) break
            }
        }
    }

    /** 轮询登录状态（对应 Dart pollQRStatus）。 */
    private suspend fun pollQRStatus() {
        try {
            val (code, cookies) = withContext(Dispatchers.IO) { pollOnce() }
            when (code) {
                0 -> {
                    if (cookies.isNotEmpty()) {
                        Log.i(cookies)
                        ServiceContainer.biliBiliAccount.setCookie(cookies)
                        ServiceContainer.biliBiliAccount.loadUserInfo()
                        pollJob?.cancel()
                        onExit?.invoke()
                    }
                }
                86038 -> {
                    qrStatus.value = QRStatus.expired
                    qrcodeKey = ""
                    pollJob?.cancel()
                }
                86090 -> {
                    qrStatus.value = QRStatus.scanned
                }
            }
        } catch (e: Exception) {
            Log.logPrint(e)
            onToast?.invoke(e.toString())
        }
    }

    /** 单次轮询：返回 (业务 code, 合并后的 set-cookie)。 */
    private fun pollOnce(): Pair<Int, String> {
        val request = Request.Builder()
            .url("https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=$qrcodeKey")
            .build()
        client.newCall(request).execute().use { response ->
            val bodyText = response.body?.string() ?: ""
            val obj = Json.parseToJsonElement(bodyText).jsonObject
            val code = obj["code"]?.strAsInt() ?: -1
            if (code != 0) {
                return Pair(code, obj["message"]?.strTrim() ?: "")
            }
            val data = obj["data"] as? JsonObject
            val dataCode = data?.get("code")?.strAsInt() ?: -1
            if (dataCode != 0) {
                return Pair(dataCode, "")
            }
            val cookies = response.headers("set-cookie").mapNotNull { item ->
                val first = item.split(";").firstOrNull()?.trim()
                first?.takeIf { it.contains("=") }
            }
            return Pair(0, cookies.joinToString(";"))
        }
    }

    /** 销毁（对应 Dart onClose）。 */
    fun close() {
        pollJob?.cancel()
    }
}

private fun kotlinx.serialization.json.JsonElement?.strTrim(): String? =
    (this as? JsonPrimitive)?.content?.trim()

private fun kotlinx.serialization.json.JsonElement?.strAsInt(): Int =
    (this as? JsonPrimitive)?.content?.toIntOrNull() ?: -1