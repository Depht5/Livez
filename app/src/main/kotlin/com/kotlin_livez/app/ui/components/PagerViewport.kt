package com.kotlin_livez.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/**
 * 分页器视口宽度（dp）—— 由 [SitePagerTabs] 下发，供页内网格直接计算列数。
 *
 * 背景（task 10-21-tablet-home-pager-fluency R5）：`SitePagerTabs` 外层已经用
 * `BoxWithConstraints` 量过宽度，页内 `PagedRoomGridCore` / `CategorySiteGrid` 再套一层
 * `BoxWithConstraints` 就是在分页器页内又开一个 `SubcomposeLayout`（测量期子组合）。
 * 下发后页内可零 `SubcomposeLayout` 直接取宽度。
 *
 * 默认 `Float.NaN` = 不在分页器内（如 `CategoryDetailScreen`），此时 [ViewportWidthBox]
 * 自动回退到 `BoxWithConstraints`，行为与改造前一致。
 */
val LocalPagerViewportWidthDp = compositionLocalOf { Float.NaN }

/**
 * 分页器是否正处于「切页过渡」中（手势滑动 / 相邻动画 / 跨页 PagerJump）。
 *
 * 用 `derivedStateOf` 收敛：过渡期间每帧都要读 `currentPageOffsetFraction`，但状态只在
 * 「开始」与「落位」两帧翻转，读数方（页内网格）因此最多重组两次，不会随每帧重组。
 * 页外使用（无分页器）恒为 false。
 */
val LocalPagerTransitionActive = compositionLocalOf { false }

/**
 * 视口宽度容器：能拿到 [LocalPagerViewportWidthDp] 时用普通 `Box`（0 层子组合），
 * 否则回退 `BoxWithConstraints`。调用方拿到的始终是「本区域可用的宽度 dp」。
 */
@Composable
fun ViewportWidthBox(
    modifier: Modifier = Modifier,
    content: @Composable (maxWidthDp: Float) -> Unit,
) {
    val provided = LocalPagerViewportWidthDp.current
    if (!provided.isNaN()) {
        Box(modifier = modifier) { content(provided) }
    } else {
        BoxWithConstraints(modifier = modifier) { content(maxWidth.value) }
    }
}
