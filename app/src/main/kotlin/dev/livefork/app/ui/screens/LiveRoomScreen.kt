package dev.livefork.app.ui.screens

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.livefork.app.app.Constant
import dev.livefork.app.app.Sites
import dev.livefork.app.app.Utils
import dev.livefork.app.di.ServiceContainer
import dev.livefork.app.models.FollowUser
import dev.livefork.app.modules.live_room.LiveRoomController
import dev.livefork.app.player.MpvInitConfig
import dev.livefork.app.player.MpvVideoView
import dev.livefork.app.player.PlayerEngine
import dev.livefork.app.services.MpvOptionsService
import dev.livefork.app.ui.DanmakuOverlayView
import dev.livefork.app.ui.Router
import dev.livefork.app.ui.components.DanmuSettingsSheet
import dev.livefork.app.ui.components.DanmuShieldSheet
import dev.livefork.app.ui.components.LineDialog
import dev.livefork.app.ui.components.LiveSettingsSheet
import dev.livefork.app.ui.components.NetImage
import dev.livefork.app.ui.components.PlayerControlsOverlay
import dev.livefork.app.ui.components.QualityDialog
import dev.livefork.app.ui.components.RoomMenuDialog
import dev.livefork.app.ui.components.RoomSheet
import dev.livefork.app.ui.theme.LiveIndicatorRed
import dev.livefork.app.ui.theme.LiveOrange
import dev.livefork.core.model.LiveContributionRankItem
import dev.livefork.core.model.LiveMessage
import dev.livefork.core.model.LiveMessageType
import dev.livefork.core.model.LiveSuperChatMessage
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

    // 屏幕常亮（对齐原 onCreate 的 FLAG_KEEP_SCREEN_ON；宿主为 MainActivity 单例窗口）
    DisposableEffect(context) {
        val activity = context as? Activity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
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
    // 沉浸改造（用户反馈：状态栏/底部小白条区域纯白不统一）：LiveApp 全局已
    // setDecorFitsSystemWindows(false)（edge-to-edge），本页不再切换 decorFits；
    // 竖屏页面背景（根 Column）延伸覆盖状态栏区，靠根容器的 statusBarsPadding 避让内容
    //（全屏隐藏系统栏后该 insets 为 0，padding 自动归零，视频铺满无影响）。
    LaunchedEffect(isFullScreen) {
        val activity = context as? Activity ?: return@LaunchedEffect
        val window = activity.window
        if (isFullScreen) {
            // 运行时方向覆盖 Manifest portrait（configChanges 已声明 → 不 recreate）
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            val insetsController = WindowInsetsControllerCompat(window, window.decorView)
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            WindowInsetsControllerCompat(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
    }
    // 离开直播间时若仍处于全屏：恢复竖屏方向与系统栏
    //（避免 MainActivity 宿主窗口残留横屏沉浸；刘海/edge-to-edge 由全局主题与 LiveApp 常开，无需恢复）
    DisposableEffect(context) {
        onDispose {
            val activity = context as? Activity
            if (activity != null) {
                runCatching {
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                        .show(WindowInsetsCompat.Type.systemBars())
                }
            }
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

    // 全屏时 BACK 先退全屏再出栈（等价原 onBackPressedDispatcher callback）
    BackHandler(enabled = isFullScreen) {
        toggleFullScreen()
    }
    // 非全屏的系统返回（手势/按键）也经由 leaveRoom 先藏 Surface 再出栈，
    // 避免「页面已退、视频残留」；两者 enabled 条件互斥。
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
        onDispose {
            controller.dispose()
            videoView.destroy()
        }
    }

    /* ---------- 状态订阅（controller 已赋值后，等价原 bindState）---------- */

    val detail by controller.detail.collectAsStateWithLifecycle()
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
        cm.setPrimaryClip(ClipData.newPlainText("livefork", text))
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

    /* ---------- 视频区手势 ---------- */

    // 本次拖动起点是否在左半屏（决定调亮度还是音量）；snapshot state 保证跨重组可见
    var dragStartLeftHalf by remember { mutableStateOf(false) }

    /**
     * 手势判定（替代已删除的手写 GestureRouter，改用 Compose 内建检测器）：
     * - 单击：显隐控制层；双击：切换全屏（playerGestureControlEnable 总控，每次触摸直读
     *   StateFlow.value —— 单例对象被闭包捕获无过期快照问题，等价旧「监听器内检查总开关」）；
     * - 垂直滑动：按 down 点 x 分左右半屏 → 左亮度（window.screenBrightness 0.05 步进、
     *   跟随系统从 0.5 起）/ 右音量（STREAM_MUSIC 步进 1），步进/范围对齐现版。
     */
    val videoGestureModifier = Modifier
        .pointerInput(context) {
            detectVerticalDragGestures(
                onDragStart = { offset ->
                    // 每次触摸重置自动隐藏计时（等价旧 onUserTouch）
                    controlsTouchTick++
                    dragStartLeftHalf = offset.x < size.width / 2f
                },
                onDragEnd = { controlsTouchTick++ },
                onDragCancel = { controlsTouchTick++ },
            ) { change, dragAmount ->
                change.consume()
                // 手势总开关每次拖动帧读取最新值（对齐旧 onScroll 内逐次检查）
                if (ServiceContainer.appSettings.playerGestureControlEnable.value) {
                    // 上滑为正增量（等价旧 adjustXxx(-distanceY)）
                    val delta = -dragAmount
                    if (dragStartLeftHalf) {
                        adjustBrightness(context as? Activity, delta) { showAdjustHint(it) }
                    } else {
                        adjustVolume(context, delta) { showAdjustHint(it) }
                    }
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

    /* ---------- 布局：LinearLayout(VERTICAL) 等价 Column ---------- */

    // 根因说明（真机反馈：横屏全屏刘海处显示白色边界）：窗口经 SHORT_EDGES 延伸进刘海区后，
    // 根容器的背景色直接决定刘海区域的观感——原根容器固定白色，主题白底从视频黑底之外
    // （含刘海延伸区）穿透出来。修复：全屏时根容器改黑色（与视频外层黑底一致），
    // 竖屏维持白色不变。
    Column(
        modifier = modifier
            .fillMaxSize()
            // 沉浸（M3 任务）：根容器背景延伸覆盖状态栏区（背景先应用铺满，再收缩内容）；
            // statusBarsPadding 让标题栏等内容避开状态栏；全屏时系统栏隐藏 insets=0，
            // padding 自动归零，视频铺满无影响。
            .background(if (isFullScreen) Color.Black else MaterialTheme.colorScheme.surface)
            .statusBarsPadding(),
    ) {

        // 标题栏：返回箭头 / 标题（16sp medium 单行省略）/ 「⋮」菜单 —— 全屏时隐藏。
        // 根因说明（真机症状 4：标题不居中、返回按钮左侧缺几个像素）：
        // 旧实现标题用 weight(1f) 挤在左右两个文本按钮之间——「←」与「⋮」字形推进宽度
        // 不相等，可用空间不对称导致标题整体偏移几个像素；且「←」按字形紧排，负 left
        // bearing 使箭头左侧尖端被裁切。
        // 修复：三段对称结构——两侧各固定 48dp 方形触控区、字形在其中居中（留足空间
        // 不再裁切）；标题脱离 Row 权重分配、由 Box 正中对齐，实现真正水平居中。
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
                    Text(text = "←", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurface)
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
                    Text(text = "⋮", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }

        // 视频区：竖屏高 = 屏宽 × 9/16（aspectRatio 由约束驱动，旋转时自动重算，
        // 对应原 onConfigurationChanged 手动重算的语义）；全屏占满整屏。黑底由父容器承担。
        // 进入直播间「先黑底、后出画面」属 mpv 异步初始化的正常表现
        // （initialize/create/surface attach 均异步完成），不做伪加载动画，
        // 黑底占位由本 Box 的 background(Color.Black) 承担。
        // 手势层挂在外层（含 letterbox 黑边区域，全屏任意位置可点/滑）。
        Box(
            modifier = (if (isFullScreen) {
                Modifier.fillMaxSize()
            } else {
                Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            })
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
        }

        // 信息区（对应 Dart buildUserProfile）：头像/名称/平台 + 人气 —— 全屏时隐藏
        if (!isFullScreen) {
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
                Text(
                    text = "🔥 ${Utils.onlineToString(online)}",
                    fontSize = 14.sp,
                    // 人气橙色（现版 #FF9800，主题常量暂无此色值）
                    color = LiveOrange,
                )
            }
        }

        // 标签栏 + 内容区（weight=1）—— 全屏时隐藏（状态在上方无条件声明，不丢失）
        if (!isFullScreen) {
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
                        // 切回聊天 Tab 时滚到底部（对齐原 showTab 的 scrollToPosition）
                        LaunchedEffect(selectedTabIndex) {
                            if (messages.isNotEmpty()) {
                                chatListState.scrollToItem(messages.size - 1)
                            }
                        }
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
    }

    /* ---------- 弹层渲染（单状态驱动；字段/setter 对照旧 LiveRoomSheets 1:1）---------- */

    when (activeSheet) {
        RoomSheet.None -> {}

        RoomSheet.Menu -> RoomMenuDialog(
            onAction = { index ->
                activeSheet = RoomSheet.None
                when (index) {
                    0 -> if (controller.qualites.value.isEmpty()) {
                        Router.toast("暂无清晰度信息")
                    } else {
                        activeSheet = RoomSheet.Quality
                    }
                    1 -> if (controller.playUrls.value.isEmpty()) {
                        Router.toast("暂无播放线路")
                    } else {
                        activeSheet = RoomSheet.Line
                    }
                    2 -> activeSheet = RoomSheet.DanmuSettings
                    3 -> activeSheet = RoomSheet.LiveSettings
                    4 -> activeSheet = RoomSheet.DanmuShield
                    5 -> copyToClipboard(controller.copyUrl() ?: "", "已复制直播间链接")
                    6 -> copyPlayUrlToClipboard()
                    7 -> shareRoom()
                    8 -> controller.refreshRoom()
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

        RoomSheet.DanmuSettings -> DanmuSettingsSheet(
            // 传入当前弹幕层高度（px）：弹层据此计算「显示几行」动态上限；
            // 全屏/竖屏状态不同 → 打开弹层时的上限不同（对齐原项目传 previewViewportHeight）
            viewportHeight = danmakuOverlay.height.toFloat(),
            onDismiss = { activeSheet = RoomSheet.None },
        )

        RoomSheet.LiveSettings -> LiveSettingsSheet(
            controller = controller,
            onDismiss = { activeSheet = RoomSheet.None },
        )

        RoomSheet.DanmuShield -> DanmuShieldSheet(onDismiss = { activeSheet = RoomSheet.None })
    }
}

/**
 * 调节屏幕亮度（左半屏上滑增亮）：window.screenBrightness 0.05 步进（delta/400），
 * 跟随系统（<0）时从 0.5 起，范围 0.05–1；提示条「亮度 N」。
 */
private fun adjustBrightness(activity: Activity?, delta: Float, showHint: (String) -> Unit) {
    if (activity == null) return
    val attributes = activity.window.attributes
    var brightness = attributes.screenBrightness
    if (brightness < 0f) brightness = 0.5f
    brightness += delta / 400f
    brightness = brightness.coerceIn(0.05f, 1f)
    attributes.screenBrightness = brightness
    activity.window.attributes = attributes
    showHint("亮度 ${(brightness * 100).toInt()}")
}

/**
 * 调节媒体音量（右半屏上滑增大）—— 100 级语义（task player-ui-polish）：
 * 当前系统音量映射到 0..100，每次滑动 ±1 级，再换算回系统档位；
 * 提示条「音量 N/100」。范围 0–100。
 */
private fun adjustVolume(context: Context, delta: Float, showHint: (String) -> Unit) {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    // 当前音量 → 0..100 级（系统档位通常 15~30，归一化便于 ±1 级步进）
    val level100 = if (max > 0) (current.toFloat() / max * 100).roundToInt() else 0
    val nextLevel = (level100 + if (delta > 0) 1 else -1).coerceIn(0, 100)
    val sysVol = if (max > 0) (nextLevel / 100f * max).roundToInt().coerceIn(0, max) else 0
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, sysVol, 0)
    showHint("音量 $nextLevel/100")
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
    // 是否贴底（留 1 条容差：贴底时新增消息仍视为贴底，继续自动滚）
    val atBottom by remember(listState) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val last = layoutInfo.visibleItemsInfo.lastOrNull()
                ?: return@derivedStateOf true
            last.index >= layoutInfo.totalItemsCount - 2 &&
                last.offset + last.size <= layoutInfo.viewportEndOffset + 4
        }
    }
    // 新消息到达且贴底 → 滚到底部（对齐原 disableAutoScroll 语义：上滑暂停、贴底恢复）
    LaunchedEffect(messages, atBottom) {
        if (messages.isNotEmpty() && atBottom) {
            listState.scrollToItem(messages.size - 1)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(messages) { msg ->
            Text(
                text = buildMessageText(msg),
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
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
