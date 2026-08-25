package dev.livefork.app.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.navigation.NavController
import dev.livefork.app.app.Log
import dev.livefork.app.app.Site

/**
 * 全局路由 —— 对应 Flutter 端 `lib/routes/` 的导航职责。
 * UI 层页面跳转统一经此入口（Compose Navigation）；
 * 全部页面已迁路由，legacy startActivity 路径已删除。
 */
object Router {

    private var appContext: Context? = null

    /** Compose 导航控制器 —— 由 MainActivity setContent 后注入（见 ui/nav/AppNavHost.kt）。 */
    internal var navController: NavController? = null

    /**
     * Compose 路由跳转：navController 为空时（非 MainActivity 宿主场景）仅记日志丢弃。
     */
    fun navigate(route: String) {
        val nc = navController
        if (nc != null) {
            nc.navigate(route)
        } else {
            Log.d("路由：navController 未注入，route=$route 已忽略")
        }
    }

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

    /** 打开直播间详情（对应 AppNavigator.toLiveRoomDetail；参数经 URL 编码进 route path）。 */
    fun openLiveRoom(site: Site, roomId: String) {
        Log.d("路由：打开直播间 site=${site.id} roomId=$roomId")
        navigate("livertoom/${Uri.encode(site.id)}/${Uri.encode(roomId)}")
    }

    /** 打开功能页（全部 key 均走 Compose 路由）。 */
    fun openFeature(key: String, title: String) {
        Log.d("路由：打开功能页 key=$key")
        when (key) {
            "settings" -> navigate("settings")
            "history" -> navigate("history")
            "account" -> navigate("account")
            "search" -> navigate("search")
            "follow" -> navigate("follow")
            else -> toast("$title 页面将在后续版本接入")
        }
    }
}
