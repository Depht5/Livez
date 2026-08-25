package dev.livefork.app.modules.home

import dev.livefork.app.app.Site
import dev.livefork.app.app.Sites
import dev.livefork.app.app.controller.BaseController

/**
 * 首页控制器 —— 对应 Dart `HomeController`
 * （lib/modules/home/home_controller.dart）。
 * 站点 Tab 控件由页面层持有；此处仅管理站点子列表控制器。
 *
 * 注：kBottomNavigationBarClicked 的监听已移至 UI 层 `ui/screens/HomeScreen.kt` ——
 * Dart 版监听后调用的 scrollToTopOrRefresh 依赖视图持有的 ScrollController /
 * EasyRefreshController；Compose 下滚动状态（LazyGridState）同样由页面层持有，
 * 故「重复点击首页底栏 Tab → 回顶或刷新」整体在 HomeScreen 处理
 * （发射语义见 AppNavHost.MainShell 底栏：仅重复点击当前 Tab 时发射，对齐 Dart）。
 */
class HomeController : BaseController() {
    val sites: List<Site> = Sites.supportSites
    val homeListControllers: Map<String, HomeListController> = sites.associate { it.id to HomeListController(it) }

    /**
     * 释放资源（对应 Dart onClose 的 streamSubscription.cancel 与各子控制器 onClose）：
     * 取消各站点列表控制器的协程作用域（其中可能存在进行中的网络请求）。
     */
    fun dispose() {
        homeListControllers.values.forEach { it.dispose() }
    }
}
