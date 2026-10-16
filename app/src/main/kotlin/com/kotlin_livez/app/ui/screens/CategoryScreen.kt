package com.kotlin_livez.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kotlin_livez.app.app.Site
import com.kotlin_livez.app.modules.category.AppLiveCategory
import com.kotlin_livez.app.modules.category.CategoryController
import com.kotlin_livez.app.modules.category.CategoryListController
import com.kotlin_livez.app.ui.components.NetImage
import com.kotlin_livez.app.ui.components.SitePagerTabs
import com.kotlin_livez.app.ui.theme.cardContainerColor
import com.kotlin_livez.core.model.LiveSubCategory

/**
 * 分类页（Compose 版）—— 对应 Dart `modules/category/category_page.dart`（站点 TabBar）+
 * `category_list_view.dart`（分类列表），替换现版 View 体系 `ui/CategoryView.kt`。
 *
 * 结构镜像：顶部站点 Tab（logo 24dp + 8dp 间距 + 文字 16sp，选中分类蓝 #3498DB /
 * 未选中 #666666 手动切色，指示条 #3498DB；装得下时水平居中），站点横向翻页器：
 * 每页一个分类网格：主分类标题为 full-span item 随滚动（**不做吸顶**，用户已决策，
 * 视觉取舍记入设计文档），子分类卡片 +「显示全部」展开；Tab 点击与滑动手势双向同步。
 *
 * 标签行与分页器（含「切换站点跳过中间页」两页过渡）由公共组件 [SitePagerTabs] 承载
 * ——与首页共用同一实现（原两份逐行同构的 SiteTabRow 已收敛）。
 * 本页只声明「本页是什么」：底栏下标（2026-10 收敛后 选中站点索引 / Pager 状态 /
 * 「重按分类 Tab → 回顶或刷新」监听全部内聚在 [SitePagerTabs] 内，见其注释）。
 *
 * 各站点列表控制器由 [CategoryController] 壳层持有（跨底栏 Tab 切换存活）；
 * 滚动状态按站点各持一个 [LazyGridState]，由壳层传入（跨底栏切换与路由导航均不丢位置）。
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding(),
    ) {
        SitePagerTabs(
            sites = sites,
            gridStates = gridStates,
            // 分类页指示条/选中色跟随 secondary（分类蓝 #3498DB 的 M3 映射）
            indicatorColor = MaterialTheme.colorScheme.secondary,
            // 底栏「重按分类 Tab」事件载荷 = 底栏下标（分类页 = 2）：
            // 有滚动偏移则回顶、否则刷新选中站点，逻辑在 SitePagerTabs 内
            bottomNavTabIndex = 2,
            onRefreshSite = { site -> controller.categoryListControllers[site.id]?.refreshData() },
            pageContent = { pageSite, gridState ->
                val listController = controller.categoryListControllers[pageSite.id]
                if (listController != null) {
                    // 首屏加载守卫在 CategorySiteGrid 内部，行为不变
                    CategorySiteGrid(
                        listController = listController,
                        gridState = gridState,
                        onOpenSub = { sub -> onOpenCategory(pageSite, sub) },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    // 控制器尚未就绪的兜底空页（正常流程不会出现）
                    Box(modifier = Modifier.fillMaxSize())
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
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
            // 刷新指示器颜色对齐现版 setColorSchemeColors(#3498DB，现映射 secondary)
            indicator = {
                PullToRefreshDefaults.Indicator(
                    isRefreshing = pageLoading,
                    state = pullState,
                    modifier = Modifier.align(Alignment.TopCenter),
                    color = MaterialTheme.colorScheme.secondary,
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    pageEmpty -> {
                        StatusOverlay(onClick = { listController.refreshData() }) {
                            Text(
                                text = "这里什么都没有\n点击刷新",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/** 分组标题：18sp 加粗、onSurface、surface 底不透明、左缩进 4dp + 上下 8dp（对齐现版 buildHeader）。 */
@Composable
private fun HeaderText(name: String) {
    Text(
        text = name,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
    )
}

/**
 * 子分类卡片 —— 对齐现版 buildSubCard 视觉：白底圆角 8dp、elevation 2dp、
 * 统一固定高 84dp、内边距 8/10、图 40dp 圆角 8dp（等价 NetImage.loadRound）、
 * 名称 12sp onSurfaceVariant 单行居中；[hideImage]=true 时为「显示全部」卡片（隐藏图片槽位）。
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
            .background(cardContainerColor(), RoundedCornerShape(8.dp))
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/** 居中加载圈（secondary，对应原分类蓝）。 */
@Composable
private fun LoadingOverlay() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
    }
}

/** 居中状态覆盖层：整面可点击触发刷新，内容由调用方给定（范式同 HomeScreen）。 */
@Composable
private fun StatusOverlay(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onClick)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
