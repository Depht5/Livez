package com.kotlin_livez.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kotlin_livez.app.app.EventBus
import com.kotlin_livez.app.app.Site
import com.kotlin_livez.app.modules.home.HomeController
import com.kotlin_livez.app.ui.Router
import com.kotlin_livez.app.ui.components.PagedRoomGrid
import com.kotlin_livez.app.ui.components.SitePagerTabs
import com.kotlin_livez.app.ui.theme.CustomIcons
import kotlinx.coroutines.launch

/**
 * 首页（Compose 版）—— 对应 Dart `modules/home/home_page.dart`（站点 TabBar）+
 * `home_list_view.dart`（推荐直播间网格），替换现版 View 体系 `ui/HomeView.kt`。
 *
 * 结构镜像：顶部站点 Tab（logo 24dp + 8dp 间距 + 文字 16sp，选中红系 #FF3F4B /
 * 未选中 #666666 手动切色，指示条 #FF3F4B；装得下时水平居中）+
 * 站点横向翻页器（每页一个推荐直播间网格：下拉刷新 + 触底分页 + 错误/空态覆盖层），
 * Tab 点击与滑动手势双向同步；行尾固定搜索入口（48dp）。
 *
 * 标签行与分页器（含「切换平台跳过中间页」两页过渡）由公共组件 [SitePagerTabs] 承载
 * ——与分类页共用同一实现（原两份逐行同构的 SiteTabRow 已收敛）。
 *
 * 各站点列表控制器由 [HomeController] 壳层持有（跨底栏 Tab 切换存活）；
 * 滚动状态按站点各持一个 [LazyGridState]，切回站点不丢位置。
 *
 * 底栏「重复点击首页 Tab」事件在本页监听：回顶或刷新（见下方 DisposableEffect 注释）。
 *
 * @param controller 首页控制器（由壳层 ShellState 持有）
 * @param gridStates 各站点网格滚动状态（键 = 站点 id，由壳层持有——路由往返不丢位置）
 * @param onOpenLiveRoom 点击卡片回调（进直播间路由 livertoom/{siteId}/{roomId}）
 */
@Composable
fun HomeScreen(
    controller: HomeController,
    gridStates: Map<String, LazyGridState>,
    onOpenLiveRoom: (site: Site, roomId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sites = controller.sites
    val coroutineScope = rememberCoroutineScope()

    // 当前站点 Tab（rememberSaveable：进程重建后恢复选中态，作 pager 的 initialPage 兜底；
    // 之后以 pager 的 currentPage 为准回写——点击（含两页过渡落位）与滑动两条路径统一收敛到这一处写回）
    var selectedSiteIndex by rememberSaveable { mutableIntStateOf(0) }
    // 越界保护：设置里站点排序变化导致索引失效时回落到第一个站点
    val siteIndex = selectedSiteIndex.coerceIn(0, sites.lastIndex.coerceAtLeast(0))

    // 横向翻页器（foundation 1.7 stable API）：站点网格页左右滑动切换；
    // 默认不预组合邻页（beyondViewportPageCount=0），滑动中邻页被组合时其内部
    // 「仅首次加载」守卫触发邻站首载，等价旧 View attach 即加载语义，可接受。
    val pagerState = rememberPagerState(initialPage = siteIndex, pageCount = { sites.size })

    // 滑动过程中回写选中索引，驱动 Tab 切色/指示条跟随；
    // 点击 Tab 走组件内过渡（相邻站点 animateScrollToPage / 跨多页两页过渡），
    // 落页与过渡落位后同样经此路径写回（幂等赋值，无循环风险）。
    // 根因说明（真机反馈：左右滑动时顶部 Tab 变化慢一拍）：settledPage 要等翻页动画
    // 完全 settle 才更新；改用 currentPage——跨过页面中线立即切换，指示器实时跟手
    //（指示条自身逐帧插值跟随，不会生硬跳变）。
    LaunchedEffect(pagerState.currentPage) {
        selectedSiteIndex = pagerState.currentPage
    }

    // ===== 底栏重复点击事件（对齐 Dart indexed_controller.setIndex 的发射语义）=====
    // Dart 行为（lib/modules/indexed/indexed_controller.dart setIndex + lib/modules/home/
    // home_controller.dart onInit/refreshOrScrollTop + lib/app/controller/base_controller.dart
    // scrollToTopOrRefresh）：仅当重复点击当前 Tab 且当前页是首页时，若列表有滚动偏移则
    // 动画回顶，否则触发刷新。滚动状态在 Compose 下由本页持有，故监听从控制器移到此处。
    DisposableEffect(Unit) {
        val job = EventBus.listen<Int>(EventBus.kBottomNavigationBarClicked) { index ->
            if (index == 0) {
                // 注意读最新的可见页索引：闭包只创建一次，必须经 pagerState（remember 持有的
                // 稳定实例）读取当前 currentPage（与 Tab 选中态同一来源），不能用每次重组重算的局部变量
                val current = pagerState.currentPage.coerceIn(0, sites.lastIndex.coerceAtLeast(0))
                val listController = controller.homeListControllers[sites[current].id]
                val gridState = gridStates[sites[current].id]
                if (listController != null && gridState != null) {
                    val scrolled =
                        gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0
                    if (scrolled) {
                        // 对齐 Dart scrollController.animateTo(0, 200ms)
                        coroutineScope.launch { gridState.animateScrollToItem(0) }
                    } else {
                        listController.refreshData()
                    }
                }
            }
        }
        onDispose { job.cancel() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding(),
    ) {
        SitePagerTabs(
            pagerState = pagerState,
            sites = sites,
            gridStates = gridStates,
            selectedIndex = siteIndex,
            // 首页指示条/选中色跟随 primary（红系 #FF3F4B 的 M3 映射）
            indicatorColor = MaterialTheme.colorScheme.primary,
            // 两页过渡起跳时即时反馈选中站点（过渡中 currentPage 尚未变化）
            onSelectSite = { selectedSiteIndex = it },
            pageContent = { pageSite, gridState ->
                val listController = controller.homeListControllers[pageSite.id]
                if (listController != null) {
                    // 推荐网格：三态覆盖由 PagedRoomGrid 公共组件承载（指示器色跟随 secondary）
                    PagedRoomGrid(
                        listController = listController,
                        gridState = gridState,
                        onOpenRoom = { roomId -> onOpenLiveRoom(pageSite, roomId) },
                        indicatorColor = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.fillMaxSize(),
                        siteId = pageSite.id,
                    )
                } else {
                    // 控制器尚未就绪的兜底空页（正常流程不会出现）
                    Box(modifier = Modifier.fillMaxSize())
                }
            },
            modifier = Modifier.fillMaxSize(),
            // 行尾固定搜索入口：48dp 触控区 + 23dp 图标（用户定标；task mine-page-cleanup）
            trailing = {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .size(48.dp)
                        .clickable { Router.openFeature("search", "搜索") },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = CustomIcons.Search,
                        contentDescription = "搜索",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(23.dp),
                    )
                }
            },
        )
    }
}
