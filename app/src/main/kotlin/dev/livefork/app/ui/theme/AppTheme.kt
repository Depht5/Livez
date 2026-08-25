package dev.livefork.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Compose 主题与全局色值 —— 对应 Dart `AppStyle`/各页面取色。
 * App 不做暗色模式（浅色-only，见 index.md「显示全部 & 窗口装饰」批次的既有决策），
 * 故只提供 lightColorScheme，不区分暗色分支。
 */

// ===== 常用色 top-level 常量（以现版 View 代码实际取值为准）=====

/** 直播红系主色：首页/直播 Tab 选中、按钮、直播状态徽标等（对应现版 #FF3F4B）。 */
val LiveRed = Color(0xFFFF3F4B)

/** 分类蓝：分类页站点 Tab 选中色、刷新指示器等（对应现版 #3498DB / Dart seedColor）。 */
val CategoryBlue = Color(0xFF3498DB)

/** 主文字色（正文、直播间信息区等，对应现版 #333333）。 */
val TextPrimary = Color(0xFF333333)

/** 卡片标题色（对应 LiveRoomCard 的 #222222）。 */
val CardTitle = Color(0xFF222222)

/** 次级文字色（Tab 未选中态等，对应现版 #666666）。 */
val TextSecondary = Color(0xFF666666)

/** 三级文字色（卡片主播名/人气等，对应现版 #888888）。 */
val TextTertiary = Color(0xFF888888)

/** 弱提示色（关注页未开播状态等，对应现版 #999999）。 */
val TextDisabled = Color(0xFF999999)

/** 图片占位灰块底色（对应 NetImage 占位 #F2F2F2）。 */
val ImagePlaceholder = Color(0xFFF2F2F2)

/** 图片加载失败灰块底色（对应 NetImage 失败占位 #E0E0E0）。 */
val ImageError = Color(0xFFE0E0E0)

/**
 * 应用主题：MaterialTheme 浅色方案，主色映射为直播红系（#FF3F4B），
 * 次色保留分类蓝（#3498DB），供各页面按需直接引用 top-level 色常量。
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = LiveRed,
            onPrimary = Color.White,
            secondary = CategoryBlue,
            onSecondary = Color.White,
            background = Color.White,
            onBackground = TextPrimary,
            surface = Color.White,
            onSurface = TextPrimary,
        ),
        content = content,
    )
}
