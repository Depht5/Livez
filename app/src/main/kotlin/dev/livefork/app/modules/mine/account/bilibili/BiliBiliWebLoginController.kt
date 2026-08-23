package dev.livefork.app.modules.mine.account.bilibili

import android.webkit.CookieManager
import android.webkit.WebView
import dev.livefork.app.app.Log
import dev.livefork.app.di.ServiceContainer
import java.net.URI

/**
 * B 站网页登录控制器 —— 对应 Dart `web_login_controller.dart`（51 行）。
 * 使用 WebView 加载 passport.bilibili.com/login；跳转到 m.bilibili.com 视为登录完成，
 * 读取 bilibili.com 的 Cookie 保存账号。
 * WebView 实例与页面渲染由 UI 层负责（注入 webView，并在 onPageFinished 调用 onLoadStop）。
 */
class BiliBiliWebLoginController {

    /** UI 层注入的 WebView（页面容器）。 */
    var webView: WebView? = null

    /** UI 层注入回调。 */
    var onToast: ((String) -> Unit)? = null
    var onExit: (() -> Unit)? = null

    fun loginUrl(): String = "https://passport.bilibili.com/login"

    /** UI 层在 WebView 创建时调用：加载登录页。 */
    fun onWebViewCreated() {
        webView?.loadUrl(loginUrl())
    }

    /** 跳转扫码登录页（对应 Dart toQRLogin；onNavigate 由 UI 层执行路由）。 */
    fun toQRLogin(onNavigate: () -> Unit) {
        onNavigate()
        onExit?.invoke()
    }

    /** UI 层在 WebViewClient.onPageFinished 调用（对应 Dart onLoadStop）。 */
    fun onLoadStop(url: String?) {
        if (url == null) return
        val uri = runCatching { URI(url) }.getOrNull() ?: return
        if (uri.host == "m.bilibili.com") {
            logined()
        }
    }

    /** 读取 Cookie 并保存账号（对应 Dart logined）。 */
    fun logined() {
        try {
            val cookies = CookieManager.getInstance().getCookie("https://bilibili.com") ?: ""
            if (cookies.isEmpty()) return
            Log.i(cookies)
            ServiceContainer.biliBiliAccount.setCookie(cookies)
            kotlinx.coroutines.runBlocking { ServiceContainer.biliBiliAccount.loadUserInfo() }
            onToast?.invoke("B 站账号登录成功")
            onExit?.invoke()
        } catch (e: Exception) {
            Log.logPrint(e)
        }
    }
}