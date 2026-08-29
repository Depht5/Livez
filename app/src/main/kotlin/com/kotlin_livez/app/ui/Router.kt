package com.kotlin_livez.app.ui

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.navigation.NavController
import com.kotlin_livez.app.app.Log
import com.kotlin_livez.app.app.Site

/**
 * 全局路由 —— 对应 Flutter 端 `lib/routes/` 的导航职责。
 * UI 层页面跳转统一经此入口（Compose Navigation）；
 * 全部页面已迁路由，legacy startActivity 路径已删除。
 */
object Router {

    /**
     * 导航节流窗口（task 08-29-live-room-bars-follow-status 真机实证：快速双击卡片/选项
     * 会打开两次目标页）——navigate 同步执行后转场（280ms）期间上一页仍可交互，第二击
     * 再次导航即叠加入栈；窗口取 600ms > 转场时长且覆盖典型双击间隔，窗口内合法的跨页
     * 连跳实际不存在（转场中上一页不可交互）。back()/popBackStack 不受限。
     */
    private const val navigateThrottleMs = 600L

    /** 上次成功导航的时刻（elapsedRealtime 单调时钟，不受系统时间回拨影响）。 */
    private var lastNavigateElapsed = -navigateThrottleMs

    private var appContext: Context? = null

    /** Compose 导航控制器 —— 由 MainActivity setContent 后注入（见 ui/nav/AppNavHost.kt）。 */
    internal var navController: NavController? = null

    /**
     * Compose 路由跳转：navController 为空时（非 MainActivity 宿主场景）仅记日志丢弃；
     * 节流窗口内的重复导航忽略（防快速双击叠开两次页面）。
     */
    fun navigate(route: String) {
        val nc = navController
        if (nc == null) {
            Log.d("路由：navController 未注入，route=$route 已忽略")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastNavigateElapsed < navigateThrottleMs) {
            Log.d("路由：节流窗口内忽略重复导航 route=$route")
            return
        }
        lastNavigateElapsed = now
        nc.navigate(route)
    }

    /** 由 MainActivity/Application 在启动时注入。 */
    fun install(context: Context) {
        appContext = context.applicationContext
    }

    /** 返回上一页（navController 未注入时忽略；task m3-mine-settings 设置页顶栏返回）。 */
    fun back() {
        navController?.popBackStack()
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
