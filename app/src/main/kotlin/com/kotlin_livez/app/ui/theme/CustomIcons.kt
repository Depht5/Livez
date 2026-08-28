package com.kotlin_livez.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 自定义图标集（手写 SVG path，转换自项目根目录同名按钮 SVG）。
 *
 * 覆盖：直播间关注按钮 / 播放器全屏切换 / 直播间标题栏与播放器控制层按钮
 * （返回 Back / 菜单 Menu / 刷新 Refresh / 播放 Play / 暂停 Pause /
 * 弹幕开关 DanmakuOn/DanmakuOff / 弹幕设置 DanmuSetting）/
 * 底栏 Tab（首页 Home / 关注复用 HeartOutline / 分类 Category / 我的 Mine）。
 *
 * 实际显示色由调用处 `Icon(tint = ...)` 的 ColorFilter 决定（material3 Icon 的
 * `tint = Color.Unspecified` 会回退为 LocalContentColor.current 染色，故调用处必须显式上色）：
 * - 关注按钮：已关注 `Color(0xFFFF0000)`（红心）/ 未关注 `Color(0xFF808080)`（灰心）
 * - 播放器控件：`Color.White`（深色控制栏上的白色图标）
 * - 标题栏：`MaterialTheme.colorScheme.onSurface`
 * 图标内建 fill/stroke 色仅作兜底，实际显示以 tint 为准。
 *
 * 转换规则：SVG `<circle>`（描边圆）展开为两段 A 弧；`<rect rx>` 展开为 a 弧闭合路径；
 * `弹幕设置.svg` 的 mask 已由用户烘焙为中断开放路径（该 SVG 为转换唯一事实源）。
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

    /** 返回（返回.svg：左向长箭头，白色描边）。直播间标题栏 / 播放器顶栏共用。 */
    val Back: ImageVector by lazy {
        ImageVector.Builder(
            name = "Back",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = PathParser().parsePathString(
                    """
                    M20 12H5
                    M11 6L5 12 L11 18
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

    /** 菜单（菜单.svg：竖排三圆角方点，白色填充）。直播间标题栏 / 播放器顶栏共用。 */
    val Menu: ImageVector by lazy {
        ImageVector.Builder(
            name = "Menu",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                // rect(10.5, y, 3, 3, rx=0.5) × 3（y=3.5/10.5/17.5）展开为 a 弧闭合路径
                pathData = PathParser().parsePathString(
                    """
                    M11 3.5 H13 A0.5 0.5 0 0 1 13.5 4 V6 A0.5 0.5 0 0 1 13 6.5 H11
                    A0.5 0.5 0 0 1 10.5 6 V4 A0.5 0.5 0 0 1 11 3.5 Z
                    M11 10.5 H13 A0.5 0.5 0 0 1 13.5 11 V13 A0.5 0.5 0 0 1 13 13.5 H11
                    A0.5 0.5 0 0 1 10.5 13 V11 A0.5 0.5 0 0 1 11 10.5 Z
                    M11 17.5 H13 A0.5 0.5 0 0 1 13.5 18 V20 A0.5 0.5 0 0 1 13 20.5 H11
                    A0.5 0.5 0 0 1 10.5 20 V18 A0.5 0.5 0 0 1 11 17.5 Z
                    """.trimIndent()
                ).toNodes(),
                fill = SolidColor(Color.White),
            )
        }.build()
    }

    /** 刷新（刷新.svg：双弧循环箭头，白色描边）。播放器底栏刷新按钮。 */
    val Refresh: ImageVector by lazy {
        ImageVector.Builder(
            name = "Refresh",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                // polyline(3 21 3 16 8 16) / (21 3 21 8 16 8) 展开为 V/H 折线
                pathData = PathParser().parsePathString(
                    """
                    M21 12a9 9 0 0 1-15.29 6.44L3 16
                    M3 21V16H8
                    M3 12a9 9 0 0 1 15.29-6.44L21 8
                    M21 3V8H16
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

    /** 播放（播放.svg：右向三角，白色描边）。播放器底栏播放键。 */
    val Play: ImageVector by lazy {
        ImageVector.Builder(
            name = "Play",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = PathParser().parsePathString("M8 5 L19 12 L8 19 Z").toNodes(),
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** 暂停（暂停.svg：双竖线，白色描边）。播放器底栏暂停键。 */
    val Pause: ImageVector by lazy {
        ImageVector.Builder(
            name = "Pause",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = PathParser().parsePathString("M8 5V19 M16 5V19").toNodes(),
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** 弹幕气泡主体（弹幕开启/关闭共用：圆角气泡 + 尾巴，白色描边；三个圆点为白色填充）。 */
    private fun ImageVector.Builder.addDanmakuBubble() {
        addPath(
            pathData = PathParser().parsePathString(
                """
                M4 5.5 C4 4.67 4.67 4 5.5 4 H18.5 C19.33 4 20 4.67 20 5.5 V14.5
                C20 15.33 19.33 16 18.5 16 H10 L6 20 V16 H5.5 C4.67 16 4 15.33 4 14.5 Z
                """.trimIndent()
            ).toNodes(),
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            // circle(8/12/16, 10, r=1) × 3 展开为 A 弧闭合（fill 白）
            pathData = PathParser().parsePathString(
                """
                M7 10 A1 1 0 1 1 9 10 A1 1 0 1 1 7 10 Z
                M11 10 A1 1 0 1 1 13 10 A1 1 0 1 1 11 10 Z
                M15 10 A1 1 0 1 1 17 10 A1 1 0 1 1 15 10 Z
                """.trimIndent()
            ).toNodes(),
            fill = SolidColor(Color.White),
        )
    }

    /** 弹幕开启（弹幕开启.svg：气泡 + 三圆点，无斜线）。弹幕开关开态。 */
    val DanmakuOn: ImageVector by lazy {
        ImageVector.Builder(
            name = "DanmakuOn",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply { addDanmakuBubble() }.build()
    }

    /** 弹幕关闭（弹幕关闭.svg：气泡 + 三圆点 + 右上-左下斜线）。弹幕开关关态。 */
    val DanmakuOff: ImageVector by lazy {
        ImageVector.Builder(
            name = "DanmakuOff",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addDanmakuBubble()
            addPath(
                pathData = PathParser().parsePathString("M3 3L19 19").toNodes(),
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** 弹幕设置（弹幕设置.svg 已烘焙版：六边形中断路径 + 内圆 + 右侧三条设置线）。 */
    val DanmuSetting: ImageVector by lazy {
        ImageVector.Builder(
            name = "DanmuSetting",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // mask 已烘焙：六边形被矩形 [13.2,22.2]×[12.8,21.8] 擦除区段拆出，
            // 开放路径两端圆头（切口 (19.5,12.8) / (13.2,18.82)）
            addPath(
                pathData = PathParser().parsePathString(
                    "M13.2 18.82 L12 19.5 L4.5 15.25 L4.5 6.75 L12 2.5 L19.5 6.75 L19.5 12.8"
                ).toNodes(),
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                // circle(12, 11, r=3.2) 展开为 A 弧
                pathData = PathParser().parsePathString(
                    "M8.8 11 A3.2 3.2 0 1 1 15.2 11 A3.2 3.2 0 1 1 8.8 11"
                ).toNodes(),
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                pathData = PathParser().parsePathString(
                    "M15.5 15H22 M15.5 18H22 M15.5 21H22"
                ).toNodes(),
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** 首页（首页.svg：实心房子，fill + stroke 同色）。底栏 Tab 图标。 */
    val Home: ImageVector by lazy {
        ImageVector.Builder(
            name = "Home",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = PathParser().parsePathString(
                    """
                    M3.5 10.5 L12 3.5 L20.5 10.5 V19
                    C20.5 19.8 19.8 20.5 19 20.5 H15 V14 H9 V20.5 H5
                    C4.2 20.5 3.5 19.8 3.5 19 Z
                    """.trimIndent()
                ).toNodes(),
                fill = SolidColor(Color.White),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** 分类（分类.svg：2×2 圆角方块网格，白色描边）。底栏 Tab 图标。 */
    val Category: ImageVector by lazy {
        ImageVector.Builder(
            name = "Category",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                // rect(4|13.5, 4|13.5, 6.5, 6.5, rx=1.5) × 4 展开为 a 弧闭合路径
                pathData = PathParser().parsePathString(
                    """
                    M5.5 4 H9 A1.5 1.5 0 0 1 10.5 5.5 V9 A1.5 1.5 0 0 1 9 10.5 H5.5
                    A1.5 1.5 0 0 1 4 9 V5.5 A1.5 1.5 0 0 1 5.5 4 Z
                    M15 4 H18.5 A1.5 1.5 0 0 1 20 5.5 V9 A1.5 1.5 0 0 1 18.5 10.5 H15
                    A1.5 1.5 0 0 1 13.5 9 V5.5 A1.5 1.5 0 0 1 15 4 Z
                    M5.5 13.5 H9 A1.5 1.5 0 0 1 10.5 15 V18.5 A1.5 1.5 0 0 1 9 20 H5.5
                    A1.5 1.5 0 0 1 4 18.5 V15 A1.5 1.5 0 0 1 5.5 13.5 Z
                    M15 13.5 H18.5 A1.5 1.5 0 0 1 20 15 V18.5 A1.5 1.5 0 0 1 18.5 20 H15
                    A1.5 1.5 0 0 1 13.5 18.5 V15 A1.5 1.5 0 0 1 15 13.5 Z
                    """.trimIndent()
                ).toNodes(),
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }

    /** 我的（我的.svg：圆头 + 肩弧人形，白色描边）。底栏 Tab 图标。 */
    val Mine: ImageVector by lazy {
        ImageVector.Builder(
            name = "Mine",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                // circle(12, 8, r=3.5) 展开为 A 弧
                pathData = PathParser().parsePathString(
                    """
                    M8.5 8 A3.5 3.5 0 1 1 15.5 8 A3.5 3.5 0 1 1 8.5 8
                    M5 20 C5.5 16.5 8.2 14.5 12 14.5 C15.8 14.5 18.5 16.5 19 20
                    """.trimIndent()
                ).toNodes(),
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
    }
}