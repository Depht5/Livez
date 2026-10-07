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
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.kotlin_livez.app.ui.components.DanmuSettingsSheet
import com.kotlin_livez.app.ui.components.DanmuSettingsSidePanel
import com.kotlin_livez.app.ui.components.DanmuShieldSheet
import com.kotlin_livez.app.ui.components.LineDialog
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
import com.kotlin_livez.app.ui.nav.liveRoomContainerSharedElement
import com.kotlin_livez.app.ui.nav.liveRoomCoverSharedElement
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * 大屏版式：平板横屏（[useWideLayout]）**非全屏**时左右分栏（播放器居左、信息栏 + 标签
 * 内容居右）；**全屏时不分栏**，播放器独占整屏（用户决策 2026-10-07，反转原「全屏也分栏」）。
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

    // 屏幕常亮（对齐原 onCreate 的 FLAG_KEEP_SCREEN_ON；Dart 参考 player_controller
    // playing→WakelockPlus.enable 的页面级超集：暂停期间也保持常亮）。view 级
    // keepScreenOn 不依赖 LocalContext 的具体类型——旧写法 `context as? Activity`
    // 强转失败会静默不加 window flag，屏幕按系统超时熄灭（task player-icons-follow-ux）。
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    /* ---------- 全屏态（UI 层状态；等价原 isFullScreen 字段）---------- */

    // rememberSaveable：进程重建恢复语义对照原 onSaveInstanceState(KEY_FULL_SCREEN)——
    // 恢复为 true 时下面的窗口副作用 LaunchedEffect 自动重新进入全屏
    var isFullScreen by rememberSaveable { mutableStateOf(false) }

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
            // 运行时方向覆盖 Manifest portrait（configChanges 已声明 → 不 recreate）
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            // transient 栏图标明暗：全屏为黑色视频底，滑动呼出的临时系统栏需浅色图标
            //（false = 浅色图标）；否则深色图标在黑底上不可见。
            insetsController.isAppearanceLightStatusBars = false
            insetsController.isAppearanceLightNavigationBars = false
        } else {
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
            if (activity != null) {
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
    // 通知权限（后台保活通知可见性）：Android 13+ POST_NOTIFICATIONS 为运行时权限，
    // 未授予时前台服务仍运行但通知被系统抑制——用户以通知为保活生效的依据，进入直播间时请求一次。
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
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

    /* ---------- 调节提示条（亮度/音量；1.2s 自动消失，job 取消防叠加）---------- */

    val coroutineScope = rememberCoroutineScope()
    var adjustHint by remember { mutableStateOf<String?>(null) }
    var adjustHintJob by remember { mutableStateOf<Job?>(null) }
    fun showAdjustHint(message: String) {
        // 取消旧提示 job 防叠加（等价旧 mainHandler.removeCallbacks(hideJob)）
        adjustHintJob?.cancel()
        adjustHintJob = coroutineScope.launch {
            adjustHint = message
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
    var leaving by remember { mutableStateOf(false) }
    fun leaveRoom() {
        if (leaving) return
        leaving = true
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
    DisposableEffect(controller, videoView) {
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
        onDispose {
            if (ServiceContainer.activeLiveRoom === controller) {
                ServiceContainer.activeLiveRoom = null
            }
            controller.dispose()
            videoView.destroy()
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
    val online by controller.online.collectAsStateWithLifecycle()
    val liveStatus by controller.liveStatus.collectAsStateWithLifecycle()
    val loadError by controller.loadError.collectAsStateWithLifecycle()
    val messages by controller.messages.collectAsStateWithLifecycle()
    val superChats by controller.superChats.collectAsStateWithLifecycle()
    val contributionUsers by controller.contributionRankUsers.collectAsStateWithLifecycle()
    val qualites by controller.qualites.collectAsStateWithLifecycle()
    val qualityName by controller.currentQualityInfo.collectAsStateWithLifecycle()
    val lineName by controller.currentLineInfo.collectAsStateWithLifecycle()
    val liveDuration by controller.liveDuration.collectAsStateWithLifecycle()
    val danmuEnable by ServiceContainer.appSettings.danmuEnable.collectAsStateWithLifecycle()
    val followLive by ServiceContainer.followService.liveList.collectAsStateWithLifecycle()
    val followNotLive by ServiceContainer.followService.notLiveList.collectAsStateWithLifecycle()
    // 播放状态（▶/⏸ 图标）
    val playerState by PlayerEngine.instance.state.collectAsStateWithLifecycle()

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

    // 浮层弹幕增量收集：以「最后一条已入队消息」为实例引用游标做尾部增量。
    // 历史说明：控制器在消息数超过 200 后每来一条新消息会先 drop(1) 裁剪头部再追加；
    // 旧的「按列表长度差量」在此场景失效，故改用尾部实例引用比对（列表为不可变拷贝，
    // drop(1) 不改变既有元素引用，从尾部回溯到游标即可精确算出新增段）。
    //
    // 游标失联的语义（本版修正）：indexOfLast 找不到游标（返回 -1）说明两次收集之间
    // 涌入的消息数超过了整个列表容量（>200 条/帧间隙，高热房间突发流量才可能），
    // 游标被头部裁剪吞掉。此时**绝不能**从 0 全量重放——重放会把上百条延迟弹幕
    // （最长 6s）瞬间灌进 DanmakuOverlayView，轨道全满后每条新弹幕都会按「最小 x」
    // 覆盖一条在飞弹幕，宏观表现正是「弹幕走到屏幕中间偏右就成批消失」。
    // 保守做法：把游标重置为当前列表尾、跳过本帧（丢几条弹幕可接受），绝不清轨。
    var danmakuLastEnqueued by remember(controller) { mutableStateOf<LiveMessage?>(null) }
    LaunchedEffect(controller, messages) {
        val list = messages
        if (list.isEmpty()) {
            // 切房间/刷新清空：重置游标并清轨（对齐原 danmakuLastCount 重置语义）
            danmakuLastEnqueued = null
            danmakuOverlay.clear()
            return@LaunchedEffect
        }
        val cursor = danmakuLastEnqueued
        val fromIndex: Int = if (cursor == null) {
            0 // 进房首帧（列表从空开始增长）：全量入队
        } else {
            val idx = list.indexOfLast { it === cursor }
            if (idx == -1) {
                // 游标失联：保守跳过本帧（见上方注释），不清轨、不全量重放
                danmakuLastEnqueued = list.last()
                return@LaunchedEffect
            }
            idx + 1
        }
        if (fromIndex < list.size) {
            for (msg in list.subList(fromIndex, list.size)) {
                danmakuOverlay.enqueue(msg)
            }
            danmakuLastEnqueued = list.last()
        }
    }

    // 进房自动全屏：设置开启时读一次即进入（等价原 videoFrame.post { enterFullScreen() }，
    // 位于 create 之后执行，无需持续 collect）
    LaunchedEffect(controller) {
        if (ServiceContainer.appSettings.autoFullScreen.value && !isFullScreen) {
            isFullScreen = true
            controlsVisible = true
            controlsTouchTick++
        }
    }

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
                    showAdjustHint("亮度 ${(seekB * 100).toInt()}")
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
                    showAdjustHint("音量 $level")
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

    val tabs = remember(siteId) { buildTabEntries(siteId) }
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    val selectedKey = tabs.getOrNull(selectedTabIndex)?.key
    // 聊天列表滚动状态提升到页面级：切走再切回聊天 Tab 位置不丢（对齐原 RecyclerView 复用）
    val chatListState = rememberLazyListState()

    /* ---------- 聊天自动跟随（对齐原项目 disableAutoScroll 机制，task 08-30）---------- */

    // 原项目机制：跟随 = 每条消息 postFrame jumpTo(maxScrollExtent)，唯一守卫是
    // disableAutoScroll（**用户意图标志**——scrollListener 仅在「用户上滑离底」置 true、
    // 「贴底」置 false；程序化滚动不影响它；列表当前位置不参与门控）。
    // 原项目全屏时同样卸载竖屏页（build 分支），恢复全靠下一条消息自然贴底——因此这里
    // 不需要任何锚定代码；此前三轮「位置派生守卫（atBottom）+ 锚定」方案全部失败
    // （恢复组合时位置不在底 → 守卫自锁），根因分析见前任务 research 文档。
    var disableAutoScroll by remember { mutableStateOf(false) }
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
    // 对齐原 scrollListener：拖动中离底 → 暂停跟随；贴底 → 恢复跟随
    LaunchedEffect(chatUserDragging, chatAtBottom) {
        if (chatAtBottom) {
            if (disableAutoScroll) Log.d("聊天跟随恢复（贴底）")
            disableAutoScroll = false
        } else if (chatUserDragging) {
            if (!disableAutoScroll) Log.d("聊天跟随暂停（用户上滑离底）")
            disableAutoScroll = true
        }
    }
    // 每条消息到达 → 贴底。门控仅 disableAutoScroll（位置不参与）：全屏/切 Tab 恢复组合
    // 时本 effect 随之启动执行，下一条消息即贴底——与原项目完全一致。
    // requestScrollToItem 非挂起、无互斥（弹幕连发不丢滚动），index 取数据条数
    // （列表与 messages 严格 1:1、无头部项），等价原项目 jumpTo(maxScrollExtent)。
    // 根因说明（真机闪退：跟随系统主题切深色重建 Activity，重组时本 effect 先于
    // 首次布局执行）：原取 layoutInfo.totalItemsCount-1，未布局时总数为 0 → 传 -1
    // 崩溃；数据索引不依赖布局时序，isNotEmpty 守卫保证非负。
    LaunchedEffect(messages) {
        if (messages.isNotEmpty() && !disableAutoScroll) {
            chatListState.requestScrollToItem(messages.size - 1)
            val size = messages.size
            if (size == 1 || size % 20 == 0) {
                Log.d("UI 跟随: size=$size disable=$disableAutoScroll atBottom=$chatAtBottom")
            }
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
                            indication = null,
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
                    text = detail?.title?.ifEmpty { null } ?: "直播间",
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
                            indication = null,
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
                .liveRoomCoverSharedElement(siteId, roomId)
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
                val roomCover = detail?.cover.orEmpty()
                if (roomCover.isNotEmpty() && playerState.width == null) {
                    NetImage(
                        url = roomCover,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                // mpv 渲染内核（互操作白名单）：Surface 附加由 MpvVideoView 的
                // SurfaceHolder.Callback 自动处理（surfaceCreated → attachSurface）
                AndroidView(
                    factory = { videoView },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // 浮层弹幕：铺满整个视频区（**外层**，全屏时含 letterbox 黑边）。
            // 根因说明（task danmaku-overlay-fix：全屏弹幕从右侧直接出现/靠近左侧直接消失）：
            // 原实现把弹幕层放在上方 letterbox（aspectRatio(videoAspect)）内层——全屏横屏时
            // 弹幕层窄于屏幕（左右黑边），弹幕从 letterbox 右缘滑入、出左缘即越界消失，
            // 观感为「未从屏幕外移入/未移出屏幕」。对齐原项目：canvas_danmaku 弹幕层 =
            // 整个播放器区域（Positioned.fill，全屏含黑边），弹幕从物理屏右缘外滑入、
            // 完全走出左缘才消失。非交互层不拦截点击。
            AndroidView(
                factory = { danmakuOverlay },
                modifier = Modifier.fillMaxSize(),
            )
            // 未开播覆盖层：白字居中（对应原 unliveOverlay）
            if (!liveStatus) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = "未开播", fontSize = 16.sp, color = Color.White)
                }
            }
            // 直播间加载/刷新中：居中转圈（task player-ui-polish：刷新与加载使用同样的动画；
            // detail 未就绪或刷新中且无加载错误 → 转圈；完成后即消失）
            if ((detail == null || refreshing) && !loadError) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
            // 调节提示条（亮度/音量）：独立于控件显隐（task player-ui-polish）——
            // 控制层 5s 自动隐藏后滑动调音量/亮度，提示仍显示；1.2s 自动消失由上方 job 管理。
            val hint = adjustHint
            if (hint != null) {
                Text(
                    text = hint,
                    fontSize = 15.sp,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color(0x66000000), shape = RoundedCornerShape(6.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            // 控制层覆盖（Compose 版 PlayerControlsOverlay；显隐/计时由本页面驱动）
            PlayerControlsOverlay(
                visible = controlsVisible,
                fullScreen = isFullScreen,
                playing = playerState.playing,
                liveStatus = liveStatus,
                danmakuEnabled = overlayDanmakuOn,
                durationText = liveDuration,
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
            // 全限定调用：此处处于根 Column 的 ColumnScope 作用域内，foundation 的
            // ColumnScope.AnimatedVisibility（禁用级 @Deprecated）会遮蔽顶层同名函数。
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NetImage(
                url = detail?.userAvatar.orEmpty(),
                modifier = Modifier.size(48.dp),
                isRound = true,
            )
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    text = detail?.userName ?: "",
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
            // HeartOutline，已关注 = 红色实心爱心 HeartFilled；点击切换关注状态
            Icon(
                imageVector = if (followed) CustomIcons.HeartFilled else CustomIcons.HeartOutline,
                contentDescription = null,
                // Icon 的 ColorFilter 染色决定实际颜色（tint=Unspecified 会回退为
                // LocalContentColor 染黑）：显式按状态上色——已关注红 #FF0000；
                // 未关注浅色灰 #808080，深色下浅灰 #B0B0B0（深灰底对比不足，
                // task 08-29-dark-mode 用户定标；经 LocalAppDarkTheme 感知手动切换）
                tint = if (followed) {
                    Color(0xFFFF0000)
                } else if (LocalAppDarkTheme.current) {
                    Color(0xFFB0B0B0)
                } else {
                    Color(0xFF808080)
                },
                modifier = Modifier
                    // 顺序关键：padding 外层撑点击区、size 最内层决定爱心本体尺寸
                    //（反序会让 padding 挤压渲染区，爱心实际只显示 size−padding）
                    .clickable {
                        if (followed) {
                            controller.removeFollowUser()
                        } else {
                            controller.followUser()
                        }
                    }
                    .padding(horizontal = 4.dp, vertical = 4.dp)
                    .size(23.dp),
            )
            Text(
                text = "🔥 ${Utils.onlineToString(online)}",
                fontSize = 14.sp,
                // 人气橙色（现版 #FF9800，主题常量暂无此色值）
                color = LiveOrange,
            )
        }
    }

    /** 标签栏 + 内容区片段：weight 需要 Column 作用域，故声明为 ColumnScope 扩展，
     *  由调用处的 Column 提供（宽屏右列 / 窄屏根列均满足）。 */
    val roomTabsAndContent: @Composable ColumnScope.() -> Unit = {
        TabRow(
            selectedTabIndex = selectedTabIndex,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.secondary,
            indicator = { tabPositions ->
                Box(
                    modifier = Modifier
                        .tabIndicatorOffset(tabPositions[selectedTabIndex])
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.secondary),
                )
            },
        ) {
            tabs.forEachIndexed { index, tab ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = {
                        Text(
                            text = tab.label,
                            fontSize = 14.sp,
                            // 选中蓝 / 未选中灰（对齐原 setTabTextColors）
                            color = if (selectedTabIndex == index) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when (selectedKey) {
                TAB_KEY_CHAT -> {
                    // 自动跟随在页面级（chatListState 声明处）：意图守卫 + 每消息贴底；
                    // 面板恢复组合（全屏退出/切回 Tab）后本分支重组，消息 effect 即刻生效。
                    ChatTabContent(
                        messages = messages,
                        listState = chatListState,
                    )
                }
                TAB_KEY_SUPER_CHAT -> SuperChatTabContent(superChats)
                TAB_KEY_FOLLOW -> FollowTabContent(
                    items = followLive + followNotLive,
                    onOpenRoom = { follow ->
                        val target = Sites.allSites[follow.siteId] ?: return@FollowTabContent
                        controller.resetRoom(target, follow.roomId)
                    },
                )
                TAB_KEY_CONTRIBUTION_RANK -> ContributionRankTabContent(contributionUsers)
                TAB_KEY_SETTINGS -> SettingsTabPlaceholder(
                    onOpenSettings = { activeSheet = RoomSheet.LiveSettings },
                )
            }
        }
    }

    // ---------- 根容器：宽屏（平板横屏）两列 / 窄屏单列 ----------
    // 背景色沿用原根容器写法：全屏黑底（刘海延伸区观感），竖屏主题底色。
    val rootModifier = modifier
        .liveRoomContainerSharedElement(siteId, roomId)
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
    if (useWideLayout()) {
        Row(modifier = rootModifier) {
            // 左列：标题栏 + 播放器（播放器吃掉剩余高度；letterbox 逻辑在片段内不变）
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                roomTitleBar()
                roomVideo(Modifier.weight(1f).fillMaxWidth())
            }
            // 分隔线与右列（主播信息栏 + 标签栏 + 消息区）**仅非全屏**组合：
            // 既满足「全屏 = 播放器独占整屏」，又不为此切换分支（见上方根因说明）。
            if (!isFullScreen) {
                // 分隔线：对齐原项目内容区左侧 1px 灰线
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                Column(
                    modifier = Modifier
                        .width(panelWidth)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    roomInfoBar()
                    roomTabsAndContent()
                }
            }
        }
    } else {
        Column(modifier = rootModifier) {
            roomTitleBar()
            roomVideo(
                if (isFullScreen) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                },
            )
            if (!isFullScreen) {
                roomInfoBar()
                roomTabsAndContent()
            }
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
 * 贡献榜（douyu 叫「亲密榜」）/ 设置——对照原 buildTabEntries 1:1。
 */
private fun buildTabEntries(siteId: String): List<RoomTab> {
    val tabs = mutableListOf<RoomTab>()
    tabs += RoomTab(TAB_KEY_CHAT, "聊天")
    when (siteId) {
        Constant.kBiliBili -> tabs += RoomTab(TAB_KEY_SUPER_CHAT, "SC")
        Constant.kHuya -> tabs += RoomTab(TAB_KEY_SUPER_CHAT, "头条")
    }
    tabs += RoomTab(TAB_KEY_FOLLOW, "关注")
    tabs += RoomTab(
        TAB_KEY_CONTRIBUTION_RANK,
        if (siteId == Constant.kDouyu) "亲密榜" else "贡献榜",
    )
    tabs += RoomTab(TAB_KEY_SETTINGS, "设置")
    return tabs
}

/* ---------- 聊天 Tab：弹幕列表 ---------- */

/**
 * 聊天列表 —— 对应原 MessagesAdapter：
 * 用户名（灰）+ 正文（深色）两段式；系统消息整行灰色；行距 8dp。
 * 上滑暂停自动滚底、贴底恢复（等价原 RecyclerView.canScrollVertically(1) 判断）。
 */
@Composable
private fun ChatTabContent(messages: List<LiveMessage>, listState: LazyListState) {
    // 弹幕气泡开关（设置 → 其他；task 08-29-chat-pin-bubble-follow-grid）
    val danmuBubble by ServiceContainer.appSettings.danmuBubble.collectAsStateWithLifecycle()
    // 自动跟随逻辑在页面级（chatListState 声明处）：意图守卫 + 每消息贴底
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(messages) { msg ->
            Text(
                text = buildMessageText(msg),
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
}

/**
 * 弹幕消息富文本 —— 对应原 SpannableStringBuilder 两段式语义改用 AnnotatedString：
 * 用户名灰（onSurfaceVariant）+ 正文（onSurface）；系统消息整行灰；
 * SC 类消息前缀加「【醒目】」（对齐原 MessagesAdapter.buildMessageText）。
 * @Composable：读取 MaterialTheme.colorScheme（调用方 ChatTabContent 为 @Composable）。
 */
@Composable
private fun buildMessageText(item: LiveMessage) = buildAnnotatedString {
    if (item.userName == "LiveSysMessage") {
        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(item.message) }
    } else {
        val prefix = if (item.type == LiveMessageType.superChat) {
            "【醒目】${item.userName}"
        } else {
            item.userName
        }
        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append("$prefix：") }
        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) { append(item.message) }
    }
}

/* ---------- SC / 头条 Tab：醒目留言列表 ---------- */

/** SC 列表 —— 价格醒目（橙）+ 用户名（红）+ 留言（对齐原 SuperChatAdapter）。 */
@Composable
private fun SuperChatTabContent(items: List<LiveSuperChatMessage>) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items) { item ->
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = "💬 ${item.userName}（醒目留言）",
                    fontSize = 13.sp,
                    color = LiveIndicatorRed,
                )
                Spacer(modifier = Modifier.size(4.dp))
                Text(text = item.message, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.size(4.dp))
                Text(
                    text = "¥${item.price}",
                    fontSize = 12.sp,
                    // 价格橙色（现版 #FF9800）
                    color = LiveOrange,
                )
            }
        }
    }
}

/* ---------- 贡献榜 Tab ---------- */

/** 贡献榜列表 —— 名次（橙）+ 昵称 + 贡献值（对齐原 ContributionRankAdapter）。 */
@Composable
private fun ContributionRankTabContent(items: List<LiveContributionRankItem>) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items) { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${item.rank}",
                    fontSize = 14.sp,
                    color = LiveOrange,
                    modifier = Modifier.padding(end = 12.dp),
                )
                Text(
                    text = item.userName,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(text = item.scoreText, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/* ---------- 关注 Tab：直播间内关注列表 ---------- */

/**
 * 直播间内关注列表（简化版）—— 直播中优先 + 未开播（复用 FollowService 数据），
 * 行样式对齐原 FollowTabAdapter：标题/平台+房间标题/直播状态徽标；点击换房。
 */
@Composable
private fun FollowTabContent(
    items: List<FollowUser>,
    onOpenRoom: (FollowUser) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items) { item ->
            val siteName = Sites.allSites[item.siteId]?.name ?: item.siteId
            val sub = if (item.roomTitle.isEmpty()) siteName else "$siteName · ${item.roomTitle}"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = { onOpenRoom(item) })
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.userName,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(text = sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    text = when (item.liveStatus) {
                        2 -> "直播中"
                        0 -> "状态未知"
                        else -> "未开播"
                    },
                    fontSize = 12.sp,
                    color = if (item.liveStatus == 2) LiveIndicatorRed else MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/* ---------- 设置 Tab 入口 ---------- */

/** 设置 Tab：入口说明（点击打开直播设置弹层，对齐原 tabPlaceholder 点击行为）。 */
@Composable
private fun SettingsTabPlaceholder(onOpenSettings: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "直播间设置请使用顶部⋮菜单",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOpenSettings,
                )
                .padding(16.dp),
        )
    }
}
