package dev.livefork.app.ui

import kotlin.math.floor
import kotlin.math.max

/**
 * 直播间卡片网格布局计算 —— 对应 Dart `LiveRoomGridLayout`
 * （lib/widgets/live_room_grid_layout.dart）。参数镜像 Dart：
 * 封面 16:9、间距 12dp、水平内边距 12dp、最小卡宽 176dp、列数 2–8。
 */
class LiveRoomGridLayout private constructor(
    /** 网格列数 */
    val crossAxisCount: Int,
    /** 单卡片像素宽 */
    val itemWidth: Int,
    /** 单卡片像素高 = 封面高度 + 文字区高度 */
    val mainAxisExtent: Int,
) {

    companion object {
        /** 封面宽高比（对应 Dart coverAspectRatio = 16 / 9） */
        const val coverAspectRatio = 16f / 9f

        private val density: Float
            get() = android.content.res.Resources.getSystem().displayMetrics.density

        /** 水平内边距（Dart defaultHorizontalPadding = 12dp） */
        val defaultHorizontalPadding: Int get() = (12 * density).toInt()

        /** 卡片间距（Dart defaultSpacing = 12dp） */
        val defaultSpacing: Int get() = (12 * density).toInt()

        /** 最小卡片宽度（Dart defaultMinCardWidth = 176dp） */
        val defaultMinCardWidth: Int get() = (176 * density).toInt()

        const val defaultMinColumns = 2
        const val defaultMaxColumns = 8

        /** 镜像 Dart resolve：根据可用宽度计算列数与卡片尺寸。 */
        fun resolve(viewportWidth: Int, detailsExtent: Int): LiveRoomGridLayout {
            val horizontalPadding = defaultHorizontalPadding
            val spacing = defaultSpacing
            val minCardWidth = defaultMinCardWidth
            val contentWidth = max(0, viewportWidth - horizontalPadding * 2)
            val desiredColumns =
                floor((contentWidth + spacing).toDouble() / (minCardWidth + spacing).toDouble()).toInt()
            val columns = desiredColumns.coerceIn(defaultMinColumns, defaultMaxColumns)
            val cardsWidth = max(0, contentWidth - spacing * (columns - 1))
            val itemWidth = cardsWidth / columns
            val mainAxisExtent = (itemWidth / coverAspectRatio).toInt() + detailsExtent
            return LiveRoomGridLayout(columns, itemWidth, mainAxisExtent)
        }
    }
}