package dev.livefork.app.ui

import android.content.Context
import android.widget.Toast
import androidx.navigation.NavController
import dev.livefork.app.app.Log
import dev.livefork.app.app.Site

/**
 * 全局路由 —— 对应 Flutter 端 `lib/routes/` 的导航职责。
 * UI 层页面跳转统一经此入口；过渡期为双模式：
 * - 已迁移页面：navController 注入后走 [navigate]（Compose Navigation）；
 * - 未迁移页面：保留 startActivity 兜底路径，全部迁移完成后删除 legacy。
 */
object Router {

    private var appContext: Context? = null

    /** Compose 导航控制器 —— 由 MainActivity setContent 后注入（见 ui/nav/AppNavHost.kt）。 */
    internal var navController: NavController? = null

    /**
     * Compose 路由跳转（双模式）：
     * - navController 非空：走 Compose Navigation；
     * - 为空（如非 MainActivity 宿主场景）：legacy 兜底。本批仅 main 路由存在，
     *   其余路由在后续批次迁移时在此接入对应兜底或直接改走 navigate。
     */
    fun navigate(route: String) {
        val nc = navController
        if (nc != null) {
            nc.navigate(route)
        } else {
            Log.d("路由：navController 未注入，route=$route 暂无 legacy 兜底")
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