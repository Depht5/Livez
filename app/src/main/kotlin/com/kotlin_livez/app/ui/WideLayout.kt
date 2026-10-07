package com.kotlin_livez.app.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * 大屏（平板）布局判定与方向策略 —— 事实基准为 dart_simple_live 原项目：
 *
 * - **阈值 600dp** 与原项目 `_shouldUseAndroidPhoneOrientationPolicy()`
 *   （`player_controller.dart`：`min(displayWidth, displayHeight) / devicePixelRatio < 600`
 *   视为「紧凑手机」）语义一致；此处改用 Android 原生 `smallestScreenWidthDp`
 *   —— 该值**与屏幕方向无关**（旋转后不变），是「是否平板」的稳定判据。
 * - **方向策略**对齐原项目 `resetPreferredOrientation()`：平板释放全部方向交给系统
 *   （原注释：*Tablets keep the OS orientation policy so their layouts are not forced
 *   through portrait*），紧凑手机锁定竖屏。
 */

/** 平板判定阈值（最短边 dp）。 */
const val TABLET_MIN_SMALLEST_WIDTH_DP = 600

/** 是否平板设备（与当前屏幕方向无关；旋转后结果不变）。 */
fun Context.isTabletDevice(): Boolean =
    resources.configuration.smallestScreenWidthDp >= TABLET_MIN_SMALLEST_WIDTH_DP

/**
 * 设备档位对应的方向策略（退出全屏 / 启动时恢复用）：
 * 平板 → `UNSPECIFIED`（交给系统与自动旋转）；紧凑手机 → `PORTRAIT`（保持既有锁定语义）。
 */
fun Context.deviceOrientationPolicy(): Int =
    if (isTabletDevice()) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

/**
 * 是否启用大屏横向布局：**平板 + 横屏**。
 * 主壳层据此把底栏换成左侧 NavigationRail；直播间据此走「播放器居左 / 信息栏+弹幕居右」分栏。
 * 手机（< 600dp）任何方向都返回 false —— 直播间全屏维持沉浸满屏视频（用户决策）。
 */
@Composable
fun useWideLayout(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.smallestScreenWidthDp >= TABLET_MIN_SMALLEST_WIDTH_DP &&
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
}
