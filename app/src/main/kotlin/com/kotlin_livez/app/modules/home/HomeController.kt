package com.kotlin_livez.app.modules.home

import com.kotlin_livez.app.app.Site
import com.kotlin_livez.app.app.Sites
import com.kotlin_livez.app.app.controller.BaseController

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

    init {
        // 启动即并行预取全部站点首屏（task mine-page-cleanup 滑动卡顿优化）：
        // 原语义为"页面首次组合才首载"，首次横滑/点 tab 到达某站点时 = 组合 + 网络等待 +
        // 数据到达插入卡片 + 图片解码全堆在手势/动画中 → 首次切换必卡。站点数固定且少，
        // 启动预取后首次切换只剩已就绪数据的渲染。loadData 有 loading 防重入守卫，
        // 与 PagedRoomGrid 首载守卫（currentPage==1 && list 空）互不重复请求。
        homeListControllers.values.forEach { it.loadData() }
    }

    /**
     * 释放资源（对应 Dart onClose 的 streamSubscription.cancel 与各子控制器 onClose）：
     * 取消各站点列表控制器的协程作用域（其中可能存在进行中的网络请求）。
     */
    fun dispose() {
        homeListControllers.values.forEach { it.dispose() }
    }
}
