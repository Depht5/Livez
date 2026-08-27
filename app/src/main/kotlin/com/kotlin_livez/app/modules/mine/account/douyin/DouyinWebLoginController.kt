package com.kotlin_livez.app.modules.mine.account.douyin

import android.webkit.CookieManager
import android.webkit.WebView
import com.kotlin_livez.app.app.Log
import com.kotlin_livez.app.di.ServiceContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 抖音网页登录控制器 —— 对应 Dart `web_login_controller.dart`（100 行）。
 * 加载抖音首页，用户完成登录后读取 3 个域名的 Cookie 合并保存；
 * 通过登录态关键字（sessionid/sid_guard/sid_tt/uid_tt/login_status）判断是否已登录。
 * WebView 由 UI 层注入；onPageFinished 回调触发 onLoadStop/saveCookie。
 */
class DouyinWebLoginController {

    companion object {
        private const val loginUrl = "https://www.douyin.com/"
        private const val desktopUserAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36 Edg/125.0.0.0"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** UI 层注入的 WebView。 */
    var webView: WebView? = null

    val progress = MutableStateFlow(0f)
    val checking = MutableStateFlow(false)

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

    /** UI 层 onPageFinished 回调。 */
    fun onLoadStop() {
        progress.value = 1f
        saveCookie(silent = true)
    }

    fun reload() {
        webView?.reload()
    }

    /** 保存 Cookie（对应 Dart saveCookie）。 */
    fun saveCookie(silent: Boolean = false) {
        if (checking.value) return
        checking.value = true
        scope.launch {
            try {
                val cookie = readCookie()
                if (cookie.isEmpty()) {
                    if (!silent) onToast?.invoke("未读取到抖音 Cookie")
                    return@launch
                }
                if (!hasLoginState(cookie)) {
                    if (!silent) onToast?.invoke("还没有检测到登录态，请先在页面中完成抖音登录")
                    return@launch
                }
                ServiceContainer.douyinAccount.setCookie(cookie)
                onToast?.invoke("抖音登录态已保存，可用于搜索")
                onExit?.invoke()
            } catch (e: Exception) {
                Log.e("保存抖音 Cookie 失败：$e")
                if (!silent) onToast?.invoke("保存失败：$e")
            } finally {
                checking.value = false
            }
        }
    }

    /** 合并 3 个域名的 Cookie（对应 Dart _readCookie）。 */
    private fun readCookie(): String {
        val values = LinkedHashMap<String, String>()
        for (url in listOf("https://www.douyin.com", "https://douyin.com", "https://live.douyin.com")) {
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

    /** 判断是否包含登录态（对应 Dart _hasLoginState）。 */
    private fun hasLoginState(cookie: String): Boolean {
        val lower = cookie.lowercase()
        return lower.contains("sessionid=") ||
            lower.contains("sid_guard=") ||
            lower.contains("sid_tt=") ||
            lower.contains("uid_tt=") ||
            lower.contains("login_status=1")
    }
}