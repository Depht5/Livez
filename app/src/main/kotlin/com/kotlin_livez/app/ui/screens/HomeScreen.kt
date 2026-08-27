package com.kotlin_livez.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.kotlin_livez.app.app.EventBus
import com.kotlin_livez.app.app.Site
import com.kotlin_livez.app.modules.home.HomeController
import com.kotlin_livez.app.ui.components.PagedRoomGrid
import kotlin.math.floor
import kotlinx.coroutines.launch

/**
 * 首页（Compose 版）—— 对应 Dart `modules/home/home_page.dart`（站点 TabBar）+
 * `home_list_view.dart`（推荐直播间网格），替换现版 View 体系 `ui/HomeView.kt`。
 *
 * 结构镜像：顶部可滑动站点 Tab（logo 24dp + 8dp 间距 + 文字 16sp，选中红系 #FF3F4B /
 * 未选中 #666666 手动切色，指示条 #FF3F4B），下方为站点横向翻页器（HorizontalPager）：
 * 每页一个推荐直播间网格（下拉刷新 + 触底分页 + 错误/空态覆盖层，由公共组件 PagedRoomGrid
 * 承载），Tab 点击与滑动手势双向同步。
 *
 * 各站点列表控制器由 [HomeController] 壳层持有（跨底栏 Tab 切换存活）；
 * 滚动状态按站点各持一个 [LazyGridState]，切回站点不丢位置。
 *
 * 底栏「重复点击首页 Tab」事件在本页监听：回顶或刷新（见下方 DisposableEffect 注释）。
 *
 * @param controller 首页控制器（由壳层 ShellState 持有）
 * @param gridStates 各站点网格滚动状态（键 = 站点 id，由壳层持有——路由往返不丢位置）
 * @param onOpenLiveRoom 点击卡片回调（进直播间；直播间仍为旧 Activity，Batch 7 才迁移路由）
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
    // 之后以 pager 的 currentPage 为准回写——点击与滑动两条路径统一收敛到这一处写回）
    var selectedSiteIndex by rememberSaveable { mutableIntStateOf(0) }
    // 越界保护：设置里站点排序变化导致索引失效时回落到第一个站点
    val siteIndex = selectedSiteIndex.coerceIn(0, sites.lastIndex.coerceAtLeast(0))

    // 横向翻页器（foundation 1.7 stable API）：站点网格页左右滑动切换；
    // 默认不预组合邻页（beyondViewportPageCount=0），滑动中邻页被组合时其内部
    // 「仅首次加载」守卫触发邻站首载，等价旧 View attach 即加载语义，可接受。
    val pagerState = rememberPagerState(initialPage = siteIndex, pageCount = { sites.size })

    // 滑动过程中回写选中索引，驱动 Tab 切色/指示条跟随；
    // 点击 Tab 走 animateScrollToPage，落页后同样经此路径写回（幂等赋值，无循环风险）。
    // 根因说明（真机反馈：左右滑动时顶部 Tab 变化慢一拍）：settledPage 要等翻页动画
    // 完全 settle 才更新；改用 currentPage——跨过页面中线立即切换，指示器实时跟手
    //（tabIndicatorOffset 自带过渡动画，不会生硬跳变）。
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

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
        SiteTabRow(
            sites = sites,
            selectedIndex = siteIndex,
            pagerState = pagerState,
            onSelect = { index ->
                // 点击 Tab → 动画翻页；选中态由上方 currentPage 回写统一驱动
                coroutineScope.launch { pagerState.animateScrollToPage(index) }
            },
        )

        // 各站点一页：网格滚动状态仍由壳层 gridStates Map 持有（键 = 站点 id），
        // pager 页销毁重建不影响位置；下拉刷新/触底分页/首屏加载守卫均在 PagedRoomGrid 内部，
        // 行为不变。pager 横滑与网格纵向滚动方向正交，无手势冲突。
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
            // 越界保护：sites 尺寸变化瞬间 pageCount 已更新但页面索引可能短暂越界
            val pageIdx = page.coerceIn(0, sites.lastIndex.coerceAtLeast(0))
            val pageSite = sites[pageIdx]
            val listController = controller.homeListControllers[pageSite.id]
            val gridState = gridStates[pageSite.id]
            if (listController != null && gridState != null) {
                // 推荐网格：三态覆盖由 PagedRoomGrid 公共组件承载（指示器色跟随 secondary）
                PagedRoomGrid(
                    listController = listController,
                    gridState = gridState,
                    onOpenRoom = { roomId -> onOpenLiveRoom(pageSite, roomId) },
                    indicatorColor = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // 控制器尚未就绪的兜底空页（正常流程不会出现）
                Box(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * 站点 Tab 行 —— 对应 Dart home_page.dart 的 TabBar（logo + 文字）与现版 View 的
 * TabLayout MODE_SCROLLABLE 自定义 tab。
 * 注意：custom tab 内容不受 Tab 文字配色控制，选中色需在内容内手动切换
 * （对齐现版 HomeView.updateTabLabel 的做法）；点击去涟漪（对齐 tabRippleColor = TRANSPARENT）。
 *
 * 指示条逐帧跟随：不用 tabIndicatorOffset（其按 selectedTabIndex 定位 + 自带过渡动画，
 * 页面跨中线才更新，真机反馈「滑多少、指示条不动」）。改为读取 pager 连续位置
 * （currentPage + currentPageOffsetFraction，组合期订阅该快照状态 → 滑动中本组件每帧重组），
 * 在相邻两个 TabPosition 之间线性插值出偏移与宽度，实现「页面滑多少、指示条动多少」。
 * 选中文字色仍按 currentPage 切换（简单起见不做逐帧渐变，视觉差异可忽略）。
 */
@Composable
private fun SiteTabRow(
    sites: List<Site>,
    selectedIndex: Int,
    pagerState: PagerState,
    onSelect: (Int) -> Unit,
) {
    ScrollableTabRow(
        selectedTabIndex = selectedIndex,
        containerColor = MaterialTheme.colorScheme.surface,
        // Tab 内容左右留白（对应 Dart labelPadding H20）；起始边距取小值贴近左缘（对齐 GRAVITY_START）
        edgePadding = 12.dp,
        // 指示条颜色跟随 primary（M3），宽度随标签内容（对齐 isTabIndicatorFullWidth = false）
        indicator = { tabPositions ->
            // 连续页位置：整数部分 = 当前页，小数部分 = 滑动进度（可为负——往回滑时
            // currentPage 已进位而 offset 为负，floor 后仍落在正确的相邻区间）
            val pos = pagerState.currentPage + pagerState.currentPageOffsetFraction
            val leftIndex = floor(pos).toInt().coerceIn(0, tabPositions.lastIndex)
            val rightIndex = (leftIndex + 1).coerceIn(0, tabPositions.lastIndex)
            val fraction = (pos - leftIndex).coerceIn(0f, 1f)
            val leftRect = tabPositions[leftIndex]
            val rightRect = tabPositions[rightIndex]
            // 手写等价 SecondaryIndicator + tabIndicatorOffset：偏移/宽度随连续位置插值；
            // 高度 3dp 对齐 M3 SecondaryIndicator 默认粗细
            Box(
                Modifier
                    .wrapContentSize(Alignment.BottomStart)
                    .offset(x = lerp(leftRect.left.value, rightRect.left.value, fraction).dp)
                    .width(lerp(leftRect.width.value, rightRect.width.value, fraction).dp)
                    .height(3.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
        },
        // 现版 TabLayout 无底部分隔线，去掉 M3 默认 divider
        divider = { },
    ) {
        sites.forEachIndexed { index, site ->
            val selected = index == selectedIndex
            Row(
                modifier = Modifier
                    .clickable { onSelect(index) }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 平台 logo：24dp（与分类页 Tab 统一尺寸，对应现版 icon 24dp）
                Image(
                    painter = painterResource(site.logoRes),
                    contentDescription = site.name,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = site.name,
                    fontSize = 16.sp,
                    // 手动切色：选中 primary / 未选中 onSurfaceVariant（对齐 setTabTextColors）
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
