package dev.livefork.app.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.livefork.app.app.Sites
import dev.livefork.app.di.ServiceContainer
import dev.livefork.app.modules.live_room.LiveRoomController
import dev.livefork.app.player.MpvInitConfig
import dev.livefork.app.player.MpvVideoView
import dev.livefork.app.player.PlayerEngine
import dev.livefork.app.services.MpvOptionsService
import dev.livefork.core.model.LiveMessage
import dev.livefork.core.model.LiveMessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 直播间播放页 —— 对应 Flutter `live_room_page.dart`（View 层）。
 * 全屏 mpv 视频 + 覆盖式控制层（信息/操作/弹幕），支持横屏手势：
 * 单击 显示/隐藏控制栏，双击 播放/暂停，上下滑动 调节音量。
 */
class LiveRoomActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_SITE_ID = "site_id"
        private const val EXTRA_ROOM_ID = "room_id"

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
    private lateinit var videoView: MpvVideoView
    private lateinit var controlPanel: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var titleText: TextView
    private lateinit var subText: TextView
    private lateinit var followButton: TextView
    private lateinit var qualityButton: TextView
    private lateinit var lineButton: TextView
    private lateinit var messagesRecycler: RecyclerView
    private lateinit var messagesAdapter: MessagesAdapter

    private val audioManager: AudioManager get() = getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // 手势累计：音量/亮度（左右半屏分开）
    private var volumeScrollAccum = 0f
    private var brightnessScrollAccum = 0f
    private var controlsVisible = true
    private var adjustMsgJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val siteId = intent.getStringExtra(EXTRA_SITE_ID) ?: run { finish(); return }
        val roomId = intent.getStringExtra(EXTRA_ROOM_ID) ?: run { finish(); return }
        val site = Sites.allSites[siteId] ?: run {
            Router.toast("站点不存在：$siteId")
            finish()
            return
        }

        // 沉浸 + 常亮
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setFormat(PixelFormat.TRANSLUCENT)

        // ---- 布局：全屏视频 + 覆盖控制层 ----
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        videoView = MpvVideoView(this)
        root.addView(videoView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        // 覆盖控制层（底部）
        controlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setBackgroundColor(Color.parseColor("#88000000"))
        }

        // 信息栏
        val infoBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp3(16), dp3(10), dp3(16), dp3(10))
        }
        val infoTexts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        titleText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            maxLines = 1
        }
        subText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#CCCCCC"))
        }
        infoTexts.addView(titleText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        infoTexts.addView(subText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        infoBar.addView(infoTexts, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
        ))
        followButton = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#E53935"))
            setPadding(dp3(12), dp3(6), dp3(12), dp3(6))
            text = "关注"
        }
        infoBar.addView(followButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        controlPanel.addView(infoBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        // 操作栏
        val actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp3(16), dp3(6), dp3(16), dp3(6))
        }
        qualityButton = makeActionButton("清晰度")
        lineButton = makeActionButton("线路")
        statusText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#CCCCCC"))
            gravity = Gravity.END
        }
        actionBar.addView(qualityButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        actionBar.addView(lineButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        actionBar.addView(statusText, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
        ))
        controlPanel.addView(actionBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        // 弹幕列表
        messagesRecycler = RecyclerView(this)
        messagesAdapter = MessagesAdapter()
        messagesRecycler.layoutManager = LinearLayoutManager(this)
        messagesRecycler.adapter = messagesAdapter
        controlPanel.addView(messagesRecycler, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp3(220),
        ))

        root.addView(controlPanel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        setContentView(root)
        setupGestures(root)
        hideSystemBars()

        // ---- 控制器与播放器 ----
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

        followButton.setOnClickListener {
            if (controller.followed.value) {
                controller.removeFollowUser()
            } else {
                controller.followUser()
            }
        }
        qualityButton.setOnClickListener {
            showQualityDialog()
        }
        lineButton.setOnClickListener {
            showLineDialog()
        }

        bindState()

        controller.create(site, roomId)
    }

    /* ---------- 手势 ---------- */

    private fun setupGestures(root: View) {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                togglePlayPause()
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                // 垂直滑动：左半屏调亮度，右半屏调音量
                val startX = e1?.x ?: e2.x
                val screenWidth = resources.displayMetrics.widthPixels
                if (startX < screenWidth / 2f) {
                    brightnessScrollAccum += distanceY
                    if (kotlin.math.abs(brightnessScrollAccum) >= dp3(24)) {
                        adjustBrightness(brightnessScrollAccum > 0)
                        brightnessScrollAccum = 0f
                    }
                } else {
                    volumeScrollAccum += distanceY
                    if (kotlin.math.abs(volumeScrollAccum) >= dp3(24)) {
                        adjustVolume(volumeScrollAccum > 0)
                        volumeScrollAccum = 0f
                    }
                }
                return true
            }
        })
        // 手势开关实时生效（PlayerGestureControlEnable）
        root.setOnTouchListener { _, event ->
            if (!ServiceContainer.appSettings.playerGestureControlEnable.value) return@setOnTouchListener false
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun toggleControls() {
        controlsVisible = !controlsVisible
        controlPanel.visibility = if (controlsVisible) View.VISIBLE else View.GONE
    }

    private fun togglePlayPause() {
        val player = PlayerEngine.instance
        if (player.state.value.playing) {
            player.pause()
            toast("已暂停")
        } else {
            player.play()
            toast("继续播放")
        }
    }

    private fun adjustVolume(increase: Boolean) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = (if (increase) current + 1 else current - 1).coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        showAdjustMessage("音量 $target/$max")
    }

    /** 屏幕亮度（默认值跟随系统，首滑从 0.5 起步）。 */
    private fun adjustBrightness(increase: Boolean) {
        val lp = window.attributes
        val current = if (lp.screenBrightness in 0.01f..1f) lp.screenBrightness else 0.5f
        val target = (if (increase) current + 0.05f else current - 0.05f).coerceIn(0.05f, 1f)
        lp.screenBrightness = target
        window.attributes = lp
        showAdjustMessage("亮度 ${(target * 100).toInt()}%")
    }

    /** 操作栏临时显示调节信息，1.2s 后恢复直播状态。 */
    private fun showAdjustMessage(message: String) {
        statusText.text = message
        adjustMsgJob?.cancel()
        adjustMsgJob = scope.launch {
            kotlinx.coroutines.delay(1200)
            statusText.text = if (controller.liveStatus.value) "直播中" else "未开播/转播"
        }
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun showSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    /* ---------- UI 工具 ---------- */

    private fun makeActionButton(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.parseColor("#FF8A80"))
        gravity = Gravity.CENTER
        setPadding(dp3(10), dp3(4), dp3(10), dp3(4))
    }

    /** 绑定控制器状态流 → 视图。 */
    private fun bindState() {
        scope.launch {
            controller.detail.collect { detail ->
                titleText.text = detail?.userName ?: "加载中…"
                subText.text = when {
                    detail == null -> ""
                    else -> "${detail.title}\n${detail.categoryName ?: ""} · 在线 ${controller.online.value}"
                }
            }
        }
        scope.launch {
            controller.online.collect {
                val detail = controller.detail.value
                if (detail != null) {
                    subText.text = "${detail.title}\n${detail.categoryName ?: ""} · 在线 $it"
                }
            }
        }
        scope.launch {
            controller.followed.collect {
                followButton.text = if (it) "已关注" else "关注"
            }
        }
        scope.launch {
            controller.liveStatus.collect {
                statusText.text = if (it) "直播中" else "未开播/转播"
            }
        }
        scope.launch {
            controller.loadError.collect { error ->
                if (error) statusText.text = "加载失败：${controller.errorMsg.value}"
            }
        }
        scope.launch {
            controller.messages.collect { list ->
                messagesAdapter.submit(list)
                if (!controller.disableAutoScroll.value && list.isNotEmpty()) {
                    messagesRecycler.scrollToPosition(list.size - 1)
                }
            }
        }
        scope.launch {
            controller.currentQualityInfo.collect { info ->
                qualityButton.text = if (info.isEmpty()) "清晰度" else info
            }
        }
        scope.launch {
            controller.currentLineInfo.collect { info ->
                lineButton.text = if (info.isEmpty()) "线路" else info
            }
        }
    }

    private fun showQualityDialog() {
        val qualities = controller.qualites.value
        if (qualities.isEmpty()) {
            Router.toast("暂无清晰度信息")
            return
        }
        val names = qualities.map { it.quality }
        AlertDialog.Builder(this)
            .setTitle("选择清晰度")
            .setItems(names.toTypedArray()) { _, which ->
                controller.currentQuality = which
                controller.getPlayUrl()
            }
            .show()
    }

    private fun showLineDialog() {
        val lines = controller.playUrls.value
        if (lines.isEmpty()) {
            Router.toast("暂无线路信息")
            return
        }
        val labels = lines.mapIndexed { index, _ -> "线路 ${index + 1}" }
        AlertDialog.Builder(this)
            .setTitle("选择播放线路")
            .setItems(labels.toTypedArray()) { _, which ->
                controller.changePlayLine(which)
            }
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        controller.dispose()
        videoView.destroy()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }
}

/** 弹幕消息适配器。 */
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
            setPadding(dp3(12), dp3(4), dp3(12), dp3(4))
            textSize = 13f
        }
        return VH(text)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.text.text = buildMessageText(item)
        holder.text.setTextColor(
            when {
                item.userName == "LiveSysMessage" -> Color.parseColor("#FF9800")
                item.type == LiveMessageType.superChat -> Color.parseColor("#E91E63")
                else -> Color.WHITE
            },
        )
    }

    override fun getItemCount(): Int = items.size

    private fun buildMessageText(item: LiveMessage): String = when {
        item.userName == "LiveSysMessage" -> "【系统】${item.message}"
        item.type == LiveMessageType.superChat -> "【醒目】${item.userName}：${item.message}"
        else -> "${item.userName}：${item.message}"
    }
}

private fun dp3(value: Int): Int =
    (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()