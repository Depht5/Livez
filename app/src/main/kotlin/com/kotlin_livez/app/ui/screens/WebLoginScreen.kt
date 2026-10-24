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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kotlin_livez.app.modules.mine.account.bilibili.BiliBiliWebLoginController
import com.kotlin_livez.app.modules.mine.account.douyin.DouyinWebLoginController
import com.kotlin_livez.app.ui.Router
import com.kotlin_livez.app.ui.theme.CustomIcons

/**
 * 网页登录页（Compose 版）—— 对应 Dart `web_login_page.dart`（B 站/抖音复用），
 * 注册为 "weblogin/{siteId}" 路由。
 *
 * 结构镜像：M3 顶栏（标题居中 + 左上角返回）+ 顶部 3dp 水平进度条 + WebView 填满剩余空间。
 * 标题按 siteId 区分（"登录哔哩哔哩账号" / "登录抖音账号"）。WebView settings
 * （JS/domStorage/LOAD_DEFAULT）、桌面 UA（抖音）、回调链路（onProgressChanged /
 * onPageFinished → 控制器 onLoadStop）与 CookieManager 读取行为全部照搬现版。
 *
 * 本轮保留的两处修复（其余针对"页面内容堆在顶部/下方大片空白"的试验性改动已全部回滚，
 * 该问题**仍未解决**，现场与结论见 `.trellis/spec/kotlin_re/ui-compose.md` 条目 41）：
 * 1. **进度条加载结束不消失**：原实现 3dp 轨道 + 填充常驻渲染（轨道浅灰 #E0E0E0，
 *    加载完就是一条实线）。现在只在 `1..99` 期间画填充；`onPageStarted` 归零，
 *    `onPageFinished` 与「主文档错误」置 100（兜底：部分页面 progress 到不了 100）。
 * 2. **登录成功后不落库/页面被整站接替**：补 `shouldOverrideUrlLoading` —— 命中
 *    `m.bilibili.com` / `www.bilibili.com` 时由控制器读 Cookie 保存并取消导航
 *    （对齐 Dart 的双 host 判定）；非 http(s) 的 scheme 一律拦下，避免 WebView
 *    显示「网页无法打开」。
 *
 * 返回键：Navigation 默认 popBackStack 对齐原 Activity finish，无需额外 BackHandler。
 * WebView 互操作说明：按规范白名单经 AndroidView 包裹；onDispose 调 webView.destroy()
 * 对齐原 onDestroy 清理。
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

    // WebView 初始化与控制器启动：一次性副作用（对齐现版 onCreate 尾部 setup）
    DisposableEffect(webView, siteId) {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        // 抖音需要桌面 UA 才能出网页登录态（照搬现版）
        if (douyinController != null) {
            settings.userAgentString = douyinController.userAgent
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

        biliController?.onWebViewCreated()
        douyinController?.onWebViewCreated()

        onDispose {
            // 对齐原 onDestroy 的 webView.destroy()；
            // 控制器持有的 webView 引用一并失效
            webView.destroy()
        }
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

        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
