package dev.livefork.app.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.livefork.app.modules.mine.account.bilibili.BiliBiliQRLoginController
import dev.livefork.app.modules.mine.account.bilibili.QRStatus
import dev.livefork.app.ui.Router
import dev.livefork.app.ui.theme.CardTitle
import dev.livefork.app.ui.theme.TextTertiary

/**
 * B 站扫码登录页（Compose 版）—— 对应 Dart `qr_login_page.dart`，
 * 替换现版 View 体系 `ui/QRLoginActivity.kt`，注册为 "qrlogin" 路由。
 *
 * 结构镜像：标题「B 站扫码登录」18sp 居中 + 状态文案 13sp #888 居中 +
 * WebView 打开官方扫码确认页（qrcodeUrl）。控制器负责 generate + 3s 轮询 +
 * set-cookie 保存账号；登录成功 onExit → popBackStack（对齐原 finish）。
 *
 * 返回键：Navigation 默认 popBackStack 对齐原 Activity finish，无需 BackHandler。
 *
 * WebView 互操作说明：按规范白名单经 AndroidView 包裹；
 * onDispose 先 controller.close() 停轮询、再 webView.destroy()，
 * 顺序对齐原 onDestroy（controller.close → scope cancel → destroy）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun QRLoginScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // 控制器路由级生命周期：remember 创建；onDispose close() 停止轮询协程
    val controller = remember {
        BiliBiliQRLoginController().apply {
            onToast = { Router.toast(it) }
            onExit = { onBack() }
        }
    }

    // WebView 实例：remember 持有，AndroidView 复用同一实例
    val qrWebView = remember { WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        webViewClient = WebViewClient()
    } }

    // 二维码链接到达后加载（对齐现版 collect qrcodeUrl → loadUrl）
    val qrcodeUrl by controller.qrcodeUrl.collectAsStateWithLifecycle()
    LaunchedEffect(qrcodeUrl) {
        if (qrcodeUrl.isNotEmpty()) {
            qrWebView.loadUrl(qrcodeUrl)
        }
    }

    // 登录状态文案（映射对齐现版 collect qrStatus 的 when 分支）
    val status by controller.qrStatus.collectAsStateWithLifecycle()
    val statusText = when (status) {
        QRStatus.loading -> "二维码加载中…"
        QRStatus.unscanned -> "请使用手机 B 站扫码"
        QRStatus.scanned -> "已扫码，请在手机上确认"
        QRStatus.expired -> "二维码已过期，请重新加载"
        QRStatus.failed -> "二维码加载失败"
    }

    Column(modifier = modifier.fillMaxSize().background(Color.White)) {
        Text(
            text = "B 站扫码登录",
            fontSize = 18.sp,
            color = CardTitle,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 8.dp),
        )
        Text(
            text = statusText,
            fontSize = 13.sp,
            color = TextTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        )

        AndroidView(
            factory = { qrWebView },
            modifier = Modifier.fillMaxSize(),
        )
    }

    // 启动加载二维码（对齐现版 onCreate 尾部的 controller.loadQRCode()）
    DisposableEffect(controller) {
        controller.loadQRCode()
        onDispose {
            // 对齐原 onDestroy：先停控制器轮询、再销毁 WebView
            controller.close()
            qrWebView.destroy()
        }
    }
}
