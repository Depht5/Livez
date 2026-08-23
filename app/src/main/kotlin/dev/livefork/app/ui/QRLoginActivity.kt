package dev.livefork.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dev.livefork.app.modules.mine.account.bilibili.BiliBiliQRLoginController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * B 站扫码登录页 —— 对应 Flutter `qr_login_page.dart`。
 * 用 WebView 打开二维码链接（官方扫码确认页），控制器轮询登录状态，
 * 成功后保存账号并退出；二维码图片渲染（无图片库）以文本链接兜底。
 */
class QRLoginActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, QRLoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: BiliBiliQRLoginController
    private lateinit var statusText: TextView
    private lateinit var qrWebView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val header = TextView(this).apply {
            text = "B 站扫码登录"
            textSize = 18f
            setTextColor(Color.parseColor("#222222"))
            gravity = Gravity.CENTER
            setPadding(0, dpR(16), 0, dpR(8))
        }
        root.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        statusText = TextView(this).apply {
            text = "二维码加载中…"
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dpR(8))
        }
        root.addView(statusText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        qrWebView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
        }
        root.addView(qrWebView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

        setContentView(root)

        controller = BiliBiliQRLoginController().apply {
            onToast = { Router.toast(it) }
            onExit = { finish() }
        }

        scope.launch {
            controller.qrStatus.collect { status ->
                statusText.text = when (status) {
                    dev.livefork.app.modules.mine.account.bilibili.QRStatus.loading -> "二维码加载中…"
                    dev.livefork.app.modules.mine.account.bilibili.QRStatus.unscanned -> "请使用手机 B 站扫码"
                    dev.livefork.app.modules.mine.account.bilibili.QRStatus.scanned -> "已扫码，请在手机上确认"
                    dev.livefork.app.modules.mine.account.bilibili.QRStatus.expired -> "二维码已过期，请重新加载"
                    dev.livefork.app.modules.mine.account.bilibili.QRStatus.failed -> "二维码加载失败"
                }
            }
        }
        scope.launch {
            controller.qrcodeUrl.collect { url ->
                if (url.isNotEmpty()) {
                    qrWebView.loadUrl(url)
                }
            }
        }
        controller.loadQRCode()
    }

    override fun onDestroy() {
        controller.close()
        scope.coroutineContext[Job]?.cancel()
        qrWebView.destroy()
        super.onDestroy()
    }
}