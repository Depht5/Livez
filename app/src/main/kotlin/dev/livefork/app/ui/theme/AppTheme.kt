package dev.livefork.app.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Compose 主题与全局色值 —— 对应 Dart `AppStyle`/各页面取色。
 * App 不做暗色模式（浅色-only，见 index.md「显示全部 & 窗口装饰」批次的既有决策），
 * 故只提供 lightColorScheme，不区分暗色分支。
 *
 * Material 3 风格化（task 08-26-material3-ui-restyle）：
 * - [AppTheme] 新增 `dynamicColor` 参数：开关开启且 Android 12+（API 31+）时使用
 *   `dynamicLightColorScheme`（跟随系统壁纸 Monet 取色）；否则使用 `lightColorScheme()`
 *   （M3 默认色板）。开关默认关（AppSettingsController.isDynamic 默认 false）。
 * - 直播语义强调色固定保留为常量（动态色/默认色不影响）：直播中状态/SC 用户名红
 *   用 [LiveIndicatorRed]，人气/SC 价格橙用 [LiveOrange]；「取消关注」等删除语义改走
 *   `colorScheme.error`。其余布局/文本/交互色一律从 `MaterialTheme.colorScheme` 读取
 *   （页面/组件无局部硬编码色）。
 */

// ===== 直播语义强调色（固定保留，不随主题/壁纸变化）=====

/** 直播强调红：直播中状态徽标、SC 用户名等（原 LiveRed #FF3F4B 的语义保留）。 */
val LiveIndicatorRed = Color(0xFFFF3F4B)

/** 人气/价格橙（对应现版 #FF9800）：人气值、SC 价格、贡献榜名次等。 */
val LiveOrange = Color(0xFFFF9800)

// ===== 图片占位/失败底（中性，桌面/移动通用）=====

/** 图片占位灰块底色（对应 NetImage 占位 #F2F2F2）。 */
val ImagePlaceholder = Color(0xFFF2F2F2)

/** 图片加载失败灰块底色（对应 NetImage 失败占位 #E0E0E0）。 */
val ImageError = Color(0xFFE0E0E0)

/**
 * 应用主题 —— Material 3 风格化。
 *
 * @param dynamicColor 动态取色开关（设置 → 外观 → 动态取色）：
 *   开启且 Android 12+ → `dynamicLightColorScheme`（壁纸 Monet）；
 *   关闭（默认）或 API < 31 → `lightColorScheme()`（M3 默认色板）。
 */
@Composable
fun AppTheme(
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicLightColorScheme(LocalContext.current)
    } else {
        lightColorScheme()
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}