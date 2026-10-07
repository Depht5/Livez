package com.kotlin_livez.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.kotlin_livez.app.app.Site
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 站点标签行 + 站点横向分页器（首页 / 分类页**唯一实现**）—— 对应 Dart
 * `home_page.dart` / `category_page.dart` 的 `TabBar` + `TabBarView`。
 *
 * 收敛原因：首页与分类页原有两份逐行同构的 `SiteTabRow` + `HorizontalPager`，
 * 本次两项改动（标签居中、切换跳过中间页）两边都要，必须只有一份实现，否则必然漂移。
 *
 * 与改动前一致的部分：Tab 内容（logo 24dp + 8dp 间距 + 16sp 站名、选中色手动切换、
 * 点击无涟漪）、指示条逐帧跟随插值、`beyondViewportPageCount` 默认 0（静止只组合当前页）、
 * 相邻站点点击与滑动切页走 `animateScrollToPage`。
 *
 * 本次新增：① 标签组装得下时水平居中（见 [rememberCenteredEdgePadding]）；
 * ② 点击相隔 ≥2 个站点的 Tab 时播放「两页过渡」，只组合当前页与目标页（见 [SitePagerTabs]）。
 */

/* ---------- 尺寸常量（来源：material3 ScrollableTabRow 实现与既有 Tab 样式）---------- */

/**
 * 单 tab 最小宽度 —— 对齐 material3 `ScrollableTabRowMinimumTabWidth = 90.dp`
 * （TabRow.kt 内部常量）。参与总宽计算是必须的：短站名时 tab 会被撑到该宽度，
 * 漏算会低估总宽 → 居中偏移。
 */
private val TabMinWidth = 90.dp

/** Tab 内容左右内边距（对齐 Dart `labelPadding: edgeInsetsH20`）。 */
private val TabContentPadding = 20.dp

/** 平台 logo 尺寸（与分类页 Tab 统一 24dp）。 */
private val TabLogoSize = 24.dp

/** logo 与站名的间距（对齐 Dart `hGap8`）。 */
private val TabLogoGap = 8.dp

/** 标签行起始边距兜底值：标签组超宽时维持左对齐 + 可横向滚动（改动前的既有行为）。 */
private val TabEdgePadding = 12.dp

/** 两页过渡时长（与全局页面转场 `tween(280)` 一致）。 */
private const val PAGE_JUMP_DURATION_MS = 280

/**
 * 「跳过中间页」两页过渡状态 —— 过渡进度随对象创建，目标页覆盖层与 Pager 平移共用它。
 *
 * 为何进度放在这里而不是页面级 `remember { Animatable }`：每次起跳都新建对象、进度初值恒为 0，
 * 起跳当帧渲染的就是「源页满屏」；若复用同一个 Animatable 再在 `LaunchedEffect` 里
 * `snapTo(0f)`，会先渲染出一帧「新过渡 + 旧进度 1」的画面（目标页整屏闪一帧）。
 */
private class PagerJump(
    val source: Int,
    val target: Int,
    /**
     * 目标页覆盖层专用网格状态（由目标页真实状态播种）。
     * 落位那一帧 Pager 会用**真实**状态渲染目标页，此处若复用同一个 [LazyGridState]，
     * 同一个状态会被两个 LazyVerticalGrid 同时挂载（共享 scrollPosition/layoutInfo/prefetch
     * 策略，属未定义行为）—— 故覆盖层必须用独立实例。
     */
    val overlayGridState: LazyGridState,
) {
    /** 0 → 1 过渡进度。 */
    val progress = Animatable(0f)

    /** 滑动方向：目标在右为 +1、在左为 -1。 */
    val direction: Int get() = if (target > source) 1 else -1
}

/**
 * 站点 Tab 行 —— 对应 Dart `TabBar`（logo + 文字、`isScrollable: true`、
 * `labelPadding H20`、`TabAlignment.center`、`indicatorSize: label`）。
 *
 * 指示条逐帧跟随：不用 `tabIndicatorOffset`（按 selectedTabIndex 定位 + 自带过渡动画，
 * 页面跨中线才更新，真机反馈「滑多少、指示条不动」）。改为由调用方提供连续页位置
 * （[indicatorPosition]，手势/翻页动画期 = `currentPage + currentPageOffsetFraction`，
 * 两页过渡期 = 源→目标插值位置），在相邻两个 TabPosition 之间线性插值出偏移与宽度，
 * 实现「页面滑多少、指示条动多少」。该 lambda 在**布局阶段**被调用，读状态只触发重排，
 * 不会让调用方每帧重组。
 *
 * @param sites 站点列表（顺序即 Tab 顺序）
 * @param selectedIndex 当前选中站点（驱动站名选中色）
 * @param indicatorPosition 连续页位置（可为小数；跨多格的过渡进度也由它表达）
 * @param indicatorColor 指示条与选中站名颜色（首页 primary / 分类页 secondary）
 * @param onSelect 点击 Tab 回调（参数为站点下标）
 * @param trailing 行尾固定槽（首页 = 搜索入口；分类页不传）。声明为 RowScope 扩展，
 *   便于调用方用 `Modifier.align(Alignment.CenterVertically)` 垂直居中
 */
@Composable
fun SiteTabRow(
    sites: List<Site>,
    selectedIndex: Int,
    indicatorPosition: () -> Float,
    indicatorColor: Color,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // 标签滚动区占剩余宽度（居中在「可用区」内进行，不含行尾固定槽，
        // 语义对齐 Dart AppBar 的 title 区不包含 actions）
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val edgePadding = rememberCenteredEdgePadding(sites, maxWidth)
            ScrollableTabRow(
                selectedTabIndex = selectedIndex,
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.surface,
                edgePadding = edgePadding,
                // 指示条宽度随标签内容（对齐 isTabIndicatorFullWidth = false）
                indicator = { tabPositions ->
                    // 空列表守卫：tabPositions 为空时 lastIndex = -1，coerceIn 会抛异常
                    if (tabPositions.isNotEmpty()) {
                        // 连续页位置：整数部分 = 左邻 Tab，小数部分 = 过渡进度
                        // （可为负——往回滑时 currentPage 已进位而 offset 为负，floor 后仍落在正确区间）
                        val pos = indicatorPosition()
                        val leftIndex = floor(pos).toInt().coerceIn(0, tabPositions.lastIndex)
                        val rightIndex = (leftIndex + 1).coerceIn(0, tabPositions.lastIndex)
                        val fraction = (pos - leftIndex).coerceIn(0f, 1f)
                        val leftRect = tabPositions[leftIndex]
                        val rightRect = tabPositions[rightIndex]
                        // 手写等价 SecondaryIndicator + tabIndicatorOffset：偏移/宽度随连续位置插值；
                        // 高度 3dp 对齐 M3 SecondaryIndicator 默认粗细
                        Box(
                            modifier = Modifier
                                .wrapContentSize(Alignment.BottomStart)
                                .offset(
                                    x = lerp(leftRect.left.value, rightRect.left.value, fraction).dp,
                                )
                                .width(
                                    lerp(leftRect.width.value, rightRect.width.value, fraction).dp,
                                )
                                .height(3.dp)
                                .background(indicatorColor),
                        )
                    }
                },
                // 现版 TabLayout 无底部分隔线，去掉 M3 默认 divider
                divider = { },
            ) {
                sites.forEachIndexed { index, site ->
                    val selected = index == selectedIndex
                    Row(
                        modifier = Modifier
                            .clickable { onSelect(index) }
                            .padding(horizontal = TabContentPadding, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(
                            painter = painterResource(site.logoRes),
                            contentDescription = site.name,
                            modifier = Modifier.size(TabLogoSize),
                        )
                        Spacer(modifier = Modifier.width(TabLogoGap))
                        Text(
                            text = site.name,
                            fontSize = 16.sp,
                            // 手动切色：custom tab 内容不受 Tab 配色控制（对齐 setTabTextColors）
                            color = if (selected) {
                                indicatorColor
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }

        trailing?.invoke(this)
    }
}

/**
 * 站点横向分页器 —— 标签行 + 分页器 + 「跳过中间页」两页过渡。
 *
 * 背景（为什么需要它）：点 Tab 走 `animateScrollToPage` 时滚动位置连续穿过中间页，
 * `HorizontalPager` 会依次组合与视口相交的页 —— 从 B 站切到抖音会把斗鱼/虎牙的卡片
 * 与图片请求一起触发（大屏上代价明显）。本组件对「相隔 ≥2 个站点」的点击改为两页过渡：
 * 当前页整体滑出、目标页从相邻方向滑入，**全程只组合这两页**，时长固定 [PAGE_JUMP_DURATION_MS] ms。
 *
 * 实现要点（均为源码级约束，改动前请先读）：
 * 1. 过渡期 Pager **必须保持组合**，只做位移：`PagerState.scrollToPage` 内部
 *    `awaitLayoutModifier.waitForFirstLayout()`（foundation `PagerState.kt`），
 *    把 Pager 摘出组合会让落位挂起。
 * 2. 源页由 Pager 自身渲染（它停在源页，静止时只组合当前页）→ 零重复组合；
 *    目标页由覆盖层渲染（临时网格状态，见 [PagerJump.overlayGridState]）。
 * 3. 两页位移互补：进度 p 时源页占 [-pW, W-pW]、目标页占 [W-pW, 2W-pW]，
 *    并集恒为视口 → 无空白帧。
 * 4. 结束顺序不可颠倒：先 `scrollToPage(target)`（此时覆盖层仍遮住整屏），
 *    再清 `jump` —— 同一帧露出已组合好的目标页，无闪烁。
 *
 * @param pagerState 分页器状态（由页面创建并持有：底栏「重按首页/分类」事件要读 currentPage）
 * @param sites 站点列表
 * @param gridStates 各站点网格滚动状态（键 = 站点 id，由壳层持有）
 * @param selectedIndex 当前选中站点下标
 * @param indicatorColor 指示条/选中色
 * @param onSelectSite 选中站点变化的即时回调（两页过渡起跳时立刻通知，让 Tab 选中色即时反馈）
 * @param pageContent 单页内容（首页传 PagedRoomGrid / 分类页传分类网格）；
 *   两页过渡的目标页会以**临时网格状态**再调一次，故实现必须是可重入的纯渲染
 * @param trailing 透传给 [SiteTabRow] 的行尾槽
 */
@Composable
fun SitePagerTabs(
    pagerState: PagerState,
    sites: List<Site>,
    gridStates: Map<String, LazyGridState>,
    selectedIndex: Int,
    indicatorColor: Color,
    onSelectSite: (Int) -> Unit,
    pageContent: @Composable (site: Site, gridState: LazyGridState) -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    // 当前两页过渡（null = 空闲）。用 remember 而非 rememberSaveable：过渡是瞬时 UI 态，
    // 进程重建/旋转后不需要恢复（旋转会中断动画，落位由下方 scrollToPage 兜底语义承担）。
    var jump by remember { mutableStateOf<PagerJump?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val lastIndex = sites.lastIndex.coerceAtLeast(0)

    // 过渡驱动：起跳对象即动画载体。
    LaunchedEffect(jump) {
        val active = jump ?: return@LaunchedEffect
        active.progress.animateTo(1f, tween(PAGE_JUMP_DURATION_MS))
        // Pager 仍组合（只是被平移出视口）——scrollToPage 才能立即生效
        pagerState.scrollToPage(active.target)
        jump = null
    }

    val onSelect: (Int) -> Unit = { index ->
        val current = pagerState.currentPage.coerceIn(0, lastIndex)
        when {
            // 过渡进行中忽略重复请求（过渡仅 280ms，排队反而更差）
            jump != null -> Unit
            // 重复点击当前站点：无动作（对齐既有语义，Null 分支不触发滚动）
            index == current -> Unit
            // 相邻站点：维持整段滑动（此时视口内天然只有这两页，无中间页问题）
            abs(index - current) <= 1 -> coroutineScope.launch {
                pagerState.animateScrollToPage(index)
            }
            // 跨多页：两页过渡（只组合当前页与目标页）
            else -> {
                val real = sites.getOrNull(index)?.let { gridStates[it.id] }
                jump = PagerJump(
                    source = current,
                    target = index,
                    overlayGridState = LazyGridState(
                        real?.firstVisibleItemIndex ?: 0,
                        real?.firstVisibleItemScrollOffset ?: 0,
                    ),
                )
                // Tab 选中色即时反馈（对齐 M3 语义：颜色即切、指示条动画跟随）
                onSelectSite(index)
            }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 过渡幅宽 = 分页区宽度（像素）：两页各占一屏，位移按像素计算避免 Dp 取整误差
        val viewportWidthPx = constraints.maxWidth

        Column(modifier = Modifier.fillMaxSize()) {
            SiteTabRow(
                sites = sites,
                selectedIndex = selectedIndex,
                // 连续页位置：过渡期按源→目标插值（指示条跨多格移动），
                // 其余时刻跟随手势/翻页动画的连续位置
                indicatorPosition = {
                    val active = jump
                    if (active != null) {
                        active.source + (active.target - active.source) * active.progress.value
                    } else {
                        pagerState.currentPage + pagerState.currentPageOffsetFraction
                    }
                },
                indicatorColor = indicatorColor,
                onSelect = onSelect,
                trailing = trailing,
            )

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        // 源页：过渡期整体移出视口。在 placement 阶段（offset lambda）读进度
                        // → 每帧只重排不重组
                        .offset {
                            val active = jump ?: return@offset IntOffset.Zero
                            IntOffset(
                                x = (-active.direction * active.progress.value * viewportWidthPx)
                                    .roundToInt(),
                                y = 0,
                            )
                        },
                ) { page ->
                    // 越界保护：sites 尺寸变化瞬间 pageCount 已更新但页面索引可能短暂越界
                    val pageIdx = page.coerceIn(0, lastIndex)
                    val site = sites[pageIdx]
                    val gridState = gridStates[site.id]
                    if (gridState != null) {
                        pageContent(site, gridState)
                    } else {
                        // 控制器尚未就绪的兜底空页（正常流程不会出现）
                        Box(modifier = Modifier.fillMaxSize())
                    }
                }

                val active = jump
                if (active != null) {
                    // 目标页：从对侧滑入，与源页位移互补（并集恒为视口，无空白帧）
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .offset {
                                IntOffset(
                                    x = (active.direction * (1f - active.progress.value) *
                                        viewportWidthPx).roundToInt(),
                                    y = 0,
                                )
                            },
                    ) {
                        val targetSite = sites.getOrNull(active.target)
                        if (targetSite != null) {
                            pageContent(targetSite, active.overlayGridState)
                        }
                    }
                    // 过渡期吞掉触摸：防误触移动中的卡片（Initial 阶段消费，优先级最高）
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitPointerEvent(PointerEventPass.Initial)
                                            .changes
                                            .forEach { it.consume() }
                                    }
                                }
                            },
                    )
                }
            }
        }
    }
}

/**
 * 计算标签行的居中边距（对应 Dart `TabBar(tabAlignment: TabAlignment.center)`）。
 *
 * 根因（material3 1.3.x `ScrollableTabRowImpl`，TabRow.kt）：每个 tab 取自然宽度
 * （下限 [TabMinWidth]），从 `edgePadding` 起依次左排，容器为 `CenterStart` 对齐 ——
 * **装得下也不会居中**，居中只能由调用方给出「能让内容居中的 edgePadding」。
 *
 * 总宽 = Σ max(TabMinWidth, logo + 间距 + 文字宽 + 左右内边距)。
 * 文字度量必须与真实 `Text` 同款 style：Tab 内 `Text` 只覆盖了 `fontSize`，
 * 其余属性（含 `letterSpacing`）来自 MaterialTheme 下发的 `LocalTextStyle`（= bodyLarge），
 * 直接量默认 style 会偏窄 → 居中偏移。
 *
 * 装不下时回落 [TabEdgePadding]：维持改动前的「左对齐 + 可横向滚动」。
 */
@Composable
private fun rememberCenteredEdgePadding(sites: List<Site>, available: Dp): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp)
    val totalWidth = remember(sites, textStyle, density, measurer) {
        sites.fold(0.dp) { acc, site ->
            val textWidth = with(density) {
                measurer.measure(site.name, style = textStyle).size.width.toDp()
            }
            acc + maxOf(
                TabMinWidth,
                TabLogoSize + TabLogoGap + textWidth + TabContentPadding * 2,
            )
        }
    }
    return if (totalWidth < available) (available - totalWidth) / 2 else TabEdgePadding
}
