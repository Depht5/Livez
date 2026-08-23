package dev.livefork.app.modules.search.douyin

import android.webkit.WebView
import dev.livefork.app.app.Log
import dev.livefork.app.app.Site
import dev.livefork.app.app.controller.BaseController
import dev.livefork.core.model.LiveRoomItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.URI
import java.net.URLEncoder

/**
 * 抖音搜索控制器 —— 对应 Dart `douyin_search_controller.dart`（77 行）。
 * 用 WebView 加载抖音搜索页（type=live），用户点击主播时拦截
 * live.douyin.com/{id} 的新窗口/跳转并路由到直播详情页。
 * WebView 由 UI 层注入；onPageStarted/onPageFinished 回调页面加载状态；
 * 打开直播详情的路由经 onOpenLiveRoom 回调交给 UI 层。
 */
class DouyinSearchController(
    private val site: Site,
) : BaseController() {

    /** UI 层注入的 WebView。 */
    var webView: WebView? = null

    val list = MutableStateFlow<List<LiveRoomItem>>(emptyList())
    val searchMode = MutableStateFlow(0)
    val pageLoadding = MutableStateFlow(false)

    var keyword: String = ""

    var searchUrl = "https://www.douyin.com/search/dnf?type=live"

    /** UI 层注入：打开直播详情（对应 AppNavigator.toLiveRoomDetail）。 */
    var onOpenLiveRoom: ((roomId: String) -> Unit)? = null
    /** UI 层注入：打开系统浏览器/返回工具页（对应 Dart openBrowser）。 */
    var onOpenBrowser: (() -> Unit)? = null

    /** 重新加载搜索（对应 Dart reloadWebView）。 */
    fun reloadWebView() {
        if (keyword.isEmpty()) return
        searchUrl = "https://www.douyin.com/search/${URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")}?type=live"
        webView?.loadUrl(searchUrl)
    }

    /** UI 层 onPageFinished 回调。 */
    fun onLoadStop() {
        pageLoadding.value = false
    }

    /** UI 层 onPageStarted 回调。 */
    fun onLoadStart() {
        pageLoadding.value = true
    }

    /**
     * 拦截新窗口/跳转（对应 Dart onCreateWindow / shouldOverrideUrlLoading）。
     * 返回 true 表示已处理（live.douyin.com 直播间 → 详情页）。
     */
    fun handleUrl(url: String?): Boolean {
        if (url == null) return false
        val host = runCatching { URI(url).host }.getOrNull() ?: return false
        if (host == "live.douyin.com") {
            val match = Regex("live\\.douyin\\.com/([\\d\\w]+)").find(url)
            val id = match?.groupValues?.get(1) ?: ""
            if (id.isNotEmpty()) {
                onOpenLiveRoom?.invoke(id)
                return true
            }
        }
        return false
    }

    /** 打开系统浏览器并返回工具页（对应 Dart openBrowser）。 */
    fun openBrowser() {
        onOpenBrowser?.invoke()
    }
}