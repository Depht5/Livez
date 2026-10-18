package com.kotlin_livez.app.di

import android.content.Context
import com.kotlin_livez.app.app.controller.AppSettingsController
import com.kotlin_livez.app.services.BiliBiliAccountService
import com.kotlin_livez.app.services.FollowService
import com.kotlin_livez.app.services.CurrentRoomService
import com.kotlin_livez.app.services.DBService
import com.kotlin_livez.app.services.DouyinAccountService
import com.kotlin_livez.app.services.DouyuAccountService
import com.kotlin_livez.app.modules.live_room.LiveRoomController
import com.kotlin_livez.app.modules.search.AppSearchController
import com.kotlin_livez.app.services.LocalStorageService

/**
 * 轻量服务容器（替代 GetX 的 GetxService 全局注册）。
 * 对应 Flutter 端 `lib/services/` 各单例服务与 `lib/main.dart` 的 Get.put 装配。
 * 各服务以懒加载单例形式挂载；新增服务在此注册，供 ViewModel/页面获取。
 */
object ServiceContainer {

    private lateinit var app: Context

    /** 数据库服务（Room：follow / history / tag，对应 Dart DBService）。 */
    val dbService: DBService by lazy { DBService(app) }

    /** 本地键值存储（DataStore，对应 Dart LocalStorageService + 设置 box）。 */
    val localStorage: LocalStorageService by lazy { LocalStorageService(app) }

    /** 当前房间服务（对应 Dart CurrentRoomService）。 */
    val currentRoom: CurrentRoomService by lazy { CurrentRoomService() }

    /** B 站账号服务（对应 Dart BiliBiliAccountService）。 */
    val biliBiliAccount: BiliBiliAccountService by lazy { BiliBiliAccountService(localStorage) }

    /** 抖音账号服务（对应 Dart DouyinAccountService）。 */
    val douyinAccount: DouyinAccountService by lazy { DouyinAccountService(localStorage) }

    /** 斗鱼账号服务（对应 Dart DouyuAccountService）。 */
    val douyuAccount: DouyuAccountService by lazy { DouyuAccountService(localStorage) }

    /** 应用设置控制器（对应 Dart AppSettingsController）。 */
    val appSettings: AppSettingsController by lazy { AppSettingsController(localStorage) }

    /** 搜索会话控制器（对应 Dart 路由 Binding 注册的 AppSearchController）——
     *  直播间是压栈路由，返回时搜索会话必须仍是同一份（关键词/结果/模式/站点都在）。 */
    val appSearchController: AppSearchController by lazy { AppSearchController(appSettings) }

    /** 关注服务（对应 Dart FollowService）。 */
    val followService: FollowService by lazy {
        FollowService(dbService, localStorage, currentRoom, appSettings)
    }

    fun install(app: Context) {
        this.app = app.applicationContext
        // 初始化各账号服务（镜像 Dart main.dart 中的 Get.put + onInit）
        appSettings.init()
        biliBiliAccount.init()
        douyinAccount.init()
        douyuAccount.init()
        followService.install()
        // 后续在此注册同步服务等
    }

    fun context(): Context = app

    /** 当前活跃直播间控制器（若在直播间内）：MainActivity 生命周期转发与后台播放逻辑的接线点，
     *  由 LiveRoomScreen 的 DisposableEffect 注册/注销（仿 Router 持有 navController 模式）。 */
    var activeLiveRoom: LiveRoomController? = null
}
