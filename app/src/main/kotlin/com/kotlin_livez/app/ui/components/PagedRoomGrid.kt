package com.kotlin_livez.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kotlin_livez.app.app.controller.BasePageController
import com.kotlin_livez.core.model.LiveRoomItem

/** 触底加载阈值：最后可见条目距列表末尾不足该数量时预加载下一页（对应 Dart EasyRefresh footer）。 */
private const val LOAD_MORE_THRESHOLD_ITEMS = 6

/**
 * 分页直播间网格（公共组件）—— 收敛自 HomeScreen 的 SiteRoomGrid 与
 * CategoryDetailScreen 的 DetailRoomGrid 逐行相同的实现
 * （Batch 3 检查遗留决策：第 3 个消费者 SearchScreen 出现时收敛）。
 *
 * 重构说明：本组件是对两处已验证代码的逐行对照迁移，行为不变——首屏加载条件、
 * 触底分页守卫、下拉刷新绑定、三态覆盖文案与点击重试语义全部保持原样；
 * 错误/空态文案改为参数化（默认值 = HomeScreen 原文案；CategoryDetailScreen
 * 显式传入自己的文案）。唯一统一差异：卡片行高容器补了 fillMaxWidth
 * （HomeScreen 原来只有 height，网格 item 宽度本就受列约束，视觉等价）。
 * 结构镜像 Dart `PageGridView` + `LiveRoomGridLayout.resolve`：
 * 下拉刷新 + 触底分页 + 首屏加载/错误重试/空态三态覆盖层 + 卡片网格。
 */

/**
 * 标准直播间卡片网格 —— [PagedRoomGridCore] 的房间卡片封装，
 * 供首页 / 分类详情使用（itemContent 固定为 LiveRoomCard，行高 = mainAxisExtent）。
 *
 * @param listController 分页列表控制器（list/pageLoading/pageEmpty/pageError 等状态源）
 * @param gridState 网格滚动状态（由调用方持有以跨重组存活）
 * @param onOpenRoom 点击卡片回调（参数为 roomId）
 * @param indicatorColor 刷新指示器颜色（M3 化后调用方传 colorScheme.secondary；默认旧红兜底）
 * @param errorTextBuilder 错误态文案（默认 = HomeScreen 原文案；分类详情传入自己的文案）
 * @param emptyText 空态文案（默认 = HomeScreen 原文案；分类详情传入自己的文案）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PagedRoomGrid(
    listController: BasePageController<LiveRoomItem>,
    gridState: LazyGridState,
    onOpenRoom: (roomId: String) -> Unit,
    modifier: Modifier = Modifier,
    indicatorColor: Color = Color(0xFFFF3F4B),
    errorTextBuilder: (errorMsg: String) -> String =
        { msg -> msg.trim().ifEmpty { "加载失败" } + "\n点击刷新" },
    emptyText: String = "这里什么都没有\n点击刷新",
    /** 站点 id：非空时卡片封面参与一镜到底共享元素（task 09-12-shared-element-transition） */
    siteId: String? = null,
) {
    PagedRoomGridCore(
        listController = listController,
        gridState = gridState,
        modifier = modifier,
        indicatorColor = indicatorColor,
        errorTextBuilder = errorTextBuilder,
        emptyText = emptyText,
        itemContent = { _, item, mainAxisExtentDp ->
            // 固定行高 = mainAxisExtent（封面 16:9 高 + 文字区 64dp），对齐 Dart mainAxisExtent
            Box(modifier = Modifier.fillMaxWidth().height(mainAxisExtentDp.dp)) {
                LiveRoomCard(
                    item = item,
                    onClick = { onOpenRoom(item.roomId) },
                    modifier = Modifier.fillMaxSize(),
                    sharedElementSiteId = siteId,
                )
            }
        },
    )
}

/**
 * 泛型分页网格核心 —— [PagedRoomGrid] 的通用形态，供混合类型结果列表复用
 * （如搜索页 BasePageController&lt;Any&gt; 直播间/主播混排：主播行 fullSpan 整行展示）。
 *
 * @param fullSpan 返回 true 的 item 跨整行展示（LazyVerticalGrid span）；null 表示全部单格
 * @param autoFirstLoad 首次组合是否自动触发首屏加载（默认 true = 首页/分类详情的既有行为；
 *   搜索页必须传 false——现版 SearchActivity 进入页面不自动搜索，若开启会因空关键词
 *   getData 返回空列表而误置 pageEmpty，刚进页面就显示「这里什么都没有」空态）
 * @param itemContent 单元格内容；(index, item, mainAxisExtentDp)——mainAxisExtentDp 为
 *   当前网格规格的固定行高 dp 值（Float），标准卡片用其包一层定高 Box
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun <T> PagedRoomGridCore(
    listController: BasePageController<T>,
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
    indicatorColor: Color = Color(0xFFFF3F4B),
    errorTextBuilder: (errorMsg: String) -> String =
        { msg -> msg.trim().ifEmpty { "加载失败" } + "\n点击刷新" },
    emptyText: String = "这里什么都没有\n点击刷新",
    fullSpan: ((item: T) -> Boolean)? = null,
    autoFirstLoad: Boolean = true,
    itemContent: @Composable (index: Int, item: T, mainAxisExtentDp: Float) -> Unit,
) {
    val list by listController.list.collectAsStateWithLifecycle()
    val pageLoading by listController.pageLoading.collectAsStateWithLifecycle()
    val pageEmpty by listController.pageEmpty.collectAsStateWithLifecycle()
    val pageError by listController.pageError.collectAsStateWithLifecycle()
    val errorMsg by listController.errorMsg.collectAsStateWithLifecycle()
    val canLoadMore by listController.canLoadMore.collectAsStateWithLifecycle()

    // 首次可见时触发首屏加载（对齐 Dart EasyRefresh firstRefresh：仅首次构建加载；
    // 条件从控制器状态推导——currentPage 仍为 1 且无数据说明尚未成功加载过，
    // 加载失败后重新进入本 Tab 会自动重试，与现版 attach 即 loadData 行为一致；
    // 搜索页经 autoFirstLoad=false 关闭——见参数注释）
    LaunchedEffect(listController) {
        if (autoFirstLoad &&
            !listController.loading &&
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
                    color = indicatorColor,
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
                    itemsIndexed(
                        list,
                        // fullSpan 命中的 item（如主播搜索结果行）跨整行展示
                        span = { _, item ->
                            if (fullSpan?.invoke(item) == true) GridItemSpan(maxLineSpan)
                            else GridItemSpan(1)
                        },
                    ) { index, item ->
                        Box {
                            itemContent(index, item, spec.mainAxisExtentDp)
                        }
                    }
                }

                // ===== 三态覆盖层（对应 PageGridView 的 Offstage 覆盖）=====
                when {
                    // 加载失败：错误信息 + 点击重试
                    pageError -> {
                        StatusOverlay(onClick = { listController.refreshData() }) {
                            Text(
                                text = errorTextBuilder(errorMsg),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    // 空态：文案 + 点击刷新
                    pageEmpty -> {
                        StatusOverlay(onClick = { listController.refreshData() }) {
                            Text(
                                text = emptyText,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            CircularProgressIndicator(color = indicatorColor)
                        }
                    }
                }
            }
        }
    }
}

/** 居中状态覆盖层：整面可点击触发刷新（默认 ripple），内容由调用方给定。 */
@Composable
private fun StatusOverlay(
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
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
