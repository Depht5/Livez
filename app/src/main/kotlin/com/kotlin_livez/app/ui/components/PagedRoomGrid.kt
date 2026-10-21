package com.kotlin_livez.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kotlin_livez.app.app.controller.BasePageController
import com.kotlin_livez.app.ui.nav.activeSharedRoomKey
import com.kotlin_livez.app.ui.nav.sharedElementBlockedIndexes
import com.kotlin_livez.core.model.LiveRoomItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** 触底加载阈值：最后可见条目距列表末尾不足该数量时预加载下一页（对应 Dart EasyRefresh footer）。 */
private const val LOAD_MORE_THRESHOLD_ITEMS = 6

/** 下拉刷新启动宽限：onRefresh 后若这么久内 pageLoading 仍未变真，视为本次下拉是 no-op（关键词为空 /
 *  2s 刷新冷却 early-return），直接收回指示器。沿用 FollowService.emptyTargetsCollapseDelayMs 的既有范式。 */
private const val PULL_REFRESH_START_GRACE_MS = 400L

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
 *
 * task 10-17-ui-animation-fluency-polish 的增量（其余仍逐行等价）：
 * 1. **稳定 key**（`itemKey`，本组件在 `SitePagerTabs` 页内）：条目按 `siteId_roomId` 身份复用；
 *    搜索页不传 → 保持「位置即身份」。**不给任何 item 挂动画**（页内网格逐项
 *    `graphicsLayer`/`animateItem` 已被 ui-compose.md 条目 27 的 C2 结论证伪）。
 * 2. **Phase 2.3 真机回归**：状态整体 `Crossfade` **已回退**——首帧多一次状态跳变，稳态在首页
 *    主路径上多挂一层全屏图层，且真机实测切平台会因「新 list + 旧 keys 撕裂」闪退。
 *    现恢复为「常驻网格 + 上层三态覆盖层」（与 `CategoryScreen` 同构），`list` 取静态快照。
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
    // item 稳定 key（`siteId_roomId`）：让「刷新/换页后同一房间」按身份复用条目而不是按位置。
    // 必须 remember 固定 lambda 实例——PagedRoomGridCore 内以 itemKey 作 remember key，
    // 每次重组新建 lambda 会让它的 keys 缓存失效、退化为每次重组重算 O(n)。
    // 注：`LiveRoomItem`（core 模型）**没有** siteId 字段，此处的 siteId 来自本组件参数。
    val keyOf: (item: LiveRoomItem) -> Any = remember(siteId) {
        { item: LiveRoomItem -> if (siteId.isNullOrEmpty()) item.roomId else "${siteId}_${item.roomId}" }
    }
    // 同屏共享元素 key 唯一性守卫（见 SharedTransition.sharedElementBlockedIndexes）：
    // 同一 roomId 的第 2 次及以后出现、以及空 roomId 不挂一镜到底
    //（LazyGrid 的 `#n` 兜底只保护 item key，不保护共享元素 key）。
    val rooms = listController.list.collectAsStateWithLifecycle().value
    // R1（task 10-21-tablet-home-pager-fluency）：只有存在激活房间时才需要同屏去重遍历——
    // 否则卡片根本不会创建共享节点（见 liveRoomCoverSharedElement 的门控），遍历纯属浪费。
    val sharedRoomActive = activeSharedRoomKey != null
    val blockedIndexes = remember(rooms, sharedRoomActive) {
        if (sharedRoomActive) sharedElementBlockedIndexes(rooms.map { it.roomId }) else emptySet()
    }
    PagedRoomGridCore(
        listController = listController,
        gridState = gridState,
        modifier = modifier,
        indicatorColor = indicatorColor,
        errorTextBuilder = errorTextBuilder,
        emptyText = emptyText,
        itemKey = keyOf,
        itemContent = { index, item, mainAxisExtentDp ->
            // 固定行高 = mainAxisExtent（封面 16:9 高 + 文字区 64dp），对齐 Dart mainAxisExtent
            Box(modifier = Modifier.fillMaxWidth().height(mainAxisExtentDp.dp)) {
                LiveRoomCard(
                    item = item,
                    onClick = { onOpenRoom(item.roomId) },
                    modifier = Modifier.fillMaxSize(),
                    sharedElementSiteId = if (index in blockedIndexes) null else siteId,
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
 * @param itemKey item 稳定 key 工厂（可选）。非 null 时网格条目按身份复用（下拉刷新换列表、
 *   翻页追加后不发生位置错配）；为 null 时保持「位置即身份」的既有行为（搜索页混排列表不传）。
 *   **唯一性由调用方保证**，本组件对同一基础 key 的重复出现追加 `#序号` 兜底（LazyGrid 遇
 *   重复 key 会抛异常），细节见函数内注释。
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
    itemKey: ((item: T) -> Any)? = null,
    /** 空会话占位文案：仅当「无错误、非空态、非加载中、列表为空」且本参数非 null 时，居中显示一段
     *  非交互文案（不挂 pointer 修饰，不抢下拉手势）。默认 null = 既有行为（首页/分类/关注不受影响）。
     *  搜索页传「输入关键词搜索」——覆盖"尚未发起搜索 / 会话未开始"这一原本三态全不命中而空白的状态。 */
    emptyPlaceholderText: String? = null,
    itemContent: @Composable (index: Int, item: T, mainAxisExtentDp: Float) -> Unit,
) {
    // ⚠ 必须取**本组合帧的静态快照**（`.value`），不得写成 `val list by …collectAsStateWithLifecycle()`。
    // 属性委托在 `LazyVerticalGrid` 闭包内传 `items = list` 时会实时读快照，并被 `LazyGridItemProvider`
    // 的 `DerivedSnapshotState` 订阅：网络返回瞬间 `SnapshotStateObserver.drainChanges()` 会在
    // **下一帧重组之前**重算 item provider，于是 `items` 读到新列表（20 条）而同一闭包捕获的 key
    // 数组仍是旧帧的空列表（0 条）⇒ 首页切换平台必崩 `IndexOutOfBoundsException: Index 0 out of
    // bounds for length 0`（Phase 2.3 真机日志）。取 `.value` 后 `items` 与下方 keys 永远同帧同长。
    val list = listController.list.collectAsStateWithLifecycle().value
    val pageLoading by listController.pageLoading.collectAsStateWithLifecycle()
    val pageEmpty by listController.pageEmpty.collectAsStateWithLifecycle()
    val pageError by listController.pageError.collectAsStateWithLifecycle()
    val errorMsg by listController.errorMsg.collectAsStateWithLifecycle()
    val canLoadMore by listController.canLoadMore.collectAsStateWithLifecycle()

    // item 稳定 key（可选）+ 越界兜底 —— 二者在**同一个 `remember(list, itemKey)`** 内一起算
    // （此前只存 keys 数组、由 `itemsIndexed` 的 key 闭包按下标取，Phase 2.3 收紧为此形态）。
    // 设计规定 `siteId_roomId`，但**其唯一性并非结构保证**：首页/分类详情按「在线人数」排序分页，
    // 翻页之间榜单变动会让同一房间重复出现；而 LazyGrid 遇**重复 key 会抛异常**
    // （compose-ui `SubcomposeLayout.kt:481` "Key ... was already used"）→ 崩溃。
    // 故对同一基础 key 的第 2 次及以后出现追加 `#序号`：无重复时（正常情形）key 与设计完全一致
    // （首个出现者保持裸 key，其余条目不受影响）。
    // 与列表一一对应：keys[i] 属于 list[i]，同一 remember 作用域内计算，长度恒定。
    // 兜底再走一层 `getOrElse`：下标越界绝不允许把首页打崩（撕裂机理见上方 `list` 的注释）。
    val itemKeyOf: ((index: Int, item: T) -> Any)? = remember(list, itemKey) {
        val keyOf = itemKey
        if (keyOf == null) {
            null
        } else {
            val seen = HashMap<String, Int>()
            val keys = list.map { item ->
                val base = keyOf(item).toString()
                val times = seen.getOrDefault(base, 0)
                seen[base] = times + 1
                if (times == 0) base else "$base#$times"
            }
            // 必须落到标注了类型的局部量再返回：直接写 `{ index, item -> … }` 作为块内最后一个表达式
            // 时，解析器会把它当作紧邻的 `map { … }` 的**第二个尾随 lambda** 而报
            // 「Only one lambda expression is allowed outside a parenthesized argument list」。
            val byIndex: (index: Int, item: T) -> Any = { index, item ->
                keys.getOrElse(index) { "${keyOf(item)}#$index" }
            }
            byIndex
        }
    }

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

    ViewportWidthBox(modifier = modifier.fillMaxSize()) { viewportWidthDp ->
        // 网格规格按视口宽度实时计算（对应 Dart LayoutBuilder + LiveRoomGridLayout.resolve）；
        // 视口宽度优先来自 SitePagerTabs 下发的 Local（0 层 SubcomposeLayout），
        // 页外调用方（分类详情/关注）回退 BoxWithConstraints——见 ViewportWidthBox。
        val spec = resolveLiveRoomGrid(viewportWidthDp)
        val pullState = rememberPullToRefreshState()

        // 下拉指示器自保（见 ui-compose.md 条目 19）：onRefresh 若因关键词为空 / 2s 冷却被 early-return，
        // pageLoading 不产生 true→false 变化沿，M3 指示器会永久停在阈值位。用本地状态兜底，保证必定归位。
        var pullRefreshing by remember(listController) { mutableStateOf(false) }
        val refreshing = pageLoading || pullRefreshing
        LaunchedEffect(listController, pullRefreshing) {
            if (!pullRefreshing) return@LaunchedEffect
            // ① 宽限期内等请求真正开始（BaseController.loadData 的 scope 为 Main.immediate，
            //    refreshData() 返回前 pageLoading 已同步置位 ⇒ 正常刷新这里立即返回，不额外等满 400ms）；
            // ② 未开始 ⇒ 视为 no-op，宽限期满直接收回。
            val started = withTimeoutOrNull(PULL_REFRESH_START_GRACE_MS) {
                listController.pageLoading.first { it }
            }
            if (started != null) listController.pageLoading.first { !it }   // 请求已开始：等它结束再收回
            pullRefreshing = false
        }

        // 下拉刷新（对应 Dart RefreshIndicator / 现版 SwipeRefreshLayout）：
        // isRefreshing 绑定「控制器加载状态 + 本地自保状态」，刷完自动收起
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                pullRefreshing = true
                listController.refreshData()
            },
            state = pullState,
            modifier = Modifier.fillMaxSize(),
            // 刷新指示器颜色对齐现版 setColorSchemeColors(#3498DB)
            indicator = {
                PullToRefreshDefaults.Indicator(
                    isRefreshing = refreshing,
                    state = pullState,
                    modifier = Modifier.align(Alignment.TopCenter),
                    color = indicatorColor,
                )
            },
        ) {
            // ===== 网格常驻 + 三态覆盖层（Phase 2.3 真机回归修正：撤销四态 Crossfade）=====
            // 这里曾把「首屏加载态 ↔ 内容/空态/错误态」做成整块 Crossfade；真机上导致首页
            // **切换平台直接闪退**。移除理由各自独立成立：
            //   ① 首帧状态跳变：`Content`（pageLoading 尚未置位）→ `Loading` → `Content` 会让网格
            //      被反复挂载/卸载，每轮都要经历一次「空列表测量」（空数据测量会重置滚动位置）；
            //   ② 稳态多一层全屏图层（非逐项、不违反铁律），在首页主路径上无收益；
            //   ③ 与 `list` 取静态快照配合后，网格常驻是唯一能让 `LazyGridItemProvider` 的条目集合
            //      与 key 数组始终同帧的形态。
            // 恢复为与 `CategoryScreen` 同构的「常驻网格 + 上层 `when` 覆盖层」：三态互斥、语义与
            // Crossfade 版本逐条一致（pageError → 错误；pageEmpty → 空态；
            // pageLoading && list.isEmpty() → 首屏转圈；其余 = 网格 + 顶部下拉指示器）。
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
                        items = list,
                        // 稳定 key（见上方 itemKeyOf 注释；itemKey 为 null 时保持位置即身份）
                        key = itemKeyOf,
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

                when {
                    // 加载失败：错误信息 + 点击重试
                    pageError -> StatusOverlay(onClick = { listController.refreshData() }) {
                        Text(
                            text = errorTextBuilder(errorMsg),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }

                    // 空态：文案 + 点击刷新
                    pageEmpty -> StatusOverlay(onClick = { listController.refreshData() }) {
                        Text(
                            text = emptyText,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }

                    // 首屏加载中（尚无数据时居中转圈；已有数据时的翻页刷新由顶部下拉指示器表达）
                    pageLoading && list.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = indicatorColor)
                    }

                    // 未开始请求（搜索页无关键词 / 会话未开始）：三态全不命中时显式占位，
                    // 避免结果区一片空白。默认参数为 null ⇒ 首页/分类/关注恒不渲染本分支。
                    else -> if (emptyPlaceholderText != null && list.isEmpty()) {
                        // 复用共享空态组件（见 ui-compose.md 条目 31 第 5 点：新页面不要再各写一份）
                        EmptyHint(text = emptyPlaceholderText)
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
