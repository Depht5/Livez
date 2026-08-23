package dev.livefork.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import dev.livefork.app.modules.mine.account.bilibili.BiliBiliWebLoginController
import dev.livefork.app.modules.mine.account.douyin.DouyinWebLoginController
import dev.livefork.app.modules.mine.account.kuaishou.KuaishouWebLoginController

/**
 * 网页登录页 —— 对应 Flutter `web_login_page.dart`（B 站/抖音/快手复用）。
 * 内嵌 WebView，加载各站点登录页；用户完成登录后自动读取 Cookie 保存账号。
 */
class WebLoginActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_TARGET = "target"

        fun start(context: Context, target: String) {
            context.startActivity(Intent(context, WebLoginActivity::class.java)
                .putExtra(EXTRA_TARGET, target)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private var biliController: BiliBiliWebLoginController? = null
    private var douyinController: DouyinWebLoginController? = null
    private var kuaishouController: KuaishouWebLoginController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = intent.getStringExtra(EXTRA_TARGET) ?: "bilibili"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        root.addView(progressBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpR(3),
        ))

        webView = WebView(this)
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                when (target) {
                    "douyin" -> douyinController?.onProgressChanged(newProgress)
                    "kuaishou" -> kuaishouController?.onProgressChanged(newProgress)
                }
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (target == "kuaishou") kuaishouController?.onLoadStart()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                when (target) {
                    "bilibili" -> biliController?.onLoadStop(url)
                    "douyin" -> douyinController?.onLoadStop()
                    "kuaishou" -> kuaishouController?.onLoadStop()
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    kuaishouController?.onReceivedError(error?.description?.toString() ?: "加载失败")
                }
            }
        }
        root.addView(webView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        setContentView(root)

        when (target) {
            "bilibili" -> {
                val controller = BiliBiliWebLoginController().apply {
                    webView = this@WebLoginActivity.webView
                    onToast = { Router.toast(it) }
                    onExit = { finish() }
                }
                biliController = controller
                controller.onWebViewCreated()
            }
            "douyin" -> {
                webView.settings.userAgentString = DouyinWebLoginController().userAgent
                val controller = DouyinWebLoginController().apply {
                    webView = this@WebLoginActivity.webView
                    onToast = { Router.toast(it) }
                    onExit = { finish() }
                }
                douyinController = controller
                controller.onWebViewCreated()
            }
            "kuaishou" -> {
                webView.settings.userAgentString = KuaishouWebLoginController().userAgent
                val controller = KuaishouWebLoginController().apply {
                    webView = this@WebLoginActivity.webView
                    onToast = { Router.toast(it) }
                    onExit = { finish() }
                }
                kuaishouController = controller
                controller.onWebViewCreated()
            }
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}