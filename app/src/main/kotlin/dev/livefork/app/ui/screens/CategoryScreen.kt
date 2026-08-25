package dev.livefork.app.ui.screens

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.livefork.app.app.EventBus
import dev.livefork.app.app.Site
import dev.livefork.app.modules.category.AppLiveCategory
import dev.livefork.app.modules.category.CategoryController
import dev.livefork.app.modules.category.CategoryListController
import dev.livefork.app.ui.components.NetImage
import dev.livefork.app.ui.theme.CardTitle
import dev.livefork.app.ui.theme.CategoryBlue
import dev.livefork.app.ui.theme.TextSecondary
import dev.livefork.core.model.LiveSubCategory
import kotlinx.coroutines.launch

/**
 * 分类页（Compose 版）—— 对应 Dart `modules/category/category_page.dart`（站点 TabBar）+
 * `category_list_view.dart`（分类列表），替换现版 View 体系 `ui/CategoryView.kt`。
 *
 * 结构镜像：顶部可滑动站点 Tab（logo 24dp + 8dp 间距 + 文字 16sp，选中分类蓝 #3498DB /
 * 未选中 #666666 手动切色，指示条 #3498DB），下方为当前站点的分类网格：
 * 主分类标题为 full-span item 随滚动（**不做吸顶**，用户已决策，视觉取舍记入设计文档），
 * 子分类卡片 +「显示全部」展开。
 *
 * 各站点列表控制器由 [CategoryController] 壳层持有（跨底栏 Tab 切换存活）；
 * 滚动状态按站点各持一个 [LazyGridState]，由壳层传入（跨底栏切换与路由导航均不丢位置）。
 *
 * 底栏「重复点击分类 Tab」事件在本页监听：回顶或刷新（见下方 DisposableEffect 注释）。
 *
 * @param controller 分类控制器（由 MainShell 壳层 remember 持有）
 * @param gridStates 各站点网格滚动状态（键 = 站点 id，由壳层持有）
 * @param onOpenCategory 点击子分类回调（进详情路由 category/{siteId}?categoryId=...）
 */
@Composable
fun CategoryScreen(
    controller: CategoryController,
    gridStates: Map<String, LazyGridState>,
    onOpenCategory: (site: Site, subCategory: LiveSubCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sites = controller.sites
    val coroutineScope = rememberCoroutineScope()

    // 当前站点 Tab（rememberSaveable：进程重建后恢复选中态，对齐壳层 currentIndex 的取舍）
    var selectedSiteIndex by rememberSaveable { mutableIntStateOf(0) }
    // 越界保护：设置里站点排序变化导致索引失效时回落到第一个站点
    val siteIndex = selectedSiteIndex.coerceIn(0, sites.lastIndex.coerceAtLeast(0))

    // ===== 底栏重复点击事件（对齐 Dart indexed_controller.setIndex 的发射语义）=====
    // Dart 行为（lib/modules/category/category_controller.dart onInit/refreshOrScrollTop +
    // lib/app/controller/base_controller.dart scrollToTopOrRefresh）：重复点击当前 Tab 且当前页是
    // 分类页时，若当前站点列表有滚动偏移则动画回顶，否则触发刷新。原 Kotlin 版在控制器内订阅并
    // 固定刷新第 0 个站点；Compose 下当前索引与滚动状态由本页持有，故监听从控制器移到此处
    // 并按真实选中站点处理。
    DisposableEffect(Unit) {
        val job = EventBus.listen<Int>(EventBus.kBottomNavigationBarClicked) { index ->
            if (index == 2) {
                // 注意读最新的选中索引：此处闭包只创建一次，必须经 rememberSaveable 的
                // 委托属性读取当前值（不能用每次重组重算的局部变量）
                val current = selectedSiteIndex.coerceIn(0, sites.lastIndex.coerceAtLeast(0))
                val listController = controller.categoryListControllers[sites[current].id]
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

        val currentListController = controller.categoryListControllers[sites[siteIndex].id]
        val currentGridState = gridStates[sites[siteIndex].id]
        if (currentListController != null && currentGridState != null) {
            CategorySiteGrid(
                listController = currentListController,
                gridState = currentGridState,
                onOpenSub = { sub -> onOpenCategory(sites[siteIndex], sub) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 站点 Tab 行 —— 对应 Dart category_page.dart 的 TabBar（logo + 文字）与现版 View 的
 * TabLayout MODE_SCROLLABLE 自定义 tab。配色对齐现版 CategoryView：
 * 指示条 #3498DB、选中 #3498DB / 未选中 #666666（custom tab 内容需手动切色）。
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
        // 指示条颜色对齐现版 setSelectedTabIndicatorColor(#3498DB)，宽度随标签内容
        // （对齐 isTabIndicatorFullWidth = false）
        indicator = { tabPositions ->
            SecondaryIndicator(
                modifier = Modifier.tabIndicatorOffset(tabPositions[selectedIndex]),
                color = CategoryBlue,
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
                        indication = null, // 移除点击涟漪（对齐 tabRippleColor = TRANSPARENT）
                    ) { onSelect(index) }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(site.logoRes),
                    contentDescription = site.name,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = site.name,
                    fontSize = 16.sp,
                    // 手动切色：选中 #3498DB / 未选中 #666666（对齐 setTabTextColors）
                    color = if (selected) CategoryBlue else TextSecondary,
                )
            }
        }
    }
}

/* ---------- 分类行模型（镜像现版 CategoryView 的 CategoryRow sealed class）---------- */

/** 分类网格行：主分类标题跨全宽、子分类卡片、「显示全部」卡片。 */
private sealed class CategoryRow {
    class Header(val cat: AppLiveCategory) : CategoryRow()
    class Sub(val cat: AppLiveCategory, val sub: LiveSubCategory) : CategoryRow()
    class ShowMore(val cat: AppLiveCategory) : CategoryRow()
}

/**
 * 单站点分类网格 —— 对应 Dart category_list_view.dart（EasyRefresh + ListView +
 * StickyHeader + GridView.count）与现版 CatListView。
 *
 * 差异记录：Dart 用 sticky_headers 吸顶分组标题、现版 View 自研 translationY 卡位吸顶；
 * 本版按用户决策**不做吸顶**——主分类标题作为 full-span item 随内容滚动。
 * 「显示全部」逻辑照搬 AppLiveCategory.take15/showAll：点击置 showAll=true 后展开全部子分类。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CategorySiteGrid(
    listController: CategoryListController,
    gridState: LazyGridState,
    onOpenSub: (LiveSubCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    val categories by listController.list.collectAsStateWithLifecycle()
    val pageLoading by listController.pageLoading.collectAsStateWithLifecycle()
    val pageEmpty by listController.pageEmpty.collectAsStateWithLifecycle()
    val pageError by listController.pageError.collectAsStateWithLifecycle()
    val errorMsg by listController.errorMsg.collectAsStateWithLifecycle()

    // 各分类的「显示全部」展开态（showAll 为 StateFlow，逐个收集；
    // 点击「显示全部」置 true 后此处重组，行结构随之展开）
    val showAllMap = HashMap<String, Boolean>()
    for (cat in categories) {
        showAllMap[cat.id] = cat.showAll.collectAsStateWithLifecycle().value
    }

    // 展开后的扁平行序列（镜像现版 CategoryAdapter.submit 的 flatMap 构建逻辑）
    val rows = categories.flatMap { cat ->
        buildList {
            add(CategoryRow.Header(cat))
            val showAll = showAllMap[cat.id] ?: true
            val subs = if (showAll) cat.children else cat.take15
            subs.forEach { add(CategoryRow.Sub(cat, it)) }
            if (!showAll && cat.children.size > cat.take15.size) {
                add(CategoryRow.ShowMore(cat))
            }
        }
    }

    // 首次可见时触发首屏加载（对齐 Dart EasyRefresh firstRefresh：仅首次构建加载；
    // 条件从控制器状态推导——currentPage 仍为 1 且无数据说明尚未成功加载过，
    // 加载失败后重新进入会自动重试，范式同 HomeScreen）
    LaunchedEffect(listController) {
        if (!listController.loading &&
            !listController.pageLoading.value &&
            listController.currentPage == 1 &&
            listController.list.value.isEmpty()
        ) {
            listController.loadData()
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 列数对齐 Dart/现版：(视口宽 / 80).clamp(1, 12)
        val columns = (maxWidth.value / 80f).toInt().coerceIn(1, 12)
        val pullState = rememberPullToRefreshState()

        // 下拉刷新（对应 Dart EasyRefresh MaterialHeader / 现版 SwipeRefreshLayout）
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
                    columns = GridCells.Fixed(columns),
                    state = gridState,
                    // 边缘由 contentPadding 承担（对齐现版 recycler padding 12dp）、
                    // 列间/行间统一 8dp（对齐现版 CategorySpacingDecoration 的对称间距语义；
                    // 标题行的上下 8dp 间距也由此表达）
                    contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(
                        items = rows,
                        // 主分类标题跨全宽（随滚动，不做吸顶——用户已决策）
                        span = { row ->
                            if (row is CategoryRow.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1)
                        },
                        contentType = { row ->
                            when (row) {
                                is CategoryRow.Header -> "header"
                                is CategoryRow.Sub -> "sub"
                                is CategoryRow.ShowMore -> "more"
                            }
                        },
                    ) { row ->
                        when (row) {
                            is CategoryRow.Header -> HeaderText(row.cat.name)
                            is CategoryRow.Sub -> SubCategoryCard(
                                name = row.sub.name,
                                pic = row.sub.pic,
                                onClick = { onOpenSub(row.sub) },
                            )
                            is CategoryRow.ShowMore -> SubCategoryCard(
                                name = "显示全部",
                                pic = null,
                                hideImage = true,
                                onClick = {
                                    // 先置位展开标记再重组（对齐 Dart item.showAll.value = true；
                                    // 只重建不置位会导致点击看似无效——既有 gotcha）
                                    row.cat.showAll.value = true
                                },
                            )
                        }
                    }
                }

                // ===== 三态覆盖层（对应 PageGridView 的 Offstage 覆盖，文案对齐 HomeScreen）=====
                when {
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
                    pageLoading && categories.isEmpty() -> {
                        LoadingOverlay()
                    }
                }
            }
        }
    }
}

/** 分组标题：18sp 加粗、#222、白底不透明、左缩进 4dp + 上下 8dp（对齐现版 buildHeader）。 */
@Composable
private fun HeaderText(name: String) {
    Text(
        text = name,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = CardTitle,
        modifier = Modifier
            .background(Color.White)
            .padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
    )
}

/**
 * 子分类卡片 —— 对齐现版 buildSubCard 视觉：白底圆角 8dp、elevation 2dp、
 * 统一固定高 84dp、内边距 8/10、图 40dp 圆角 8dp（等价 NetImage.loadRound）、
 * 名称 12sp #666 单行居中；[hideImage]=true 时为「显示全部」卡片（隐藏图片槽位）。
 */
@Composable
private fun SubCategoryCard(
    name: String,
    pic: String?,
    onClick: () -> Unit,
    hideImage: Boolean = false,
) {
    Column(
        modifier = Modifier
            .height(84.dp)
            .shadow(elevation = 2.dp, shape = RoundedCornerShape(8.dp))
            .background(Color.White, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(start = 8.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (!hideImage) {
            // 无图时 NetImage 内部显示灰色占位（对齐现版注释：不含蓝色边框装饰）
            NetImage(
                url = pic ?: "",
                modifier = Modifier.size(40.dp),
                cornerRadiusDp = 8f,
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        Text(
            text = name,
            fontSize = 12.sp,
            color = TextSecondary,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/** 居中加载圈（颜色对齐分类蓝）。 */
@Composable
private fun LoadingOverlay() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = CategoryBlue)
    }
}

/** 居中状态覆盖层：整面可点击触发刷新（去涟漪），内容由调用方给定（范式同 HomeScreen）。 */
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
