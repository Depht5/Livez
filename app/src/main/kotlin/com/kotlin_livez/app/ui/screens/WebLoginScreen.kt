package com.kotlin_livez.app.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kotlin_livez.app.app.Log
import com.kotlin_livez.app.modules.mine.account.bilibili.BiliBiliWebLoginController
import com.kotlin_livez.app.modules.mine.account.douyin.DouyinWebLoginController
import com.kotlin_livez.app.ui.Router
import com.kotlin_livez.app.ui.theme.CustomIcons
import kotlinx.coroutines.delay

/**
 * 网页登录页（Compose 版）—— 对应 Dart `web_login_page.dart`（B 站/抖音复用），
 * 注册为 "weblogin/{siteId}" 路由。
 *
 * 结构镜像：M3 顶栏（标题居中 + 左上角返回）+ 顶部 3dp 进度条 + WebView 填满剩余空间。
 * 标题按 siteId 区分（"登录哔哩哔哩账号" / "登录抖音账号"）。
 *
 * 修复过的显示缺陷：
 * 1. **进度条不消失**：原来 3dp 轨道 + 填充常驻渲染（轨道是浅灰 #E0E0E0，加载完就是一条实线）。
 *    现在只在 `1..99` 期间画填充，完成/失败立刻消失（3dp 高度始终占位，不产生跳动）。
 * 2. **登录后不落库/页面被整站顶掉**：补 `shouldOverrideUrlLoading`，命中
 *    `m.bilibili.com` 或 `www.bilibili.com` 即读 Cookie 保存并取消导航（Dart 的双 host 判定）；
 *    非 http(s) 的 scheme（`bilibili://` 等）一律拦下，避免 WebView 报「网页无法打开」。
 * 3. **页面内容集中在顶部、下方大片空白**（真机实测：登录页协议行停在登录按钮处）：
 *    页面按**中间态视口高度**完成了首次排版（IME / 系统栏 inset 动画期间 WebView 高度会变），
 *    之后不会重排。修法三件套：WebView 容器去掉 `imePadding()`（它会随输入法动画收缩视口）、
 *    等尺寸**稳定 120ms** 后再 `loadUrl`、`onPageFinished` 时派发一次 `resize` 让页面重算视口。
 *    UA 只保留 B 站 iPhone UA（对齐 Dart）；`useWideViewPort`/`loadWithOverviewMode`/`textZoom`
 *    **刻意不设**（实测无帮助，且会与页面自身的 viewport 处理打架）。
 * 4. **被系统导航栏遮挡**：WebView 容器改为 `weight(1f)`（等价旧版 LinearLayout weight=1）
 *    并加 `navigationBarsPadding()`。
 *
 * 返回键：Navigation 默认 popBackStack 对齐原 Activity finish，无需额外 BackHandler。
 * WebView 互操作说明：按规范白名单经 AndroidView 包裹；onDispose 先 stopLoading 再 destroy。
 *
 * @param siteId 登录站点 id："bilibili" 或 "douyin"（route path 参数）
 * @param onBack 返回回调（对应原 Activity finish / 控制器 onExit → popBackStack）
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLoginScreen(
    siteId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 进度条状态（0-100）：WebChromeClient.onProgressChanged 驱动；100 表示"已结束"（不显示）
    var progress by remember { mutableIntStateOf(0) }

    // WebView 尺寸与"可以加载了"门控：尺寸稳定后才加载，避免按中间态视口排版
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var viewReady by remember { mutableStateOf(false) }

    // WebView 实例：remember 持有，AndroidView 复用同一实例（避免 factory 重组重建）
    val context = LocalContext.current
    val webView = remember { WebView(context) }

    // 按站点创建控制器并接线（对齐现版 onCreate 的 when 分支）
    val biliController = remember(siteId) {
        if (siteId == "bilibili") {
            BiliBiliWebLoginController().apply {
                this.webView = webView
                onToast = { Router.toast(it) }
                onExit = { onBack() }
            }
        } else {
            null
        }
    }
    val douyinController = remember(siteId) {
        if (siteId == "douyin") {
            DouyinWebLoginController().apply {
                this.webView = webView
                onToast = { Router.toast(it) }
                onExit = { onBack() }
            }
        } else {
            null
        }
    }

    // WebView 设置与回调接线（一次性；UA 必须在 loadUrl 之前设置好）
    DisposableEffect(webView, siteId) {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        // 只保留 UA 一项（与 Dart web_login_page 的 initialSettings 等价）：
        // useWideViewPort / loadWithOverviewMode / textZoom 刻意**不设** ——
        // 本页曾因这些"viewer 适配"设置与页面自身的 viewport 处理打架，
        // 且实测对布局无帮助（详见 ui-compose.md 条目 41）。
        when {
            douyinController != null -> settings.userAgentString = douyinController.userAgent
            biliController != null -> settings.userAgentString = biliController.userAgent
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress = newProgress
                douyinController?.onProgressChanged(newProgress)
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progress = 0
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // 兜底收起进度条：部分页面 onProgressChanged 到不了 100
                progress = 100
                // 兜底让页面重算视口：若首屏按旧尺寸排版过（vh / innerHeight 被缓存），
                // 派发一次 resize 让依赖视口高度的元素回到正确位置（多数 H5 监听 resize）。
                view?.evaluateJavascript("window.dispatchEvent(new Event('resize'))", null)
                // 诊断留痕（抓取日志可见）：页面自报的视口尺寸 vs WebView 实际尺寸。
                // 若后续再出现"内容堆在顶部/下方空白"，看这一行就能定位是不是视口高度问题。
                view?.evaluateJavascript(
                    "(function(){try{return JSON.stringify({ih:innerHeight,iw:innerWidth," +
                        "dpr:devicePixelRatio,ch:document.documentElement.clientHeight," +
                        "bh:document.body.scrollHeight})}catch(e){return '{}'}})()",
                ) { raw ->
                    Log.d("[WebLogin] viewport=$raw view=${view.width}x${view.height}")
                }
                biliController?.onLoadStop(url)
                douyinController?.onLoadStop()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                // 只对主文档错误收起进度条（子资源失败不应打断整页进度语义）
                if (request?.isForMainFrame == true) progress = 100
            }

            /**
             * 登录完成判定：B 站命中 m/www.bilibili.com 即保存并取消导航；
             * 其余非 http(s) 的 scheme 一律拦下（否则 WebView 会显示「网页无法打开」）。
             */
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") return true
                return biliController?.shouldInterceptNavigation(uri.host) == true
            }
        }
        onDispose {
            // 对齐原 onDestroy：先停加载再销毁（控制器持有的 webView 引用一并失效）
            webView.stopLoading()
            webView.destroy()
        }
    }

    // 尺寸**稳定**后再加载：IME / 系统栏 inset 动画期间 WebView 高度会变化，
    // 页面若按中间态的视口高度完成首次排版（vh / window.innerHeight），
    // footer 之类依赖视口的元素会停在错误位置且不再自动重排
    //（真机实测：登录页协议行停在登录按钮处，下方整片空白）。
    LaunchedEffect(viewSize, viewReady) {
        if (viewReady) return@LaunchedEffect
        if (viewSize.width <= 0 || viewSize.height <= 0) return@LaunchedEffect
        delay(120)
        viewReady = true
    }

    LaunchedEffect(viewReady) {
        if (!viewReady) return@LaunchedEffect
        biliController?.onWebViewCreated()
        douyinController?.onWebViewCreated()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding(),
    ) {
        // M3 顶栏：标题居中 + 左上角返回（从账号管理跳入，需明确可返回）
        Box(
            modifier = Modifier.fillMaxWidth().height(56.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (siteId == "douyin") "登录抖音账号" else "登录哔哩哔哩账号",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(48.dp)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = CustomIcons.Back,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        // 顶部进度条：3dp 高度恒定占位；只在 1..99 期间画填充，加载结束即消失
        Box(modifier = Modifier.fillMaxWidth().height(3.dp)) {
            if (progress in 1..99) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress / 100f)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }

        // WebView 容器：占满剩余高度（等价旧版 LinearLayout weight=1），并避开系统导航栏。
        // **不加 imePadding()**：输入法 inset 动画会改变 WebView 高度，页面若在这一瞬完成首次排版，
        // 依赖视口高度的元素（协议行等）会停在错误位置（真机实测的"下方大片空白"就是这个）。
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            AndroidView(
                factory = { webView },
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { size -> viewSize = size },
            )
        }
    }
}
