package com.kotlin_livez.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 自定义图标集（手写 SVG path，对应直播间关注按钮 / 播放器全屏按钮）。
 *
 * 颜色按调用场景内建（不随 Icon tint 染色，调用处以 `tint = Color.Unspecified` 保留原色）：
 * - [HeartOutline]：灰色空心爱心（未关注，描边 #808080）
 * - [HeartFilled]：红色实心爱心（已关注，填充 #FF0000）
 * - [Fullscreen] / [ExitFullscreen]：白色描边（匹配播放器控件白色样式），按全屏状态切换
 */
object CustomIcons {

    /** 灰色空心爱心（未关注）。 */
    val HeartOutline: ImageVector by lazy {
        ImageVector.Builder(
            name = "HeartOutline",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = PathParser().parsePathString(
                    """
                    M20.84 4.61
                    C19.75 3.52 18.26 3 16.77 3
                    C15.28 3 13.79 3.52 12.7 4.61
                    L12 5.31
                    L11.3 4.61
                    C9.11 2.42 5.56 2.42 3.37 4.61
                    C1.18 6.8 1.18 10.35 3.37 12.54
                    L12 21.17
                    L20.63 12.54
                    C22.82 10.35 22.82 6.8 20.84 4.61 Z
                    """.trimIndent()
                ).toNodes(),
                fill = null,
                stroke = SolidColor(Color(0xFF808080)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** 红色实心爱心（已关注）。 */
    val HeartFilled: ImageVector by lazy {
        ImageVector.Builder(
            name = "HeartFilled",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = PathParser().parsePathString(
                    """
                    M20.84 4.61
                    C19.75 3.52 18.26 3 16.77 3
                    C15.28 3 13.79 3.52 12.7 4.61
                    L12 5.31
                    L11.3 4.61
                    C9.11 2.42 5.56 2.42 3.37 4.61
                    C1.18 6.8 1.18 10.35 3.37 12.54
                    L12 21.17
                    L20.63 12.54
                    C22.82 10.35 22.82 6.8 20.84 4.61 Z
                    """.trimIndent()
                ).toNodes(),
                fill = SolidColor(Color(0xFFFF0000)),
            )
        }.build()
    }

    /** 全屏（窗口态显示，白色描边）。 */
    val Fullscreen: ImageVector by lazy {
        ImageVector.Builder(
            name = "Fullscreen",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = PathParser().parsePathString(
                    """
                    M4 9V4h5
                    M15 4h5v5
                    M20 15v5h-5
                    M9 20H4v-5
                    """.trimIndent()
                ).toNodes(),
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** 退出全屏（全屏态显示，白色描边）。 */
    val ExitFullscreen: ImageVector by lazy {
        ImageVector.Builder(
            name = "ExitFullscreen",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = PathParser().parsePathString(
                    """
                    M9 4v5H4
                    M15 4v5h5
                    M9 20v-5H4
                    M15 20v-5h5
                    """.trimIndent()
                ).toNodes(),
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }
}