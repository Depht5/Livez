package dev.livefork.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.livefork.app.app.EventBus
import dev.livefork.app.app.Site
import dev.livefork.app.modules.home.HomeController
import dev.livefork.app.ui.components.PagedRoomGrid
import dev.livefork.app.ui.theme.CategoryBlue
import dev.livefork.app.ui.theme.LiveRed
import dev.livefork.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * 首页（Compose 版）—— 对应 Dart `modules/home/home_page.dart`（站点 TabBar）+
 * `home_list_view.dart`（推荐直播间网格），替换现版 View 体系 `ui/HomeView.kt`。
 *
 * 结构镜像：顶部可滑动站点 Tab（logo 24dp + 8dp 间距 + 文字 16sp，选中红系 #FF3F4B /
 * 未选中 #666666 手动切色，指示条 #FF3F4B），下方为当前站点的推荐直播间网格
 * （下拉刷新 + 触底分页 + 错误/空态覆盖层，由公共组件 PagedRoomGrid 承载）。
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

    // 当前站点 Tab（rememberSaveable：进程重建后恢复选中态，对齐壳层 currentIndex 的取舍）
    var selectedSiteIndex by rememberSaveable { mutableIntStateOf(0) }
    // 越界保护：设置里站点排序变化导致索引失效时回落到第一个站点
    val siteIndex = selectedSiteIndex.coerceIn(0, sites.lastIndex.coerceAtLeast(0))

    // ===== 底栏重复点击事件（对齐 Dart indexed_controller.setIndex 的发射语义）=====
    // Dart 行为（lib/modules/indexed/indexed_controller.dart setIndex + lib/modules/home/
    // home_controller.dart onInit/refreshOrScrollTop + lib/app/controller/base_controller.dart
    // scrollToTopOrRefresh）：仅当重复点击当前 Tab 且当前页是首页时，若列表有滚动偏移则
    // 动画回顶，否则触发刷新。滚动状态在 Compose 下由本页持有，故监听从控制器移到此处。
    DisposableEffect(Unit) {
        val job = EventBus.listen<Int>(EventBus.kBottomNavigationBarClicked) { index ->
            if (index == 0) {
                // 注意读最新的选中索引：此处闭包只创建一次，必须经 rememberSaveable 的
                // 委托属性读取当前值（不能用每次重组重算的局部变量）
                val current = selectedSiteIndex.coerceIn(0, sites.lastIndex.coerceAtLeast(0))
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

    Column(modifier = modifier.fillMaxSize().background(Color.White)) {
        SiteTabRow(
            sites = sites,
            selectedIndex = siteIndex,
            onSelect = { index -> selectedSiteIndex = index },
        )

        val currentListController = controller.homeListControllers[sites[siteIndex].id]
        val currentGridState = gridStates[sites[siteIndex].id]
        if (currentListController != null && currentGridState != null) {
            // 推荐网格：下拉刷新/触底分页/三态覆盖由 PagedRoomGrid 公共组件承载
            //（原 SiteRoomGrid 实现已逐行收敛进该组件，行为不变；指示器色 #3498DB）
            PagedRoomGrid(
                listController = currentListController,
                gridState = currentGridState,
                onOpenRoom = { roomId -> onOpenLiveRoom(sites[siteIndex], roomId) },
                indicatorColor = CategoryBlue,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 站点 Tab 行 —— 对应 Dart home_page.dart 的 TabBar（logo + 文字）与现版 View 的
 * TabLayout MODE_SCROLLABLE 自定义 tab。
 * 注意：custom tab 内容不受 Tab 文字配色控制，选中色需在内容内手动切换
 * （对齐现版 HomeView.updateTabLabel 的做法）；点击去涟漪（对齐 tabRippleColor = TRANSPARENT）。
 */
@Composable
private fun SiteTabRow(
    sites: List<Site>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    ScrollableTabRow(
        selectedTabIndex = selectedIndex,
        containerColor = Color.White,
        // Tab 内容左右留白（对应 Dart labelPadding H20）；起始边距取小值贴近左缘（对齐 GRAVITY_START）
        edgePadding = 12.dp,
        // 指示条颜色对齐现版 setSelectedTabIndicatorColor(#FF3F4B)，宽度随标签内容
        //（对齐 isTabIndicatorFullWidth = false）
        indicator = { tabPositions ->
            SecondaryIndicator(
                modifier = Modifier.tabIndicatorOffset(tabPositions[selectedIndex]),
                color = LiveRed,
            )
        },
        // 现版 TabLayout 无底部分隔线，去掉 M3 默认 divider
        divider = { },
    ) {
        sites.forEachIndexed { index, site ->
            val selected = index == selectedIndex
            Row(
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null, // 移除点击涟漪
                    ) { onSelect(index) }
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
                    // 手动切色：选中 #FF3F4B / 未选中 #666666（对齐 setTabTextColors）
                    color = if (selected) LiveRed else TextSecondary,
                )
            }
        }
    }
}
