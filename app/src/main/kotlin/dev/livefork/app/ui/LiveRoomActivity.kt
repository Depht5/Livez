package dev.livefork.app.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import dev.livefork.app.app.Constant
import dev.livefork.app.app.Sites
import dev.livefork.app.app.Utils
import dev.livefork.app.di.ServiceContainer
import dev.livefork.app.modules.live_room.LiveRoomController
import dev.livefork.app.player.MpvInitConfig
import dev.livefork.app.player.MpvVideoView
import dev.livefork.app.player.PlayerEngine
import dev.livefork.app.services.MpvOptionsService
import dev.livefork.app.services.FollowService
import dev.livefork.core.model.LiveContributionRankItem
import dev.livefork.core.model.LiveMessage
import dev.livefork.core.model.LiveMessageType
import dev.livefork.core.model.LiveSuperChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 直播间页面（竖屏）—— 对应 Flutter `live_room_page.dart` 的竖屏布局（buildPhoneUI）。
 * 结构：标题栏（返回/标题/菜单占位）→ 16:9 视频画面 → 信息区（头像/名称/平台 + 人气）→
 * 标签栏（默认"聊天"）+ 内容区（聊天 = 弹幕列表，其余标签占位）。
 * 播放：libmpv（MpvVideoView + PlayerEngine）；数据/弹幕状态流由 LiveRoomController 提供。
 */
class LiveRoomActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_SITE_ID = "site_id"
        private const val EXTRA_ROOM_ID = "room_id"
        private const val TAB_CHAT = "chat"
        private const val KEY_FULL_SCREEN = "full_screen"

        fun start(context: Context, siteId: String, roomId: String) {
            val intent = Intent(context, LiveRoomActivity::class.java)
                .putExtra(EXTRA_SITE_ID, siteId)
                .putExtra(EXTRA_ROOM_ID, roomId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var controller: LiveRoomController
    private val followService: FollowService
        get() = ServiceContainer.followService
    private lateinit var videoView: MpvVideoView
    private lateinit var unliveOverlay: TextView
    private lateinit var titleText: TextView
    private lateinit var userAvatar: ImageView
    private lateinit var userNameText: TextView
    private lateinit var siteNameText: TextView
    private lateinit var onlineText: TextView
    private lateinit var contentFrame: FrameLayout
    private lateinit var messagesRecycler: RecyclerView
    private lateinit var messagesAdapter: MessagesAdapter

    /** 直播间 Tab 内容（子任务：剩余 Tab；SC/贡献榜/关注/设置）。 */
    private lateinit var superChatRecycler: RecyclerView
    private lateinit var superChatAdapter: SuperChatAdapter
    private lateinit var contributionRecycler: RecyclerView
    private lateinit var contributionAdapter: ContributionRankAdapter
    private lateinit var followTabRecycler: RecyclerView
    private lateinit var followTabAdapter: FollowTabAdapter
    private lateinit var tabPlaceholder: TextView

    /** 视频浮层弹幕（子任务：浮层弹幕；叠加在视频区，非交互层）。 */
    private lateinit var danmakuOverlay: DanmakuOverlayView

    /** 播放器控制层（子任务：控制层与手势；顶栏/底栏/提示条）。 */
    private lateinit var controlsOverlay: PlayerControlsOverlay

    /** 已入列的消息计数（增量收集：新消息 = 当前列表尾部新增）。 */
    private var danmakuLastCount = 0

    /** 视频手势判定（单击/双击/滑动；手写替代 GestureDetector，见 onCreate 注释）。 */
    private val gestureRouter = GestureRouter(
        onSingleTap = { controlsOverlay.toggle() },
        onDoubleTap = {
            if (ServiceContainer.appSettings.playerGestureControlEnable.value) {
                togglePlayPause()
            }
        },
        onScroll = { startX, distanceY ->
            if (ServiceContainer.appSettings.playerGestureControlEnable.value) {
                val leftHalf = startX < (videoFrame.width / 2f)
                if (leftHalf) {
                    adjustBrightness(-distanceY)
                } else {
                    adjustVolume(-distanceY)
                }
            }
        },
    )

    /* ---------- 全屏切换（子任务：横屏全屏切换）---------- */

    /** 是否处于横屏全屏（对应 Dart fullScreenState）；纯 View 态，onSaveInstanceState 持久。 */
    private var isFullScreen = false

    /** 竖屏根布局与各区块引用（全屏时隐藏非播放器区块）。 */
    private lateinit var root: LinearLayout
    private lateinit var titleBar: LinearLayout
    private lateinit var videoFrame: FrameLayout
    private lateinit var infoBar: LinearLayout
    private lateinit var tabLayout: TabLayout

    /** 视频区竖屏高度（16:9，屏宽 × 9/16）。 */
    private val videoHeight: Int
        get() = (resources.displayMetrics.widthPixels / LiveRoomGridLayout.coverAspectRatio).toInt()

    /** 用户上滑后暂停自动滚底；贴底后恢复。 */
    private var disableAutoScroll = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val siteId = intent.getStringExtra(EXTRA_SITE_ID) ?: run { finish(); return }
        val roomId = intent.getStringExtra(EXTRA_ROOM_ID) ?: run { finish(); return }
        val site = Sites.allSites[siteId] ?: run {
            Router.toast("站点不存在：$siteId")
            finish()
            return
        }

        // 竖屏页：保持系统装饰（顶部状态栏留白由系统处理）；屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        /* ---- 顶部标题栏（对应 Dart AppBar + _buildMobileAppBarTitle）---- */
        titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            setBackgroundColor(Color.WHITE)
        }
        val backButton = TextView(this).apply {
            text = "←"
            textSize = 22f
            setTextColor(Color.parseColor("#333333"))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
        }
        backButton.setOnClickListener { finish() }
        titleBar.addView(backButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        titleText = TextView(this).apply {
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.parseColor("#333333"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }
        titleBar.addView(titleText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val menuButton = TextView(this).apply {
            text = "⋮"
            textSize = 22f
            setTextColor(Color.parseColor("#333333"))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
        }
        // 菜单：打开直播间菜单弹层（清晰度/线路/弹幕设置/屏蔽/复制/分享等）
        menuButton.setOnClickListener { LiveRoomSheets.showMenuSheet(this, controller) }
        titleBar.addView(menuButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(titleBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        /* ---- 视频区（16:9：宽 = 屏宽，高 = 宽 × 9/16；未开播黑色 + 提示）---- */
        videoFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        videoView = MpvVideoView(this)
        videoFrame.addView(videoView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        // 浮层弹幕：叠加在视频之上、未开播提示之下；非交互层不拦截点击
        danmakuOverlay = DanmakuOverlayView(this)
        danmakuOverlay.siteId = siteId
        videoFrame.addView(danmakuOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        // 控制层：视频之上、可交互（顶栏/底栏/提示条）
        controlsOverlay = PlayerControlsOverlay(this)
        videoFrame.addView(controlsOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        unliveOverlay = TextView(this).apply {
            text = "未开播"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        videoFrame.addView(unliveOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        root.addView(videoFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, videoHeight,
        ))

        /* ---- 信息区（对应 Dart buildUserProfile：头像/名称/平台 + 人气）---- */
        infoBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.WHITE)
        }
        userAvatar = ImageView(this)
        infoBar.addView(userAvatar, LinearLayout.LayoutParams(dp(48), dp(48)))
        val infoTexts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        userNameText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#333333"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        // 平台行：图标 + 平台名
        val siteRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val siteIcon = ImageView(this).apply { setImageResource(site.logoRes) }
        siteIcon.layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
        siteNameText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#888888"))
        }
        siteRow.addView(siteIcon, LinearLayout.LayoutParams(dp(16), dp(16)))
        siteRow.addView(
            siteNameText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(4) },
        )
        infoTexts.addView(userNameText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        infoTexts.addView(siteRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        infoBar.addView(infoTexts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        onlineText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#FF9800"))
        }
        infoBar.addView(onlineText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(infoBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        /* ---- 标签栏（对应 Dart buildMessageArea 的 TabBar；仅"聊天"实现内容）---- */
        val tabs = buildTabEntries(siteId)
        tabLayout = TabLayout(this).apply {
            setSelectedTabIndicatorColor(Color.parseColor("#3498DB"))
            setTabTextColors(Color.parseColor("#888888"), Color.parseColor("#3498DB"))
            tabGravity = TabLayout.GRAVITY_FILL
            setBackgroundColor(Color.WHITE)
        }
        for ((_, label) in tabs) {
            tabLayout.addTab(tabLayout.newTab().setText(label))
        }
        root.addView(tabLayout, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        /* ---- 内容区（weight=1；聊天 = 弹幕列表，其余标签占位）---- */
        contentFrame = FrameLayout(this)
        messagesRecycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@LiveRoomActivity)
            addItemDecoration(ChatGapDecoration(dp(8)))
        }
        messagesAdapter = MessagesAdapter()
        messagesRecycler.adapter = messagesAdapter
        // 上滑暂停自动滚底；贴底恢复（对应 Dart disableAutoScroll）
        messagesRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                disableAutoScroll = recyclerView.canScrollVertically(1)
            }
        })
        root.addView(contentFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        contentFrame.addView(messagesRecycler, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        /* ---- 其余 Tab 内容（SC/贡献榜/关注/设置；初始 GONE，切 tab 显示）---- */
        superChatAdapter = SuperChatAdapter()
        superChatRecycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@LiveRoomActivity)
            adapter = superChatAdapter
            visibility = View.GONE
        }
        contributionAdapter = ContributionRankAdapter()
        contributionRecycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@LiveRoomActivity)
            adapter = contributionAdapter
            visibility = View.GONE
        }
        // 直播间内关注列表：直播中优先（复用 FollowService.liveList/notLiveList 渲染风格）
        followTabAdapter = FollowTabAdapter(
            onOpenRoom = { follow ->
                val site = Sites.allSites[follow.siteId] ?: return@FollowTabAdapter
                controller.resetRoom(site, follow.roomId)
            },
        )
        followTabRecycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@LiveRoomActivity)
            adapter = followTabAdapter
            visibility = View.GONE
        }
        // 设置 Tab：入口列表（弹层与子任务④共用 LiveRoomSheets）
        tabPlaceholder = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
            visibility = View.GONE
        }
        contentFrame.addView(superChatRecycler, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        contentFrame.addView(contributionRecycler, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        contentFrame.addView(followTabRecycler, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        contentFrame.addView(tabPlaceholder, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showTab(tabs.getOrNull(tab.position)?.first)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}

            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        setContentView(root)

        /* ---- 控制器与播放器（沿用原逻辑）---- */
        controller = LiveRoomController(
            appSettings = ServiceContainer.appSettings,
            dbService = ServiceContainer.dbService,
            followService = ServiceContainer.followService,
            currentRoom = ServiceContainer.currentRoom,
            player = PlayerEngine.instance,
        )

        val videoConfig = MpvOptionsService.videoControllerConfiguration(ServiceContainer.appSettings)
        runCatching {
            videoView.initialize(
                configDir = filesDir.absolutePath,
                cacheDir = cacheDir.absolutePath,
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
        // 播放器初始化失败（缺 native so 等）→ 提示，页面保持可用
        scope.launch {
            PlayerEngine.instance.initError.collect { message ->
                if (message != null) Router.toast(message)
            }
        }

        bindState()

        // 全屏切换：BACK 键在全屏时先退出全屏，而非退出直播间
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFullScreen) {
                    exitFull()
                } else {
                    finish()
                }
            }
        })

        // 控制层回调接线
        controlsOverlay.onBackClick = { exitFull() }
        controlsOverlay.onRefreshClick = { controller.refreshRoom() }
        controlsOverlay.onPlayPauseClick = { togglePlayPause() }
        controlsOverlay.onDanmakuClick = { toggleDanmaku() }
        controlsOverlay.onQualityClick = { LiveRoomSheets.showQualitySheet(this, controller) }
        controlsOverlay.onLineClick = { LiveRoomSheets.showPlayUrlsSheet(this, controller) }
        controlsOverlay.onFullScreenClick = { toggleFullScreen() }

        // 视频区手势：单击显隐控制层、双击播放/暂停、左右滑动调亮度/音量（对齐历史批次）。
        // 注：android.view.GestureDetector 的 onScroll/onFling（带原始 float 参数）在
        // Kotlin 2.0.21 覆盖解析异常（overrides nothing），故手写判定。
        videoFrame.setOnTouchListener { _, event ->
            if (controlsOverlay.visibility == View.VISIBLE) controlsOverlay.onUserTouch()
            gestureRouter.onTouch(event)
            true
        }

        controller.create(site, roomId)

        // 进程重建恢复：之前处于全屏 → 重新进入全屏
        if (savedInstanceState?.getBoolean(KEY_FULL_SCREEN) == true) {
            videoFrame.post { enterFullScreen() }
        }

        // 自动全屏：设置开启时进入直播间自动横屏全屏
        if (ServiceContainer.appSettings.autoFullScreen.value) {
            videoFrame.post { enterFullScreen() }
        }
    }

    /* ---------- 横屏全屏切换（对应 Dart enterFullScreen/exitFull/toggleFullScreen）---------- */

    /** 进入横屏全屏：横屏方向 + 隐藏系统栏 + 仅播放器布局。 */
    fun enterFullScreen() {
        if (isFullScreen) return
        isFullScreen = true
        // 运行时方向覆盖 Manifest 的 portrait（SENSOR_LANDSCAPE 允许左右两个横向；configChanges 已声明，不 recreate）
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        applyFullScreenLayout()
        controlsOverlay.updateFullScreen(true)
        controlsOverlay.show()
    }

    /** 退出横屏全屏：恢复竖屏方向 + 系统栏 + 完整页面布局。 */
    fun exitFull() {
        if (!isFullScreen) return
        isFullScreen = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.show(WindowInsetsCompat.Type.systemBars())
        applyFullScreenLayout()
        controlsOverlay.updateFullScreen(false)
        controlsOverlay.show()
    }

    /** 切换全屏状态。 */
    fun toggleFullScreen() {
        if (isFullScreen) exitFull() else enterFullScreen()
    }

    /** 根据全屏状态切换布局：全屏仅视频区（MATCH_PARENT），竖屏恢复完整页面。 */
    private fun applyFullScreenLayout() {
        val videoLp = videoFrame.layoutParams as LinearLayout.LayoutParams
        if (isFullScreen) {
            // 隐藏非播放器区块，视频占满全屏
            titleBar.visibility = View.GONE
            infoBar.visibility = View.GONE
            tabLayout.visibility = View.GONE
            contentFrame.visibility = View.GONE
            videoLp.width = LinearLayout.LayoutParams.MATCH_PARENT
            videoLp.height = LinearLayout.LayoutParams.MATCH_PARENT
        } else {
            // 恢复竖屏完整页面（标题/信息/Tab/聊天状态都在原 View 中，零丢失）
            titleBar.visibility = View.VISIBLE
            infoBar.visibility = View.VISIBLE
            tabLayout.visibility = View.VISIBLE
            contentFrame.visibility = View.VISIBLE
            videoLp.width = LinearLayout.LayoutParams.MATCH_PARENT
            videoLp.height = videoHeight
        }
        videoFrame.layoutParams = videoLp
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_FULL_SCREEN, isFullScreen)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // configChanges 已声明 orientation|screenSize → 不 recreate；方向变化后重算竖屏 16:9 高度
        if (!isFullScreen && ::videoFrame.isInitialized) {
            val lp = videoFrame.layoutParams as LinearLayout.LayoutParams
            lp.width = LinearLayout.LayoutParams.MATCH_PARENT
            lp.height = videoHeight
            videoFrame.layoutParams = lp
        }
    }

    /* ---------- 播放器控制（对应 Dart player 控制 / 手势）---------- */

    private fun togglePlayPause() {
        val engine = PlayerEngine.instance
        if (engine.state.value.playing) {
            engine.pause()
        } else {
            engine.play()
        }
    }

    /** 弹幕开关：切换浮层弹幕启用状态并同步控制层按钮（对应 Dart setDanmakuVisible）。 */
    private fun toggleDanmaku() {
        val enabled = !danmakuOverlay.danmakuEnabled
        danmakuOverlay.danmakuEnabled = enabled
        controlsOverlay.updateDanmakuEnabled(enabled)
        Router.toast(if (enabled) "弹幕已开启" else "弹幕已关闭")
    }

    /** 调节屏幕亮度（左半屏滑动）：window.screenBrightness 0.05 步进，跟随系统时从 0.5 起。 */
    private fun adjustBrightness(delta: Float) {
        val attributes = window.attributes
        var brightness = attributes.screenBrightness
        if (brightness < 0f) brightness = 0.5f
        brightness += delta / 400f
        brightness = brightness.coerceIn(0.05f, 1f)
        attributes.screenBrightness = brightness
        window.attributes = attributes
        controlsOverlay.showAdjustHint("亮度 ${(brightness * 100).toInt()}")
    }

    /** 调节媒体音量（右半屏滑动）：AudioManager STREAM_MUSIC 步进 1。 */
    private fun adjustVolume(delta: Float) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        var volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        volume += if (delta > 0f) 1 else -1
        volume = volume.coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        controlsOverlay.showAdjustHint("音量 $volume/$max")
    }

    /* ---------- Tab 内容切换（对应 Dart buildMessageArea TabBar）---------- */

    /** 显示某 tab 的内容视图；聊天 = 弹幕列表，其余为对应内容，设置 = 入口占位。 */
    private fun showTab(tabKey: String?) {
        val isChat = tabKey == TAB_CHAT
        messagesRecycler.visibility = if (isChat) View.VISIBLE else View.GONE
        superChatRecycler.visibility = if (tabKey == "super_chat") View.VISIBLE else View.GONE
        contributionRecycler.visibility =
            if (tabKey == "contribution_rank") View.VISIBLE else View.GONE
        followTabRecycler.visibility = if (tabKey == "follow") View.VISIBLE else View.GONE
        val isSettings = tabKey == "settings"
        tabPlaceholder.visibility = if (isSettings) View.VISIBLE else View.GONE
        if (isSettings) {
            // 设置 tab：弹层入口说明（弹层本身在⋮菜单/控制层已有；此处引导）
            tabPlaceholder.text = "直播间设置请使用顶部⋮菜单"
            tabPlaceholder.setOnClickListener {
                LiveRoomSheets.showLiveSettingsSheet(this, controller)
            }
        } else {
            tabPlaceholder.setOnClickListener(null)
        }
        if (isChat && messagesAdapter.itemCount > 0) {
            messagesRecycler.scrollToPosition(messagesAdapter.itemCount - 1)
        }
    }

    /* ---------- 标签集合（对应 Dart 默认标签顺序 + 条件过滤）---------- */

    private fun buildTabEntries(siteId: String): List<Pair<String, String>> {
        val tabs = mutableListOf<Pair<String, String>>()
        tabs += TAB_CHAT to "聊天"
        when (siteId) {
            Constant.kBiliBili -> tabs += "super_chat" to "SC"
            Constant.kHuya -> tabs += "super_chat" to "头条"
        }
        tabs += "follow" to "关注"
        tabs += "contribution_rank" to if (siteId == Constant.kDouyu) "亲密榜" else "贡献榜"
        tabs += "settings" to "设置"
        return tabs
    }

    /* ---------- 状态绑定 ---------- */

    private fun bindState() {
        scope.launch {
            controller.detail.collect { detail ->
                titleText.text = detail?.title?.ifEmpty { null } ?: "直播间"
                userNameText.text = detail?.userName ?: ""
                siteNameText.text = controller.site?.name ?: ""
                NetImage.loadRound(userAvatar, detail?.userAvatar.orEmpty(), 24f)
            }
        }
        scope.launch {
            controller.online.collect {
                onlineText.text = "🔥 ${Utils.onlineToString(it)}"
            }
        }
        scope.launch {
            controller.liveStatus.collect {
                unliveOverlay.visibility = if (it) View.GONE else View.VISIBLE
            }
        }
        scope.launch {
            controller.messages.collect { list ->
                messagesAdapter.submit(list)
                if (!disableAutoScroll && list.isNotEmpty()) {
                    messagesRecycler.scrollToPosition(list.size - 1)
                }
                // 浮层弹幕：增量收集（列表清空/切房间时重置计数并清轨）
                if (list.size < danmakuLastCount) {
                    danmakuLastCount = 0
                    danmakuOverlay.clear()
                }
                if (list.size > danmakuLastCount) {
                    for (msg in list.subList(danmakuLastCount, list.size)) {
                        danmakuOverlay.enqueue(msg)
                    }
                    danmakuLastCount = list.size
                }
            }
        }
        scope.launch {
            controller.liveStatus.collect { live ->
                // 未开播/下播 → 浮层弹幕停用并清空（对齐 Dart emit 条件 !liveStatus return）
                danmakuOverlay.danmakuEnabled = live && ServiceContainer.appSettings.danmuEnable.value
            }
        }
        scope.launch {
            // 弹幕总开关设置变化 → 即时反映到浮层
            ServiceContainer.appSettings.danmuEnable.collect { enabled ->
                danmakuOverlay.danmakuEnabled = controller.liveStatus.value && enabled
            }
        }
        // 控制层：播放状态（▶/⏸）
        scope.launch {
            PlayerEngine.instance.state.collect { state ->
                controlsOverlay.updatePlaying(state.playing)
            }
        }
        // 控制层：直播状态（未开播隐藏播放按钮）
        scope.launch {
            controller.liveStatus.collect { live ->
                controlsOverlay.updateLiveStatus(live)
                // 未开播期间自动隐藏控制层
                if (!live) controlsOverlay.hide()
            }
        }
        // 控制层：直播时长
        scope.launch {
            controller.liveDuration.collect { duration ->
                controlsOverlay.updateDuration(duration)
            }
        }
        // 控制层：全屏态（顶栏/退出按钮）
        // isFullScreen 为 View 层字段，随 enterFullScreen/exitFull 直接同步
        // 控制层：清晰度/线路名
        scope.launch {
            controller.currentQualityInfo.collect { quality ->
                controlsOverlay.updateQualityLine(
                    quality,
                    controller.currentLineInfo.value,
                )
            }
        }
        scope.launch {
            controller.currentLineInfo.collect { line ->
                controlsOverlay.updateQualityLine(
                    controller.currentQualityInfo.value,
                    line,
                )
            }
        }
        scope.launch {
            // 自动退出倒计时结束 → 退出直播间（对应 Dart autoExitCompleting 触发返回）
            controller.autoExitCompleting.collect { completing ->
                if (completing) {
                    Router.toast("自动退出直播间")
                    finish()
                }
            }
        }
        // SC / 贡献榜 / 关注列表数据绑定（controller 已赋值后启动，避免 lateinit 未初始化）
        scope.launch {
            controller.superChats.collect { list ->
                superChatAdapter.submit(list)
            }
        }
        scope.launch {
            controller.contributionRankUsers.collect { list ->
                contributionAdapter.submit(list)
            }
            // 贡献榜首次进入时拉取（设置启用才拉；控制器已有逻辑）
            if (ServiceContainer.appSettings.contributionRankEnable.value &&
                !controller.contributionRankFetched.value
            ) {
                controller.fetchContributionRank()
            }
        }
        // 关注列表：直播中 + 未开播（复用 FollowService 数据；直播间内简化版）
        scope.launch {
            followService.liveList.collect { live ->
                followTabAdapter.submit(live + followService.notLiveList.value)
            }
        }
        scope.launch {
            followService.notLiveList.collect { notLive ->
                followTabAdapter.submit(followService.liveList.value + notLive)
            }
        }
    }

    override fun onDestroy() {
        controller.dispose()
        videoView.destroy()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

/** 聊天消息行间距（对应 Dart chatTextGap × 2 = 8dp）。 */
private class ChatGapDecoration(private val gap: Int) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(
        outRect: android.graphics.Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        outRect.bottom = gap
    }
}

/** 弹幕消息适配器：用户名（灰）+ 消息正文（深色）两段式；系统消息整行灰色。 */
private class MessagesAdapter : RecyclerView.Adapter<MessagesAdapter.VH>() {

    private val items = mutableListOf<LiveMessage>()

    fun submit(list: List<LiveMessage>) {
        items.clear()
        items.addAll(list.takeLast(200))
        notifyDataSetChanged()
    }

    class VH(val text: TextView) : RecyclerView.ViewHolder(text)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val text = TextView(parent.context).apply {
            setPadding(dp(12), dp(4), dp(12), dp(4))
            textSize = 14f
        }
        return VH(text)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.text.text = buildMessageText(items[position])
    }

    override fun getItemCount(): Int = items.size

    private fun buildMessageText(item: LiveMessage): CharSequence {
        val grey = Color.parseColor("#888888")
        val dark = Color.parseColor("#333333")
        if (item.userName == "LiveSysMessage") {
            return SpannableString(item.message).apply {
                setSpan(ForegroundColorSpan(grey), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        val builder = SpannableStringBuilder()
        val prefix = if (item.type == LiveMessageType.superChat) "【醒目】${item.userName}" else item.userName
        builder.append(prefix).append("：")
        builder.setSpan(ForegroundColorSpan(grey), 0, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        val start = builder.length
        builder.append(item.message)
        builder.setSpan(ForegroundColorSpan(dark), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        return builder
    }
}

/** SC 醒目留言列表：价格（醒目）+ 用户名 + 留言（对应 Dart buildSuperChats）。 */
private class SuperChatAdapter : RecyclerView.Adapter<SuperChatAdapter.VH>() {

    private val items = mutableListOf<LiveSuperChatMessage>()

    fun submit(list: List<LiveSuperChatMessage>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val root: LinearLayout, val nameText: TextView, val msgText: TextView, val priceText: TextView)
        : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val root = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(Color.WHITE)
        }
        val nameText = TextView(parent.context).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#FF3F4B"))
        }
        val msgText = TextView(parent.context).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#333333"))
        }
        val priceText = TextView(parent.context).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#FF9800"))
        }
        root.addView(nameText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(msgText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4) })
        root.addView(priceText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4) })
        return VH(root, nameText, msgText, priceText)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.nameText.text = "💬 ${item.userName}（醒目留言）"
        holder.msgText.text = item.message
        holder.priceText.text = "¥${item.price}"
    }

    override fun getItemCount(): Int = items.size
}

/** 贡献榜列表：名次 + 昵称 + 贡献值（对应 Dart 贡献榜）。 */
private class ContributionRankAdapter : RecyclerView.Adapter<ContributionRankAdapter.VH>() {

    private val items = mutableListOf<dev.livefork.core.model.LiveContributionRankItem>()

    fun submit(list: List<dev.livefork.core.model.LiveContributionRankItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val root: LinearLayout, val rankText: TextView, val nameText: TextView, val scoreText: TextView)
        : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val root = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(Color.WHITE)
        }
        val rankText = TextView(parent.context).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#FF9800"))
        }
        val nameText = TextView(parent.context).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#333333"))
        }
        val scoreText = TextView(parent.context).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
        }
        root.addView(rankText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(12) })
        root.addView(nameText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(scoreText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        return VH(root, rankText, nameText, scoreText)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.rankText.text = "${item.rank}"
        holder.nameText.text = item.userName
        holder.scoreText.text = item.scoreText
    }

    override fun getItemCount(): Int = items.size
}

/** 直播间内关注列表（简化版）：标题/用户名 + 平台 + 直播状态；点击切换直播间。 */
private class FollowTabAdapter(
    private val onOpenRoom: (dev.livefork.app.models.FollowUser) -> Unit,
) : RecyclerView.Adapter<FollowTabAdapter.VH>() {

    private val items = mutableListOf<dev.livefork.app.models.FollowUser>()

    fun submit(list: List<dev.livefork.app.models.FollowUser>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(
        val root: LinearLayout,
        val title: TextView,
        val sub: TextView,
        val status: TextView,
    ) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val root = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.WHITE)
        }
        val texts = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val title = TextView(parent.context).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#222222"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val sub = TextView(parent.context).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#888888"))
        }
        texts.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        texts.addView(sub, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val status = TextView(parent.context).apply {
            textSize = 12f
        }
        root.addView(status, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        return VH(root, title, sub, status)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val site = Sites.allSites[item.siteId]
        holder.title.text = item.userName
        holder.sub.text = (site?.name ?: item.siteId) +
            (item.roomTitle.ifEmpty { "" }.let { if (it.isEmpty()) "" else " · $it" })
        holder.status.text = when (item.liveStatus) {
            2 -> "直播中"
            0 -> "状态未知"
            else -> "未开播"
        }
        holder.status.setTextColor(
            if (item.liveStatus == 2) Color.parseColor("#FF3F4B") else Color.parseColor("#999999"),
        )
        holder.root.setOnClickListener { onOpenRoom(item) }
    }

    override fun getItemCount(): Int = items.size
}

private fun dp(value: Int): Int =
    (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()