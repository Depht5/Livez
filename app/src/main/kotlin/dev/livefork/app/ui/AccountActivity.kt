package dev.livefork.app.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import dev.livefork.app.di.ServiceContainer
import dev.livefork.app.modules.mine.account.AccountController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * 账号管理页 —— 对应 Flutter `account_page.dart`。
 * 三站账号状态 + 登录方式（网页登录/扫码登录/粘贴 Cookie）+ 登出；
 * 文件导入、浏览器打开等能力后续接入。
 */
class AccountActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, AccountActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private val account = AccountController(
        bilibiliAccount = ServiceContainer.biliBiliAccount,
        douyinAccount = ServiceContainer.douyinAccount,
        kuaishouAccount = ServiceContainer.kuaishouAccount,
    )
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(0xF5, 0xF5, 0xF5))
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(content)
        setContentView(root)

        buildBilibiliSection()
        buildDouyinSection()
        buildKuaishouSection()
        refreshAccountStates()
    }

    private fun buildBilibiliSection() {
        content.addView(SettingRows.sectionTitle(this, "哔哩哔哩"))
        content.addView(SettingRows.clickRow(this, "网页登录", "使用浏览器登录 B 站") {
            WebLoginActivity.start(this, "bilibili")
        })
        content.addView(SettingRows.clickRow(this, "扫码登录", "使用手机扫码登录") {
            QRLoginActivity.start(this)
        })
        content.addView(SettingRows.clickRow(this, "粘贴 Cookie") {
            showCookieDialog("B 站 Cookie", "请输入 Cookie（形如 SESSDATA=...; bili_jct=...）") { input ->
                scopeLaunch {
                    account.doBiliBiliCookieLogin(input.trim())
                    Router.toast("B 站账号登录成功")
                }
            }
        })
        content.addView(SettingRows.clickRow(this, "退出登录", "清除 B 站账号") {
            account.logoutBilibili()
            refreshAccountStates()
        })
    }

    private fun buildDouyinSection() {
        content.addView(SettingRows.sectionTitle(this, "抖音"))
        content.addView(SettingRows.clickRow(this, "网页登录", "在页面内完成抖音登录") {
            WebLoginActivity.start(this, "douyin")
        })
        content.addView(SettingRows.clickRow(this, "粘贴 Cookie") {
            showCookieDialog("抖音 Cookie", "请输入抖音登录 Cookie (sid_guard=...)") { input ->
                account.applyDouyinCookieInput(input)
                refreshAccountStates()
            }
        })
        content.addView(SettingRows.clickRow(this, "恢复默认 ttwid") {
            AlertDialog.Builder(this)
                .setTitle("恢复默认 ttwid")
                .setMessage("确定恢复默认 ttwid 并清除自定义 Cookie 吗？")
                .setPositiveButton("恢复") { _, _ ->
                    account.applyDouyinCookieInput("")
                    refreshAccountStates()
                }
                .setNegativeButton("取消", null)
                .show()
        })
    }

    private fun buildKuaishouSection() {
        content.addView(SettingRows.sectionTitle(this, "快手"))
        content.addView(SettingRows.clickRow(this, "网页登录", "在页面内完成快手登录") {
            WebLoginActivity.start(this, "kuaishou")
        })
        content.addView(SettingRows.clickRow(this, "粘贴 Cookie") {
            showCookieDialog("快手 Cookie", "请输入快手 Cookie（可附带 kwfv1）") { input ->
                account.applyKuaishouCookieInput(input)
                refreshAccountStates()
            }
        })
    }

    /** 刷新账号状态副标题。 */
    private fun refreshAccountStates() {
        // 简化：重建页面以显示最新状态
        content.removeAllViews()
        buildBilibiliSection()
        buildDouyinSection()
        buildKuaishouSection()
    }

    private fun showCookieDialog(title: String, message: String, onConfirm: (String) -> Unit) {
        val input = EditText(this).apply {
            hint = "在此输入 Cookie"
            textSize = 14f
            isSingleLine = false
            minLines = 3
            maxLines = 6
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(input)
            .setPositiveButton("保存") { _, _ -> onConfirm(input.text?.toString() ?: "") }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun scopeLaunch(block: suspend () -> Unit) {
        GlobalScope.launch(Dispatchers.Main) { block() }
    }

    override fun onDestroy() {
        account.dispose()
        super.onDestroy()
    }
}