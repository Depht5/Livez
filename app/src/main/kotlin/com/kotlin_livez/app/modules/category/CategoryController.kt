package com.kotlin_livez.app.modules.category

import com.kotlin_livez.app.app.Site
import com.kotlin_livez.app.app.Sites
import com.kotlin_livez.app.app.controller.BaseController

/**
 * 类目页控制器 —— 对应 Dart `CategoryController`
 * （lib/modules/category/category_controller.dart）。
 * Tab 控件由页面层持有；此处管理站点子列表控制器。
 *
 * 注：kBottomNavigationBarClicked 的监听已移至 UI 层 `ui/components/SitePagerTabs.kt`
 * （首页/分类页共享的「标签行 + 分页器」组件，task 10-16-live-room-pager-review-fixes
 * 起由页面各自持有收敛到这里）——
 * Dart 版（onInit 订阅）监听后调用的 refreshOrScrollTop 依赖 TabController.index 与
 * scrollController；Compose 下当前站点索引与滚动状态（LazyGridState）同样由页面层持有，
 * 故「重复点击分类底栏 Tab → 回顶或刷新」整体在 SitePagerTabs 处理，刷新动作经
 * `onRefreshSite` 由 CategoryScreen 传入（`categoryListControllers[site.id]?.refreshData()`）
 * （发射语义见 AppNavHost.MainShell 底栏：仅重复点击当前 Tab 时发射，对齐 Dart）。
 */
class CategoryController : BaseController() {
    val sites: List<Site> = Sites.supportSites
    val categoryListControllers: Map<String, CategoryListController> =
        sites.associate { it.id to CategoryListController(it) }

    /**
     * 释放资源（对应 Dart onClose 的 streamSubscription.cancel 与各子控制器 onClose；
     * EventBus 订阅已下沉 UI 层）：取消各站点列表控制器的协程作用域
     * （其中可能存在进行中的网络请求）。Job.cancel 幂等，重复调用安全。
     */
    fun dispose() {
        categoryListControllers.values.forEach { it.dispose() }
    }
}
