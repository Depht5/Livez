package com.kotlin_livez.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.kotlin_livez.app.app.EventBus
import com.kotlin_livez.app.app.Site
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 站点标签行 + 站点横向分页器（首页 / 分类页 / 搜索页**唯一实现**）—— 对应 Dart
 * `home_page.dart` / `category_page.dart` 的 `TabBar` + `TabBarView`。
 *
 * 收敛原因：首页与分类页原有两份逐行同构的 `SiteTabRow` + `HorizontalPager`，
 * 本次两项改动（标签居中、切换跳过中间页）两边都要，必须只有一份实现，否则必然漂移。
 * 二次收敛：底栏「重按当前 Tab → 回顶或刷新」的选中态与监听同样从两个页面收敛到这里
 * （原先是两份逐行同构的 `selectedSiteIndex` / `pagerState` / `DisposableEffect` 样板）。
 * 三次收敛（task 10-18-search-swipe-site-pager）：搜索页也改为复用本组件（原页面私有的
 * `SiteTextTabRow` 已删），站点选中态与结果区分页器不再各写一份样板。
 *
 * 搜索页的取参（纯文字观感不变，见下方各参数）：`showLogo = false`（不渲染平台 logo）、
 * `siteNameFontSize = 14.sp`（保持原字号）、`bottomNavTabIndex = null`（搜索是路由、不属底栏，
 * 不注册「重按底栏」监听），并经 `onSiteChanged` 把选中站点回写
 * `AppSearchController.setTabIndex`（沿用其「有关键词且该站点结果为空则自动补拉」语义）。
 * **有意偏离 Dart**：Dart 搜索页的 `TabBarView` 是 `NeverScrollableScrollPhysics`
 * （`search_page.dart:91`，上游刻意禁用滑动）；本组件按需求方要求**不随 Dart 禁用滑动**，
 * 搜索页结果区可左右滑动切平台 —— 日后勿当「对齐 Dart」把它改回去。
 *
 * 默认取参下与改动前一致的部分：Tab 内容（logo 24dp + 8dp 间距 + 16sp 站名、点击无涟漪；
 * 搜索页关 logo、站名 14sp，见 `showLogo` / `siteNameFontSize`）、
 * 指示条逐帧跟随插值、站点槽位常驻（`beyondViewportPageCount = sites.size - 1`，
 * 内容由 `activeSiteIds` 按需门控）、相邻站点点击与滑动切页走 `animateScrollToPage`。
 *
 * 本次新增：① 标签组装得下时水平居中（见 [rememberCenteredEdgePadding]）；
 * ② 点击相隔 ≥2 个站点的 Tab 时播放「两页过渡」（见 [SitePagerTabs]）；
 * ③ 两页过渡改为**页内原位平移**（[pageJumpOffset]）：源页滑出、目标页滑入、
 * 无关页停在视口外 ±2W —— 不再有覆盖层，也没有 `movableContentOf` 跨树搬移，
 * 起跳/落位都不发生节点拆卸与整页重测量（改动前每跳一次都要把目标页节点树搬一次）；
 * ④ 站名颜色改为**绘制期**连续插值（`BasicText` + `ColorProducer`，见 [SiteTabRow]），
 * 相邻站点点击的 `animateScrollToPage` 显式传
 * `tween(PAGE_JUMP_DURATION_MS, FastOutSlowInEasing)` —— 站名与指示条同步跟手，
 * 且滑页/跨页期间不产生重组。
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

/** 指示条粗细（对齐 M3 `SecondaryIndicator` 默认值）。 */
private val IndicatorHeight = 3.dp

/** 两页过渡时长（与全局页面转场 `tween(280)` 一致）。 */
private const val PAGE_JUMP_DURATION_MS = 280

/**
 * 「跳过中间页」两页过渡状态 —— 过渡进度随对象创建，各页的页内位移共用它。
 *
 * 为何进度放在这里而不是页面级 `remember { Animatable }`：每次起跳都新建对象、进度初值恒为 0，
 * 起跳当帧渲染的就是「源页满屏」；若复用同一个 Animatable 再在 `LaunchedEffect` 里
 * `snapTo(0f)`，会先渲染出一帧「新过渡 + 旧进度 1」的画面（目标页整屏闪一帧）。
 *
 * 目标页内容**使用它自己的槽位**（R2 改为页内原位平移）：4 个站点槽位常驻，
 * 目标页就在 `(target - source)` 个视口之外组合好，过渡时通过 `Modifier.offset` 平移进视口，
 * 落位后 `scrollToPage(target)` 让 Pager 真正落到目标页。全程同一棵节点树、同一份
 * [LazyGridState]，不存在「覆盖层与 Pager 各组合一份」的双份网格。
 */
private class PagerJump(
    val source: Int,
    val target: Int,
) {
    /** 0 → 1 过渡进度。 */
    val progress = Animatable(0f)

    /** 滑动方向：目标在右为 +1、在左为 -1。 */
    val direction: Int get() = if (target > source) 1 else -1
}

/**
 * 跨页过渡的页内位移（task 10-21-tablet-home-pager-fluency R2）。
 *
 * Pager 始终**定格在源页**（过渡中不滚动），页 p 的自然位置是 `(p - source) * W`；
 * 叠加本函数的位移后：
 * - 源页 → `-direction * progress * W`（滑出）；
 * - 目标页 → `direction * (1 - progress) * W`（从对侧滑入）；
 * - 其他页 → `±2W`（落在视口外，不参与构图）。
 * 进度 1 时目标页位移为 0，随后 `scrollToPage(target)` 让 Pager 真正落到目标页，
 * 槽位偏移由 `currentPage == target` 守卫归零。
 */
private fun pageJumpOffset(
    active: PagerJump?,
    pageIdx: Int,
    currentPage: Int,
    viewportWidthPx: Int,
): Int {
    if (active == null) return 0
    // 落位后（Pager 已切到目标页）不再偏移，避免 scrollToPage 后残留一帧错位
    if (currentPage == active.target) return 0
    val direction = active.direction
    val progress = active.progress.value
    val offset = when (pageIdx) {
        active.source -> -direction * progress * viewportWidthPx
        active.target ->
            ((active.source - active.target) + direction * (1f - progress)) * viewportWidthPx
        else -> ((active.source - pageIdx + 2 * direction) * viewportWidthPx).toFloat()
    }
    return offset.roundToInt()
}

/**
 * 站点 Tab 行 —— 对应 Dart `TabBar`（logo + 文字、`isScrollable: true`、
 * `labelPadding H20`、`TabAlignment.center`、`indicatorSize: label`）。
 *
 * 指示条逐帧跟随：不用 `tabIndicatorOffset`（按 selectedTabIndex 定位 + 自带过渡动画，
 * 页面跨中线才更新，真机反馈「滑多少、指示条不动」）。改为由调用方提供连续页位置
 * （[indicatorPosition]，手势/翻页动画期 = `currentPage + currentPageOffsetFraction`，
 * 两页过渡期 = 源→目标插值位置），在相邻两个 TabPosition 之间线性插值出偏移与宽度，
 * 实现「页面滑多少、指示条动多少」。该 lambda 在**布局阶段**被调用（见下方 `Modifier.layout`），
 * 读状态只触发重排，不会让调用方每帧重组。
 *
 * 站名颜色同样逐帧连续：原来按 `if (selected) 选中色 else 未选中色` 离散取色 —— `selectedIndex`
 * 变更瞬间跳变，跨页过渡期间还会与指示条位置不一致。现改用 [BasicText] 的 `color: ColorProducer`：
 * 该 lambda **只在绘制阶段**被调用（foundation `TextStringSimpleNode.draw()` 内
 * `overrideColor?.invoke()`），每帧按 [indicatorPosition] 在「未选中色 → 选中色」之间 `lerp`
 * （相邻页滑动时两个站名交叉淡化），滑页/跳页动画期间每帧只重绘、**0 次重组**。
 * 注意：指示条本体仍在**布局**阶段读同一位置（下方 `Modifier.layout`），布局每帧失效重排是既有行为，
 * 本条只改取色时机，不引入任何 `graphicsLayer` 图层（PagerJump 铁律 5）。
 *
 * 为何不用 material3 `Text`：1.3.2 的 `Text` 只有 `color: Color`（无 `ColorProducer` 重载），
 * 该能力在 foundation 的 [BasicText] 上。`BasicText` 不读 `LocalTextStyle`（`style` 默认
 * `TextStyle.Default`），故必须把主题下发的 bodyLarge 显式合并进 `style`，
 * 否则字号/字重/行高会与原来的 `Text(text, fontSize = 16.sp)` 不一致。
 *
 * @param sites 站点列表（顺序即 Tab 顺序）
 * @param selectedIndex 当前选中站点（驱动 [ScrollableTabRow] 的选中项与滚动定位；站名颜色
 *   已改为绘制期插值，不再依赖它的离散比较）
 * @param indicatorPosition 连续页位置（可为小数；跨多格的过渡进度也由它表达）
 * @param indicatorColor 指示条与选中站名颜色（首页 primary / 分类页 secondary）
 * @param onSelect 点击 Tab 回调（参数为站点下标）
 * @param showLogo 是否渲染平台 logo 与 logo-站名间距（默认 true = 首页/分类页既有观感；
 *   搜索页传 false，保持纯文字 Tab）。同时决定居中度量是否计入 logo 宽度
 *   （见 [rememberCenteredEdgePadding]，漏传会让纯文字 Tab 的居中偏移多算一个 logo 宽）
 * @param siteNameFontSize 站名字号（null = 16sp 现状；搜索页传 14.sp 保持原字号）。
 *   渲染与居中度量共用同一个 style（单一事实源）
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
    showLogo: Boolean = true,
    siteNameFontSize: TextUnit? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    // 未选中站名颜色（绘制期插值的起点）；选中色由调用方以 indicatorColor 传入
    val unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
    // 站名排版：与 material3 `Text(text, fontSize = …)` 等价的样式 —— 基础来自主题下发的
    // LocalTextStyle（MaterialTheme 里 `ProvideTextStyle(typography.bodyLarge)`），仅覆盖字号。
    // BasicText 不读 LocalTextStyle，必须显式传入，否则字重/行高/字距会退回 TextStyle.Default。
    // 字号可被调用方覆盖（siteNameFontSize，默认 16sp = 首页/分类页现状）。
    val tabTextStyle = LocalTextStyle.current.merge(TextStyle(fontSize = siteNameFontSize ?: 16.sp))

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // 标签滚动区占剩余宽度（居中在「可用区」内进行，不含行尾固定槽，
        // 语义对齐 Dart AppBar 的 title 区不包含 actions）
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val edgePadding = rememberCenteredEdgePadding(sites, maxWidth, tabTextStyle, showLogo)
            ScrollableTabRow(
                selectedTabIndex = selectedIndex,
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.surface,
                edgePadding = edgePadding,
                // 指示条宽度随标签内容（对齐 isTabIndicatorFullWidth = false）
                indicator = { tabPositions ->
                    // 空列表守卫：tabPositions 为空时 lastIndex = -1，coerceIn 会抛异常
                    if (tabPositions.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                // 指示条位置/宽度在**布局阶段**读取并落位：连续页位置读的是动画状态，
                                // 若在 @Composable 函数体里读，indicator 子组合会在滑页/跳页动画期间每帧重组
                                // （正是审查检出的 Finding 6）；放进 Modifier.layout 后，状态变化只让本次布局
                                // 失效（重排），动画期间子组合 0 次重组。
                                // 占位节点铺满整行只是为了让 placeable 能在行内任意 x/y 落位，
                                // 真正被画成指示条的是下面按固定尺寸测量的那个 placeable。
                                .layout { measurable, constraints ->
                                    // 连续页位置：整数部分 = 左邻 Tab，小数部分 = 过渡进度
                                    // （可为负——往回滑时 currentPage 已进位而 offset 为负，floor 后仍落在正确区间）
                                    val pos = indicatorPosition()
                                    val leftIndex = floor(pos).toInt().coerceIn(0, tabPositions.lastIndex)
                                    val rightIndex = (leftIndex + 1).coerceIn(0, tabPositions.lastIndex)
                                    val fraction = (pos - leftIndex).coerceIn(0f, 1f)
                                    val leftRect = tabPositions[leftIndex]
                                    val rightRect = tabPositions[rightIndex]
                                    // 手写等价 SecondaryIndicator + tabIndicatorOffset：偏移/宽度随连续位置插值。
                                    // 先 roundToPx 再插值（而不是用 Dp 小数插值后交给下游取整），
                                    // 避免相邻两 tab 的取整误差方向不一致时指示条比标签宽/窄 1px。
                                    val indicatorWidthPx = lerp(
                                        leftRect.width.roundToPx().toFloat(),
                                        rightRect.width.roundToPx().toFloat(),
                                        fraction,
                                    ).roundToInt().coerceAtLeast(0)
                                    val indicatorOffsetXPx = lerp(
                                        leftRect.left.roundToPx().toFloat(),
                                        rightRect.left.roundToPx().toFloat(),
                                        fraction,
                                    ).roundToInt()
                                    val indicatorHeightPx = IndicatorHeight.roundToPx()
                                    // background 画在「被测量出来的 placeable」尺寸上（不是本布局节点的尺寸），
                                    // 故必须用固定尺寸约束测量它，否则整行都会被涂成指示条色
                                    val placeable = measurable.measure(
                                        Constraints.fixed(indicatorWidthPx, indicatorHeightPx),
                                    )
                                    layout(constraints.maxWidth, constraints.maxHeight) {
                                        placeable.placeRelative(
                                            x = indicatorOffsetXPx,
                                            y = (constraints.maxHeight - indicatorHeightPx).coerceAtLeast(0),
                                        )
                                    }
                                }
                                .background(indicatorColor),
                        )
                    }
                },
                // 现版 TabLayout 无底部分隔线，去掉 M3 默认 divider
                divider = { },
            ) {
                sites.forEachIndexed { index, site ->
                    Row(
                        modifier = Modifier
                            .clickable { onSelect(index) }
                            .padding(horizontal = TabContentPadding, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 平台 logo 可关（showLogo = false：搜索页保持纯文字 Tab）：
                        // 关时不渲染 logo 与间距，其余（clickable / padding / 站名绘制期取色）不动
                        if (showLogo) {
                            Image(
                                painter = painterResource(site.logoRes),
                                contentDescription = site.name,
                                modifier = Modifier.size(TabLogoSize),
                            )
                            Spacer(modifier = Modifier.width(TabLogoGap))
                        }
                        BasicText(
                            text = site.name,
                            style = tabTextStyle,
                            // 绘制期取色（ColorProducer 只在 draw 中调用）：
                            // 连续页位置离本 tab 越近越接近选中色，相邻页交叉淡化。
                            // 滑页/跨页跳转期间本组合不重组（读状态发生在 draw 阶段，
                            // 只失效绘制），因此不会像原来那样在 selectedIndex 变更瞬间跳变。
                            // 不用 material3 Text：其 1.3.2 版本无 color: ColorProducer 重载。
                            color = ColorProducer {
                                val fraction =
                                    (1f - abs(indicatorPosition() - index)).coerceIn(0f, 1f)
                                when {
                                    // 端点直接返回原色：ui-graphics 的 Color lerp 在 Oklab 空间插值
                                    // （`Color.kt:518`，无 t=0/1 快路径），往返转换可能让 8bit 通道
                                    // 差 ±1 —— 静止态（fraction 恰为 0/1）必须与改动前逐像素一致，
                                    // 故两个端点短路返回，只有过渡中才做插值。
                                    fraction >= 1f -> indicatorColor
                                    fraction <= 0f -> unselectedColor
                                    // 全限定调用：本文件已 import androidx.compose.ui.util.lerp
                                    // （Float 插值），颜色插值在同名的 androidx.compose.ui.graphics 下，
                                    // 避免导入冲突
                                    else -> androidx.compose.ui.graphics.lerp(
                                        unselectedColor,
                                        indicatorColor,
                                        fraction,
                                    )
                                }
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
 * 站点横向分页器 —— 标签行 + 分页器 + 「跳过中间页」两页过渡 + 底栏重按联动
 * （首页 / 分类页 / 搜索页共用）。
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
 * 2. 过渡期间 Pager 自身不动，位移发生在**每个页槽内部**（[pageJumpOffset]）：
 *    源页滑出、目标页从对侧滑入、其余页固定停在视口外 ±2W。
 * 3. 目标页依赖「槽位常驻」：`beyondViewportPageCount = sites.size - 1` 保证目标槽位
 *    在过渡开始前就已组合（内容由 `activeSiteIds` 门控，冷启动不会一次组合 4 个站点）。
 * 4. 两页位移互补：进度 p 时源页占 [-pW, W-pW]、目标页占 [W-pW, 2W-pW]，
 *    并集恒为视口 → 无空白帧。
 * 5. 落位顺序：`scrollToPage(target)` 后再 `jump = null`；`currentPage == target` 守卫让
 *    页内偏移当帧归零，因此不会残留错位帧（见下方 `LaunchedEffect(jump)` 注释）。
 *
 * @param sites 站点列表
 * @param gridStates 各站点网格滚动状态（键 = 站点 id，由壳层持有）
 * @param indicatorColor 指示条/选中色
 * @param onRefreshSite 底栏重按且当前站点列表已回顶时的刷新回调（页面侧传
 *   `controller.xxxListControllers[site.id]?.refreshData()`）
 * @param pageContent 单页内容（首页传 PagedRoomGrid / 分类页传分类网格 / 搜索页传结果网格）
 * @param bottomNavTabIndex 本页在底栏中的下标（底栏「重按当前 Tab」事件的载荷即此下标：
 *   首页 = 0 / 分类页 = 2）；传 `null` = 本页不属底栏（搜索是路由），**不注册**该监听
 * @param showLogo 站点 Tab 是否渲染平台 logo（默认 true = 首页/分类页既有观感；
 *   搜索页传 false = 保持纯文字 Tab），透传给 [SiteTabRow]
 * @param siteNameFontSize 站名字号（null = 16sp 现状；搜索页传 14.sp 保持原字号），
 *   透传给 [SiteTabRow]
 * @param onSiteChanged 逻辑选中站点变化时的回调（滑动落位回写 / 相邻点击 / 跨页点击 /
 *   首次组合同步四条路径都发射，见下）。搜索页据此回写
 *   `AppSearchController.setTabIndex` —— 沿用其「有关键词且该站点结果为空则自动补拉」语义；
 *   默认 null = 无回调（首页/分类页行为不变）
 * @param initialSiteIndex 无已保存状态时的初始选中站点（默认 0 = 首页/分类页既有行为）。
 *   搜索页传 `AppSearchController.index`：页面层不再自持站点下标（改由本组件持有），
 *   若不给初值，重新进入搜索路由时会把选中平台拉回第 0 个 —— 与 ui-compose.md 条目 32 第 2 点
 *   「站点 Tab 下标要以存活控制器为准」冲突。**仅在无 saveable 恢复值时生效**：
 *   自直播间返回（back stack 保留的 saveable state）仍按恢复值。
 * @param trailing 透传给 [SiteTabRow] 的行尾槽
 */
@Composable
fun SitePagerTabs(
    sites: List<Site>,
    gridStates: Map<String, LazyGridState>,
    indicatorColor: Color,
    onRefreshSite: (Site) -> Unit,
    pageContent: @Composable (site: Site, gridState: LazyGridState) -> Unit,
    modifier: Modifier = Modifier,
    bottomNavTabIndex: Int? = null,
    showLogo: Boolean = true,
    siteNameFontSize: TextUnit? = null,
    onSiteChanged: ((Int) -> Unit)? = null,
    initialSiteIndex: Int = 0,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val lastIndex = sites.lastIndex.coerceAtLeast(0)

    // 当前站点 Tab（rememberSaveable：进程重建/底栏 Tab 切换回来后恢复选中态，亦作 pager 的
    // initialPage 兜底）。点击（含两页过渡）与滑动两条路径分别即时置位 / 落位回写。
    // 初值取 initialSiteIndex（默认 0）—— 见该参数注释：搜索页必须传控制器下标。
    var selectedSiteIndex by rememberSaveable { mutableIntStateOf(initialSiteIndex) }
    // 越界保护：设置里站点排序变化导致索引失效时回落到第一个站点
    val siteIndex = selectedSiteIndex.coerceIn(0, lastIndex)

    // 选中站点变化通知（可选回调，搜索页据此回写 controller.setTabIndex）。
    // `in sites.indices` 兜底越界：回写路径的下标来自 pagerState，站点列表变化瞬间理论上可能越界。
    // 发射点共三处，覆盖四条路径：① onSelect 相邻站点分支、② onSelect 跨多页分支（点击路径）、
    // ③ LaunchedEffect(pagerState.currentPage) 的落位回写（滑动/相邻动画落位 + 首次组合初始同步）。
    val notifySite: (Int) -> Unit = { index ->
        if (index in sites.indices) onSiteChanged?.invoke(index)
    }

    // 横向翻页器（foundation 1.7 stable API）：站点页左右滑动切换；
    // 默认不预组合邻页（beyondViewportPageCount=0），滑动中邻页被组合时其内部
    // 「仅首次加载」守卫触发邻站首载，等价旧 View attach 即加载语义，可接受。
    // 内聚到本组件：底栏「重按」事件要按逻辑选中站点取列表状态，已选项与 Pager 页码可能失步。
    val pagerState = rememberPagerState(initialPage = siteIndex, pageCount = { sites.size })

    // 当前两页过渡（null = 空闲）。用 remember 而非 rememberSaveable：过渡是瞬时 UI 态，
    // 进程重建/旋转后不需要恢复（旋转会中断动画，落位由下方失步自愈守卫兜底语义承担）。
    var jump by remember { mutableStateOf<PagerJump?>(null) }
    // 切页过渡态（task 10-21-tablet-home-pager-fluency R3）：供页内网格做「首屏渲染错峰」。
    // 用 derivedStateOf 收敛到布尔：过渡期间每帧都会读 currentPageOffsetFraction，
    // 但状态只在开始/落位两帧翻转，页内网格因此最多重组两次（中途不随帧重组）。
    val isPagerTransitioning by remember(pagerState) {
        derivedStateOf {
            jump != null ||
                pagerState.isScrollInProgress ||
                abs(pagerState.currentPageOffsetFraction) > 0.001f
        }
    }
    // 相邻站点点击启动的 animateScrollToPage 协程：起跳/跨页点击前要先取消它（否则两次滚动
    // 互相抢 MutatorMutex，旧协程留下的半页偏移会让新动画从中间位置起步），回写也要避开它。
    var animateScrollJob by remember { mutableStateOf<Job?>(null) }
    val coroutineScope = rememberCoroutineScope()

    // ===== 站点页「按需保活」（task 10-21-tablet-home-pager-fluency R2）=====
    // `HorizontalPager` 的 4 个站点槽位常驻（切走不销毁），但**内容是否渲染**由本集合门控：
    // 冷启动/首次进入只组合当前站点，避免一上来就把 4 个站点的 80~120 张卡片全组合。
    // 用户真正落位过的站点被记住，切回时 0 重建、0 首屏测量（对齐原项目 KeepAlive 的收益）。
    //
    // 刻意不做「空闲后把 visited 全量渐进激活」：本仓库实测过「离屏页全部参与布局」会让
    // 滚动 janky 8.5%→17.7%、PSS 258MB→280MB（ui-compose.md 条目 27 C1），故只保活落位过的站点。
    val activeSiteIds = remember(sites) { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(pagerState, sites) {
        // 初始页先记一笔，之后每一次「成为当前页」的站点都标记为已访问（离开当前页后内容仍在）
        activeSiteIds[sites.getOrNull(pagerState.currentPage)?.id ?: return@LaunchedEffect] = true
        launch {
            snapshotFlow { pagerState.currentPage }.collect { index ->
                sites.getOrNull(index)?.let { activeSiteIds[it.id] = true }
            }
        }
        // 手势滑动的第一帧就要激活「正在进入」的那一页：只在 currentPage 越过中线时才激活的话，
        // 前半程会看到一张空白页。offsetFraction 为正 = 下一页正在进来（foundation PagerState
        // 语义），为负 = 上一页。
        snapshotFlow { pagerState.currentPageOffsetFraction }.collect { fraction ->
            if (abs(fraction) <= 0.001f) return@collect
            val neighbor = if (fraction > 0f) {
                pagerState.currentPage + 1
            } else {
                pagerState.currentPage - 1
            }
            sites.getOrNull(neighbor)?.let { activeSiteIds[it.id] = true }
        }
    }

    // ===== 底栏重复点击事件（对齐 Dart indexed_controller.setIndex 的发射语义）=====
    // Dart 行为（lib/modules/indexed/indexed_controller.dart setIndex + lib/modules/home/
    // home_controller.dart onInit/refreshOrScrollTop + lib/modules/category/category_controller.dart
    // onInit/refreshOrScrollTop + lib/app/controller/base_controller.dart scrollToTopOrRefresh）：
    // 仅当重复点击当前 Tab 且当前页是本页时，若列表有滚动偏移则动画回顶，否则触发刷新。
    // 滚动状态与选中站点都由本组件持有，故监听从两个页面收敛到此处（原 HomeScreen/CategoryScreen
    // 各有一份逐行同构的 DisposableEffect）。
    DisposableEffect(bottomNavTabIndex, sites) {
        // 非底栏页面（搜索页）传 null：本页不属底栏，没有「重按当前 Tab」语义，不注册监听
        val tabIndex = bottomNavTabIndex
        if (tabIndex == null) {
            onDispose { }
        } else {
            val job = EventBus.listen<Int>(EventBus.kBottomNavigationBarClicked) { index ->
                if (index == tabIndex) {
                    // 注意读**逻辑选中**站点（selectedSiteIndex）而不是 pagerState.currentPage：
                    // 后者是延迟落位的（280ms 跨页过渡期间仍停在源页），用它会在「点跨页 Tab 后立刻
                    // 双击底栏」时刷新到**源站点**。selectedSiteIndex 由 rememberSaveable 持有
                    // （remember 出来的稳定实例），闭包每次执行都读到最新值，不能用每次重组重算的局部变量。
                    val current = selectedSiteIndex.coerceIn(0, sites.lastIndex.coerceAtLeast(0))
                    val site = sites[current]
                    val gridState = gridStates[site.id]
                    if (gridState != null) {
                        val scrolled =
                            gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0
                        if (scrolled) {
                            // 对齐 Dart scrollController.animateTo(0, 200ms)
                            coroutineScope.launch { gridState.animateScrollToItem(0) }
                        } else {
                            onRefreshSite(site)
                        }
                    }
                }
            }
            onDispose { job.cancel() }
        }
    }

    // 过渡驱动：起跳对象即动画载体。
    LaunchedEffect(jump) {
        val active = jump ?: return@LaunchedEffect
        // 起跳前先把 Pager 定格在源页：上一次手势滑动或相邻站点 animateScrollToPage 可能仍在
        // 进行（沿途会组合中间页），甚至停在非整页位置 —— 直接叠加「整页位移」会画出错位画面。
        if (pagerState.isScrollInProgress || pagerState.currentPageOffsetFraction != 0f) {
            pagerState.scrollToPage(active.source)
        }
        active.progress.animateTo(1f, tween(PAGE_JUMP_DURATION_MS))
        // ---- 落位（task 10-21-tablet-home-pager-fluency R2：改为页内原位平移）----
        // 进度 1 时目标页的页内偏移已归零、与视口重合；先把 Pager 内部切到目标页，
        // 再清 jump。全程节点都在自己原来的槽位里（不再跨覆盖层/Pager 两棵树搬移），
        // 起跳与落位都不发生拆卸/重挂，也就没有「搬完再测一遍整页」的开销。
        pagerState.scrollToPage(active.target)
        jump = null
    }

    // 滑动/落位回写选中索引，驱动 Tab 切色与指示条跟随。
    // 根因说明（真机反馈：左右滑动时顶部 Tab 变化慢一拍）：settledPage 要等翻页动画完全 settle
    // 才更新；改用 currentPage——跨过页面中线立即切换，指示器实时跟手
    //（指示条自身逐帧插值跟随，不会生硬跳变）。
    // 两条守卫（缺一不可）：
    // ① jump == null —— 两页过渡期间 currentPage 仍是**源页**，无守卫会把刚点下的目标站点改回
    //    源页（选中色/指示条弹回）；过渡落位时 currentPage 已等于目标站点，此写回是幂等的；
    // ② animateScrollJob 不在进行中 —— 相邻站点点击已即时置位，动画中途回写只会造成指示条抖动。
    LaunchedEffect(pagerState.currentPage) {
        if (jump == null && animateScrollJob?.isActive != true) {
            selectedSiteIndex = pagerState.currentPage
        }
    }

    // 回写调用方只在**落位**时进行（不是跨过中线就回写）。
    // 搜索页的 onSiteChanged → setTabIndex 是「有关键词且该站点结果为空则补拉」的**唯一**入口
    // （该页 autoFirstLoad = false）：若跟着 currentPage 走，一次跨多站的快速滑动会把途经站点
    // 逐个触发搜索请求与 lastSearchSiteId 写入。settledPage 在手势/滚动动画进行中停在源页，
    // 落位才更新 ⇒ 途经站点不再被请求（与「点 Tab 才加载」的改动前语义一致）。
    // 点击路径（相邻 / 跨多页）已在 onSelect 内即时回写，这里是幂等重复（setTabIndex 同值早退）。
    // 首次组合也走这里：settledPage == 初始页 ⇒ 把调用方的选中态对齐到 Pager 实际页，
    // 解决「进程重建后 pager 恢复页 ≠ 调用方 index」的失配。
    LaunchedEffect(pagerState.settledPage) {
        if (jump == null && animateScrollJob?.isActive != true) {
            notifySite(pagerState.settledPage)
        }
    }

    // 失步自愈守卫：280ms 过渡中途离开组合（进搜索页 / 切底栏 —— selectedSiteIndex 是
    // rememberSaveable、pagerState 的 Saver 存的是**尚未落位**的源页）再返回，或过渡中途双击底栏，
    // 都会留下「逻辑选中站点 ≠ Pager 页码」。空闲态（无过渡、无进行中滚动）下以逻辑选中站点为准拉回。
    // 空闲判定除 isScrollInProgress 还要看 animateScrollJob：相邻站点点击刚 launch 时协程尚未真正
    // 开始滚动（isScrollInProgress 此刻仍为 false），只看后者会在点击当帧就 scrollToPage 而把整段
    // 滑动动画跳过（表现为「点 Tab 直接闪过去」）。
    LaunchedEffect(siteIndex, jump, pagerState.isScrollInProgress) {
        if (jump == null &&
            animateScrollJob?.isActive != true &&
            !pagerState.isScrollInProgress &&
            pagerState.currentPage != siteIndex
        ) {
            pagerState.scrollToPage(siteIndex)
        }
    }

    val onSelect: (Int) -> Unit = { index ->
        val current = pagerState.currentPage.coerceIn(0, lastIndex)
        // 目标站点一旦被点选就进入保活集合：跨页过渡需要在它的槽位里直接渲染内容
        // （R2：不再借覆盖层搬移）；相邻滑动同样受益（避免滑到一半才开始组合）。
        sites.getOrNull(index)?.let { activeSiteIds[it.id] = true }
        when {
            // 过渡进行中忽略重复请求（过渡仅 280ms，排队反而更差）
            jump != null -> Unit
            // 重复点击当前站点：无动作（对齐既有语义，Null 分支不触发滚动）
            index == current -> Unit
            // 相邻站点：维持整段滑动（此时视口内天然只有这两页，无中间页问题）
            abs(index - current) <= 1 -> {
                // 先取消上一次相邻点击的滚动，再置选中态并起新动画
                animateScrollJob?.cancel()
                selectedSiteIndex = index
                // 点击路径也要通知：滑动路径由落位回写发射，这里不能漏（否则点击切站不回写调用方）
                notifySite(index)
                animateScrollJob = coroutineScope.launch {
                    // 显式曲线：与跨页过渡（下方 PagerJump）及全局路由转场同为
                    // tween(280, FastOutSlowInEasing)（默认是 spring(StiffnessMediumLow)，
                    // 时长/曲线与指示条、站名插值的节奏不一致）
                    pagerState.animateScrollToPage(
                        page = index,
                        animationSpec = tween(
                            durationMillis = PAGE_JUMP_DURATION_MS,
                            easing = FastOutSlowInEasing,
                        ),
                    )
                }
            }
            // 跨多页：两页过渡（只组合当前页与目标页）
            else -> {
                // 同上：手势滑动或上一次相邻动画未停稳时直接起跳，会让 Pager 从非整数页位置
                // 叠加位移；先取消协程，起跳前再由 LaunchedEffect 把 Pager 定格到源页
                animateScrollJob?.cancel()
                jump = PagerJump(source = current, target = index)
                // 逻辑选中态即时置位（供底栏「重按」与失步自愈读取）。
                // 站名颜色与指示条不再「即切」：两者都由连续页位置插值跟随，
                // 过渡期 pos 从源页连续走到目标页，站名按离 pos 的距离交叉淡化。
                selectedSiteIndex = index
                // 跨页过渡期间 currentPage 仍停在源页（落位回写被 jump 守卫挡住），
                // 故目标站点必须在这里通知调用方
                notifySite(index)
            }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 过渡幅宽 = 分页区宽度（像素）：两页各占一屏，位移按像素计算避免 Dp 取整误差
        val viewportWidthPx = constraints.maxWidth
        // 视口宽度（dp）下发给页内网格：页内不再套第二层 BoxWithConstraints（SubcomposeLayout）
        val viewportWidthDp = maxWidth.value

        Column(modifier = Modifier.fillMaxSize()) {
            SiteTabRow(
                sites = sites,
                selectedIndex = siteIndex,
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
                showLogo = showLogo,
                siteNameFontSize = siteNameFontSize,
                trailing = trailing,
            )

            // 分页区**必须裁剪**：过渡期源页与目标页都用 Modifier.offset 平移出本区域，
            // 而宽屏壳层的内容 Box 是 Row 的最后一个子级（绘制在 NavigationRail 之上，
            // 见 AppNavHost.MainShell），不裁剪时平移出内容区的页面会浮在左侧导航栏上。
            // 网格自身（LazyVerticalGrid）本就裁剪边界，故卡片阴影观感不受影响。
            Box(modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                HorizontalPager(
                    state = pagerState,
                    // 4 个站点槽位常驻（内容由 activeSiteIds 门控）：切走不销毁，
                    // 跨页跳转可直接在各自槽位内平移，不再需要外层覆盖层。
                    beyondViewportPageCount = (sites.size - 1).coerceAtLeast(0),
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    // 越界保护：sites 尺寸变化瞬间 pageCount 已更新但页面索引可能短暂越界
                    val pageIdx = page.coerceIn(0, lastIndex)
                    val site = sites[pageIdx]
                    val gridState = gridStates[site.id]
                    val active = jump
                    val showContent = activeSiteIds[site.id] == true ||
                        pageIdx == siteIndex ||
                        pageIdx == active?.target
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            // 原位过渡（R2）：源页向 -direction 滑出、目标页从对侧滑入，
                            // 无关页保持在视口外 ±2W。placement 阶段读进度 → 每帧只重排不重组。
                            .offset {
                                IntOffset(
                                    x = pageJumpOffset(
                                        active = active,
                                        pageIdx = pageIdx,
                                        currentPage = pagerState.currentPage,
                                        viewportWidthPx = viewportWidthPx,
                                    ),
                                    y = 0,
                                )
                            },
                    ) {
                        if (showContent && gridState != null) {
                            CompositionLocalProvider(
                                LocalPagerViewportWidthDp provides viewportWidthDp,
                                LocalPagerTransitionActive provides isPagerTransitioning,
                            ) { pageContent(site, gridState) }
                        } else {
                            Box(modifier = Modifier.fillMaxSize())
                        }
                    }
                }

                if (jump != null) {
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
 * 总宽 = Σ max(TabMinWidth, logo + 间距 + 文字宽 + 左右内边距)；
 * 纯文字 Tab（`showLogo = false`，搜索页）不计 logo 与间距那一项。
 * 文字度量必须与真实站名同款 style：站名 `BasicText` 只覆盖了 `fontSize`，其余属性
 * （含 `letterSpacing`）来自 MaterialTheme 下发的 `LocalTextStyle`（= bodyLarge），
 * 直接量默认 style 会偏窄 → 居中偏移。故直接由调用方传入**渲染时用的同一个**
 * `tabTextStyle`（单一事实源，避免两处各写一份样式后漂移）。
 *
 * 全程按**整数像素**求和与求商（而不是 Dp 小数）：Dp 的小数部分在下游会被各自取整，
 * 临界宽度下「Σ 各 tab 取整后宽度」会略大于容器可用宽 —— 表现为标签组明明放得下却多出
 * 1~4px 的横向滚动。整数像素下总宽与可用宽同一量纲，判据与实现一致。
 *
 * 装不下时回落 [TabEdgePadding]（由 `maxOf` 保证下限）：维持改动前的「左对齐 + 可横向滚动」。
 */
@Composable
private fun rememberCenteredEdgePadding(
    sites: List<Site>,
    available: Dp,
    textStyle: TextStyle,
    showLogo: Boolean,
): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // remember 的 keys 必须含 showLogo：logo 宽度计入与否会改变总宽（漏传 ⇒ 纯文字 Tab 居中偏移）
    val totalWidthPx = remember(sites, textStyle, showLogo, density, measurer) {
        with(density) {
            val minTabWidthPx = TabMinWidth.roundToPx()
            val fixedChromePx =
                (if (showLogo) TabLogoSize.roundToPx() + TabLogoGap.roundToPx() else 0) +
                    TabContentPadding.roundToPx() * 2
            sites.sumOf { site ->
                val textWidthPx = measurer.measure(site.name, style = textStyle).size.width
                maxOf(minTabWidthPx, fixedChromePx + textWidthPx)
            }
        }
    }
    return with(density) {
        val availablePx = available.roundToPx()
        val minEdgePaddingPx = TabEdgePadding.roundToPx()
        val centeredEdgePaddingPx = (availablePx - totalWidthPx) / 2
        maxOf(minEdgePaddingPx, centeredEdgePaddingPx).toDp()
    }
}
