package dev.livefork.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import dev.livefork.app.app.Constant
import dev.livefork.app.app.controller.AppSettingsController
import dev.livefork.app.di.ServiceContainer
import dev.livefork.core.model.LiveMessage
import dev.livefork.core.model.LiveMessageType

/**
 * 视频浮层滚动弹幕 —— 对标 Flutter `canvas_danmaku` 的浮层渲染
 * （`live_room_controller.dart` `_scheduleOverlayDanmaku` + `addDanmaku`）。
 * 消息来自弹幕流（View 层增量收集后 enqueue）；录制参数直读 [AppSettingsController]。
 * 渲染：Canvas 描边 + 填充文本，从右向左滚动，出左边界释放轨道。
 * 实现为**非交互层**（不拦截点击），保证全屏/控制层手势可用。
 */
class DanmakuOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 弹幕设置来源（ServiceContainer 单例）。 */
    val appSettings: AppSettingsController
        get() = ServiceContainer.appSettings

    /** 当前站点 id（弹幕延迟按站点取值；huya 额外 +1000ms，对齐 Dart）。 */
    var siteId: String = ""

    /** 是否启用浮层弹幕（Activity 汇总 danmuEnable + 弹幕开关 + 直播状态后设置）。 */
    var danmakuEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (!value) {
                clear()
            }
        }

    /* ---------- 在轨弹幕模型 ---------- */

    private class TrackItem(
        val text: String,
        val color: Int,
        val textSizePx: Float,
        val strokeWidthPx: Float,
        val strokeColor: Int,
        val font: Typeface,
        /** 起始 x = 视口宽度（右侧外部） */
        var x: Float,
        /** px/秒 */
        val speed: Float,
    ) {
        var measuredWidth: Float = 0f
    }

    /** 轨道：每轨道同时一条弹幕（简单 round-robin 分配）。 */
    private val tracks = ArrayList<TrackItem?>()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingRunnables = ArrayList<Runnable>()

    private var animator: ValueAnimator? = null
    private var lastFrameTime = 0L

    init {
        // 非交互层：不消费触摸事件
        isClickable = false
        isFocusable = false
    }

    /* ---------- 对外接口 ---------- */

    /** 入队一条弹幕（延迟 + 色值换算在这里做，对齐 Dart `_scheduleOverlayDanmaku`）。 */
    fun enqueue(msg: LiveMessage) {
        if (!danmakuEnabled) return
        if (msg.type != LiveMessageType.chat) return
        // 系统消息不滚动（对齐 Dart：只对普通 chat addDanmaku）
        if (msg.userName == "LiveSysMessage") return

        val baseDelay = appSettings.getDanmuDelayMs(siteId)
        val totalDelay = (baseDelay + if (siteId == Constant.kHuya) 1000 else 0).coerceIn(0, 6000)
        val task = object : Runnable {
            override fun run() {
                pendingRunnables.remove(this)
                addToTrack(msg)
            }
        }
        if (totalDelay == 0) {
            addToTrack(msg)
        } else {
            pendingRunnables.add(task)
            mainHandler.postDelayed(task, totalDelay.toLong())
        }
    }

    /** 清空轨道与未触发的延迟弹幕（切房间/关闭弹幕时调用）。 */
    fun clear() {
        pendingRunnables.forEach { mainHandler.removeCallbacks(it) }
        pendingRunnables.clear()
        for (i in tracks.indices) {
            tracks[i] = null
        }
        stopAnimator()
        invalidate()
    }

    /** 销毁（onDetachedFromWindow 已自动清理；宿主可再显式调用）。 */
    fun release() {
        clear()
    }

    /* ---------- 弹幕入轨 ---------- */

    private fun addToTrack(msg: LiveMessage) {
        if (!danmakuEnabled) return
        // 轨道懒初始化：按当前弹幕行数保证轨道槽数量（tracks 初始为空，首条弹幕到达时补齐）
        val lineCount = appSettings.danmuLineCount.value.coerceAtLeast(1)
        while (tracks.size < lineCount) {
            tracks.add(null)
        }
        val size = appSettings.danmuSize.value
        val textSizePx = size * resources.displayMetrics.density
        val font = when (appSettings.danmuFontWeight.value) {
            in 7..9 -> Typeface.create("sans-serif-medium", Typeface.BOLD)
            in 5..6 -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> Typeface.DEFAULT
        }
        val color = Color.rgb(msg.color.r, msg.color.g, msg.color.b)

        fillPaint.textSize = textSizePx
        fillPaint.typeface = font
        val width = fillPaint.measureText(msg.message)

        val item = TrackItem(
            text = msg.message,
            color = color,
            textSizePx = textSizePx,
            strokeWidthPx = appSettings.danmuStrokeWidth.value * resources.displayMetrics.density,
            strokeColor = Color.BLACK,
            font = font,
            x = 0f,
            speed = speedPxPerSec(appSettings.danmuSpeed.value),
        )
        item.measuredWidth = width

        // 轨道选择：优先找空轨道；全满则取 x 最小（最接近出界）的轨道复用
        var target = -1
        for (i in tracks.indices) {
            if (tracks[i] == null) {
                target = i
                break
            }
        }
        if (target == -1) {
            var minX = Float.MAX_VALUE
            for (i in tracks.indices) {
                val t = tracks[i] ?: continue
                if (t.x < minX) {
                    minX = t.x
                    target = i
                }
            }
        }
        if (target == -1) return
        // 起始位置：视口右侧外部（从屏幕右边缘外滑入，不挤占已有弹幕）；
        // 注意用视口宽度 this.width，而非弹幕文本宽度，否则会在左半屏"突然出现"
        item.x = this.width.toFloat()

        tracks[target] = item
        ensureAnimator()
    }

    private fun speedPxPerSec(speed: Float): Float {
        // 量纲换算：danmuSpeed(默认 10) → 约 150dp/s，观感对齐 Dart
        return speed * resources.displayMetrics.density * 12f
    }

    /* ---------- 动画驱动（单循环推进所有在轨弹幕） ---------- */

    private fun ensureAnimator() {
        if (animator != null) return
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = Long.MAX_VALUE
        a.interpolator = LinearInterpolator()
        a.addUpdateListener {
            val now = System.nanoTime()
            if (lastFrameTime == 0L) {
                lastFrameTime = now
                return@addUpdateListener
            }
            val dtSec = (now - lastFrameTime) / 1_000_000_000f
            lastFrameTime = now
            step(dtSec)
        }
        a.start()
        animator = a
    }

    private fun stopAnimator() {
        animator?.cancel()
        animator = null
        lastFrameTime = 0L
    }

    private fun step(dtSec: Float) {
        var dirty = false
        for (i in tracks.indices) {
            val t = tracks[i] ?: continue
            t.x -= t.speed * dtSec
            if (t.x + t.measuredWidth <= 0f) {
                // 出左边界 → 释放轨道
                tracks[i] = null
            }
            dirty = true
        }
        if (dirty) {
            invalidate()
        }
        // 全空 → 停动画省电
        if (tracks.all { it == null }) {
            stopAnimator()
        }
    }

    /* ---------- 绘制 ---------- */

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 视口变化：清空重来（全屏切换时轨道简单重置）
        clear()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (tracks.isEmpty()) return

        val lineCount = appSettings.danmuLineCount.value.coerceAtLeast(1)
        val area = appSettings.danmuArea.value.coerceIn(0.1f, 1f)
        val opacity = appSettings.danmuOpacity.value.coerceIn(0f, 1f)
        val topMargin = appSettings.danmuTopMargin.value * resources.displayMetrics.density
        val bottomMargin = appSettings.danmuBottomMargin.value * resources.displayMetrics.density

        val usableTop = topMargin
        val usableHeight = (height * area - topMargin - bottomMargin).coerceAtLeast(0f)
        val lineHeight = if (lineCount > 0) usableHeight / lineCount else usableHeight

        for (i in tracks.indices) {
            val t = tracks[i] ?: continue
            val baseline = usableTop + i * lineHeight + lineHeight * 0.8f

            fillPaint.color = t.color
            fillPaint.alpha = (opacity * 255).toInt()
            fillPaint.textSize = t.textSizePx
            fillPaint.typeface = t.font

            if (t.strokeWidthPx > 0) {
                strokePaint.color = t.strokeColor
                strokePaint.alpha = fillPaint.alpha
                strokePaint.textSize = t.textSizePx
                strokePaint.typeface = t.font
                strokePaint.style = Paint.Style.STROKE
                strokePaint.strokeWidth = t.strokeWidthPx
                canvas.drawText(t.text, t.x, baseline, strokePaint)
            }
            canvas.drawText(t.text, t.x, baseline, fillPaint)
        }
    }

    override fun onDetachedFromWindow() {
        clear()
        super.onDetachedFromWindow()
    }
}