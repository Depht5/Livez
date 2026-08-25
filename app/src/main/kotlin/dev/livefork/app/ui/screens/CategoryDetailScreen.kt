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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.livefork.app.app.Sites
import dev.livefork.app.modules.category.detail.CategoryDetailController
import dev.livefork.app.ui.Router
import dev.livefork.app.ui.components.LiveRoomCard
import dev.livefork.app.ui.components.resolveLiveRoomGrid
import dev.livefork.app.ui.theme.CategoryBlue
import dev.livefork.app.ui.theme.TextPrimary
import dev.livefork.app.ui.theme.TextSecondary
import dev.livefork.core.model.LiveSubCategory

/** 触底加载阈值：最后可见条目距列表末尾不足该数量时预加载下一页（对齐 HomeScreen）。 */
private const val LOAD_MORE_THRESHOLD_ITEMS = 6

/**
 * 分类详情页（Compose 版）—— 对应 Dart `modules/category/detail/category_detail_page.dart`，
 * 替换现版 View 体系 `ui/CategoryDetailActivity.kt`。
 *
 * 结构镜像：顶部栏（返回「←」+ 居中标题 16sp medium + 右侧对称占位），
 * 主体为直播间卡片网格（复用 LiveRoomCard 全套）：下拉刷新 + 触底分页 +
 * 首屏加载/错误重试/空态三态（范式与文案全部对齐 HomeScreen）。
 *
 * 参数经 route path/query 字符串传入（siteId / categoryId / categoryName）；
 * 控制器按参数 remember 创建，离开组合即销毁（等价原 Activity onDestroy）。
 *
 * @param siteId 站点 id（route path 参数）
 * @param categoryId 子分类 id（query 参数，可空）
 * @param categoryName 子分类名（仅用于标题展示；详情接口只用 id）
 * @param onBack 返回回调（对应原 Activity finish → popBackStack）
 */
@Composable
fun CategoryDetailScreen(
    siteId: String,
    categoryId: String,
    categoryName: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val site = Sites.allSites[siteId]

    // 站点不存在：提示后退出（对应原 onCreate 里 toast + finish）
    LaunchedEffect(siteId) {
        if (site == null) {
            Router.toast("站点不存在：$siteId")
            onBack()
        }
    }
    if (site == null) {
        // 空白帧等待返回动画（下一帧即 popBackStack）
        Box(modifier = modifier.fillMaxSize().background(Color.White))
        return
    }

    // 控制器路由级生命周期：remember 创建 + DisposableEffect 销毁
    // （对齐原 Activity onDestroy 调 controller.dispose()）
    val controller = remember(siteId, categoryId) {
        CategoryDetailController(
            site = site,
            subCategory = LiveSubCategory(name = categoryName, pic = null, id = categoryId, parentId = ""),
        )
    }
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }

    Column(modifier = modifier.fillMaxSize().background(Color.White)) {
        DetailTitleBar(title = categoryName.ifEmpty { "分类详情" }, onBack = onBack)
        DetailRoomGrid(
            controller = controller,
            onOpenRoom = { roomId ->
                // 直播间仍为旧 Activity（Batch 7 才迁路由），入口保持 Router.openLiveRoom
                Router.openLiveRoom(site, roomId)
            },
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 顶部栏 —— 对齐现版布局：返回键（22sp #333）+ 居中标题（16sp medium 单行省略）+
 * 右侧占位使标题居中。
 */
@Composable
private fun DetailTitleBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .background(Color.White)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "←",
            fontSize = 22.sp,
            color = TextPrimary,
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onBack,
                )
                .padding(vertical = 12.dp, horizontal = 8.dp),
        )
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 12.dp),
        )
        // 右侧占位使标题居中（对应返回键对称位置）
        Text(
            text = "",
            fontSize = 22.sp,
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
        )
    }
}

/**
 * 直播间卡片网格 —— 复用首页网格范式（下拉刷新 + 触底分页 + 三态覆盖层），
 * 网格规格经 [resolveLiveRoomGrid] 计算、行高固定 mainAxisExtent，均对齐 HomeScreen。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailRoomGrid(
    controller: CategoryDetailController,
    onOpenRoom: (roomId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val list by controller.list.collectAsStateWithLifecycle()
    val pageLoading by controller.pageLoading.collectAsStateWithLifecycle()
    val pageEmpty by controller.pageEmpty.collectAsStateWithLifecycle()
    val pageError by controller.pageError.collectAsStateWithLifecycle()
    val errorMsg by controller.errorMsg.collectAsStateWithLifecycle()
    val canLoadMore by controller.canLoadMore.collectAsStateWithLifecycle()
    val gridState = remember { LazyGridState() }

    // 首次进入加载（对齐原 Activity onCreate 的 controller.loadData()：
    // currentPage==1 且无数据才自动加载；失败重进自动重试）
    LaunchedEffect(controller) {
        if (!controller.loading &&
            !controller.pageLoading.value &&
            controller.currentPage == 1 &&
            controller.list.value.isEmpty()
        ) {
            controller.loadData()
        }
    }

    // 触底分页：派生「接近底部」状态（loadData 内部有 loading 防重入守卫）
    val nearBottom by remember(gridState) {
        derivedStateOf {
            val info = gridState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= 0 && info.totalItemsCount - lastVisible <= LOAD_MORE_THRESHOLD_ITEMS
        }
    }
    LaunchedEffect(nearBottom, canLoadMore, pageLoading, pageEmpty, pageError) {
        if (nearBottom && canLoadMore && !pageLoading && !pageEmpty && !pageError) {
            controller.loadData()
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val spec = resolveLiveRoomGrid(maxWidth.value)
        val pullState = rememberPullToRefreshState()

        PullToRefreshBox(
            isRefreshing = pageLoading,
            onRefresh = { controller.refreshData() },
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
                    contentPadding = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(list) { item ->
                        // 固定行高 = mainAxisExtent（封面 16:9 高 + 文字区 64dp），对齐 Dart mainAxisExtent
                        Box(modifier = Modifier.fillMaxWidth().height(spec.mainAxisExtentDp.dp)) {
                            LiveRoomCard(
                                item = item,
                                onClick = { onOpenRoom(item.roomId) },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }

                // ===== 三态覆盖层（文案对齐 HomeScreen 与现版 CategoryDetailActivity）=====
                when {
                    pageError -> {
                        StatusOverlay(onClick = { controller.refreshData() }) {
                            Text(
                                text = "加载失败：" + errorMsg.trim().ifEmpty { "未知错误" } + "\n点击刷新",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    pageEmpty -> {
                        StatusOverlay(onClick = { controller.refreshData() }) {
                            Text(
                                text = "暂无内容\n点击刷新",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
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
