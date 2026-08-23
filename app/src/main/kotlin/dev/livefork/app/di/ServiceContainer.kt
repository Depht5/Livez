package dev.livefork.app.di

import android.content.Context
import dev.livefork.app.app.controller.AppSettingsController
import dev.livefork.app.services.BiliBiliAccountService
import dev.livefork.app.services.FollowService
import dev.livefork.app.services.CurrentRoomService
import dev.livefork.app.services.DBService
import dev.livefork.app.services.DouyinAccountService
import dev.livefork.app.services.KuaishouAccountService
import dev.livefork.app.services.LocalStorageService
import dev.livefork.core.LiveSiteRegistry
import dev.livefork.core.interfaces.LiveSite

/**
 * 轻量服务容器（替代 GetX 的 GetxService 全局注册）。
 * 对应 Flutter 端 `lib/services/` 各单例服务与 `lib/main.dart` 的 Get.put 装配。
 * 各服务以懒加载单例形式挂载；新增服务在此注册，供 ViewModel/页面获取。
 */
object ServiceContainer {

    private lateinit var app: Context

    private val sites: List<LiveSite> by lazy { LiveSiteRegistry.allSites }

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

    /** 快手账号服务（对应 Dart KuaishouAccountService）。 */
    val kuaishouAccount: KuaishouAccountService by lazy { KuaishouAccountService(localStorage) }

    /** 应用设置控制器（对应 Dart AppSettingsController）。 */
    val appSettings: AppSettingsController by lazy { AppSettingsController(localStorage) }

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
        kuaishouAccount.init()
        followService.install()
        // 后续在此注册同步服务等
    }

    fun context(): Context = app

    fun allSites(): List<LiveSite> = sites

    fun findSite(id: String): LiveSite? = sites.firstOrNull { it.id == id }
}
