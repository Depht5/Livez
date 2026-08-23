package dev.livefork.app.modules.home

import dev.livefork.app.app.EventBus
import dev.livefork.app.app.Site
import dev.livefork.app.app.Sites
import dev.livefork.app.app.controller.BaseController
import kotlinx.coroutines.Job

/**
 * 首页控制器 —— 对应 Dart `HomeController`
 * （lib/modules/home/home_controller.dart）。
 * Tab 控件（Flutter TabController）由页面层持有；此处管理站点子列表控制器。
 */
class HomeController : BaseController() {
    val sites: List<Site> = Sites.supportSites
    val homeListControllers: Map<String, HomeListController> = sites.associate { it.id to HomeListController(it) }

    private var eventSub: Job? = null

    init {
        eventSub = EventBus.listen<Int>(EventBus.kBottomNavigationBarClicked) { index ->
            if (index == 0) refreshOrScrollTop(homeListControllers.values.firstOrNull()?.let { 0 } ?: 0)
        }
    }

    /** 刷新或回到顶部（对应 Dart refreshOrScrollTop；滚动动画由 UI 层处理）。 */
    fun refreshOrScrollTop(tabIndex: Int) {
        val controller = sites.getOrNull(tabIndex)?.let { homeListControllers[it.id] } ?: return
        controller.refreshData()
    }

    fun dispose() {
        eventSub?.cancel()
    }
}