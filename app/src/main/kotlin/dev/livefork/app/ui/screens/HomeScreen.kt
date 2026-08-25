package dev.livefork.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.livefork.app.app.EventBus
import dev.livefork.app.app.Site
import dev.livefork.app.app.controller.BasePageController
import dev.livefork.app.modules.home.HomeController
import dev.livefork.app.ui.components.LiveRoomCard
import dev.livefork.app.ui.components.resolveLiveRoomGrid
import dev.livefork.app.ui.theme.CategoryBlue
import dev.livefork.app.ui.theme.LiveRed
import dev.livefork.app.ui.theme.TextSecondary
import dev.livefork.core.model.LiveRoomItem
import kotlinx.coroutines.launch

/** 触底加载阈值：最后可见条目距列表末尾不足该数量时预加载下一页（对应 Dart EasyRefresh footer）。 */
private const val LOAD_MORE_THRESHOLD_ITEMS = 6

/**
 * 首页（Compose 版）—— 对应 Dart `modules/home/home_page.dart`（站点 TabBar）+
 * `home_list_view.dart`（推荐直播间网格），替换现版 View 体系 `ui/HomeView.kt`。
 *
 * 结构镜像：顶部可滑动站点 Tab（logo 24dp + 8dp 间距 + 文字 16sp，选中红系 #FF3F4B /
 * 未选中 #666666 手动切色，指示条 #FF3F4B），下方为当前站点的推荐直播间网格
 * （下拉刷新 + 触底分页 + 错误/空态覆盖层）。
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
@OptIn(ExperimentalMaterial3Api::class)
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
            SiteRoomGrid(
                listController = currentListController,
                gridState = currentGridState,
                onOpenRoom = { roomId -> onOpenLiveRoom(sites[siteIndex], roomId) },
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

/**
 * 单站点推荐网格 —— 对应 Dart home_list_view.dart（PageGridView 承载）：
 * 下拉刷新 + 触底分页 + 错误/空态/首屏加载覆盖层 + 卡片网格。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SiteRoomGrid(
    listController: BasePageController<LiveRoomItem>,
    gridState: LazyGridState,
    onOpenRoom: (roomId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val list by listController.list.collectAsStateWithLifecycle()
    val pageLoading by listController.pageLoading.collectAsStateWithLifecycle()
    val pageEmpty by listController.pageEmpty.collectAsStateWithLifecycle()
    val pageError by listController.pageError.collectAsStateWithLifecycle()
    val errorMsg by listController.errorMsg.collectAsStateWithLifecycle()
    val canLoadMore by listController.canLoadMore.collectAsStateWithLifecycle()

    // 首次可见时触发首屏加载（对齐 Dart EasyRefresh firstRefresh：仅首次构建加载；
    // 条件从控制器状态推导——currentPage 仍为 1 且无数据说明尚未成功加载过，
    // 加载失败后重新进入本 Tab 会自动重试，与现版 attach 即 loadData 行为一致）
    LaunchedEffect(listController) {
        if (!listController.loading &&
            !listController.pageLoading.value &&
            listController.currentPage == 1 &&
            listController.list.value.isEmpty()
        ) {
            listController.loadData()
        }
    }

    // 触底分页：派生「接近底部」状态，满足条件且允许加载更多时请求下一页。
    // loadData 内部有 loading 防重入守卫（对齐现版依赖 EasyRefresh footer 的单次触发语义）。
    val nearBottom by remember(gridState) {
        derivedStateOf {
            val info = gridState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= 0 && info.totalItemsCount - lastVisible <= LOAD_MORE_THRESHOLD_ITEMS
        }
    }
    LaunchedEffect(nearBottom, canLoadMore, pageLoading, pageEmpty, pageError) {
        if (nearBottom && canLoadMore && !pageLoading && !pageEmpty && !pageError) {
            listController.loadData()
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 网格规格按视口宽度实时计算（对应 Dart LayoutBuilder + LiveRoomGridLayout.resolve）
        val spec = resolveLiveRoomGrid(maxWidth.value)
        val pullState = rememberPullToRefreshState()

        // 下拉刷新（对应 Dart RefreshIndicator / 现版 SwipeRefreshLayout）：
        // isRefreshing 绑定控制器加载状态，刷完自动收起（对齐现版 collect pageLoading 收起逻辑）
        PullToRefreshBox(
            isRefreshing = pageLoading,
            onRefresh = { listController.refreshData() },
            state = pullState,
            modifier = Modifier.fillMaxSize(),
            // 刷新指示器颜色对齐现版 setColorSchemeColors(#3498DB)
            indicator = {
                PullToRefreshDefaults.Indicator(
                    isRefreshing = pageLoading,
                    state = pullState,
                    modifier = Modifier.align(Alignment.TopCenter),
                    color = CategoryBlue,
                )
            },
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(spec.columns),
                    state = gridState,
                    // 边缘由 contentPadding 承担、列间/行间用 spacing 表达
                    //（替代现版 GridSpacingDecoration 语义；水平 12dp 对齐 Dart edgeInsetsA12）
                    contentPadding = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(list) { item ->
                        // 固定行高 = mainAxisExtent（封面 16:9 高 + 文字区 64dp），对齐 Dart mainAxisExtent
                        Box(modifier = Modifier.height(spec.mainAxisExtentDp.dp)) {
                            LiveRoomCard(
                                item = item,
                                onClick = { onOpenRoom(item.roomId) },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }

                // ===== 三态覆盖层（对应 PageGridView 的 Offstage 覆盖）=====
                when {
                    // 加载失败：错误信息 + 点击重试（对齐 AppErrorWidget 文案「$errorMsg 点击刷新」）
                    pageError -> {
                        StatusOverlay(onClick = { listController.refreshData() }) {
                            Text(
                                text = errorMsg.trim().ifEmpty { "加载失败" } + "\n点击刷新",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    // 空态：文案 + 点击刷新（对齐 AppEmptyWidget 默认文案「这里什么都没有」）
                    pageEmpty -> {
                        StatusOverlay(onClick = { listController.refreshData() }) {
                            Text(
                                text = "这里什么都没有\n点击刷新",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    // 首屏加载中（尚无数据时居中转圈；已有数据时的翻页刷新由顶部下拉指示器表达）
                    pageLoading && list.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = CategoryBlue)
                        }
                    }
                }
            }
        }
    }
}

/** 居中状态覆盖层：整面可点击触发刷新（去涟漪），内容由调用方给定。 */
@Composable
private fun StatusOverlay(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
