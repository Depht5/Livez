package dev.livefork.app.modules.mine.account.kuaishou

import android.webkit.CookieManager
import android.webkit.WebView
import dev.livefork.app.app.Log
import dev.livefork.app.di.ServiceContainer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * 快手网页登录控制器 —— 对应 Dart `web_login_controller.dart`（193 行）。
 * 加载快手直播首页；保存 Cookie 时额外通过 evaluateJavascript 读取 localStorage 的
 * kwfv1 合并进 Cookie（快手风控要求）；追踪核心 Cookie 的过期时间。
 * 注意：Android CookieManager 无 expiresDate API，expiresAtMs 按 null 处理
 * （Dart 端从 Cookie 解析；如需过期信息可后续从 WebView 的 Set-Cookie 头解析）。
 * WebView 由 UI 层注入。
 */
class KuaishouWebLoginController {

    companion object {
        private const val loginUrl = "https://live.kuaishou.com/"
        private const val desktopUserAgent =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** UI 层注入的 WebView。 */
    var webView: WebView? = null

    val progress = MutableStateFlow(0f)
    val checking = MutableStateFlow(false)
    val errorMessage = MutableStateFlow("")

    /** UI 层注入回调。 */
    var onToast: ((String) -> Unit)? = null
    var onExit: (() -> Unit)? = null

    fun pageUrl(): String = loginUrl

    val userAgent: String get() = desktopUserAgent

    /** UI 层创建 WebView 时调用。 */
    fun onWebViewCreated() {
        webView?.loadUrl(loginUrl)
    }

    /** UI 层 onProgressChanged 回调。 */
    fun onProgressChanged(value: Int) {
        progress.value = value / 100f
    }

    /** UI 层 onPageStarted 回调（对应 Dart onLoadStart）。 */
    fun onLoadStart() {
        progress.value = 0f
        errorMessage.value = ""
    }

    /** UI 层 onPageFinished 回调（对应 Dart onLoadStop）。 */
    fun onLoadStop() {
        progress.value = 1f
    }

    /** UI 层 onReceivedError（主框架）回调。 */
    fun onReceivedError(description: String) {
        progress.value = 1f
        errorMessage.value = description
    }

    /** UI 层 onReceivedHttpError（主框架）回调。 */
    fun onReceivedHttpError(statusCode: Int) {
        progress.value = 1f
        errorMessage.value = "HTTP $statusCode"
    }

    fun reload() {
        errorMessage.value = ""
        webView?.reload()
    }

    /** 保存 Cookie（对应 Dart saveCookie）。 */
    fun saveCookie(silent: Boolean = false, autoClose: Boolean = true) {
        if (checking.value) return
        checking.value = true
        scope.launch {
            try {
                var cookie = readCookie()
                val localStorageKww = readKww()
                if (localStorageKww.isNotEmpty() && readCookieValue(cookie, "kwfv1").isEmpty()) {
                    val encodedKww = URLEncoder.encode(localStorageKww, "UTF-8")
                    cookie = if (cookie.isEmpty()) "kwfv1=$encodedKww" else "$cookie; kwfv1=$encodedKww"
                }
                val kww = if (localStorageKww.isNotEmpty()) localStorageKww else readCookieValue(cookie, "kwfv1")
                if (cookie.isEmpty()) {
                    if (!silent) onToast?.invoke("未读取到快手 Cookie")
                    return@launch
                }
                ServiceContainer.kuaishouAccount.setCookie(cookie, kww = kww, expiresAtMs = null)
                if (kww.isEmpty()) {
                    if (!silent || autoClose) onToast?.invoke("Cookie 已保存，但未获取到 kwfv1；请刷新页面或完成验证后再保存")
                    return@launch
                }
                if (!silent || autoClose) onToast?.invoke("快手 Cookie 已保存，可用于搜索和弹幕")
                if (autoClose) onExit?.invoke()
            } catch (e: Exception) {
                Log.e("保存快手 Cookie 失败：$e")
                if (!silent) onToast?.invoke("保存失败：$e")
            } finally {
                checking.value = false
            }
        }
    }

    /** 合并 3 个域名的 Cookie（对应 Dart _readCookie）。 */
    private fun readCookie(): String {
        val values = LinkedHashMap<String, String>()
        for (url in listOf("https://live.kuaishou.com", "https://kuaishou.com", "https://www.kuaishou.com")) {
            val cookies = CookieManager.getInstance().getCookie(url) ?: continue
            for (part in cookies.split(";")) {
                val item = part.trim()
                val idx = item.indexOf('=')
                if (idx <= 0) continue
                val name = item.substring(0, idx).trim()
                val value = item.substring(idx + 1).trim()
                if (name.isNotEmpty() && value.isNotEmpty()) {
                    values.putIfAbsent(name, value)
                }
            }
        }
        return values.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** 读取 localStorage 的 kwfv1（对应 Dart _readKww，evaluateJavascript）。 */
    private suspend fun readKww(): String {
        val view = webView ?: return ""
        return withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<String>()
            view.evaluateJavascript("window.localStorage.getItem('kwfv1') || ''") { value ->
                deferred.complete(value?.trim() ?: "")
            }
            deferred.await()
        }
    }

    private fun readCookieValue(cookie: String, name: String): String {
        for (part in cookie.split(";")) {
            val item = part.trim()
            if (item.startsWith("$name=")) {
                return item.substring(name.length + 1).trim()
            }
        }
        return ""
    }
}