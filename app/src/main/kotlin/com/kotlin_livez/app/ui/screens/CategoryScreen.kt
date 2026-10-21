package com.kotlin_livez.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.kotlin_livez.app.ui.components.ViewportWidthBox
import com.kotlin_livez.app.ui.theme.LocalAppDarkTheme
import com.kotlin_livez.app.ui.theme.cardContainerColor
import com.kotlin_livez.core.model.LiveSubCategory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** 下拉刷新启动宽限：onRefresh 后若这么久内 pageLoading 仍未变真，视为本次下拉是 no-op
 *（2s 刷新冷却 early-return），直接收回指示器。与 `PagedRoomGrid.kt` 同一范式
 *（ui-compose.md 条目 19；第 3 个消费者出现时应收敛为共享实现）。 */
private const val PULL_REFRESH_START_GRACE_MS = 400L

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

/**
 * 分类网格行：主分类标题跨全宽、子分类卡片、「显示全部」卡片。
 * 三个行类型均为 `data class`（值等值）：它们不持有 lambda，值等值对渲染无行为影响。
 * 注：值等值本身不等于「可跳过重组」——行参数 `AppLiveCategory` 含 MutableStateFlow
 * 属性（非稳定类型），`CategoryRow` 也随之不是稳定类型，故**不拿行对象当 key**，
 * 而是由 `CategorySiteGrid` 从行内容派生显式字符串 key（见下方 `rowKey`）。
 */
private sealed class CategoryRow {
    data class Header(val cat: AppLiveCategory) : CategoryRow()
    data class Sub(val cat: AppLiveCategory, val sub: LiveSubCategory) : CategoryRow()
    data class ShowMore(val cat: AppLiveCategory) : CategoryRow()
}

/**
 * 分类行的「基础」稳定字符串 key（去重兜底在 [CategorySiteGrid] 内统一施加，见那里的
 * `rowKeys`）。
 *
 * ① **为什么 key 不能用行对象本身**：`CategoryRow` 的每个变体都装着 `AppLiveCategory`
 * （其 `showAll` 是 `MutableStateFlow`——非稳定类型、equals 按引用），`data class` 的值等值
 * 因此对 `AppLiveCategory` 退化为引用等值；拿行对象当 key，同一逻辑行在数据重建后会得到
 * 「新身份」，复用/滚动锚定全部失效。故一律用**显式字符串**。
 *
 * ② **为什么不能省略去重兜底**：`LazyVerticalGrid` 遇重复 key 会**直接抛运行时异常**
 * （compose-ui `SubcomposeLayout.kt:481`：Key "..." was already used，`requirePrecondition`）。
 * 在本次改动（task 10-17-ui-animation-fluency-polish 加 key）之前，
 * 这个列表**没有** key、位置即身份，重复 id 只会让卡片重复渲染（可接受的瑕疵）；
 * 加了 key 之后同一输入会升级成**分类页崩溃**。分类页是主路径，故必须兜底。
 *
 * 基础 key 的唯一性论证（同一网格内）：
 * - 三个前缀 `header:` / `sub:` / `more:` 互斥，跨行类型不可能相撞；
 * - `rows` 由 `categories.flatMap` 生成，每个 `cat` 在网格内只出现一次
 *   （`categories` 为站点分类树根列表，同站点内 `LiveCategory.id` 唯一）
 *   ⇒ `header:${cat.id}` / `more:${cat.id}` 唯一；
 * - `sub:` 用 `${cat.id}:${sub.id}` 限定作用域：只需**同一 cat 的 children 内 `sub.id` 唯一**
 *   （跨 cat 复用同一个 sub.id 也不会撞，因为带了 cat.id）。各站点的 id 来源：B 站 area `id`、
 *   斗鱼 `cate1Id`/`cate2Id`、虎牙 `gid`、抖音 `"${id_str},${type}"`——都是 API 标识符，
 *   正常唯一；但站点实现普遍用 `.str()`/`.str().orEmpty()`，**空串是可能的**（缺字段即空），
 *   那就会退化为重复 → 由 `rowKeys` 的 `#序号` 兜住。
 */
private fun CategoryRow.rowKey(): String = when (this) {
    is CategoryRow.Header -> "header:${cat.id}"
    is CategoryRow.Sub -> "sub:${cat.id}:${sub.id}"
    is CategoryRow.ShowMore -> "more:${cat.id}"
}

/**
 * 单站点分类网格 —— 对应 Dart category_list_view.dart（EasyRefresh + ListView +
 * StickyHeader + GridView.count）与现版 CatListView。
 *
 * 差异记录：Dart 用 sticky_headers 吸顶分组标题、现版 View 自研 translationY 卡位吸顶；
 * 本版按用户决策**不做吸顶**——主分类标题作为 full-span item 随内容滚动。
 * 「显示全部」逻辑照搬 AppLiveCategory.take15/showAll：点击置 showAll=true 后展开全部子分类。
 * 行序列 `rows` 按「分类数据 + 展开态快照」记忆化，只在两者变化时重建（见函数内说明）。
 *
 * 注：`AppLiveCategory.take15` 已在模型侧（`modules/category/CategoryListController.kt`）改为
 * 构造期缓存的 val——每次读取不再重新 `take(15)` 并分配列表；本页在此之上把同一分类的
 * 读取次数由 2 次降为 1 次。
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

    // 展开后的扁平行序列（镜像现版 CategoryAdapter.submit 的 flatMap 构建逻辑）。
    // 记忆化（task 10-17-ui-power-and-recomposition-governance）：仅在分类数据或任一
    // 「显示全部」展开态变化时重建，避免每次重组都重跑 flatMap 并新建整个行序列
    // （remember 的 key 按 equals 比较，key 里的 Map 内容不变即命中缓存）。
    // 展开态必须留在 key 内：点击「显示全部」只置 showAll.value = true（置位是展开的唯一驱动，
    // 见点击处的既有 gotcha）；漏掉这个 key 会让置位后 rows 不重建，表现为「点击显示全部无效」。
    val rows = remember(categories, showAllMap) {
        categories.flatMap { cat ->
            buildList {
                add(CategoryRow.Header(cat))
                val showAll = showAllMap[cat.id] ?: true
                // take15 只取一次（原实现同一分类读两次，每次都重新 take(15) 分配一个列表）
                val take15 = cat.take15
                val subs = if (showAll) cat.children else take15
                subs.forEach { add(CategoryRow.Sub(cat, it)) }
                if (!showAll && cat.children.size > take15.size) {
                    add(CategoryRow.ShowMore(cat))
                }
            }
        }
    }

    // 网格 item 的最终 key 列表（与 `rows` 下标一一对应）。
    // 与 `PagedRoomGridCore.itemKeys` **同一套口径**（便于后人理解）：基础 key 取自
    // `CategoryRow.rowKey()`，对同一基础 key 的**第 2 次及以后出现**追加 `#序号`——
    // 因为 `LazyVerticalGrid` 遇重复 key 抛运行时异常，而加 key 之前该列表是「位置即身份」、
    // 不存在这个崩溃面（详见 `rowKey` 的 ② 段）。正常（无重复）时 key 与基础 key 完全一致，
    // 首个出现者保持裸 key，其余条目不受影响。
    // 记忆化 key 直接依赖 `rows`（`rows` 自身仍由 `remember(categories, showAllMap)` 派生，
    // 展开态照旧驱动重建，见上）。
    val rowKeys = remember(rows) {
        val seen = HashMap<String, Int>()
        rows.map { row ->
            val base = row.rowKey()
            val times = seen.getOrDefault(base, 0)
            seen[base] = times + 1
            if (times == 0) base else "$base#$times"
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

    ViewportWidthBox(modifier = modifier.fillMaxSize()) { viewportWidthDp ->
        // 列数对齐 Dart/现版：(视口宽 / 80).clamp(1, 12)；
        // 视口宽度优先来自 SitePagerTabs 下发的 Local（0 层 SubcomposeLayout）
        val columns = (viewportWidthDp / 80f).toInt().coerceIn(1, 12)
        val pullState = rememberPullToRefreshState()

        // 下拉指示器自保（见 ui-compose.md 条目 19）：onRefresh 若因 2s 冷却被 early-return，
        // pageLoading 不产生 true→false 变化沿，M3 指示器会永久停在阈值位。用本地状态兜底，保证必定归位。
        var pullRefreshing by remember(listController) { mutableStateOf(false) }
        val refreshing = pageLoading || pullRefreshing
        LaunchedEffect(listController, pullRefreshing) {
            if (!pullRefreshing) return@LaunchedEffect
            // 宽限期内等请求真正开始（loadData 的 scope 为 Main.immediate ⇒ 正常刷新这里立即返回，
            // 不额外等满 400ms）；未开始 ⇒ 视为 no-op，宽限期满直接收回。
            val started = withTimeoutOrNull(PULL_REFRESH_START_GRACE_MS) {
                listController.pageLoading.first { it }
            }
            if (started != null) listController.pageLoading.first { !it }
            pullRefreshing = false
        }

        // 下拉刷新（对应 Dart EasyRefresh MaterialHeader / 现版 SwipeRefreshLayout）
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                pullRefreshing = true
                listController.refreshData()
            },
            state = pullState,
            modifier = Modifier.fillMaxSize(),
            // 刷新指示器颜色对齐现版 setColorSchemeColors(#3498DB，现映射 secondary)
            indicator = {
                PullToRefreshDefaults.Indicator(
                    isRefreshing = refreshing,
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
                    itemsIndexed(
                        items = rows,
                        // 稳定唯一 key（去重后的 rowKeys，下标与 rows 对齐；论证见 CategoryRow.rowKey
                        // 与上方 rowKeys）：「显示全部」展开/收起、下拉刷新换新数据时，让网格按身份
                        // 而非位置复用条目，并保证「站点返回空串/重复子分类 id」不会因重复 key 抛异常。
                        // `getOrElse` 是越界兜底（Phase 2.3）：rowKeys 与 rows 在同一 `remember` 内计算、
                        // 正常恒同长；但 LazyGrid 的 item provider 会在快照通知时、下一帧重组**之前**
                        // 重算，历史上正是这类「新数据 + 旧下标数组」的撕裂把首页打崩过 —— 这里绝不允许
                        // 下标越界抛 IndexOutOfBoundsException。
                        // 本页在 **SitePagerTabs 页内**，故**不加** Modifier.animateItem()
                        // （逐项 placement 动画 = 逐项 graphicsLayer，ui-compose.md 条目 27 的
                        // C2 结论已实测回退；条目 26 的 PagerJump 第 5 条铁律同样禁止）。
                        key = { index, row -> rowKeys.getOrElse(index) { "${row.rowKey()}#$index" } },
                        // 主分类标题跨全宽（随滚动，不做吸顶——用户已决策）。
                        // 网格版 itemsIndexed 的 span/contentType 首参是 index，此处仍**只按行类型**
                        // 判定（与改造前逐字等价，index 未参与），故跨全宽语义未变。
                        span = { _, row ->
                            if (row is CategoryRow.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1)
                        },
                        contentType = { _, row ->
                            when (row) {
                                is CategoryRow.Header -> "header"
                                is CategoryRow.Sub -> "sub"
                                is CategoryRow.ShowMore -> "more"
                            }
                        },
                    ) { _, row ->
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
 * 子分类卡片 —— 对齐现版 buildSubCard 视觉：白底圆角 8dp、elevation 2dp
 * （深色/纯黑主题下跳过阴影）、统一固定高 84dp、内边距 8/10、图 40dp 圆角 8dp
 * （等价 NetImage.loadRound）、名称 12sp onSurfaceVariant 单行居中；
 * [hideImage]=true 时为「显示全部」卡片（隐藏图片槽位）。
 */
@Composable
private fun SubCategoryCard(
    name: String,
    pic: String?,
    onClick: () -> Unit,
    hideImage: Boolean = false,
) {
    // 深色/纯黑主题下跳过 2dp 阴影（task 10-17-ui-power-and-recomposition-governance）：
    // 深色底上阴影几乎不可见，却仍要按 elevation 录一层阴影绘制指令，纯属浪费。
    val cardShadow = if (LocalAppDarkTheme.current) {
        Modifier
    } else {
        Modifier.shadow(elevation = 2.dp, shape = RoundedCornerShape(8.dp))
    }
    Column(
        modifier = Modifier
            .height(84.dp)
            .then(cardShadow)
            // 圆角涟漪裁剪（本任务）：先 clip 再 background/clickable，防水波纹溢出圆角。
            // clip 排在 `.shadow` **之后** —— 阴影画在自己的图层上，clip 在其前会连阴影一起裁掉。
            .clip(RoundedCornerShape(8.dp))
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
