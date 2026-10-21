package com.kotlin_livez.app.ui.screens

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kotlin_livez.app.app.Constant
import com.kotlin_livez.app.app.Log
import com.kotlin_livez.app.app.Sites
import com.kotlin_livez.app.app.Utils
import com.kotlin_livez.app.di.ServiceContainer
import com.kotlin_livez.app.models.FollowUser
import com.kotlin_livez.app.modules.live_room.LiveRoomController
import com.kotlin_livez.app.player.MpvInitConfig
import com.kotlin_livez.app.player.MpvVideoView
import com.kotlin_livez.app.player.PlayerEngine
import com.kotlin_livez.app.services.MpvOptionsService
import com.kotlin_livez.app.ui.DanmakuOverlayView
import com.kotlin_livez.app.ui.Router
import com.kotlin_livez.app.ui.deviceOrientationPolicy
import com.kotlin_livez.app.ui.useWideLayout
import com.kotlin_livez.app.ui.components.DanmuSettingsRows
import com.kotlin_livez.app.ui.components.DanmuSettingsSheet
import com.kotlin_livez.app.ui.components.DanmuSettingsSidePanel
import com.kotlin_livez.app.ui.components.DanmuShieldSheet
import com.kotlin_livez.app.ui.components.EmptyHint
import com.kotlin_livez.app.ui.components.LineDialog
import com.kotlin_livez.app.ui.components.LiveSettingsRows
import com.kotlin_livez.app.ui.components.LiveSettingsSheet
import com.kotlin_livez.app.ui.components.LiveSettingsSidePanel
import com.kotlin_livez.app.ui.components.NetImage
import com.kotlin_livez.app.ui.components.PlayerControlsOverlay
import com.kotlin_livez.app.ui.components.QualityDialog
import com.kotlin_livez.app.ui.components.ROOM_MENU_ITEMS_FULLSCREEN
import com.kotlin_livez.app.ui.components.ROOM_MENU_ITEMS_PORTRAIT
import com.kotlin_livez.app.ui.components.RoomMenuAction
import com.kotlin_livez.app.ui.components.RoomMenuDialog
import com.kotlin_livez.app.ui.components.RoomSheet
import com.kotlin_livez.app.ui.components.SheetClickRow
import com.kotlin_livez.app.ui.nav.liveRoomContainerSharedElement
import com.kotlin_livez.app.ui.nav.liveRoomCoverSharedElement
import com.kotlin_livez.app.ui.nav.activateSharedRoom
import com.kotlin_livez.app.ui.nav.clearSharedRoomIfMatch
import com.kotlin_livez.app.ui.nav.activeSharedRoomSeed
import com.kotlin_livez.app.ui.nav.liveRoomPairKey
import com.kotlin_livez.app.ui.nav.LocalNavAnimatedVisibilityScope
import com.kotlin_livez.app.ui.theme.CustomIcons
import com.kotlin_livez.app.ui.theme.LocalAppDarkTheme
import com.kotlin_livez.app.ui.theme.appThemeResolvedDark
import com.kotlin_livez.app.ui.theme.LiveIndicatorRed
import com.kotlin_livez.app.ui.theme.LiveOrange
import com.kotlin_livez.core.model.LiveContributionRankItem
import com.kotlin_livez.core.model.LiveMessage
import com.kotlin_livez.core.model.LiveMessageType
import com.kotlin_livez.core.model.LiveSuperChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * 直播间页面（Compose 版）—— 对应 Flutter `live_room_page.dart`，替换旧 View 体系
 * `ui/LiveRoomActivity.kt`（Batch 7b 后该 Activity 已删除）。
 *
 * 结构镜像：标题栏（返回「←」/标题 16sp medium 单行省略/「⋮」菜单）→
 * 视频区（AndroidView 包裹 MpvVideoView + DanmakuOverlayView 互操作；未开播黑底提示；
 * Compose 版 PlayerControlsOverlay 控制层 + pointerInput 手势）→ 信息区 →
 * 标签栏（聊天/SC(bilibili)/头条(huya)/关注/贡献榜(douyu 为亲密榜)/设置）→ 内容区。
 *
 * 全屏切换：isFullScreen 为 UI 层状态（rememberSaveable 持久，等价原 onSaveInstanceState），
 * 全屏时隐藏竖屏布局区、系统栏隐藏 + SENSOR_LANDSCAPE 方向 + 刘海模式 SHORT_EDGES；
 * 视频按源宽高比 letterbox 居中（不拉伸，黑边留白；无尺寸信息回退铺满）。
 *
 * 大屏版式：平板横屏（[useWideLayout]）**非全屏**时顶栏横跨整个顶部，其下左右分栏
 * （播放器居左、信息栏 + 标签内容居右）；**全屏时不分栏**，播放器独占整屏
 * （用户决策 2026-10-07，反转原「全屏也分栏」）。
 * 全屏**保持同一分支**、只隐藏标题栏与右栏——视频的 AndroidView 组合位置不得变化，
 * 否则 SurfaceView 会 detach 重建并丢画面（详见下方根因说明）。
 * MainActivity 已声明 configChanges 含 orientation|screenSize|screenLayout|keyboardHidden
 * → 旋转不重建，布局重算由约束驱动（aspectRatio 随宽度变化自动重算 16:9 高度，
 * 等价原 onConfigurationChanged 手动重算语义，无需显式读取 LocalConfiguration）。
 *
 * 弹层（清晰度/线路/弹幕设置/直播设置/屏蔽/⋮菜单）在 ui/components/LiveRoomDialogs.kt。
 */
@Composable
fun LiveRoomScreen(
    siteId: String,
    roomId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val site = Sites.allSites[siteId]

    // 站点不存在：提示后退出（对应原 onCreate 里 toast + finish）
    LaunchedEffect(siteId) {
        if (site == null) {
            Router.toast("站点不存在：$siteId")
            onBack()
        }
    }
    if (site == null) {
        // 空白帧等待返回动画（下一帧即 popBackStack）
        Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        return
    }

    // 一镜到底共享元素的房间标记（task 10-21-tablet-home-pager-fluency R1）：
    // - 进入即标记本房间，使列表里对应卡片同时挂上共享节点（含直播间内换房场景）；
    // - 路由真正离栈（退场动画结束、本组合销毁）时清除，返回列表稳态后卡片 0 共享节点。
    DisposableEffect(siteId, roomId) {
        activateSharedRoom(siteId, roomId)
        onDispose { clearSharedRoomIfMatch(siteId, roomId) }
    }

    val context = LocalContext.current
    // view 级屏幕常亮句柄（不依赖 context 类型，见下方 DisposableEffect）
    val view = LocalView.current

    // 控制器路由级生命周期：按 (siteId, roomId) 记住，离开组合销毁（等价原 Activity onDestroy）
    val controller = remember(siteId, roomId) {
        LiveRoomController(
            appSettings = ServiceContainer.appSettings,
            dbService = ServiceContainer.dbService,
            followService = ServiceContainer.followService,
            currentRoom = ServiceContainer.currentRoom,
            player = PlayerEngine.instance,
        )
    }
    // 互操作 View（白名单）：mpv 渲染内核 + Canvas 浮层弹幕，内部实现不改写。
    // 随路由参数重建（正常换房走 controller.resetRoom，不经过这里）。
    val videoView = remember(siteId, roomId) { MpvVideoView(context) }
    val danmakuOverlay = remember(siteId, roomId) {
        DanmakuOverlayView(context).apply { this.siteId = siteId }
    }

    // 屏幕常亮条件化（task 10-20-live-playback-power-optimization R2.4）：原写法无条件
    // 常亮，暂停 / 未开播 / 播放报错期间同样阻止系统息屏，纯属浪费。条件赋值放在
    // `showRoomError` 定义之后的 LaunchedEffect 里（下方「屏幕常亮跟随播放态」段）；
    // 此处只保留离场复位。view 级 keepScreenOn 不依赖 LocalContext 的具体类型——旧写法
    // `context as? Activity` 强转失败会静默不加 window flag（task player-icons-follow-ux）。
    DisposableEffect(view) {
        onDispose { view.keepScreenOn = false }
    }

    /* ---------- 进场转场完成门控（task 10-21-tablet-shared-element-transition-fluency）---------- */

    // 280ms 一镜到底 / 淡入转场是否已落位。转场期间要刻意「什么都不做」：不挂 SurfaceView、
    // 不跑转圈动画、不初始化 mpv / 控制器、不降刷新率 —— 否则逐帧重测量 + JNI + 网络回调
    // 会把高刷平板 6.94ms 的帧预算直接打穿（见该任务 design.md 的帧预算表）。
    // remember key 为 (siteId, roomId)：房间存活期间保持 true（含旋转、进退全屏、房内换台），
    // 因此不会导致 AndroidView 卸载重建（task 10-16 铁律）。
    val navTransition = LocalNavAnimatedVisibilityScope.current?.transition
    var enterTransitionDone by remember(siteId, roomId) {
        mutableStateOf(
            navTransition == null ||
                (navTransition.currentState == EnterExitState.Visible && !navTransition.isRunning),
        )
    }
    // 正在退场（leaveRoom 起手即置位）：转场期间不挂原生 SurfaceView、不跑转圈、恢复封面
    var leaving by remember { mutableStateOf(false) }
    LaunchedEffect(siteId, roomId, navTransition) {
        if (enterTransitionDone) return@LaunchedEffect
        // 先让出首帧，确保导航与共享元素转场已在首帧启动
        withFrameNanos { }
        // 320ms 超时兜底：极端情况下转场状态不收敛，也不能让播放器永远不初始化
        withTimeoutOrNull(320L) {
            snapshotFlow {
                navTransition == null ||
                    (navTransition.currentState == EnterExitState.Visible &&
                        !navTransition.isRunning)
            }.first { it }
        }
        enterTransitionDone = true
    }

    /* ---------- 全屏态（UI 层状态；等价原 isFullScreen 字段）---------- */

    // rememberSaveable：进程重建恢复语义对照原 onSaveInstanceState(KEY_FULL_SCREEN)——
    // 恢复为 true 时下面的窗口副作用 LaunchedEffect 自动重新进入全屏
    var isFullScreen by rememberSaveable { mutableStateOf(false) }
    // 是否真正进过全屏（task 10-21-tablet-shared-element-transition-fluency R3）：
    // 非全屏进房的第 1 帧不应执行 requestedOrientation / 系统栏这些跨进程调用
    //（转场窗口内每次 IPC 都能吃掉一帧）；只有实际进过全屏，退场/退全屏时才需要恢复。
    var hasEnteredFullScreen by remember { mutableStateOf(isFullScreen) }

    // 全屏系统栏/方向副作用：进入全屏 → SENSOR_LANDSCAPE + 隐藏 systemBars（滑动临时呼出）。
    // 等价原 enterFullScreen/exitFull 的窗口操作。控制层顶栏不做刘海避让（用户决策：
    // 刘海区不压关键控件，接受视觉覆盖，不加 statusBarsPadding）。
    // 刘海模式说明：SHORT_EDGES 由主题 values-v28/styles.xml 声明（对比原项目
    // dart_simple_live NormalTheme）——运行时对已附加窗口改 layoutInDisplayCutoutMode
    // 在部分 OEM（实测荣耀）不生效，故不做运行时切换，主题常开对竖屏无副作用。
    // 全屏延伸说明（真机实证：全屏横屏刘海侧 135px 空白）：主题 values-v30 已声明
    // LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS，但 ContentView 仍可能被系统在刘海侧避让
    // （Android 15+ / 荣耀实测 mAppBounds=Rect(135, 0 - 2800, 1194)）。
    // 沉浸改造（用户反馈：状态栏/底部小白条区域纯白不统一）：MainActivity 已
    // enableEdgeToEdge()（edge-to-edge），本页不再切换 decorFits；
    // 竖屏页面背景（根 Column）延伸覆盖状态栏区，靠根容器的 statusBarsPadding 避让内容
    //（全屏隐藏系统栏后该 insets 为 0，padding 自动归零，视频铺满无影响）。
    LaunchedEffect(isFullScreen) {
        val activity = context as? Activity ?: return@LaunchedEffect
        val window = activity.window
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        if (isFullScreen) {
            hasEnteredFullScreen = true
            // 运行时方向覆盖 Manifest portrait（configChanges 已声明 → 不 recreate）
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            // transient 栏图标明暗：全屏为黑色视频底，滑动呼出的临时系统栏需浅色图标
            //（false = 浅色图标）；否则深色图标在黑底上不可见。
            insetsController.isAppearanceLightStatusBars = false
            insetsController.isAppearanceLightNavigationBars = false
        } else if (hasEnteredFullScreen) {
            // 退出全屏恢复方向按设备档位：平板 → 释放（可继续横屏用大屏分栏布局）；
            // 紧凑手机 → 竖屏（原固定 PORTRAIT 的语义，见 ui/WideLayout.kt）
            activity.requestedOrientation = activity.deviceOrientationPolicy()
            insetsController.show(WindowInsetsCompat.Type.systemBars())
            // 恢复图标明暗 = 应用当前生效主题的对应值（task 08-29-live-room-bars-follow-status
            // 真机实证：原硬编码 true（深色图标）在深色模式下把状态栏打成黑图标不可见；
            // 经 appThemeResolvedDark 组合外镜像读取当前生效深色态）
            insetsController.isAppearanceLightStatusBars = !appThemeResolvedDark
            insetsController.isAppearanceLightNavigationBars = !appThemeResolvedDark
        }
    }
    // 离开直播间时若仍处于全屏：恢复竖屏方向与系统栏
    //（避免 MainActivity 宿主窗口残留横屏沉浸；刘海/edge-to-edge 由全局主题与 LiveApp 常开，无需恢复）
    DisposableEffect(context) {
        onDispose {
            val activity = context as? Activity
            // 非全屏进房、且从未进过全屏 → 本页没改过方向/系统栏，跳过这几次跨进程恢复
            if (activity != null && (hasEnteredFullScreen || isFullScreen)) {
                runCatching {
                    // 同样按设备档位恢复（平板不强行掰回竖屏，否则离开直播间后无法保持横屏大屏布局）
                    activity.requestedOrientation = activity.deviceOrientationPolicy()
                    val insetsController = WindowInsetsControllerCompat(
                        activity.window, activity.window.decorView,
                    )
                    insetsController.show(WindowInsetsCompat.Type.systemBars())
                    // 兜底恢复图标明暗 = 应用当前生效主题对应值（原硬编码 true 在深色模式下
                    // 使退出直播间后卡在黑图标直到重启——MainActivity 的主题效果仅在 dark
                    // 变化时重跑，dark 未变不会纠正，故此处必须自行恢复正确值）
                    insetsController.isAppearanceLightStatusBars = !appThemeResolvedDark
                    insetsController.isAppearanceLightNavigationBars = !appThemeResolvedDark
                }
            }
        }
    }
    // 窗口首选刷新率（task 10-17 功耗治理 + task 10-21-tablet-shared-element-transition-fluency R3）：
    // 直播流本身 30–60fps，高刷屏多出的帧只增功耗 ⇒ 稳态观看时把窗口钉到 60Hz；
    // 但 280ms 的进出场一镜到底动画**必须跑满原生高刷**，因此把「降频」时机从「进房第 1 帧」
    // 推迟到 `enterTransitionDone`，并在 `leaveRoom()` 启动退场动画**之前**先恢复进入前的值
    //（否则退场动画同样被锁在 60fps）。
    val activityWindow = (context as? Activity)?.window
    val savedRefreshRate = remember(activityWindow) {
        activityWindow?.attributes?.preferredRefreshRate ?: 0f
    }
    fun restoreWindowRefreshRate() {
        val window = activityWindow ?: return
        runCatching {
            val attributes = window.attributes
            if (attributes.preferredRefreshRate != savedRefreshRate) {
                attributes.preferredRefreshRate = savedRefreshRate
                window.attributes = attributes
            }
        }
    }
    LaunchedEffect(activityWindow, enterTransitionDone, leaving) {
        val window = activityWindow ?: return@LaunchedEffect
        if (!enterTransitionDone || leaving) return@LaunchedEffect
        runCatching {
            val attributes = window.attributes
            if (attributes.preferredRefreshRate != 60f) {
                attributes.preferredRefreshRate = 60f
                window.attributes = attributes
            }
        }
    }
    // 非常规出栈（进程兜底 / 被系统回收）也要恢复
    DisposableEffect(activityWindow) {
        onDispose { restoreWindowRefreshRate() }
    }

    // 通知权限（后台保活通知可见性）：Android 13+ POST_NOTIFICATIONS 为运行时权限，
    // 未授予时前台服务仍运行但通知被系统抑制——用户以通知为保活生效的依据，进入直播间时请求一次。
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(enterTransitionDone, leaving) {
        if (!enterTransitionDone || leaving) return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    /* ---------- 控制层显隐 + 自动隐藏计时 ---------- */

    // 控制层显隐（等价旧 PlayerControlsOverlay.show()/hide()/toggle()）
    var controlsVisible by remember { mutableStateOf(false) }
    // 触摸计数：每次触摸自增以重置 5s 自动隐藏计时（等价旧 postDelayed 移除重挂）
    var controlsTouchTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(controlsVisible, controlsTouchTick) {
        if (controlsVisible) {
            delay(5000L)
            controlsVisible = false
        }
    }

    /* ---------- 调节提示胶囊（亮度/音量；1.2s 自动消失，job 取消防叠加）---------- */

    val coroutineScope = rememberCoroutineScope()
    // adjustHint = 当前是否应显示（null 触发退场）；lastAdjustHint = 末次非空快照。
    // 快照是必需的：AnimatedVisibility 退场期间（adjustHint 已置 null、内容仍在组合）必须
    // 还有内容可画，否则 180ms 淡出的是一个**空胶囊**（这类动画最常见的错误）。
    var adjustHint by remember { mutableStateOf<AdjustHintState?>(null) }
    var lastAdjustHint by remember { mutableStateOf<AdjustHintState?>(null) }
    var adjustHintJob by remember { mutableStateOf<Job?>(null) }
    fun showAdjustHint(state: AdjustHintState) {
        // 取消旧提示 job 防叠加（等价旧 mainHandler.removeCallbacks(hideJob)）
        adjustHintJob?.cancel()
        adjustHintJob = coroutineScope.launch {
            lastAdjustHint = state
            adjustHint = state
            delay(1200L)
            adjustHint = null
        }
    }

    /* ---------- 刷新中状态（控制层刷新按钮转圈；加载/刷新动画，task player-ui-polish）---------- */

    var refreshing by remember { mutableStateOf(false) }
    // detail 更新（刷新完成后 loadData 下发新详情）→ 复位刷新态；兜底超时 8s
    //（复位 LaunchedEffect(detail) 放在 detail 订阅之后，见下方刷新态区）
    LaunchedEffect(refreshing) {
        if (refreshing) {
            delay(8000L)
            refreshing = false
        }
    }

    /* ---------- 全屏切换动作 ---------- */

    fun toggleFullScreen() {
        isFullScreen = !isFullScreen
        // 进/退全屏后显示控制层并重置自动隐藏（等价旧 enter/exit 里的 controlsOverlay.show()）
        controlsVisible = true
        controlsTouchTick++
    }

    /* ---------- 退出直播间（Surface 转场时序处理）---------- */

    // 根因说明（真机症状 1：退出直播间后播放器画面残留零点几秒）：
    // MpvVideoView 是 SurfaceView（独立 Z 层，由系统合成器直接上屏，不参与 View 层级绘制）。
    // 导航 pop 的转场动画期间，Compose 页面整体退出，但 Surface 图层不受该动画影响，
    // 会一直显示到视图脱离窗口为止 → 视频画面浮在目标页之上残留数百毫秒。
    // 修复：返回前先停止控制器并释放本页面拥有的播放器会话，再把两个互操作 View 置
    // GONE；这样不会把仍绑定旧 Surface 的全局 MPVLib 留给下一次进入。等待两帧确保
    // Surface 图层从合成器移除后再 popBackStack。
    fun leaveRoom() {
        if (leaving) return
        leaving = true
        // 先恢复进入前的刷新率：退场的 280ms 一镜到底动画也要跑满原生高刷
        restoreWindowRefreshRate()
        // 退出发生在 pop 之前，此处可以确定性地完整释放当前会话；onDispose 仍作为
        // 非常规出栈路径的兜底，且 controller/videoView 的 dispose 均已幂等。
        runCatching { PlayerEngine.instance.stop() }
        controller.dispose()
        videoView.destroy()
        videoView.visibility = View.GONE
        danmakuOverlay.visibility = View.GONE
        coroutineScope.launch {
            // 第一帧应用 GONE，第二帧确保 Surface 图层从合成器移除
            repeat(2) { withFrameNanos { } }
            onBack()
        }
    }

    // 全屏时 BACK 先退全屏再出栈（等价原 onBackPressedDispatcher callback）。
    BackHandler(enabled = isFullScreen) {
        toggleFullScreen()
    }
    // 非全屏手势返回必须走 leaveRoom（task 09-12-fullscreen-rotate-transition 真机结论，
    // 用户实证）：预测性返回移除后手势提交时若直接 pop，转场动画期间播放器仍在解码渲染、
    // 弹幕仍在绘制，与上一页面组合叠加 → 掉帧；leaveRoom 先 stop 播放器并移除 Surface、
    // 等两帧再 pop，使手势返回的转场开销与标题栏返回按钮完全一致。
    // （旧注释所称"预测性预览"已随 enableOnBackInvokedCallback 移除而失效，无冲突。）
    BackHandler(enabled = !isFullScreen && !leaving) {
        leaveRoom()
    }

    /* ---------- 播放器接线 + 控制器生命周期 ---------- */

    // 放在同一个 DisposableEffect 里保证销毁顺序确定；正常返回已在 leaveRoom 提前执行，
    // 此处负责非常规出栈兜底，两个释放入口均为幂等操作。
    // 注意：初始化被移到下方 `LaunchedEffect(..., enterTransitionDone)`（task 10-21 R4），
    // 这里只保留销毁；未初始化就被销毁的场景（转场 280ms 内立刻返回）必须安全幂等。
    DisposableEffect(controller, videoView) {
        onDispose {
            if (ServiceContainer.activeLiveRoom === controller) {
                ServiceContainer.activeLiveRoom = null
            }
            controller.dispose()
            videoView.destroy()
        }
    }

    // 重负载初始化错峰到转场落位之后（task 10-21-tablet-shared-element-transition-fluency R4）：
    // mpv JNI（MPVLib.create + 25+ 项参数 + init）与控制器网络/数据库请求若在 280ms 转场窗口内
    // 开跑，回调会在飞行途中回来，把整页重组 + 全屏 sharedBounds 图层重录叠加到动画帧上。
    LaunchedEffect(controller, videoView, enterTransitionDone) {
        if (!enterTransitionDone || leaving) return@LaunchedEffect
        val videoConfig =
            MpvOptionsService.videoControllerConfiguration(ServiceContainer.appSettings)
        runCatching {
            videoView.initialize(
                configDir = context.filesDir.absolutePath,
                cacheDir = context.cacheDir.absolutePath,
                config = MpvInitConfig(
                    vo = videoConfig.vo,
                    hwdec = videoConfig.hwdec,
                    options = MpvOptionsService.applyToPlayerOptions(ServiceContainer.appSettings),
                    // 直播缓存上限接入设置项（task 10-20 R2.3）：默认 32MB，设置页限幅 8..128MB。
                    bufferSizeMb = ServiceContainer.appSettings.playerBufferSize.value,
                ),
            )
        }.onFailure { e ->
            // 播放器初始化失败不闪退：提示并继续展示弹幕/信息
            Router.toast("播放器初始化失败：${e.message ?: e.javaClass.simpleName}")
        }
        controller.create(site, roomId)
        // 订阅播放器事件（播放结束/失败重试 + 后台保活服务随播放状态启停）。
        // 修复：原实现漏调此入口，媒体事件与后台播放同步从未生效。
        controller.subscribePlayerEvents()
        // 注册当前直播间（后台播放接线：MainActivity 前后台切换转发到此控制器）
        ServiceContainer.activeLiveRoom = controller
        // 进房自动全屏（原独立 LaunchedEffect 合并到这里：必须在 create 之后、
        // 且只在转场落位后触发，避免首帧方向 IPC 打断入场动画）
        if (ServiceContainer.appSettings.autoFullScreen.value && !isFullScreen) {
            isFullScreen = true
            controlsVisible = true
            controlsTouchTick++
        }
    }

    /* ---------- 状态订阅（controller 已赋值后，等价原 bindState）---------- */

    val detail by controller.detail.collectAsStateWithLifecycle()
    // 关注状态（task follow-tab-and-room-follow：信息区关注按钮用）
    val followed by controller.followed.collectAsStateWithLifecycle()
    // 刷新完成（detail 更新）→ 复位刷新态（refreshing 状态声明在调节区，此处订阅 detail 复位）
    LaunchedEffect(detail) {
        if (refreshing) refreshing = false
    }
    // 人气值（1 分钟级刷新）不在此订阅：下沉到 roomInfoBar 内部（task 10-20 R3.2），
    // 全屏 / 宽屏右栏不组合时不产生订阅与根级重组。
    val liveStatus by controller.liveStatus.collectAsStateWithLifecycle()
    val loadError by controller.loadError.collectAsStateWithLifecycle()
    // 断流/播放失败文案（task 10-17-ui-material3-details-and-redesign）：控制器在播放失败
    // 路径写入 `_errorMsg = "播放失败"`；两处清空——`loadData` 入口（换房/刷新即新会话，
    // 不清会粘住上一房间的文案并顶掉「未开播」）与真正开到线路时（`initPlaylist`）。
    val errorMsg by controller.errorMsg.collectAsStateWithLifecycle()
    // 聊天消息列表（高频：100ms 批次）不在此订阅：下沉到聊天 Tab 内的 ChatTabSection
    // （task 10-20 R3.2）——全屏模式或非聊天 Tab 时 0 订阅、0 重组、0 滚动唤醒。
    val superChats by controller.superChats.collectAsStateWithLifecycle()
    val contributionUsers by controller.contributionRankUsers.collectAsStateWithLifecycle()
    // 贡献榜三态（task 10-18-ui-closure-leftovers-fixes-and-decisions）：「加载中」先于「空列表」
    // 判定，避免在途请求期间显示「暂无贡献榜数据」。
    // 但 loading 单独盖不住**首帧**：`fetchContributionRank` 在详情到位后才启动，此前 loading
    // 仍是 false。故再订阅 fetched（请求是否已结束），二者合用即覆盖「尚未开始请求 → 请求中」全段；
    // 详情本身报错时不转圈（页面已有错误层），直接落空态。
    val contributionRankLoading by controller.contributionRankLoading.collectAsStateWithLifecycle()
    val contributionRankFetched by controller.contributionRankFetched.collectAsStateWithLifecycle()
    // 贡献榜开关（设置页「贡献榜」）：关闭时连 Tab 一并隐藏，对齐 Dart 上游
    //（live_room_page.dart 开关关闭即不添加 contribution_rank 标签页）。
    val contributionRankEnable by ServiceContainer.appSettings.contributionRankEnable.collectAsStateWithLifecycle()
    val qualites by controller.qualites.collectAsStateWithLifecycle()
    val qualityName by controller.currentQualityInfo.collectAsStateWithLifecycle()
    val lineName by controller.currentLineInfo.collectAsStateWithLifecycle()
    // 直播时长（1Hz）不在此订阅：下沉到 PlayerControlsOverlay（控制层可见时才收集），
    // 否则隐藏期每秒一次的重组会带上整页（task 10-17-ui-power-and-recomposition-governance）。
    val danmuEnable by ServiceContainer.appSettings.danmuEnable.collectAsStateWithLifecycle()
    val followLive by ServiceContainer.followService.liveList.collectAsStateWithLifecycle()
    val followNotLive by ServiceContainer.followService.notLiveList.collectAsStateWithLifecycle()
    // 播放状态（▶/⏸ 图标）
    val playerState by PlayerEngine.instance.state.collectAsStateWithLifecycle()
    // 「当前所在房间」数据源（关注 Tab 的高亮判定）：必须读控制器 rxSite/rxRoomId 而**不是**
    // 路由参数 —— 直播间内换房走 `controller.resetRoom`（关注 Tab 点击、自动换台），
    // 不改变本页路由的 siteId/roomId；rxSite/rxRoomId 在 create/resetRoom 两处同步更新
    //（`_rxSite`/`_rxRoomId`，同样喂给 CurrentRoomService）。首帧兜底用路由参数避免闪一下无高亮。
    val rxSite by controller.rxSite.collectAsStateWithLifecycle()
    val rxRoomId by controller.rxRoomId.collectAsStateWithLifecycle()

    // 转场种子回退（task 10-21-tablet-shared-element-transition-fluency R5）：
    // 仅当控制器仍停留在**路由初始房间**时使用（房内换台后 rxRoomId/rxSite 变化，不再误用旧种子）。
    // 有了它，进场第 0 帧就能显示与卡片同一张封面（命中 Coil 内存缓存）与真实标题/主播名，
    // 而不是黑底 + 转圈等网络。源数据在点击卡片瞬间由激活入口写入。
    val isInitialRouteRoom =
        (rxRoomId.isEmpty() || rxRoomId == roomId) && (rxSite == null || rxSite?.id == siteId)
    val roomSeed = if (isInitialRouteRoom) {
        activeSharedRoomSeed?.takeIf { it.pairKey == liveRoomPairKey(siteId, roomId) }
    } else {
        null
    }

    // 视频源宽高比（width/height/aspect 均由 mpv video-params 属性监听维护）：
    // 优先采用 aspect（video-params/aspect = DAR 显示宽高比）——变形分辨率（anamorphic）
    // 源的存储分辨率 w/h 不等于真实显示比例，mpv 已把像素比折算进该值，非 0 时直接使用
    //（注意：不能再乘回 w/h，否则 16:9 源会算成 ~3.16）；DAR 缺失或为 0 时回退 w/h。
    // 任一维度缺失或非正时为 null，全屏布局据此决定 letterbox 还是回退铺满
    // （未开播/未取到尺寸的场景）
    val videoAspect: Float? = run {
        val w = playerState.width
        val h = playerState.height
        if (w == null || h == null || w <= 0 || h <= 0) return@run null
        val dar = playerState.aspect
        val ratio =
            if (dar != null && dar > 0.0 && dar.isFinite()) dar.toFloat() else w.toFloat() / h
        ratio.takeIf { it > 0f && it.isFinite() }
    }

    // 未开播期间自动隐藏控制层（等价旧 updateLiveStatus(!live) → hide()）
    LaunchedEffect(liveStatus) {
        if (!liveStatus) controlsVisible = false
    }

    /* ---------- 视频区覆盖层互斥（错误层 / 加载转圈 / 未开播）---------- */

    // 错误态 = 加载失败或断流文案非空；**刷新中不显示**（重试已按下，此刻应显示转圈）。
    // 三个覆盖层共用**同一居中位置**，故三个**条件**必须两两互斥——同帧至多一个条件成立
    //（注意：条件互斥 ≠ 画面上同帧只有一个可见，见下方「退场窗口」）：
    //   ① 错误层 `showRoomError`（本文件 roomVideo 内新增）；
    //   ② 加载转圈：`(detail == null || refreshing) && !showRoomError`（:1079）；
    //   ③ 「未开播」覆盖层：`!liveStatus && detail != null && !refreshing && !showRoomError`
    //      （:1068，加载失败时无法断言「未开播」，详情未就绪或刷新中让位给 ②）。
    // ① 与 ②③：②③ 条件里都带 `!showRoomError`，与 ① 由构造保证互斥（旧写法即如此）。
    // ② 与 ③ 由 `detail != null && !refreshing` 分工：详情未就绪或刷新中只有 ② 成立，
    // 详情就绪且不在刷新中才轮到 ③，故两个条件不可能同帧成立。
    //（task 10-18-ui-closure-leftovers-fixes-and-decisions 收紧 ③：旧写法只看 `!liveStatus`，
    //  与 ② 不互斥——首次进房或刷新已下播房间时白字会压在转圈上。）
    // **退场窗口**：① 与 ② 都是 `AnimatedVisibility(exit = fadeOut(200ms))`，条件转假后内容仍会被
    // 组合并绘制 200ms，而 ③ 是无动画裸 `if`（立即出现）。故「刷新一个已下播房间」与「点错误卡重试」
    // 这两种序列下，先出现的覆盖层在淡出期会与新出现者短暂叠印——这是淡出动画的固有代价，
    // 不为它改动画（此处只保证条件层互斥）。
    // 与旧写法（转圈 `(detail == null || refreshing) && !loadError`）的关系：在 `loadError` 这一个
    // 维度上等效（`loadError` 恒被 `showRoomError` 覆盖）；`errorMsg` 维度不同，见下两行。
    // 额外修掉两处旧行为的空档：
    // ① 仅 `errorMsg` 非空时旧写法会与错误层同时渲染；② `refreshing && loadError` 时
    // 旧写法两者都不显示（一片空白），现由转圈兜住。
    val roomHasError = loadError || errorMsg.isNotEmpty()
    val showRoomError = roomHasError && !refreshing

    // 屏幕常亮跟随播放态（task 10-20 R2.4）：仅「直播中 + 播放中 + 无错误层」时阻止息屏；
    // 暂停 / 未开播 / 报错一律让系统按超时息屏。离场复位由上方 DisposableEffect(view) 负责。
    LaunchedEffect(view, liveStatus, playerState.playing, showRoomError) {
        view.keepScreenOn = liveStatus && playerState.playing && !showRoomError
    }

    // 播放器初始化失败（缺 native so 等）→ 提示，页面保持可用
    LaunchedEffect(controller) {
        PlayerEngine.instance.initError.collect { message ->
            if (message != null) Router.toast(message)
        }
    }

    // 自动退出倒计时结束 → 退出直播间（对应 Dart autoExitCompleting 触发返回；
    // 同样走 leaveRoom 先藏 Surface 再出栈，避免残留）
    LaunchedEffect(controller) {
        controller.autoExitCompleting.collect { completing ->
            if (completing) {
                Router.toast("自动退出直播间")
                leaveRoom()
            }
        }
    }

    /* ---------- 浮层弹幕开关 ---------- */

    // 浮层弹幕启停（本地开关状态；直播状态/总开关变化时重置，用户可手动切换）
    var overlayDanmakuOn by remember(controller) { mutableStateOf(true) }
    // 未开播/下播 或 弹幕总开关关闭 → 重置浮层弹幕启用（对齐原两条 collect 的合成条件）
    LaunchedEffect(controller, liveStatus, danmuEnable) {
        overlayDanmakuOn = liveStatus && danmuEnable
    }
    LaunchedEffect(controller, overlayDanmakuOn) {
        danmakuOverlay.danmakuEnabled = overlayDanmakuOn
    }

    // 浮层弹幕直通（task 10-20-live-playback-power-optimization R3.1/R3.2）：
    // 控制器在屏蔽/去重通过后立即 `tryEmit` 到 danmakuEvents，此处收集即入轨（0 延迟）。
    // 旧实现以 `messages` 列表为源、用实例引用游标回溯整表找新增段：每条消息都要
    // `indexOfLast` 一次，且 200 条上限裁剪会让游标周期性失联导致整段弹幕断流。
    // 现在飘屏与聊天列表彻底解耦——聊天列表 100ms 批处理不会延迟飘屏，也不会触发本 effect。
    LaunchedEffect(controller, danmakuOverlay) {
        controller.danmakuEvents.collect { msg ->
            danmakuOverlay.enqueue(msg)
        }
    }
    // 换房时清掉旧房间的在飞弹幕：旧实现由「messages 被清空」顺带触发，直通通道没有
    // 列表清空信号，改为跟踪控制器房间号（首帧 "" → roomId 只是清空一个空图层）。
    LaunchedEffect(controller, rxRoomId) {
        danmakuOverlay.clear()
    }

    // 进房自动全屏已合并进上方 `LaunchedEffect(controller, videoView, enterTransitionDone)`
    // （task 10-21 R4：转场落位后再触发，避免首帧方向 IPC 打断入场动画）。

    /* ---------- 动作函数（控制层按钮回调）---------- */

    fun togglePlayPause() {
        val engine = PlayerEngine.instance
        if (engine.state.value.playing) {
            engine.pause()
        } else {
            engine.play()
        }
    }

    /** 弹幕开关：切换浮层弹幕启用状态并提示（对应 Dart setDanmakuVisible）。 */
    fun toggleDanmaku() {
        overlayDanmakuOn = !overlayDanmakuOn
        Router.toast(if (overlayDanmakuOn) "弹幕已开启" else "弹幕已关闭")
    }

    fun copyToClipboard(text: String, toast: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("livez", text))
        Router.toast(toast)
    }

    /** 复制播放直链：suspend 桥接（IO 包裹，等价旧 CoroutineScope(Main).launch + withContext(IO)）。 */
    fun copyPlayUrlToClipboard() {
        coroutineScope.launch {
            val url = withContext(Dispatchers.IO) { controller.copyPlayUrl() }
            if (url == null) {
                Router.toast("无法复制播放直链")
            } else {
                copyToClipboard(url, "已复制播放直链")
            }
        }
    }

    fun shareRoom() {
        val url = controller.shareUrl()
        if (url.isNullOrEmpty()) {
            Router.toast("暂时没有可分享的链接")
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        context.startActivity(Intent.createChooser(intent, "分享直播间"))
    }

    /* ---------- 弹层状态（单状态驱动，见 ui/components/LiveRoomDialogs.kt）---------- */

    var activeSheet by rememberSaveable { mutableStateOf(RoomSheet.None) }

    // 全屏弹幕设置/直播设置右侧小窗打开时 BACK 仅关小窗（不退全屏/不退出直播间）。注册位置
    // 晚于上方两个 BackHandler：同组合内后注册的启用回调优先生效（task 08-29-nav-anim-danmu-panel）。
    // 直播设置小窗（task 08-31-live-room-menu-brightness-fix）与弹幕设置共用同一拦截条件。
    // 竖屏时的 ModalBottomSheet 是独立窗口、自行拦截 BACK，无需此处处理。
    BackHandler(
        enabled = isFullScreen &&
            (activeSheet == RoomSheet.DanmuSettings || activeSheet == RoomSheet.LiveSettings),
    ) {
        activeSheet = RoomSheet.None
    }

    /* ---------- 视频区手势 ---------- */

    // 本次拖动起点是否在左半屏（决定调亮度还是音量）；snapshot state 保证跨重组可见
    var dragStartLeftHalf by remember { mutableStateOf(false) }

    // 竖滑手势快照（对齐 Dart onVerticalDragStart）：**开始时一次性读取**，更新期绝不
    // 回读系统——根因说明（真机反馈：大幅滑动音量卡在固定档位）：每帧 getStreamVolume 的
    // 回读会与 setStreamVolume 的异步生效竞速，快速滑动时反复基于旧值计算，导致目标被
    // 钉在个别档位。原项目模型：起点快照 + 绝对位移映射，系统是否已同步不影响下个目标。
    var verticalDragUsable by remember { mutableStateOf(false) }
    var volumeStartY by remember { mutableFloatStateOf(0f) }
    var gestureVolumeExtent by remember { mutableFloatStateOf(1f) }
    var startVolume01 by remember { mutableFloatStateOf(0f) }
    var startBrightness01 by remember { mutableFloatStateOf(0.5f) }
    var volumeLastLevel by remember { mutableIntStateOf(-1) }

    // 音量系统设置节流（对齐 Dart DelayedThrottle(200)）：首调立即生效；冷却期内的新档位
    // 只保存最后一个、冷却结束尾随补发——快速滑动时丢弃中间档、终点档位必然落到系统，
    // 同时避免密集跨进程调用造成的乱序闪烁。
    val volumeSetThrottle = remember {
        VolumeSetThrottle(delayMs = 200L) { level ->
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max > 0) {
                am.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    (level / 100f * max).roundToInt().coerceIn(0, max),
                    0,
                )
            }
        }
    }

    /**
     * 手势判定（替代已删除的手写 GestureRouter，改用 Compose 内建检测器）：
     * - 单击：显隐控制层；双击：切换全屏（playerGestureControlEnable 总控）；
     * - 垂直滑动：按 down 点 x 分左右半屏 → 左亮度 / 右音量；
     *   两者同为**绝对位移映射**（对齐 Dart setGestureBrightness/setGestureVolume）：
     *   上滑增、下滑减；起点 y 不在视口中间 2/4 区域则本次竖滑不调节（对齐原项目约束 B）。
     */
    val videoGestureModifier = Modifier
        .pointerInput(context) {
            detectVerticalDragGestures(
                onDragStart = { offset ->
                    // 每次触摸重置自动隐藏计时（等价旧 onUserTouch）
                    controlsTouchTick++
                    dragStartLeftHalf = offset.x < size.width / 2f
                    // 对齐 Dart：起点须在视口中间 2/4 区域（y ∈ [25%,75%]），否则本次不调节
                    verticalDragUsable =
                        size.height > 0 &&
                            offset.y >= size.height * 0.25f &&
                            offset.y <= size.height * 0.75f
                    // 一次性快照（详见上方根因说明）
                    volumeStartY = offset.y
                    gestureVolumeExtent = maxOf(size.height * 0.5f, 1f)
                    // 亮度起始快照（task 08-31-live-room-menu-brightness-fix）：
                    // 窗口亮度覆盖值（screenBrightness）默认 -1（BRIGHTNESS_OVERRIDE_NONE =
                    // 跟随系统），原 takeIf ?: 0.5f 恒回退 0.5 → 首次手势总从 50% 起步。
                    // 对齐 Dart ScreenBrightness.instance.application：覆盖值合法(0..1)用覆盖值，
                    // 否则读系统亮度 Settings.System.SCREEN_BRIGHTNESS（0-255）换算 0..1。
                    startBrightness01 = readStartBrightness(context)
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val amax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    startVolume01 = if (amax > 0) {
                        am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / amax
                    } else {
                        0f
                    }
                    volumeLastLevel = -1
                },
                onDragEnd = { controlsTouchTick++ },
                onDragCancel = { controlsTouchTick++ },
            ) { change, _ ->
                change.consume()
                if (!ServiceContainer.appSettings.playerGestureControlEnable.value ||
                    !verticalDragUsable
                ) {
                    return@detectVerticalDragGestures
                }
                // |位移|/满量程（满量程 = 半屏高）；上滑（y<start）增、下滑减
                val moveRatio = kotlin.math.abs(change.position.y - volumeStartY) /
                    gestureVolumeExtent
                if (dragStartLeftHalf) {
                    // 亮度：绝对映射（连续、即时 set——window 属性幂等无需量化/节流）
                    val seekB = if (change.position.y > volumeStartY) {
                        (startBrightness01 - moveRatio).coerceIn(0f, 1f)
                    } else {
                        (startBrightness01 + moveRatio).coerceIn(0f, 1f)
                    }
                    val activity = context as? Activity ?: return@detectVerticalDragGestures
                    val attributes = activity.window.attributes
                    attributes.screenBrightness = seekB
                    activity.window.attributes = attributes
                    // 亮度 = 左半屏（图标 + 进度条 + 百分比，对齐 design §2）
                    showAdjustHint(
                        AdjustHintState(
                            isVolume = false,
                            progress = seekB,
                            label = "${(seekB * 100).toInt()}%",
                        ),
                    )
                } else {
                    // 音量：绝对映射 + 5 级量化（对齐 Dart _convertVolume：(v/5).round()*5）
                    // + 去重；提示即时、真正 set 经 200ms 节流
                    val seekV = if (change.position.y > volumeStartY) {
                        (startVolume01 - moveRatio).coerceIn(0f, 1f)
                    } else {
                        (startVolume01 + moveRatio).coerceIn(0f, 1f)
                    }
                    val level = ((seekV * 100) / 5f).roundToInt() * 5
                    if (level == volumeLastLevel) return@detectVerticalDragGestures
                    volumeLastLevel = level
                    // 音量 = 右半屏；进度条取量化后的档位（与落到系统的档位一致）
                    showAdjustHint(
                        AdjustHintState(
                            isVolume = true,
                            progress = level / 100f,
                            label = "$level%",
                        ),
                    )
                    volumeSetThrottle.invoke(level)
                }
            }
        }
        .pointerInput(context) {
            detectTapGestures(
                onTap = {
                    if (controlsVisible) {
                        controlsVisible = false
                    } else {
                        controlsVisible = true
                        controlsTouchTick++
                    }
                },
                onDoubleTap = {
                    // 双击切换全屏/退出全屏（对齐现版；暂停/播放交由全屏控制层按钮）
                    if (ServiceContainer.appSettings.playerGestureControlEnable.value) {
                        toggleFullScreen()
                    }
                },
            )
        }

    /* ---------- 标签集合（无条件声明：进出全屏不丢选中态与聊天滚动位置）---------- */

    val tabs = remember(siteId, contributionRankEnable) { buildTabEntries(siteId, contributionRankEnable) }
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    // Tab 数量随贡献榜开关动态变化（关闭即少一项），下标必须夹取后再交给 TabRow/指示器/选中判定：
    // 用户可能正停在最后一项（设置）时把开关关掉，selectedTabIndex 会越界。
    // `lastIndex` 在空列表时为 -1，而 coerceIn(0, -1) 会抛异常，故 coerceAtLeast(0) 兜底
    //（buildTabEntries 恒含聊天/关注/设置，空列表实际不会出现）。
    val safeTabIndex = selectedTabIndex.coerceIn(0, tabs.lastIndex.coerceAtLeast(0))
    // 内容分支同样用夹取后的下标：否则 selectedTabIndex 越界时 selectedKey 为 null，
    // 下面的 `when (selectedKey)` **没有 else 分支** ⇒ 指示器/高亮落在最后一项、
    // 内容区却整片空白。夹取后内容与高亮始终指向同一个 Tab。
    val selectedKey = tabs.getOrNull(safeTabIndex)?.key
    // 聊天列表滚动状态提升到页面级：切走再切回聊天 Tab 位置不丢（对齐原 RecyclerView 复用）
    val chatListState = rememberLazyListState()

    /* ---------- 聊天自动跟随（对齐原项目 disableAutoScroll 机制，task 08-30）---------- */

    // 原项目机制：跟随 = 每条消息 postFrame jumpTo(maxScrollExtent)，唯一守卫是
    // disableAutoScroll（**用户意图标志**——scrollListener 仅在「用户上滑离底」置 true、
    // 「贴底」置 false；程序化滚动不影响它；列表当前位置不参与门控）。
    // task 10-20 R3.2 起：意图标志的**唯一事实源**是 controller.disableAutoScroll——
    // 页面级只写（拖动/贴底检测）不读，读取方（「↓ 回到底部」胶囊 + 自动滚底 effect）
    // 全在 ChatTabSection 内。这样全屏 / 非聊天 Tab 下既无订阅也无重组。
    var chatUserDragging by remember { mutableStateOf(false) }
    // 贴底判定（供拖动检测与调试共用；留 1 条容差）
    val chatAtBottom by remember(chatListState) {
        derivedStateOf {
            val layoutInfo = chatListState.layoutInfo
            val last = layoutInfo.visibleItemsInfo.lastOrNull()
                ?: return@derivedStateOf true
            last.index >= layoutInfo.totalItemsCount - 2 &&
                last.offset + last.size <= layoutInfo.viewportEndOffset + 4
        }
    }
    // 用户拖动检测：DragInteraction 仅来自用户触摸，requestScrollToItem 等程序化滚动
    // 不会产生——跟随自身不会误触发暂停
    LaunchedEffect(chatListState) {
        chatListState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> chatUserDragging = true
                is DragInteraction.Stop, is DragInteraction.Cancel -> chatUserDragging = false
                else -> {}
            }
        }
    }
    // 对齐原 scrollListener：拖动中离底 → 暂停跟随；贴底 → 恢复跟随。
    // 写控制器而非本地 state：StateFlow 同值不发射，重复置位无副作用。
    LaunchedEffect(chatUserDragging, chatAtBottom) {
        if (chatAtBottom) {
            if (controller.disableAutoScroll.value) Log.d("聊天跟随恢复（贴底）")
            controller.setDisableAutoScroll(false)
        } else if (chatUserDragging) {
            if (!controller.disableAutoScroll.value) Log.d("聊天跟随暂停（用户上滑离底）")
            controller.setDisableAutoScroll(true)
        }
    }

    /* ---------- 布局：LinearLayout(VERTICAL) 等价 Column ---------- */

    // 根因说明（真机反馈：横屏全屏刘海处显示白色边界）：窗口经 SHORT_EDGES 延伸进刘海区后，
    // 根容器的背景色直接决定刘海区域的观感——原根容器固定白色，主题白底从视频黑底之外
    // （含刘海延伸区）穿透出来。修复：全屏时根容器改黑色（与视频外层黑底一致），
    // 竖屏维持白色不变。

    // ---------- 版式片段（宽屏两列 / 窄屏单列共用；避免在两个分支里复制数百行）----------
    // 根修饰符（背景/共享元素/insets）由下方根容器承担，片段内不含。

    /** 标题栏片段：宽屏在播放器列顶部、窄屏在最顶部；全屏时隐藏（守卫留在片段内）。 */
    val roomTitleBar: @Composable () -> Unit = {
        // 顶栏两个图标按钮的无界圆形涟漪（PRD Requirement 1）：48dp 热区内直径 40dp 的
        // 圆形波纹，不再是无反馈的静默按钮。`ripple()` 是普通函数（material3 1.3.x），
        // remember 缓存避免每次重组新建工厂。
        val iconRipple = remember { ripple(bounded = false, radius = 20.dp) }
        if (!isFullScreen) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(48.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = iconRipple,
                            onClick = { leaveRoom() },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = CustomIcons.Back,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Text(
                    text = detail?.title?.ifEmpty { null }
                        ?: roomSeed?.title?.ifEmpty { null }
                        ?: "直播间",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        // 左右各留出超过 48dp 按钮区的边距，长标题省略而不压到按钮
                        .padding(horizontal = 56.dp),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(48.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = iconRipple,
                            onClick = { activeSheet = RoomSheet.Menu },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = CustomIcons.Menu,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }

    /** 视频区片段：布局尺寸由调用方传入（宽屏 weight 填满 / 窄屏 16:9 或全屏铺满）。 */
    val roomVideo: @Composable (Modifier) -> Unit = { outerModifier ->
        Box(
            modifier = outerModifier
                // 房间侧始终挂载：进场/退场都必须有共享节点参与配对
                .liveRoomCoverSharedElement(siteId, roomId, alwaysEnable = true)
                .then(videoGestureModifier)
                .background(Color.Black),
        ) {
            // 内层按源宽高比约束出与视频同比的区域并居中（letterbox：黑边由外层黑底留白，
            // mpv Surface 不再被拉伸）；videoAspect 为 null（未开播/未取到尺寸）时回退铺满。
            // 根因说明（真机反馈：竖屏模式画面依然拉伸）：竖屏外层是固定 16:9 框，原实现只在
            // 全屏分支做 letterbox，竖屏对非 16:9 源仍被铺满拉伸——修复后两个分支共用同一
            // 写法：竖屏布局尺寸不变（仍 16:9 黑底框），内层按源比例居中、上下留黑边。
            Box(
                modifier = if (videoAspect != null) {
                    Modifier
                        .align(Alignment.Center)
                        .aspectRatio(videoAspect)
                } else {
                    Modifier.fillMaxSize()
                },
            ) {
                // 封面占位（一镜到底，task 09-12-shared-element-transition）：转场期间与
                // 卡片封面同图连续显示（SurfaceView 内容不参与 View 过渡，PDF 结论），
                // mpv 首帧渲染（video-params 到达 → width 非空）后隐藏，视频淡入接棒。
                // 注意必须放在 AndroidView 之前：SurfaceView 图层位于窗口之下，占位图
                // 在其渲染前显示、渲染后必须移除，否则会永久盖住视频。
                //
                // 首帧平滑淡出（task 10-17-ui-animation-fluency-polish）：首帧到达时
                // 180ms 淡出，alpha 归零后**从组合树移除**（`coverAlpha > 0.01f` 守卫 ——
                // tween 结束时值精确落在 0f，故必然被移除；这条守卫就是上面「渲染后必须移除」
                // 规范的落实，**不能**只把 alpha 留在 0 而让占位图常驻组合）。
                val roomCover = detail?.cover?.takeIf { it.isNotEmpty() }
                    ?: roomSeed?.cover.orEmpty()
                // 退场立即恢复封面（snap）：退回列表的一镜到底动画要全程显示封面图，
                // 不能是「首帧黑色 + 180ms 淡入」。稳态仍按首帧到达淡出（180ms）。
                val showCover = leaving || playerState.width == null
                val coverAlpha by animateFloatAsState(
                    targetValue = if (showCover) 1f else 0f,
                    animationSpec = if (leaving) snap() else tween(durationMillis = 180),
                    label = "liveRoomCoverAlpha",
                )
                if (roomCover.isNotEmpty() && coverAlpha > 0.01f) {
                    NetImage(
                        url = roomCover,
                        modifier = Modifier
                            .fillMaxSize()
                            // alpha 走 graphicsLayer（绘制期生效），不触发 NetImage 重组
                            .graphicsLayer { alpha = coverAlpha },
                        contentScale = ContentScale.Crop,
                    )
                }
                // mpv 渲染内核（互操作白名单）：Surface 附加由 MpvVideoView 的
                // SurfaceHolder.Callback 自动处理（surfaceCreated → attachSurface）。
                //
                // 转场窗口内**不挂载**（task 10-21-tablet-shared-element-transition-fluency R2）：
                // SurfaceView 是独立图层，280ms 内逐帧尺寸变化会触发 updateSurface() 跨进程事务 +
                // setSurfaceSize JNI；转场期间屏幕上只是封面在飞（ScaleToBounds 缩放），
                // 落位后再一次性挂载即可，观感无差、帧预算完全不同。
                val attachNativeViews = enterTransitionDone && !leaving
                if (attachNativeViews) {
                    AndroidView(
                        factory = { videoView },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            // 浮层弹幕：铺满整个视频区（**外层**，全屏时含 letterbox 黑边）。
            // 根因说明（task danmaku-overlay-fix：全屏弹幕从右侧直接出现/靠近左侧直接消失）：
            // 原实现把弹幕层放在上方 letterbox（aspectRatio(videoAspect)）内层——全屏横屏时
            // 弹幕层窄于屏幕（左右黑边），弹幕从 letterbox 右缘滑入、出左缘即越界消失，
            // 观感为「未从屏幕外移入/未移出屏幕」。对齐原项目：canvas_danmaku 弹幕层 =
            // 整个播放器区域（Positioned.fill，全屏含黑边），弹幕从物理屏右缘外滑入、
            // 完全走出左缘才消失。非交互层不拦截点击。同样受转场门控（见上）。
            if (enterTransitionDone && !leaving) {
                AndroidView(
                    factory = { danmakuOverlay },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // 未开播覆盖层：白字居中（对应原 unliveOverlay）。与另两个居中覆盖层严格互斥——
            // 详情未就绪（detail == null）或刷新中让位给转圈（同处居中位置会互相压字），
            // 错误层显示时让位，且加载失败时本就无法断言「未开播」。
            //（task 10-18-ui-closure-leftovers-fixes-and-decisions：收紧前只看 `!liveStatus`，
            //  首次进房/刷新已下播房间时会与转圈同帧叠印。）
            if (!liveStatus && detail != null && !refreshing && !showRoomError) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = "未开播", fontSize = 16.sp, color = Color.White)
                }
            }
            // 直播间加载/刷新中：居中转圈（task player-ui-polish：刷新与加载使用同样的动画；
            // detail 未就绪或刷新中且无错误态 → 转圈；完成后即消失）。
            // 渐显渐隐（task 10-17-ui-animation-fluency-polish）：进 150ms / 出 200ms，
            // 避免刷新完成瞬间转圈硬切消失。visible=false 时 AnimatedVisibility 不渲染该节点。
            // 与下方错误层互斥：`!showRoomError` 取代原 `!loadError`（见 showRoomError 定义处）。
            androidx.compose.animation.AnimatedVisibility(
                // 转场窗口内不显示转圈（task 10-21 R2）：无限旋转动画会让共享覆盖层的
                // DisplayList 逐帧失效重录，144Hz 下等于每帧多一次全屏重录。
                visible = enterTransitionDone && !leaving &&
                    (detail == null || refreshing) && !showRoomError,
                modifier = Modifier.fillMaxSize(),
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(200)),
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
            // 错误/断流覆盖层（task 10-17-ui-material3-details-and-redesign）：加载失败或
            // 播放断流（errorMsg 非空）且非刷新中 → 居中给出半透明圆角容器 + 错误文案 +
            // M3「重试」按钮。点击重试与竖屏底栏刷新按钮**同一条通路**
            //（refreshing=true → controller.refreshRoom()，8s 兜底复位见上方 refreshing 区），
            // 顺带让转圈接管视觉反馈。
            androidx.compose.animation.AnimatedVisibility(
                visible = showRoomError,
                modifier = Modifier.fillMaxSize(),
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(200)),
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        modifier = Modifier
                            // 半透明深底（与手势调节胶囊同源色 0xCC000000）：叠在恒黑的
                            // 视频区上，深浅两套主题下对比都足够，故不跟随主题取色。
                            .background(Color(0xCC000000), shape = RoundedCornerShape(12.dp))
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = errorMsg.ifEmpty { "加载失败" },
                            fontSize = 14.sp,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                        )
                        FilledTonalButton(
                            onClick = {
                                refreshing = true
                                controller.refreshRoom()
                            },
                            modifier = Modifier.padding(top = 12.dp),
                        ) {
                            Text(text = "重试", fontSize = 14.sp)
                        }
                    }
                }
            }
            // 调节提示胶囊（亮度/音量）：独立于控件显隐（task player-ui-polish）——
            // 控制层 5s 自动隐藏后滑动调音量/亮度，提示仍显示；1.2s 自动消失由上方 job 管理。
            // 动画（task 10-17-ui-animation-fluency-polish）：进 120ms 淡入 + 0.92 缩放，
            // 出 180ms 淡出；退场期间用 lastAdjustHint 快照渲染内容（否则淡出的是空胶囊）。
            androidx.compose.animation.AnimatedVisibility(
                visible = adjustHint != null,
                modifier = Modifier.align(Alignment.Center),
                enter = fadeIn(tween(120)) + scaleIn(tween(120), initialScale = 0.92f),
                exit = fadeOut(tween(180)),
            ) {
                val display = adjustHint ?: lastAdjustHint
                if (display != null) {
                    AdjustHintCapsule(display)
                }
            }
            // 控制层覆盖（Compose 版 PlayerControlsOverlay；显隐/计时由本页面驱动）
            PlayerControlsOverlay(
                visible = controlsVisible,
                fullScreen = isFullScreen,
                playing = playerState.playing,
                liveStatus = liveStatus,
                danmakuEnabled = overlayDanmakuOn,
                // 传 Flow 而非值：订阅在控制层内部（隐藏时组件不组合 → 不收集）
                durationFlow = controller.liveDuration,
                qualityName = qualityName,
                lineName = lineName,
                title = detail?.title.orEmpty(),
                onBackClick = { toggleFullScreen() }, // 顶栏返回 = 退出全屏（仅全屏显示顶栏）
                onRefreshClick = {
                    refreshing = true
                    controller.refreshRoom()
                },
                onPlayPauseClick = { togglePlayPause() },
                onDanmakuToggle = { toggleDanmaku() },
                onDanmakuSettingsClick = { activeSheet = RoomSheet.DanmuSettings },
                onQualityClick = {
                    if (controller.qualites.value.isEmpty()) {
                        Router.toast("暂无清晰度信息")
                    } else {
                        activeSheet = RoomSheet.Quality
                    }
                },
                onLineClick = {
                    if (controller.playUrls.value.isEmpty()) {
                        Router.toast("暂无播放线路")
                    } else {
                        activeSheet = RoomSheet.Line
                    }
                },
                onMenuClick = { activeSheet = RoomSheet.Menu },
                onFullScreenClick = { toggleFullScreen() },
            )

            // 全屏弹幕设置右侧小窗（task 08-29-nav-anim-danmu-panel，用户反馈：
            // 全屏时从底部上滑改为原项目 showRightDialog 同款右侧小窗）：
            // - 右缘滑入/滑出 200ms（对齐原项目 animationTime）；透明遮罩点外部关闭；
            // - 挂在视频区 Box 内（全屏时视频区铺满整窗），与控制层同为 Compose 覆盖层，
            //   绘制于 mpv SurfaceView 之上（PlayerControlsOverlay 已验证的同款模式）；
            // - 竖屏形态走根级 when 分支的 ModalBottomSheet（下方加了 !isFullScreen 守卫）。
            // 全限定调用的真实原因（勘误，见 ui-compose.md 条目 29 §8）：**本文件没有
            // import 顶层 AnimatedVisibility**，故一律全限定；此处的作用域是 Box（BoxScope），
            // 并不是 ColumnScope，故「被 ColumnScope 扩展遮蔽」的旧归因是注释漂移。
            // 本文件内的 AnimatedVisibility 全部保持全限定写法（勿改成 import 混用）。
            androidx.compose.animation.AnimatedVisibility(
                visible = isFullScreen && activeSheet == RoomSheet.DanmuSettings,
                enter = slideInHorizontally(animationSpec = tween(200)) { fullX -> fullX },
                exit = slideOutHorizontally(animationSpec = tween(200)) { fullX -> fullX },
            ) {
                DanmuSettingsSidePanel(
                    viewportHeight = danmakuOverlay.height.toFloat(),
                    onDismiss = { activeSheet = RoomSheet.None },
                )
            }
            // 全屏直播设置右侧小窗（task 08-31-live-room-menu-brightness-fix：
            // 形态对齐弹幕设置——全屏不再弹底部 ModalBottomSheet，改右侧小窗）。
            // 与弹幕设置小窗同款壳层/动画/遮罩；两者经 activeSheet 互斥，不会同时显示。
            androidx.compose.animation.AnimatedVisibility(
                visible = isFullScreen && activeSheet == RoomSheet.LiveSettings,
                enter = slideInHorizontally(animationSpec = tween(200)) { fullX -> fullX },
                exit = slideOutHorizontally(animationSpec = tween(200)) { fullX -> fullX },
            ) {
                LiveSettingsSidePanel(
                    controller = controller,
                    onDismiss = { activeSheet = RoomSheet.None },
                )
            }
        }
    }

    /** 信息栏片段（对应 Dart buildUserProfile）：头像/名称/平台 + 关注 + 人气。
     *  守卫由调用方决定：窄屏非全屏显示；宽屏恒显示（右侧面板的组成部分）。 */
    val roomInfoBar: @Composable () -> Unit = {
        // 人气值订阅下沉到本片段（task 10-20 R3.2）：全屏（片段不组合）与宽屏右栏隐藏时
        // 不再产生订阅；online 变化只重组这一行，不带上整页。
        val online by controller.online.collectAsStateWithLifecycle()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NetImage(
                url = detail?.userAvatar?.takeIf { it.isNotEmpty() } ?: roomSeed?.userAvatar.orEmpty(),
                modifier = Modifier.size(48.dp),
                isRound = true,
            )
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    text = detail?.userName?.ifEmpty { null } ?: roomSeed?.userName.orEmpty(),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 平台行：图标 + 平台名
                    Image(
                        painter = painterResource(site.logoRes),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(text = site.name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // 关注按钮（task follow-tab-and-room-follow）：未关注 = 灰色空心爱心
            // HeartOutline，已关注 = 红色实心爱心 HeartFilled；点击切换关注状态。
            // task 10-17-ui-material3-details-and-redesign 升级：40dp 圆形触控热区
            //（原 31dp 方角热区，小于无障碍最小触控尺寸）+ 颜色渐变 + 缩放微动画。
            // 颜色过渡：Icon 的 ColorFilter 染色决定实际颜色（tint=Unspecified 会回退为
            // LocalContentColor 染黑）——已关注红 #FF0000；未关注浅色灰 #808080，
            // 深色下浅灰 #B0B0B0（深灰底对比不足，task 08-29-dark-mode 用户定标；
            // 经 LocalAppDarkTheme 感知手动切换）。target 值语义与旧写法逐字相同，
            // 仅改为经 animateColorAsState 过渡（关注态切换时不再硬切）。
            val followTint by animateColorAsState(
                targetValue = if (followed) {
                    Color(0xFFFF0000)
                } else if (LocalAppDarkTheme.current) {
                    Color(0xFFB0B0B0)
                } else {
                    Color(0xFF808080)
                },
                animationSpec = tween(durationMillis = 200),
                label = "roomFollowTint",
            )
            // 缩放微动画：未关注 0.94 → 已关注 1.0（点击瞬间的轻微放大反馈；
            // 落在绘制期，不参与本行重组之外的任何布局变化）
            val followScale by animateFloatAsState(
                targetValue = if (followed) 1f else 0.94f,
                animationSpec = tween(durationMillis = 200),
                label = "roomFollowScale",
            )
            Box(
                modifier = Modifier
                    .size(40.dp)
                    // clip 先于 clickable：涟漪被圆角裁剪，不溢出为直角方形
                    .clip(CircleShape)
                    .clickable {
                        if (followed) {
                            controller.removeFollowUser()
                        } else {
                            controller.followUser()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (followed) CustomIcons.HeartFilled else CustomIcons.HeartOutline,
                    contentDescription = null,
                    tint = followTint,
                    modifier = Modifier
                        .size(23.dp)
                        .graphicsLayer {
                            scaleX = followScale
                            scaleY = followScale
                        },
                )
            }
            // 人气值：矢量火焰图标（CustomIcons.Flame）替代 "🔥" Emoji
            //（task 10-17-ui-material3-details-and-redesign）——Emoji 在部分
            // 定制 ROM 字体下字形不一致/缺字，矢量图标跨设备稳定且可 tint。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = CustomIcons.Flame,
                    contentDescription = null,
                    // 人气橙色（现版 #FF9800，取自主题常量 LiveOrange）
                    tint = LiveOrange,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.size(2.dp))
                Text(
                    text = Utils.onlineToString(online),
                    fontSize = 14.sp,
                    color = LiveOrange,
                )
            }
        }
    }

    /** 标签栏 + 内容区片段：weight 需要 Column 作用域，故声明为 ColumnScope 扩展，
     *  由调用处的 Column 提供（宽屏右列 / 窄屏根列均满足）。 */
    val roomTabsAndContent: @Composable ColumnScope.() -> Unit = {
        // 一律用夹取过的 safeTabIndex（Tab 数量随贡献榜开关变化，见声明处注释）
        TabRow(
            selectedTabIndex = safeTabIndex,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.secondary,
            indicator = { tabPositions ->
                Box(
                    modifier = Modifier
                        .tabIndicatorOffset(tabPositions[safeTabIndex])
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.secondary),
                )
            },
        ) {
            tabs.forEachIndexed { index, tab ->
                Tab(
                    selected = safeTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = {
                        Text(
                            text = tab.label,
                            fontSize = 14.sp,
                            // 选中蓝 / 未选中灰（对齐原 setTabTextColors）
                            color = if (safeTabIndex == index) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when (selectedKey) {
                // 聊天内容整块下沉为独立 composable（task 10-20 R3.2）：messages 订阅、
                // 自动滚底与「↓ 回到底部」全部在 ChatTabSection 内部，只有本分支组合时才生效。
                TAB_KEY_CHAT -> ChatTabSection(
                    controller = controller,
                    listState = chatListState,
                )
                TAB_KEY_SUPER_CHAT -> SuperChatTabContent(superChats)
                TAB_KEY_FOLLOW -> FollowTabContent(
                    items = followLive + followNotLive,
                    // 当前房间取控制器状态（随 resetRoom 换房更新），路由参数仅作首帧兜底
                    currentSiteId = rxSite?.id ?: siteId,
                    currentRoomId = rxRoomId.ifEmpty { roomId },
                    onOpenRoom = { follow ->
                        val target = Sites.allSites[follow.siteId] ?: return@FollowTabContent
                        controller.resetRoom(target, follow.roomId)
                    },
                )
                TAB_KEY_CONTRIBUTION_RANK -> ContributionRankTabContent(
                    items = contributionUsers,
                    // 三态：请求中，或「尚未开始请求且详情没报错」→ 转圈；其余（请求已结束、
                    // 或详情失败）→ 「暂无贡献榜数据」。少了后一项首帧会闪空态，少了 !loadError
                    // 则详情失败时会永远转圈。
                    loading = contributionRankLoading || (!contributionRankFetched && !loadError),
                )
                TAB_KEY_SETTINGS -> SettingsTabContent(
                    controller = controller,
                    viewportHeight = danmakuOverlay.height.toFloat(),
                    qualityText = qualityName,
                    lineText = lineName,
                    onOpenQuality = {
                        // 空列表拦截与 ⋮ 菜单/控制层入口一致（toast 后不打开）
                        if (controller.qualites.value.isEmpty()) {
                            Router.toast("暂无清晰度信息")
                        } else {
                            activeSheet = RoomSheet.Quality
                        }
                    },
                    onOpenLine = {
                        if (controller.playUrls.value.isEmpty()) {
                            Router.toast("暂无播放线路")
                        } else {
                            activeSheet = RoomSheet.Line
                        }
                    },
                    onOpenDanmuShield = { activeSheet = RoomSheet.DanmuShield },
                )
            }
        }
    }

    // ---------- 根容器：宽屏（平板横屏）两列 / 窄屏单列 ----------
    // 背景色沿用原根容器写法：全屏黑底（刘海延伸区观感），竖屏主题底色。
    val rootModifier = modifier
        .liveRoomContainerSharedElement(siteId, roomId, alwaysEnable = true)
        .fillMaxSize()
        .background(if (isFullScreen) Color.Black else MaterialTheme.colorScheme.surface)
        .statusBarsPadding()

    // 右侧面板宽度：原项目固定 300dp；此处加屏宽比例上限，避免小尺寸平板被面板吃掉过多
    val panelWidth = minOf(300.dp, LocalConfiguration.current.screenWidthDp.dp * 0.35f)

    // 分栏**仅非全屏**（用户决策 2026-10-07，反转 task 10-07-tablet-landscape-layout
    // 原「全屏也分栏」决策）：平板横屏点全屏 = 播放器独占整屏，与手机全屏语义一致。
    //
    // ⚠ **全屏不换分支**（task 10-07-home-jank-jump-clip-fullscreen-video 修复）：
    // 曾把条件写成 `useWideLayout() && !isFullScreen`（让全屏走下面的窄屏分支），结果进/出
    // 全屏会让 roomVideo 里的 AndroidView 跨组合位置重建 —— AndroidView 节点与 LayoutNode
    // 生命周期绑定（AndroidComposeView.addAndroidView/removeAndroidView 在 onPreAttach/onDetach
    // 调用），节点 detach 使 SurfaceView 脱离窗口 → MpvVideoView.surfaceDestroyed →
    // PlayerEngine 切 vo=null（纯音频续播），重挂后才恢复 → 真机表现为「切全屏卡顿一下 +
    // 播放画面缺失」。现在全屏只隐藏标题栏与右侧面板：视频仍是左列子级（组合位置恒定、
    // Surface 不重建），右列不组合后 weight(1f) 自然占满整行 → 播放器独占整屏。
    //
    // ⚠ **宽/窄也不再换分支**（task 10-16-live-room-pager-review-fixes，闭合上条的「遗留」）：
    // 上面只修好了 `isFullScreen` 这一维；外层仍是一个以 `useWideLayout()` 为条件的宽/窄
    // 分支结构，平板**竖↔横旋转**使 `useWideLayout()` 翻转时依旧是同一条 detach 路径（症状：平板竖屏
    // 点全屏触发旋转进横屏、手动旋转屏、退出全屏回竖屏，画面缺失只剩声音）。根治办法是让
    // `roomVideo` 的**调用位置与组合深度恒定**：宽/窄合并为唯一的外层
    // `Column -> Row -> Column -> roomVideo`，两种版式的差异全部表达为
    // 「**同层兄弟的增删 + 同层 Modifier 变化**」——
    //   ① 右侧面板（信息栏 + 标签内容）= Row 内 `if (wideLayout && !isFullScreen)`；
    //   ② 窄屏下方信息栏 + 标签区 = 外层 Column 内 `if (!wideLayout && !isFullScreen)`；
    //   ③ 各容器与视频的 Modifier 由 `videoFillsSpace = wideLayout || isFullScreen` 切换。
    // 三条分支增删的都是**视频的兄弟节点**（①②在视频同级，③只改 Modifier、不改 call site），
    // 视频自身的组合槽位与 `wideLayout` / `isFullScreen` 的取值完全无关，故任意形态互切都
    // 不会让 AndroidView 节点 detach。这也是「为什么合并后不再有跨分支位移」。
    // 逐形态等价性推演（合并后 ≡ 合并前）：
    //  · 窄屏竖屏非全屏（wide=false, full=false）：videoFillsSpace=false → Row 只吃
    //    `fillMaxWidth()`（不权重），左列 `weight(1f)`（在主轴上吃满宽、高度随内容）；
    //    视频 `fillMaxWidth().aspectRatio(16/9)` → 与合并前「根 Column 内的 16:9 视频」同宽同高；
    //    信息栏/标签区走 ② 分支，`roomTabsAndContent` 的 `weight(1f)` 仍落在根 Column 上
    //    → 占满剩余高度。逐项与合并前一致。
    //  · 窄屏全屏（wide=false, full=true）：videoFillsSpace=true → Row `weight(1f).fillMaxWidth()`
    //    吃掉全部剩余高度、左列 `weight(1f).fillMaxHeight()`、视频 `weight(1f).fillMaxWidth()`
    //    → 播放器铺满整屏（标题栏片段自身守卫、①②都不组合，根 Column 无其它子级）
    //    → 与合并前「视频 + `fillMaxSize` 独占整屏」等价。
    //  · 宽屏非全屏（wide=true, full=false）：①为真、②为假 → Row／左列／视频／右列的 Modifier
    //    与合并前宽屏分支**逐字相同**（Row weight+fillMaxWidth、左列 weight+fillMaxHeight、
    //    视频 weight+fillMaxWidth、右列 width(panelWidth)+fillMaxHeight）。
    //  · 宽屏全屏（wide=true, full=true）：videoFillsSpace=true、①为假、②为假 → 与合并前
    //    宽屏分支「全屏只隐藏标题栏与右列」的写法等价（播放器独占整屏）。
    //  · 旋转使 `useWideLayout()` 翻转（「窄屏非全屏 ↔ 宽屏非全屏」「窄屏全屏 ↔ 宽屏全屏」）：
    //    只有 Row/左列的 Modifier 改变（重测量、不重建节点），视频调用位置不动
    //    → 不再有跨分支位移（此即本次闭合点）。
    val wideLayout = useWideLayout()
    val videoFillsSpace = wideLayout || isFullScreen

    Column(modifier = rootModifier) {
        // 顶栏**全宽**（用户决策 2026-10-07）：分栏态顶栏横跨整个顶部，其下才是
        // 「播放器居左 / 信息栏 + 标签内容居右」；不再让标题栏只占左列、信息栏顶到最上。
        // 全屏时顶栏片段自身守卫不组合 → 下面的 Row/左列自然独占整屏。
        roomTitleBar()
        Row(
            // 宽屏/全屏：吃掉标题栏以下的全部高度；窄屏非全屏：高度随内容，让位给下方信息栏
            modifier = if (videoFillsSpace) {
                Modifier.weight(1f).fillMaxWidth()
            } else {
                Modifier.fillMaxWidth()
            },
        ) {
            // 左列：播放器（宽屏/全屏吃满剩余高度；窄屏非全屏高度由 16:9 视频决定）
            Column(
                modifier = if (videoFillsSpace) {
                    Modifier.weight(1f).fillMaxHeight()
                } else {
                    Modifier.weight(1f)
                },
            ) {
                roomVideo(
                    if (videoFillsSpace) {
                        Modifier.weight(1f).fillMaxWidth()
                    } else {
                        Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    },
                )
            }
            // 右列（主播信息栏 + 标签栏 + 消息区）**仅宽屏且非全屏**组合：
            // 既满足「全屏 = 播放器独占整屏」，又不为此切换视频所在分支（见上方根因说明）。
            if (wideLayout && !isFullScreen) {
                Column(
                    modifier = Modifier
                        .width(panelWidth)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    // 信息栏上下各一条横细线（用户要求 2026-10-07）：上 = 顶栏与信息栏之间，
                    // 下 = 信息栏与标签栏之间。播放器与右栏之间**不做竖线**（用户要求删除，
                    // 靠左右底色/内容边界区分）。色调 = `outlineVariant` × alpha 0.5（用户决策
                    // 「调暗、不那么明显」；用 alpha 而非写死颜色，深浅两套主题下都只是「比背景
                    // 略深/略浅的一线」）。改用 Material3 `HorizontalDivider`（同厚度同色，其内部
                    // 即 `fillMaxWidth().height(thickness)`）收敛本文件原先的私有细线实现
                    // （task 10-16-live-room-pager-review-fixes Finding 7）。
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    roomInfoBar()
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    roomTabsAndContent()
                }
            }
        }
        // 窄屏非全屏：信息栏与标签区在播放器**下方**（宽屏已移入右列，全屏不显示）
        if (!wideLayout && !isFullScreen) {
            roomInfoBar()
            roomTabsAndContent()
        }
    }

    /* ---------- 弹层渲染（单状态驱动；字段/setter 对照旧 LiveRoomSheets 1:1）---------- */

    when (activeSheet) {
        RoomSheet.None -> {}

        RoomSheet.Menu -> RoomMenuDialog(
            // 全屏/非全屏菜单项集合不同（task 08-31-live-room-menu-brightness-fix）：
            // 全屏去掉 清晰度/播放线路/弹幕设置（控制层底栏已有入口），
            // 非全屏去掉 刷新直播间/清晰度/弹幕设置（竖屏底栏有刷新，清晰度/弹幕设置与控制层重复）。
            items = if (isFullScreen) ROOM_MENU_ITEMS_FULLSCREEN else ROOM_MENU_ITEMS_PORTRAIT,
            onAction = { action ->
                activeSheet = RoomSheet.None
                when (action) {
                    RoomMenuAction.Quality -> if (controller.qualites.value.isEmpty()) {
                        Router.toast("暂无清晰度信息")
                    } else {
                        activeSheet = RoomSheet.Quality
                    }
                    RoomMenuAction.Line -> if (controller.playUrls.value.isEmpty()) {
                        Router.toast("暂无播放线路")
                    } else {
                        activeSheet = RoomSheet.Line
                    }
                    RoomMenuAction.DanmuSettings -> activeSheet = RoomSheet.DanmuSettings
                    RoomMenuAction.LiveSettings -> activeSheet = RoomSheet.LiveSettings
                    RoomMenuAction.DanmuShield -> activeSheet = RoomSheet.DanmuShield
                    RoomMenuAction.CopyLink -> copyToClipboard(controller.copyUrl() ?: "", "已复制直播间链接")
                    RoomMenuAction.CopyPlayUrl -> copyPlayUrlToClipboard()
                    RoomMenuAction.Share -> shareRoom()
                    RoomMenuAction.Refresh -> controller.refreshRoom()
                }
            },
            onDismiss = { activeSheet = RoomSheet.None },
        )

        RoomSheet.Quality -> QualityDialog(
            names = qualites.map { it.quality },
            // 弹层打开期间列表被清空（如刷新）时不崩溃：空列表回退 0（打开入口已拦截空列表）
            checkedIndex = if (qualites.isEmpty()) {
                0
            } else {
                controller.currentQuality.coerceIn(0, qualites.lastIndex)
            },
            onSelect = { which ->
                activeSheet = RoomSheet.None
                if (which != controller.currentQuality) {
                    controller.currentQuality = which
                    controller.getPlayUrl()
                }
            },
            onDismiss = { activeSheet = RoomSheet.None },
        )

        RoomSheet.Line -> LineDialog(
            count = controller.playUrls.value.size,
            // 弹层打开期间列表被清空（如刷新）时不崩溃：空列表回退 0（打开入口已拦截空列表）
            checkedIndex = if (controller.playUrls.value.isEmpty()) {
                0
            } else {
                controller.currentLineIndex.coerceIn(0, controller.playUrls.value.size - 1)
            },
            onSelect = { which ->
                activeSheet = RoomSheet.None
                if (which != controller.currentLineIndex) {
                    controller.changePlayLine(which)
                }
            },
            onDismiss = { activeSheet = RoomSheet.None },
        )

        RoomSheet.DanmuSettings -> if (!isFullScreen) {
            // 竖屏 = 底部弹窗；全屏形态由视频区内 AnimatedVisibility 右侧小窗渲染
            //（见 PlayerControlsOverlay 之后）。守卫防止全屏/竖屏切换瞬间双渲染。
            DanmuSettingsSheet(
                // 传入当前弹幕层高度（px）：弹层据此计算「显示几行」动态上限；
                // 全屏/竖屏状态不同 → 打开弹层时的上限不同（对齐原项目传 previewViewportHeight）
                viewportHeight = danmakuOverlay.height.toFloat(),
                onDismiss = { activeSheet = RoomSheet.None },
            )
        }

        RoomSheet.LiveSettings -> if (!isFullScreen) {
            // 竖屏 = 底部弹窗；全屏形态由视频区内 AnimatedVisibility 右侧小窗渲染
            //（见 PlayerControlsOverlay 之后）。守卫防止全屏/竖屏切换瞬间双渲染
            //（对齐 RoomSheet.DanmuSettings 同款处理，task 08-31-live-room-menu-brightness-fix）。
            LiveSettingsSheet(
                controller = controller,
                onDismiss = { activeSheet = RoomSheet.None },
            )
        }

        RoomSheet.DanmuShield -> DanmuShieldSheet(onDismiss = { activeSheet = RoomSheet.None })
    }
}

/**
 * 音量系统设置节流 —— 对齐 Dart DelayedThrottle(200)（custom_throttle.dart）的语义：
 * 空闲时**立即执行**并进入冷却；冷却期内的调用只保存最后一个；
 * 冷却结束后尾随执行最近一次（保证终点档位必然落到系统，同时丢弃中间态）。
 */
private class VolumeSetThrottle(
    private val delayMs: Long = 200,
    private val apply: (Int) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var busy = false
    private var pending: Int? = null

    private val tailRunnable = Runnable {
        busy = false
        val next = pending
        pending = null
        if (next != null) {
            invoke(next)
        }
    }

    fun invoke(level: Int) {
        if (busy) {
            pending = level
            return
        }
        busy = true
        apply(level)
        mainHandler.postDelayed(tailRunnable, delayMs)
    }
}

/**
 * 亮度手势起始快照（0..1）—— task 08-31-live-room-menu-brightness-fix：
 * 窗口亮度覆盖值（screenBrightness）在 0..1 时用它；否则（默认 -1 = 跟随系统）
 * 读系统亮度 `Settings.System.SCREEN_BRIGHTNESS`（0-255，只读无需权限）换算 0..1，
 * 读失败回退 0.5。语义对齐 Dart `ScreenBrightness.instance.application`。
 */
private fun readStartBrightness(context: Context): Float {
    val windowBrightness = (context as? Activity)?.window?.attributes?.screenBrightness
    if (windowBrightness != null && windowBrightness in 0f..1f) {
        return windowBrightness
    }
    val sys = runCatching {
        Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            255,
        )
    }.getOrDefault(255)
    return (sys.coerceIn(0, 255) / 255f).coerceIn(0f, 1f)
}

/* ---------- 标签集合（对应 Dart 默认标签顺序 + 条件过滤）---------- */

private const val TAB_KEY_CHAT = "chat"
private const val TAB_KEY_SUPER_CHAT = "super_chat"
private const val TAB_KEY_FOLLOW = "follow"
private const val TAB_KEY_CONTRIBUTION_RANK = "contribution_rank"
private const val TAB_KEY_SETTINGS = "settings"

/** 直播间标签项：key 用于内容切换，label 为展示文案（各平台叫法不同）。 */
private data class RoomTab(val key: String, val label: String)

/**
 * 标签集合：聊天 / SC（仅 bilibili 叫「SC」、huya 叫「头条」）/ 关注 /
 * 贡献榜（douyu 叫「亲密榜」，受设置开关 gate）/ 设置——对照原 buildTabEntries 1:1。
 *
 * @param contributionRankEnable 设置页「贡献榜」开关：关闭时不加贡献榜标签页，
 *        对齐 Dart 上游（live_room_page.dart 开关关闭即跳过该 Tab）。
 *        注意 Tab 数量因此会动态变化，调用处的选中下标必须夹取（见 safeTabIndex）。
 */
private fun buildTabEntries(siteId: String, contributionRankEnable: Boolean): List<RoomTab> {
    val tabs = mutableListOf<RoomTab>()
    tabs += RoomTab(TAB_KEY_CHAT, "聊天")
    when (siteId) {
        Constant.kBiliBili -> tabs += RoomTab(TAB_KEY_SUPER_CHAT, "SC")
        Constant.kHuya -> tabs += RoomTab(TAB_KEY_SUPER_CHAT, "头条")
    }
    tabs += RoomTab(TAB_KEY_FOLLOW, "关注")
    if (contributionRankEnable) {
        tabs += RoomTab(
            TAB_KEY_CONTRIBUTION_RANK,
            if (siteId == Constant.kDouyu) "亲密榜" else "贡献榜",
        )
    }
    tabs += RoomTab(TAB_KEY_SETTINGS, "设置")
    return tabs
}

/* ---------- 聊天 Tab：弹幕列表 ---------- */

/**
 * 聊天 Tab 容器（task 10-20-live-playback-power-optimization R3.2）：
 * `messages` 订阅与自动滚底 effect 全部下沉在这里，只有聊天 Tab 真正组合时才存在。
 *
 * 效果：
 * - 全屏模式（`roomTabsAndContent` 整块不组合）⇒ 对 100ms 批次消息 **0 订阅 / 0 重组**；
 * - 停在 SC / 关注 / 贡献榜 / 设置 Tab ⇒ 分支不组合，同样 0 订阅；
 * - 从全屏或其它 Tab 切回聊天时，本组件重新组合 → 订阅拿到当前列表 + effect 立即贴底。
 *
 * 跟随意图标志 `controller.disableAutoScroll` 由页面级拖动/贴底检测写入（见根函数注释）。
 */
@Composable
private fun ChatTabSection(
    controller: LiveRoomController,
    listState: LazyListState,
) {
    val messages by controller.messages.collectAsStateWithLifecycle()
    val disableAutoScroll by controller.disableAutoScroll.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // 胶囊点击的平滑滚回会顺带翻转 disableAutoScroll，从而重启本 effect；若不抑制，
    // requestScrollToItem 会在同帧取消在途 animateScrollToItem（退化为瞬跳）。
    var suppressAutoScrollOnce by remember { mutableStateOf(false) }
    // 新消息到达 → 贴底。门控仅「禁自动滚动」意图（位置不参与，避免贴底判定自锁）。
    // key 同时包含最后一条消息 id：仅用 size 会在 200 条上限饱和后失效——头部裁剪 +
    // 尾部追加让 size 恒定，effect 不再重启，表现为「列表停止跟随」。
    // requestScrollToItem 非挂起、无互斥（弹幕连发不丢滚动），index 取数据条数
    //（列表与 messages 严格 1:1、无头部项），等价原项目 jumpTo(maxScrollExtent)。
    // 根因说明（真机闪退：跟随系统主题切深色重建 Activity，重组时本 effect 先于
    // 首次布局执行）：不能用 layoutInfo.totalItemsCount-1（未布局时为 0 → 传 -1）；
    // 数据索引不依赖布局时序，isNotEmpty 守卫保证非负。
    LaunchedEffect(messages.size, messages.lastOrNull()?.id, disableAutoScroll) {
        if (suppressAutoScrollOnce) {
            suppressAutoScrollOnce = false
            return@LaunchedEffect
        }
        if (messages.isNotEmpty() && !disableAutoScroll) {
            listState.requestScrollToItem(messages.size - 1)
        }
    }
    ChatTabContent(
        messages = messages,
        listState = listState,
        showJumpToBottom = disableAutoScroll,
        onJumpToBottom = {
            // 先起平滑滚动、再放开跟随意图（顺序无关，但抑制标志必须先置位）。
            // 放开后由页面级「贴底 → 恢复跟随」链路接管后续新消息。
            // 仅当确实会翻转意图（胶囊只在回看态可见）时才抑制，避免残留在下一拍误吞贴底。
            suppressAutoScrollOnce = messages.isNotEmpty() && disableAutoScroll
            if (messages.isNotEmpty()) {
                scope.launch { listState.animateScrollToItem(messages.lastIndex) }
            }
            controller.setDisableAutoScroll(false)
        },
    )
}

/**
 * 聊天列表 —— 对应原 MessagesAdapter：
 * 用户名（灰）+ 正文（深色）两段式；系统消息整行灰色；行距 8dp。
 * 上滑暂停自动滚底、贴底恢复（等价原 RecyclerView.canScrollVertically(1) 判断）。
 *
 * 根容器为 `Box`（task 10-17-ui-material3-details-and-redesign）：底层仍是原来的
 * `LazyColumn`（key 与列表动画一字未动），其上叠加「↓ 回到底部」悬浮胶囊。
 *
 * @param showJumpToBottom 是否显示回到底部胶囊（= `controller.disableAutoScroll`，
 *        由用户在列表内上滑离底置位；胶囊点击经 [onJumpToBottom] 清回跟随意图）。
 * @param onJumpToBottom 胶囊点击：恢复自动跟随并平滑滚回最新消息。
 */
@Composable
private fun ChatTabContent(
    messages: List<LiveMessage>,
    listState: LazyListState,
    showJumpToBottom: Boolean,
    onJumpToBottom: () -> Unit,
) {
    // 弹幕气泡开关（设置 → 其他；task 08-29-chat-pin-bubble-follow-grid）
    val danmuBubble by ServiceContainer.appSettings.danmuBubble.collectAsStateWithLifecycle()
    // 两段式行内配色：仅随主题变化；作为逐行 remember 的 key 按值比较（ui-compose 条目 28）
    val messageColors = ChatMessageColors(
        userName = MaterialTheme.colorScheme.onSurfaceVariant,
        message = MaterialTheme.colorScheme.onSurface,
    )
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 稳定 key（task 10-17-ui-power-and-recomposition-governance）：用 LiveMessage.id
            // 而非列表下标——头部消息被裁掉或新消息入列时，下标 key 会让整段错位并触发全量重组。
            items(messages, key = { it.id }) { msg ->
                // 逐行缓存 AnnotatedString（task 10-20 R3.2）：新批次入列时，已可见的旧行
                // 不再重复构建富文本（原有实现每次列表更新都会把 ~200 行全部重建一遍）。
                // remember 的 key 比较走 equals（ui-compose 条目 28），data class 稳定即命中。
                val messageText = remember(msg.id, messageColors) {
                    buildMessageText(msg, messageColors)
                }
                Text(
                    text = messageText,
                    fontSize = 14.sp,
                    // 弹幕气泡：开启时加主题容器色圆角背景（与单选对话框选中药丸同源
                    // primaryContainer alpha 0.7；无阴影，用户定标）；关闭为现状纯文本。
                    // padding 外层为两模式一致的页边距，背景仅包裹文本内容（气泡状）。
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .then(
                            if (danmuBubble) {
                                Modifier
                                    .background(
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                        RoundedCornerShape(8.dp),
                                    )
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            } else {
                                Modifier
                            },
                        ),
                )
            }
        }
        // 「↓ 回到底部」悬浮胶囊（task 10-17-ui-material3-details-and-redesign）：
        // 用户上滑浏览历史弹幕（disableAutoScroll=true）时浮现，点击恢复自动跟随并滚回最新。
        // 仅由 disableAutoScroll 驱动，**不引入任何第二套跟随状态**；隐藏稳态下
        // AnimatedVisibility 整块跳过组合（ui-compose 条目 29 §1 的组合门契约）。
        // 全限定调用：本文件未 import 顶层 AnimatedVisibility（既有惯例，勿改成 import）。
        androidx.compose.animation.AnimatedVisibility(
            visible = showJumpToBottom,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp),
            enter = fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.92f),
            exit = fadeOut(tween(150)),
        ) {
            // Surface 自带 clip(shape) → 内容里的点击涟漪被胶囊圆角裁剪
            //（PRD「圆角裁剪水波纹」的同一要求）；shadowElevation = 4.dp 让胶囊浮在列表之上。
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                shadowElevation = 4.dp,
            ) {
                Text(
                    text = "↓ 回到底部",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .clickable(onClick = onJumpToBottom)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * 聊天行两段式配色（用户名 / 正文）。作为 `remember` 的 key 使用：data class 按值比较，
 * 主题不变即命中缓存，主题/深色态切换时自动失效。
 */
private data class ChatMessageColors(val userName: Color, val message: Color)

/**
 * 弹幕消息富文本 —— 对应原 SpannableStringBuilder 两段式语义改用 AnnotatedString：
 * 用户名灰（onSurfaceVariant）+ 正文（onSurface）；系统消息整行灰；
 * SC 类消息前缀加「【醒目】」（对齐原 MessagesAdapter.buildMessageText）。
 *
 * 非 @Composable（task 10-20 R3.2）：配色由调用方以 [ChatMessageColors] 传入，
 * 这样每行可以用 `remember(msg.id, messageColors)` 缓存结果，避免新消息入列时
 * 已可见的旧行重复构建 AnnotatedString。
 */
private fun buildMessageText(item: LiveMessage, colors: ChatMessageColors) = buildAnnotatedString {
    if (item.userName == "LiveSysMessage") {
        withStyle(SpanStyle(color = colors.userName)) { append(item.message) }
    } else {
        val prefix = if (item.type == LiveMessageType.superChat) {
            "【醒目】${item.userName}"
        } else {
            item.userName
        }
        withStyle(SpanStyle(color = colors.userName)) { append("$prefix：") }
        withStyle(SpanStyle(color = colors.message)) { append(item.message) }
    }
}

/* ---------- SC / 头条 Tab：醒目留言列表 ---------- */

/**
 * SC（醒目留言）列表 —— 对齐原 SuperChatAdapter，task 10-17-ui-material3-details-and-redesign
 * 升级为 M3 圆角卡片：头像 [LiveSuperChatMessage.face] + 用户名 + 醒目金额胶囊 + 正文，
 * 无数据时居中提示「暂无醒目留言」。
 */
@Composable
private fun SuperChatTabContent(items: List<LiveSuperChatMessage>) {
    if (items.isEmpty()) {
        EmptyHint(text = "暂无醒目留言")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items) { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    // clip 先于 background：内容底色严格被圆角裁剪（PRD 的圆角裁剪要求）
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(12.dp),
            ) {
                NetImage(
                    url = item.face,
                    modifier = Modifier.size(36.dp),
                    isRound = true,
                )
                Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.userName,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // 用户名吃满剩余宽度 → 金额胶囊右对齐（长昵称省略而不挤压胶囊）
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        // 金额胶囊：LiveOrange 底 + 白字（醒目留言的核心信息，对齐原 Adapter）
                        Text(
                            text = "¥${item.price}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(LiveOrange)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                    if (item.message.isNotEmpty()) {
                        Text(
                            text = item.message,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/* ---------- 贡献榜 Tab ---------- */

/**
 * 贡献榜前三名徽章底盘色（金/银/铜）。
 * 就近定义于本文件的原因：主题常量集中在 `ui/theme/AppTheme.kt`，但该文件在
 * task 10-17-ui-material3-details-and-redesign 中由另一并行实现占用，故不新增主题常量。
 */
private val RankGoldColor = Color(0xFFE6A700)
private val RankSilverColor = Color(0xFF9E9E9E)
private val RankBronzeColor = Color(0xFFB87333)

/**
 * 贡献榜列表 —— 对齐原 ContributionRankAdapter，task 10-17-ui-material3-details-and-redesign
 * 升级：接入 [LiveRoomController.contributionRankLoading] 加载态、空状态提示、
 * 圆形头像 [LiveContributionRankItem.avatar]、前三名金/银/铜圆形序号徽章。
 *
 * @param loading 是否显示转圈——由调用处按控制器三态合成，即
 *        `contributionRankLoading || (!contributionRankFetched && !loadError)`：
 *        「请求中」与「尚未开始请求且详情没报错」都算加载中。加载中优先于空态，
 *        否则首帧会把「尚未返回」误显示成「暂无数据」；请求一旦结束（含失败）即落空态。
 */
@Composable
private fun ContributionRankTabContent(items: List<LiveContributionRankItem>, loading: Boolean) {
    if (loading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (items.isEmpty()) {
        EmptyHint(text = "暂无贡献榜数据")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items) { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 名次徽章：前三名金/银/铜圆形底盘（白字），其余为弱化圆形底盘（次级文字色）
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(
                            when (item.rank) {
                                1 -> RankGoldColor
                                2 -> RankSilverColor
                                3 -> RankBronzeColor
                                else -> MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${item.rank}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (item.rank in 1..3) {
                            Color.White
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Spacer(modifier = Modifier.size(10.dp))
                NetImage(
                    url = item.avatar,
                    modifier = Modifier.size(32.dp),
                    isRound = true,
                )
                Text(
                    text = item.userName,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                )
                Text(text = item.scoreText, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/* ---------- 关注 Tab：直播间内关注列表 ---------- */

/**
 * 直播间内关注列表 —— 直播中优先 + 未开播（复用 FollowService 数据），
 * 行样式对齐原 FollowTabAdapter。task 10-17-ui-material3-details-and-redesign 升级：
 * 主播圆形头像 [FollowUser.face]、**当前所在房间**高亮底色、右侧带状态圆点的
 * 「直播中/未开播」胶囊。
 *
 * @param currentSiteId/[currentRoomId] 当前正在观看的房间身份（调用处传控制器的
 *        `rxSite/rxRoomId`，随 `resetRoom` 换房更新）——与列表项一致的行加高亮底色
 *        （提示「你正在看这个房间」），点击仍走 [onOpenRoom] 换房（同一房间时为幂等重置）。
 */
@Composable
private fun FollowTabContent(
    items: List<FollowUser>,
    currentSiteId: String,
    currentRoomId: String,
    onOpenRoom: (FollowUser) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items) { item ->
            val siteName = Sites.allSites[item.siteId]?.name ?: item.siteId
            val sub = if (item.roomTitle.isEmpty()) siteName else "$siteName · ${item.roomTitle}"
            val isCurrentRoom = item.siteId == currentSiteId && item.roomId == currentRoomId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isCurrentRoom) {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        } else {
                            Color.Transparent
                        },
                    )
                    .clickable(onClick = { onOpenRoom(item) })
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NetImage(
                    url = item.face,
                    modifier = Modifier.size(36.dp),
                    isRound = true,
                )
                Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(
                        text = item.userName,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = sub,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 状态胶囊：圆点 + 文案（直播中 = 红点，与 FollowItemCard 的红色状态圆点同源；
                // 未开播/状态未知 = 弱化灰点）。0=未知 1=未直播 2=直播中（FollowUser.liveStatus）
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(
                                if (item.liveStatus == 2) LiveIndicatorRed else MaterialTheme.colorScheme.outline,
                            ),
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(
                        text = when (item.liveStatus) {
                            2 -> "直播中"
                            0 -> "状态未知"
                            else -> "未开播"
                        },
                        fontSize = 11.sp,
                        color = if (item.liveStatus == 2) {
                            LiveIndicatorRed
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/* ---------- 设置 Tab：内联设置面板 ---------- */

/**
 * 设置 Tab —— task 10-17-ui-material3-details-and-redesign：由原「引导文案占位」
 * （SettingsTabPlaceholder，已删除）升级为**可滚动的内联设置面板**，
 * 复用弹层同款行组件（不新增第二份实现，避免与弹层漂移）：
 * 直播设置（[LiveSettingsRows]：自动退出开关 + 时长滑条）→ 清晰度/线路快捷入口
 *（[SheetClickRow]，右侧显示当前值）→ 弹幕设置（[DanmuSettingsRows]：弹幕显示 8 项）
 * → 弹幕关键词屏蔽入口（打开 [DanmuShieldSheet] 弹层）。
 *
 * 各入口只改 `activeSheet`/发动作，不做控制器逻辑（沿用「弹层只发动作与持久化」惯例）；
 * 清晰度/线路的空列表拦截在调用处（与 ⋮ 菜单、控制层入口一致）。
 */
@Composable
private fun SettingsTabContent(
    controller: LiveRoomController,
    viewportHeight: Float,
    qualityText: String,
    lineText: String,
    onOpenQuality: () -> Unit,
    onOpenLine: () -> Unit,
    onOpenDanmuShield: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        // 直播设置：自动退出开关 + 退出时长滑条（与弹层共用同一组行）
        LiveSettingsRows(controller)
        // 清晰度 / 播放线路快捷入口（右侧显示当前档位，空值回退为「点击切换」）
        SheetClickRow(
            label = "清晰度",
            onClick = onOpenQuality,
            value = qualityText.ifEmpty { "点击切换" },
        )
        SheetClickRow(
            label = "播放线路",
            onClick = onOpenLine,
            value = lineText.ifEmpty { "点击切换" },
        )
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            modifier = Modifier.padding(top = 10.dp),
        )
        // 弹幕设置：弹幕显示 8 项（默认开启/显示表情/显示区域/显示几行/不透明度/字号/粗细/速度）
        DanmuSettingsRows(viewportHeight)
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            modifier = Modifier.padding(top = 10.dp),
        )
        // 弹幕关键词屏蔽入口（打开屏蔽弹层；屏蔽开关与关键词增删都在弹层内）
        SheetClickRow(label = "弹幕关键词屏蔽", onClick = onOpenDanmuShield)
    }
}

/* ---------- 手势调节胶囊（亮度/音量，task 10-17-ui-animation-fluency-polish）---------- */

/**
 * 亮度/音量手势调节的展示状态（对齐 design §2 的 `AdjustHintState`）。
 *
 * @param isVolume true = 音量（右半屏手势）/ false = 亮度（左半屏手势）—— 决定胶囊左侧图标；
 * @param progress 0..1，直接喂给 `LinearProgressIndicator`（音量取 5 级量化后的档位，
 *        与真正落到系统的档位一致）；
 * @param label 右侧百分比文案（如 "60%"）。数值含义由图标区分，故不再带「亮度/音量」前缀。
 */
private data class AdjustHintState(
    val isVolume: Boolean,
    val progress: Float,
    val label: String,
)

/**
 * 手势调节胶囊（M3 圆角胶囊）：左图标 + 100dp 进度条 + 右侧百分比文字。
 * 底色为深色半透明（不跟随主题）——提示叠加在视频区（恒黑底）上，两种主题下对比都足够。
 */
@Composable
private fun AdjustHintCapsule(state: AdjustHintState) {
    Row(
        modifier = Modifier
            .background(Color(0xCC000000), shape = RoundedCornerShape(24.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (state.isVolume) CustomIcons.VolumeUp else CustomIcons.Brightness,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.size(10.dp))
        LinearProgressIndicator(
            // material3 1.3.2 的进度量是 lambda 形态 `progress: () -> Float`
            //（`progress: Float` 那个重载 1.3.x 起已 @Deprecated，仅为二进制兼容保留）
            progress = { state.progress },
            modifier = Modifier.width(100.dp),
            color = Color.White,
            trackColor = Color(0x4DFFFFFF),
        )
        Spacer(modifier = Modifier.size(10.dp))
        Text(text = state.label, fontSize = 13.sp, color = Color.White)
    }
}
