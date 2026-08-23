package dev.livefork.app.ui

import android.content.Context
import android.widget.Toast
import dev.livefork.app.app.Log
import dev.livefork.app.app.Site

/**
 * 全局路由 —— 对应 Flutter 端 `lib/routes/` 的导航职责。
 * UI 层页面跳转统一经此入口；当前实现为回调注册 + Toast 占位，
 * 直播详情/LiveRoomActivity 等页面落地下一步接入。
 */
object Router {

    private var appContext: Context? = null

    /** 由 MainActivity/Application 在启动时注入。 */
    fun install(context: Context) {
        appContext = context.applicationContext
    }

    /** 轻量 Toast（对应 Flutter SmartDialog.showToast / Utils.showToast）。 */
    fun toast(message: String) {
        val ctx = appContext ?: return
        Log.d("Toast: $message")
        Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
    }

    /** 打开直播间详情（对应 AppNavigator.toLiveRoomDetail）。 */
    fun openLiveRoom(site: Site, roomId: String) {
        Log.d("路由：打开直播间 site=${site.id} roomId=$roomId")
        val ctx = appContext ?: run {
            openLiveRoomListener?.invoke(site, roomId)
            return
        }
        LiveRoomActivity.start(ctx, site.id, roomId)
    }

    /** UI 层注入：直播详情页跳转实现（后续由 LiveRoomActivity 注册）。 */
    var openLiveRoomListener: ((Site, String) -> Unit)? = null

    /** 打开网页登录（WebView 登录页，后续接入）。 */
    fun openWebLogin(target: String) {
        Log.d("路由：打开网页登录 target=$target")
        toast("网页登录功能将在后续版本接入")
    }

    /** 打开扫码登录（B 站）。 */
    fun openQRLogin() {
        Log.d("路由：打开扫码登录")
        toast("扫码登录功能将在后续版本接入")
    }

    /** 打开功能页（设置/历史/账号等；未接入的 key 保持占位提示）。 */
    fun openFeature(key: String, title: String) {
        Log.d("路由：打开功能页 key=$key")
        when (key) {
            "settings" -> SettingsActivity.start(appContext!!)
            "history" -> HistoryActivity.start(appContext!!)
            "account" -> AccountActivity.start(appContext!!)
            "search" -> SearchActivity.start(appContext!!)
            "follow" -> FollowUserActivity.start(appContext!!)
            else -> toast("$title 页面将在后续版本接入")
        }
    }
}