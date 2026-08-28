package com.kotlin_livez.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kotlin_livez.app.ui.theme.CustomIcons

/**
 * 播放器控制层（Compose 版，竖屏/全屏共用）—— 替换旧 View 体系
 * `ui/PlayerControlsOverlay.kt`，对应 Flutter `player_controls.dart` 的简化版。
 *
 * 结构：顶部渐隐栏（返回/标题/菜单图标，仅全屏显示）+ 底部渐隐栏
 * （刷新[含转圈态]/播放暂停[仅全屏+开播]/弹幕开关/弹幕设置/直播时长/清晰度/线路[仅全屏]/全屏切换）。
 * 控件图标（task svg-button-icons）：Back/Menu/Refresh/Play/Pause/DanmakuOn/DanmakuOff/
 * DanmuSetting/Fullscreen/ExitFullscreen 均为 CustomIcons ImageVector，统一 20dp
 * （OverlayIconButton 默认值，调用处不传 size）；弹幕开关选中色 #FFD857 / 未选白。
 *
 * **文件位置说明**：本组件约 160 行且为纯无状态展示件（所有状态经参数注入、
 * 动作经回调上抛），符合 `ui/components` 目录定位；并入 LiveRoomScreen.kt 会让页面
 * 文件超 900 行不利维护，故独立成文件。
 *
 * **提示条已移出**（task player-ui-polish）：亮度/音量调节提示不再随控件显隐，
 * 由 LiveRoomScreen 在视频区独立渲染（控件隐藏时滑动仍有指示）。
 *
 * 显隐规则严格对照旧 `refreshButtons()`：
 * - 顶栏仅全屏显示；
 * - 播放/暂停按钮仅全屏且开播时显示（竖屏按钮过多会挤出屏幕）；
 * - 线路按钮仅全屏显示；
 * - 弹幕开关选中色 #FFD857 / 未选白色；
 * - 全屏按钮为白色图标（窗口态 Fullscreen / 全屏态 ExitFullscreen）。
 *
 * show/hide/toggle 与 5s 自动隐藏的计时逻辑由宿主（LiveRoomScreen）以 LaunchedEffect
 * 实现；本组件只按参数渲染。
 */
@Composable
fun PlayerControlsOverlay(
    visible: Boolean,
    fullScreen: Boolean,
    playing: Boolean,
    liveStatus: Boolean,
    danmakuEnabled: Boolean,
    durationText: String,
    qualityName: String,
    lineName: String,
    title: String,
    onBackClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onDanmakuToggle: () -> Unit,
    onDanmakuSettingsClick: () -> Unit,
    onQualityClick: () -> Unit,
    onLineClick: () -> Unit,
    onMenuClick: () -> Unit,
    onFullScreenClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 等价旧 View 的 GONE：不参与组合，按钮状态随下次显示时的参数重建
    if (!visible) return

    Box(modifier = modifier.fillMaxSize()) {

        /* ---- 顶栏（仅全屏显示，对应旧 topBar + updateFullScreen）---- */
        if (fullScreen) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0x99000000), Color.Transparent),
                        ),
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OverlayIconButton(icon = CustomIcons.Back, onClick = onBackClick)
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )
                OverlayIconButton(icon = CustomIcons.Menu, onClick = onMenuClick)
            }
        }

        /* ---- 底栏（渐隐过渡到黑色，对齐旧 bottomBar 布局顺序）---- */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xCC000000)),
                    ),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 刷新（refreshRoom）：动画由宿主在视频区显示「与加载相同的居中转圈」，
            // 控制层按钮保持常显（task player-ui-polish）；竖屏时收窄三键间距。
            val compactH = if (!fullScreen) 6.dp else 12.dp
            OverlayIconButton(
                icon = CustomIcons.Refresh,
                onClick = onRefreshClick,
                paddingH = compactH,
            )
            // 播放/暂停：仅全屏且开播时显示（refreshButtons 规则）
            if (fullScreen && liveStatus) {
                OverlayIconButton(
                    icon = if (playing) CustomIcons.Pause else CustomIcons.Play,
                    onClick = onPlayPauseClick,
                )
            }
            // 弹幕开关（选中色 #FFD857）
            OverlayIconButton(
                icon = if (danmakuEnabled) CustomIcons.DanmakuOn else CustomIcons.DanmakuOff,
                tint = if (danmakuEnabled) Color(0xFFFFD857) else Color.White,
                onClick = onDanmakuToggle,
                paddingH = compactH,
            )
            // 弹幕设置（对齐 Dart 弹幕开关旁的设置图标按钮）
            OverlayIconButton(
                icon = CustomIcons.DanmuSetting,
                onClick = onDanmakuSettingsClick,
                paddingH = compactH,
            )
            // 直播时长
            Text(
                text = durationText,
                fontSize = 13.sp,
                color = Color.White,
                modifier = Modifier.padding(start = 8.dp),
            )
            Spacer(modifier = Modifier.weight(1f))
            // 清晰度入口（空名回退占位文案；回滚为原小号按钮）
            OverlayButton(
                text = qualityName.ifEmpty { "清晰度" },
                onClick = onQualityClick,
                fontSize = 13.sp,
                paddingH = 8.dp,
                paddingV = 6.dp,
            )
            // 线路入口：仅全屏显示（refreshButtons 规则；回滚为原小号按钮）
            if (fullScreen) {
                OverlayButton(
                    text = lineName.ifEmpty { "线路" },
                    onClick = onLineClick,
                    fontSize = 13.sp,
                    paddingH = 8.dp,
                    paddingV = 6.dp,
                )
            }
            // 全屏切换（图标随状态切换：窗口态 Fullscreen / 全屏态 ExitFullscreen；点击由宿主处理）
            OverlayIconButton(
                icon = if (fullScreen) CustomIcons.ExitFullscreen else CustomIcons.Fullscreen,
                onClick = onFullScreenClick,
            )
        }
    }
}

/**
 * 控制层图标/文字按钮：白字、去涟漪（对齐旧 iconButton 规格与整体简洁风格）。
 * 默认放大（15sp / 12×8dp，task player-ui-polish）；清晰度/线路等次要按钮可按参数回缩。
 */
@Composable
private fun OverlayButton(
    text: String,
    onClick: () -> Unit,
    tint: Color = Color.White,
    fontSize: TextUnit = 15.sp,
    paddingH: Dp = 12.dp,
    paddingV: Dp = 8.dp,
) {
    Text(
        text = text,
        fontSize = fontSize,
        color = tint,
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = paddingH, vertical = paddingV),
    )
}

/**
 * 控制层图标按钮：去涟漪、显式上色（Icon 的 ColorFilter 染色；控制层背景为深色渐隐，
 * 默认白色图标确定可见，避免 fallback 到 LocalContentColor 染黑；弹幕开关等双色场景
 * 经 tint 参数传入）。全部图标统一 20dp（默认值，调用处不传 size）。
 */
@Composable
private fun OverlayIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    tint: Color = Color.White,
    size: Dp = 20.dp,
    paddingH: Dp = 12.dp,
    paddingV: Dp = 8.dp,
) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        // material3 Icon：tint=Unspecified 会回退为 LocalContentColor.current（此处近黑），
        // 必须显式上色以在深色控制栏上可见
        tint = tint,
        modifier = Modifier
            // 顺序关键：padding 在外层撑开点击区，size 在最内层决定图标本体渲染尺寸；
            // 反序（size 外、padding 内）会使内容区 = size − padding ≤ 0，图标不渲染
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = paddingH, vertical = paddingV)
            .size(size),
    )
}
