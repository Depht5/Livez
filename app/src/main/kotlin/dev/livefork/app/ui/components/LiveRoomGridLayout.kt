package dev.livefork.app.ui.components

import kotlin.math.floor

/**
 * 直播间卡片网格布局参数（纯 Kotlin 计算函数，Compose 版）——
 * 镜像 Dart `LiveRoomGridLayout` 与现版 View 体系 `ui/LiveRoomGridLayout.kt`：
 * 封面 16:9、间距 12dp、水平内边距 12dp、最小卡宽 176dp、列数 2–8。
 *
 * 与 View 版差异：View 版以像素为单位供 RecyclerView 用；
 * Compose 版（LazyVerticalGrid）全部使用 dp，由调用方传入视口宽度
 * （如 BoxWithConstraints 的 maxWidth.value），返回列数与卡片总高（mainAxisExtent），
 * 配合 `LazyVerticalGrid(columns = GridCells.Fixed(spec.columns))` +
 * `item(mainAxisExtent = ...)` 使用。
 */
data class LiveRoomGridSpec(
    /** 网格列数 */
    val columns: Int,
    /** 单卡片宽度（dp） */
    val itemWidthDp: Float,
    /**
     * 单卡片高度（dp）= 封面高（itemWidth / 16:9）+ 文字区 64dp，
     * 对应 Dart 的 mainAxisExtent。
     */
    val mainAxisExtentDp: Float,
) {
    companion object {
        /** 封面宽高比（对应 Dart coverAspectRatio = 16 / 9） */
        const val COVER_ASPECT_RATIO = 16f / 9f

        /** 文字区高度（对应 Dart detailsExtent = 64dp） */
        const val DETAILS_EXTENT_DP = 64f

        /** 水平内边距（Dart defaultHorizontalPadding = 12dp） */
        const val DEFAULT_HORIZONTAL_PADDING_DP = 12f

        /** 卡片间距（Dart defaultSpacing = 12dp） */
        const val DEFAULT_SPACING_DP = 12f

        /** 最小卡片宽度（Dart defaultMinCardWidth = 176dp） */
        const val DEFAULT_MIN_CARD_WIDTH_DP = 176f

        const val DEFAULT_MIN_COLUMNS = 2
        const val DEFAULT_MAX_COLUMNS = 8
    }
}

/**
 * 镜像 Dart resolve：根据可用视口宽度（dp）计算列数与卡片尺寸。
 * 计算公式与 View 版逐行对齐，仅单位从 px 换为 dp。
 */
fun resolveLiveRoomGrid(viewportWidthDp: Float): LiveRoomGridSpec {
    with(LiveRoomGridSpec) {
        val contentWidth = (viewportWidthDp - DEFAULT_HORIZONTAL_PADDING_DP * 2).coerceAtLeast(0f)
        val desiredColumns =
            floor(
                ((contentWidth + DEFAULT_SPACING_DP) / (DEFAULT_MIN_CARD_WIDTH_DP + DEFAULT_SPACING_DP)).toDouble(),
            ).toInt()
        val columns = desiredColumns.coerceIn(DEFAULT_MIN_COLUMNS, DEFAULT_MAX_COLUMNS)
        val cardsWidth =
            (contentWidth - DEFAULT_SPACING_DP * (columns - 1)).coerceAtLeast(0f)
        val itemWidth = cardsWidth / columns
        val mainAxisExtent = itemWidth / COVER_ASPECT_RATIO + DETAILS_EXTENT_DP
        return LiveRoomGridSpec(columns, itemWidth, mainAxisExtent)
    }
}
