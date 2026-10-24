package com.kotlin_livez.app.modules.mine.account.bilibili

import android.webkit.CookieManager
import android.webkit.WebView
import com.kotlin_livez.app.app.Log
import com.kotlin_livez.app.di.ServiceContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * B 站网页登录控制器 —— 对应 Dart `web_login_controller.dart`（51 行）。
 * 使用 WebView 加载 passport.bilibili.com/login；跳转到 m.bilibili.com / www.bilibili.com
 * 视为登录完成，读取 bilibili.com 的 Cookie 保存账号。
 *
 * WebView 实例与页面渲染由 UI 层负责（注入 webView，并在 onPageFinished 调 [onLoadStop]、
 * 在 shouldOverrideUrlLoading 调 [shouldInterceptNavigation]）。
 */
class BiliBiliWebLoginController {

    companion object {
        /**
         * B 站登录页 UA —— 与 Dart `web_login_page.dart` 的 `initialSettings.userAgent` 完全一致。
         *
         * 必须显式设置：默认 Android Chrome UA 在平板上会让 passport 页面按 1385dp 视口排版，
         * 出现元素错位/下方大片空白；iPhone UA 命中 B 站为移动端准备的窄屏 H5 布局。
         */
        const val USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/13.0.3 Mobile/15E148 Safari/604.1 Edg/118.0.0.0"
    }

    /** UI 层注入的 WebView（页面容器）。 */
    var webView: WebView? = null

    /** UI 层注入回调。 */
    var onToast: ((String) -> Unit)? = null
    var onExit: (() -> Unit)? = null

    /** 异步保存账号用（网络请求不得占用主线程）。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val userAgent: String get() = USER_AGENT

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

    /**
     * UI 层在 `shouldOverrideUrlLoading` 调用：登录完成的导航要拦下来。
     *
     * Dart 在 `m.bilibili.com` **与** `www.bilibili.com` 两处都 `CANCEL` 并保存 Cookie；
     * 若只认 `m.bilibili.com`，登录成功后 WebView 会被 B 站整站页面接替（看起来"位置全乱"），
     * 而且可能永远不触发保存。命中即返回 true（取消导航）。
     */
    fun shouldInterceptNavigation(host: String?): Boolean {
        if (host != "m.bilibili.com" && host != "www.bilibili.com") return false
        logined()
        return true
    }

    /** UI 层在 WebViewClient.onPageFinished 调用（对应 Dart onLoadStop）。 */
    fun onLoadStop(url: String?) {
        if (url == null) return
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return
        if (host == "m.bilibili.com") {
            logined()
        }
    }

    /** 读取 Cookie 并保存账号（对应 Dart logined）。 */
    fun logined() {
        try {
            val cookies = CookieManager.getInstance().getCookie("https://bilibili.com") ?: ""
            if (cookies.isEmpty()) return
            // 只记长度，不把 Cookie 明文写进日志
            Log.i("[BiliBiliWebLogin] 读取到 Cookie（${cookies.length} 字符）")
            ServiceContainer.biliBiliAccount.setCookie(cookies)
            // 原实现在主线程 runBlocking 里请求用户信息（ANR 风险），改到主线程协程异步执行
            scope.launch {
                ServiceContainer.biliBiliAccount.loadUserInfo()
                onToast?.invoke("B 站账号登录成功")
                onExit?.invoke()
            }
        } catch (e: Exception) {
            Log.logPrint(e)
        }
    }
}
