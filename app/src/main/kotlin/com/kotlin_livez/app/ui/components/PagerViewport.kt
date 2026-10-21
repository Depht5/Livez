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
