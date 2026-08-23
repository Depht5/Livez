package dev.livefork.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.livefork.app.app.controller.AppSettingsController
import dev.livefork.app.di.ServiceContainer

/**
 * 播放器控制层（竖屏/全屏共用）—— 对应 Flutter `player_controls.dart`
 * `buildControls`/`buildFullControls` 的 Android 简化版。
 * 结构：视频区顶部渐隐栏（返回/标题，全屏时） + 底部渐隐栏
 * （刷新/播放暂停/弹幕开关/直播时长/清晰度/线路/音量/退出全屏）+ 中央提示条（亮度/音量）。
 * 触摸与手势由宿主（LiveRoomActivity）挂 videoFrame 处理；本 View 只承载展示与按钮。
 */
class PlayerControlsOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val appSettings: AppSettingsController
        get() = ServiceContainer.appSettings

    private val mainHandler = Handler(Looper.getMainLooper())
    private var hideJob: Runnable? = null

    /* ---------- 对外状态 ---------- */

    /** 播放中（true 显示暂停图标） */
    var playing: Boolean = false
    /** 浮层弹幕开关 */
    var danmakuEnabled: Boolean = true
    /** 全屏态（true 显示顶栏 + 退出全屏按钮；false 竖屏布局） */
    var fullScreen: Boolean = false
    /** 是否已开播（false 隐藏播放/暂停按钮） */
    var liveStatus: Boolean = false

    /* ---------- 按钮点击回调（宿主注入） ---------- */

    var onBackClick: () -> Unit = {}
    var onRefreshClick: () -> Unit = {}
    var onPlayPauseClick: () -> Unit = {}
    var onDanmakuClick: () -> Unit = {}
    var onQualityClick: () -> Unit = {}
    var onLineClick: () -> Unit = {}
    var onFullScreenClick: () -> Unit = {}

    /* ---------- View ---------- */

    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var adjustHintText: TextView

    /* ---------- 按钮引用（状态更新用） ---------- */

    private lateinit var playPauseBtn: TextView
    private lateinit var danmakuBtn: TextView
    private lateinit var qualityBtn: TextView
    private lateinit var lineBtn: TextView
    private lateinit var fullScreenBtn: TextView
    private lateinit var durationText: TextView

    /** 自动隐藏延迟（ms）。 */
    private var autoHideMs = 5000L

    init {
        setBackgroundColor(Color.TRANSPARENT)

        /* ---- 顶栏（竖向条，全屏时显示） ---- */
        topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x99000000.toInt(), Color.TRANSPARENT),
            )
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val backBtn = iconButton("←").apply {
            setOnClickListener { onBackClick() }
        }
        topBar.addView(backBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        val topTitle = TextView(context).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            maxLines = 1
        }
        topBar.addView(topTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(topBar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        /* ---- 底栏（渐隐过渡到黑色） ---- */
        bottomBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xCC000000.toInt(), Color.TRANSPARENT),
            )
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        // 刷新
        bottomBar.addView(iconButton("⟳").apply {
            setOnClickListener { onRefreshClick() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        // 播放/暂停（按钮文字随状态由 refreshButtons 更新）
        playPauseBtn = iconButton("▶")
        playPauseBtn.setOnClickListener { onPlayPauseClick() }
        bottomBar.addView(playPauseBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        // 弹幕开关
        danmakuBtn = iconButton("弹")
        danmakuBtn.setOnClickListener { onDanmakuClick() }
        bottomBar.addView(danmakuBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        // 直播时长
        durationText = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
        }
        bottomBar.addView(durationText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(8) })

        // 弹性空间
        bottomBar.addView(LinearLayout(context), LinearLayout.LayoutParams(0, 0, 1f))

        // 清晰度入口（toast 占位 → 子任务④接线）
        qualityBtn = iconButton("清晰度")
        qualityBtn.setOnClickListener { onQualityClick() }
        bottomBar.addView(qualityBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(6) })
        // 线路入口
        lineBtn = iconButton("线路")
        lineBtn.setOnClickListener { onLineClick() }
        bottomBar.addView(lineBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(6) })
        // 全屏切换
        fullScreenBtn = iconButton("⛶")
        fullScreenBtn.setOnClickListener { onFullScreenClick() }
        bottomBar.addView(fullScreenBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(6) })

        val bottomLp = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        bottomLp.gravity = Gravity.BOTTOM
        addView(bottomBar, bottomLp)

        /* ---- 中央提示条（亮度/音量调节提示，1.2s 自动消失） ---- */
        adjustHintText = TextView(context).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0x66000000.toInt())
                cornerRadius = dp(6).toFloat()
            }
            setPadding(dp(16), dp(8), dp(16), dp(8))
            visibility = View.GONE
        }
        val hintLp = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        hintLp.gravity = Gravity.CENTER
        addView(adjustHintText, hintLp)

        visibility = View.GONE
    }

    private fun iconButton(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(6), dp(8), dp(6))
    }

    /* ---------- 对外控制 ---------- */

    /** 显示控制层并重置自动隐藏计时。 */
    fun show() {
        visibility = View.VISIBLE
        refreshButtons()
        scheduleAutoHide()
    }

    /** 立即隐藏控制层。 */
    fun hide() {
        visibility = View.GONE
        hideJob?.let { mainHandler.removeCallbacks(it) }
        hideJob = null
    }

    fun toggle() {
        if (visibility == View.VISIBLE) hide() else show()
    }

    /** 播放状态更新（▶/⏸ 图标）。 */
    fun updatePlaying(value: Boolean) {
        playing = value
        playPauseBtn.text = if (value) "⏸" else "▶"
    }

    /** 弹幕开关状态更新（选中色）。 */
    fun updateDanmakuEnabled(value: Boolean) {
        danmakuEnabled = value
        danmakuBtn.setTextColor(if (value) Color.parseColor("#FFD857") else Color.WHITE)
    }

    /** 全屏态更新：顶栏显隐 + 全屏按钮文字。 */
    fun updateFullScreen(value: Boolean) {
        fullScreen = value
        topBar.visibility = if (value) View.VISIBLE else View.GONE
        fullScreenBtn.text = if (value) "⛶✕" else "⛶"
    }

    /** 直播状态：未开播时隐藏播放/暂停按钮。 */
    fun updateLiveStatus(value: Boolean) {
        liveStatus = value
        playPauseBtn.visibility = if (value) View.VISIBLE else View.GONE
    }

    /** 直播时长（对应 Dart liveDuration）。 */
    fun updateDuration(text: String) {
        durationText.text = text
    }

    /** 清晰度/线路名称（未接线时显示占位名）。 */
    fun updateQualityLine(quality: String, line: String) {
        qualityBtn.text = quality.ifEmpty { "清晰度" }
        lineBtn.text = line.ifEmpty { "线路" }
    }

    /** 显示调节提示（亮度/音量），1.2s 后消失（若有旧提示先移除）。 */
    fun showAdjustHint(message: String) {
        adjustHintText.text = message
        adjustHintText.visibility = View.VISIBLE
        hideJob?.let { mainHandler.removeCallbacks(it) }
        hideJob = Runnable { adjustHintText.visibility = View.GONE }
        mainHandler.postDelayed(hideJob!!, 1200L)
    }

    /** 每次触点重置自动隐藏计时。 */
    fun onUserTouch() {
        if (visibility == View.VISIBLE) scheduleAutoHide()
    }

    private fun scheduleAutoHide() {
        hideJob?.let { mainHandler.removeCallbacks(it) }
        hideJob = Runnable { hide() }
        mainHandler.postDelayed(hideJob!!, autoHideMs)
    }

    private fun refreshButtons() {
        playPauseBtn.text = if (playing) "⏸" else if (liveStatus) "▶" else ""
        playPauseBtn.visibility = if (liveStatus) View.VISIBLE else View.GONE
        danmakuBtn.setTextColor(if (danmakuEnabled) Color.parseColor("#FFD857") else Color.WHITE)
        topBar.visibility = if (fullScreen) View.VISIBLE else View.GONE
        fullScreenBtn.text = if (fullScreen) "⛶✕" else "⛶"
    }

    override fun onDetachedFromWindow() {
        hideJob?.let { mainHandler.removeCallbacks(it) }
        hideJob = null
        super.onDetachedFromWindow()
    }
}

private fun dp(value: Int): Int =
    (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()