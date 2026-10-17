package com.kotlin_livez.app.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Compose 主题与全局色值 —— 对应 Dart `AppStyle`/各页面取色。
 *
 * 深色模式（task 08-29-dark-mode，逆转"浅色-only"既有决策）：
 * [AppTheme] 接收**已解析**的 `dark` 态（跟随系统/手动三态由 MainActivity 统一解析，
 * 系统栏适配共用同一出口），内部选择 light/dark 色板：
 * - 动态取色开启且 Android 12+（API 31+）→ `dynamicLight/DarkColorScheme`（壁纸 Monet）；
 * - 否则 `lightColorScheme()` / `darkColorScheme()`（M3 基线色板，深浅对称）。
 * - `amoledBlack` 仅在深色生效时覆盖页面级背景角色为纯黑
 *   （background/surface/surfaceContainerLowest/Low，含 ModalBottomSheet 弹层底——
 *   其容器为 SurfaceContainerLow）；**surfaceContainer 不覆盖**（底栏 NavigationBar
 *   容器即该色槽，用户决策底栏保持基线深灰）；高档容器（分组卡片等）同样保持暗灰。
 * - 解析结果经 [LocalAppDarkTheme] 下发，供页面级局部色判断（如关注按钮未关注态）。
 * - 直播语义强调色固定保留为常量（动态色/深浅色不影响）：直播中状态/SC 用户名红
 *   用 [LiveIndicatorRed]，人气/SC 价格橙用 [LiveOrange]；「取消关注」等删除语义改走
 *   `colorScheme.error`。其余布局/文本/交互色一律从 `MaterialTheme.colorScheme` 读取。
 */

// ===== 直播语义强调色（固定保留，不随主题/壁纸变化）=====

/** 直播强调红：直播中状态徽标、SC 用户名等（原 LiveRed #FF3F4B 的语义保留）。 */
val LiveIndicatorRed = Color(0xFFFF3F4B)

/** 人气/价格橙（对应现版 #FF9800）：人气值、SC 价格、贡献榜名次等。 */
val LiveOrange = Color(0xFFFF9800)

/**
 * 「未开播」等弱化状态点：固定中性灰（对应现版 #BDBDBD）。
 *
 * 与 [LiveIndicatorRed] 配对使用，画在封面上的半透明深色胶囊（`0x99000000`）内，
 * 须与胶囊底色保持稳定对比，故**固定色**而非取随主题明暗变化的 `outline`/`onSurfaceVariant`；
 * 落在纯色容器上的同类灰点（如直播间内关注 Tab）仍用 `colorScheme.outline`。
 */
val LiveIndicatorIdleGray = Color(0xFFBDBDBD)

// ===== 图片占位/失败底（中性，桌面/移动通用）=====

/** 图片占位灰块底色（对应 NetImage 占位 #F2F2F2）。 */
val ImagePlaceholder = Color(0xFFF2F2F2)

/** 图片加载失败灰块底色（对应 NetImage 失败占位 #E0E0E0）。 */
val ImageError = Color(0xFFE0E0E0)

/**
 * 主题感知的图片占位色 —— 浅色沿用 [ImagePlaceholder]（#F2F2F2）；深色（含纯黑 amoled）
 * 取 `surfaceContainerHighest`（深色色板里的高档容器灰，跟随主题/动态取色）。
 *
 * 修复点：深色底上固定浅灰占位会亮成白块。色槽而非硬编码——amoled 只覆盖
 * background/surface/surfaceContainerLowest/Low，`surfaceContainerHighest` 不受影响。
 * 用于 [com.kotlin_livez.app.ui.components.NetImage] 的默认参数与各调用方的封面底色。
 */
@Composable
fun imagePlaceholderColor(): Color =
    if (LocalAppDarkTheme.current) MaterialTheme.colorScheme.surfaceContainerHighest else ImagePlaceholder

/** 主题感知的图片加载失败色 —— 浅色 [ImageError]（#E0E0E0）；深色取 `surfaceContainerHigh`。 */
@Composable
fun imageErrorColor(): Color =
    if (LocalAppDarkTheme.current) MaterialTheme.colorScheme.surfaceContainerHigh else ImageError

/**
 * 当前生效的深色态 —— 跟随系统/手动三态解析后的最终结果。
 * 页面内局部色需要按深浅分支时读这里（勿用 isSystemInDarkTheme()，其不感知应用内手动切换）。
 */
val LocalAppDarkTheme = staticCompositionLocalOf { false }

/**
 * 生效深色态的组合外镜像 —— MainActivity 每次解析后写入（SideEffect），与
 * [LocalAppDarkTheme] 同源同值；供 effect/回调等**非 Composable 上下文**读取
 * （CompositionLocal.current 不可在 effect 协程内读，如直播间系统栏图标恢复）。
 */
var appThemeResolvedDark: Boolean = false
    internal set

/**
 * 卡片容器色（首页/分类/关注页的直播间与子分类卡片，task 08-29-chat-pin-bubble-follow-grid
 * 用户定标）：深色下取 **#1D1B20**（M3 暗色基线 Neutral10 = surfaceContainerLow 档位）——
 * 精确介于页面背景（surface #141218）与底栏（surfaceContainer #211F26）之间，纯黑主题下
 * 同样适用（在纯黑背景上更清晰，且不受 amoled 覆盖列表影响）；浅色维持 surface（现状不变）。
 */
@Composable
fun cardContainerColor(): Color =
    if (LocalAppDarkTheme.current) Color(0xFF1D1B20) else MaterialTheme.colorScheme.surface

/** 纯黑（OLED）主题底色：amoledBlack 开启时页面级背景角色的覆盖值。 */
private val AmoledBlack = Color(0xFF000000)

/**
 * 应用主题 —— Material 3 风格化。
 *
 * @param dark 已解析的生效深色态（MainActivity：2 手动深 / 1 手动浅 / 0 跟随系统）
 * @param amoledBlack 纯黑主题开关（仅 dark=true 时起作用）
 * @param dynamicColor 动态取色开关（设置 → 外观 → 动态取色）：
 *   开启且 Android 12+ → dynamic 色板；关闭（默认）或 API < 31 → M3 基线色板。
 */
@Composable
fun AppTheme(
    dark: Boolean,
    amoledBlack: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (dark) {
        val base = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            dynamicDarkColorScheme(LocalContext.current)
        } else {
            darkColorScheme()
        }
        if (amoledBlack) {
            base.copy(
                background = AmoledBlack,
                surface = AmoledBlack,
                surfaceContainerLowest = AmoledBlack,
                surfaceContainerLow = AmoledBlack,
            )
        } else {
            base
        }
    } else {
        if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            dynamicLightColorScheme(LocalContext.current)
        } else {
            lightColorScheme()
        }
    }
    CompositionLocalProvider(LocalAppDarkTheme provides dark) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}
