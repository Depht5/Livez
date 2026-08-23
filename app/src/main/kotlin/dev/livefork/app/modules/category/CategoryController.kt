package dev.livefork.app.modules.category

import dev.livefork.app.app.EventBus
import dev.livefork.app.app.Site
import dev.livefork.app.app.Sites
import dev.livefork.app.app.controller.BaseController
import kotlinx.coroutines.Job

/**
 * 类目页控制器 —— 对应 Dart `CategoryController`
 * （lib/modules/category/category_controller.dart）。
 * Tab 控件由页面层持有；此处管理站点子列表控制器。
 */
class CategoryController : BaseController() {
    val sites: List<Site> = Sites.supportSites
    val categoryListControllers: Map<String, CategoryListController> =
        sites.associate { it.id to CategoryListController(it) }

    private var eventSub: Job? = null

    init {
        eventSub = EventBus.listen<Int>(EventBus.kBottomNavigationBarClicked) { index ->
            if (index == 2) refreshOrScrollTop(0)
        }
    }

    /** 刷新或回到顶部（对应 Dart refreshOrScrollTop）。 */
    fun refreshOrScrollTop(tabIndex: Int) {
        val controller = sites.getOrNull(tabIndex)?.let { categoryListControllers[it.id] } ?: return
        controller.refreshData()
    }

    fun dispose() {
        eventSub?.cancel()
    }
}