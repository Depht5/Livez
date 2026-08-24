package dev.livefork.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import coil.ImageLoader
import coil.decode.DataSource
import coil.request.ImageRequest
import dev.livefork.app.app.Constant
import dev.livefork.app.app.controller.AppSettingsController
import dev.livefork.app.di.ServiceContainer
import dev.livefork.core.model.LiveMessage
import dev.livefork.core.model.LiveMessageSpan
import dev.livefork.core.model.LiveMessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 视频浮层滚动弹幕 —— 对标 Flutter `canvas_danmaku` 的浮层渲染
 * （`live_room_controller.dart` `_scheduleOverlayDanmaku` + `addDanmaku`）。
 * 消息来自弹幕流（View 层增量收集后 enqueue）；录制参数直读 [AppSettingsController]。
 * 渲染：Canvas 逐片段绘制（文本 描边+填充；表情图 内联 bitmap），从右向左滚动，
 * 出左边界释放轨道。`danmuRenderEmoji` 开 → 渲染 spans/imageUrls 内联表情图。
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

    /* ---------- 弹幕片段模型（文本/表情图） ---------- */

    /** 单个片段：文本或图片二选一。 */
    private class Segment(
        val text: String?,
        val imageUrl: String?,
        /** 绘制宽度（文本 measureText / 图片缩放后宽） */
        val width: Float,
        /** 图片位图（null = 尚未加载，绘制占位） */
        val bitmap: Bitmap?,
        /** 图片显示高度（文本片段忽略） */
        val imageHeight: Float,
    )

    private class TrackItem(
        val segments: List<Segment>,
        /** 纯文本色（图片片段只参与绘制不设色） */
        val color: Int,
        val textSizePx: Float,
        val strokeWidthPx: Float,
        val strokeColor: Int,
        val font: Typeface,
        /** 起始 x = 视口宽度（右侧外部） */
        var x: Float,
        /** px/秒 */
        val speed: Float,
        val imageHeightPx: Float,
    ) {
        /** 弹幕总宽（片段宽之和，用于出界判定与轨道布局）。 */
        var measuredWidth: Float = 0f
    }

    /** 轨道：每轨道同时一条弹幕（简单分配）。 */
    private val tracks = ArrayList<TrackItem?>()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingRunnables = ArrayList<Runnable>()

    private var animator: ValueAnimator? = null
    private var lastFrameTime = 0L

    /* ---------- 表情图缓存（LRU，上限 64） ---------- */

    private val emojiCache = object : LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > 64
    }

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        // 对齐 Dart：显示几行 = 0 → 不显示弹幕
        val lineCount = appSettings.danmuLineCount.value
        if (lineCount <= 0) return
        // 轨道懒初始化：按当前弹幕行数保证轨道槽数量（tracks 初始为空，首条弹幕到达时补齐）
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
        val strokeWidthPx = appSettings.danmuStrokeWidth.value * resources.displayMetrics.density
        // 表情图高度 = 字号 * 1.2（对齐 canvas_danmaku 观感）；宽度按图等比
        val imageHeightPx = textSizePx * 1.2f

        fillPaint.textSize = textSizePx
        fillPaint.typeface = font

        // 构建片段：danmuRenderEmoji 开且消息含表情 → 图文混合；否则单文本
        val segments: List<Segment> =
            if (appSettings.danmuRenderEmoji.value) {
                buildSegmentsWithEmoji(msg, textSizePx, imageHeightPx)
            } else {
                listOf(
                    Segment(
                        text = msg.message,
                        imageUrl = null,
                        width = fillPaint.measureText(msg.message),
                        bitmap = null,
                        imageHeight = 0f,
                    ),
                )
            }
        if (segments.isEmpty()) return

        val item = TrackItem(
            segments = segments,
            color = color,
            textSizePx = textSizePx,
            strokeWidthPx = strokeWidthPx,
            strokeColor = Color.BLACK,
            font = font,
            x = 0f,
            speed = speedPxPerSec(appSettings.danmuSpeed.value),
            imageHeightPx = imageHeightPx,
        )
        item.measuredWidth = segments.sumOf { it.width.toDouble() }.toFloat()

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
        // 预取未加载的表情图（异步；就绪后动画循环自然重绘出现）
        preloadEmojis(segments)
        ensureAnimator()
    }

    /** 构建图文混合片段（对齐 Dart `_buildDanmakuContentParts`：spans 富文本，兜底 imageUrls）。 */
    private fun buildSegmentsWithEmoji(
        msg: LiveMessage,
        textSizePx: Float,
        imageHeightPx: Float,
    ): List<Segment> {
        val out = mutableListOf<Segment>()
        val gap = dp(2).toFloat()
        val spans = msg.spans
        if (spans != null && spans.isNotEmpty()) {
            for (span in spans) {
                if (span.isText) {
                    val text = span.text.orEmpty()
                    if (text.isNotEmpty()) {
                        out.add(
                            Segment(
                                text = text,
                                imageUrl = null,
                                width = fillPaint.measureText(text),
                                bitmap = null,
                                imageHeight = 0f,
                            ),
                        )
                    }
                } else if (span.isImage) {
                    val url = span.imageUrl?.trim().orEmpty()
                    if (url.isNotEmpty()) {
                        val scaled = scaledImageSize(url, textSizePx, imageHeightPx)
                        out.add(
                            Segment(
                                text = null,
                                imageUrl = url,
                                width = scaled.first + if (out.isNotEmpty()) gap else 0f,
                                bitmap = emojiCache[url],
                                imageHeight = scaled.second,
                            ),
                        )
                        ensureEmojiPreload(url)
                    }
                }
            }
        } else if (!msg.imageUrls.isNullOrEmpty()) {
            // 无 spans 但有 imageUrls（纯图弹幕）：图 + 文本兜底（对齐 Dart parts==null 分支）
            for (url in msg.imageUrls) {
                if (url.isBlank()) continue
                val scaled = scaledImageSize(url, textSizePx, imageHeightPx)
                out.add(
                    Segment(
                        text = null,
                        imageUrl = url,
                        width = scaled.first,
                        bitmap = emojiCache[url],
                        imageHeight = scaled.second,
                    ),
                )
                ensureEmojiPreload(url)
            }
            if (out.isNotEmpty() && msg.message.isNotBlank()) {
                out.add(
                    Segment(
                        text = msg.message,
                        imageUrl = null,
                        width = fillPaint.measureText(msg.message),
                        bitmap = null,
                        imageHeight = 0f,
                    ),
                )
            }
        } else {
            out.add(
                Segment(
                    text = msg.message,
                    imageUrl = null,
                    width = fillPaint.measureText(msg.message),
                    bitmap = null,
                    imageHeight = 0f,
                ),
            )
        }
        return out
    }

    /** 图片显示尺寸：高=imageHeightPx、宽按原图比例，限宽 120dp。 */
    private fun scaledImageSize(url: String, textSizePx: Float, imageHeightPx: Float): Pair<Float, Float> {
        val cached = emojiCache[url]
        if (cached != null) {
            val ratio = if (cached.height > 0) cached.width.toFloat() / cached.height.toFloat() else 1f
            var w = imageHeightPx * ratio
            val maxW = dp(120).toFloat()
            if (w > maxW) {
                w = maxW
            }
            return w to imageHeightPx
        }
        // 未加载：按方形占位宽 = 高
        return imageHeightPx to imageHeightPx
    }

    /** 异步预取表情图（Coil 解码 → LRU 缓存）；轨道持有 bitmap 引用，出界释放。 */
    private fun ensureEmojiPreload(url: String) {
        if (emojiCache.containsKey(url)) return
        ioScope.launch {
            try {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .allowHardware(false)
                    .build()
                val result = ImageLoader(context).execute(request)
                val bitmap = result.drawable?.let { drawable ->
                    val bmp = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    bmp?.copy(Bitmap.Config.ARGB_8888, false)
                }
                if (bitmap != null) {
                    emojiCache[url] = bitmap
                    // 主线程重绘（动画可能在跑，量小直接 invalidate）
                    mainHandler.post { invalidate() }
                }
            } catch (_: Exception) {
                // 表情图加载失败：忽略（后续到轨道会画占位）
            }
        }
    }

    private fun preloadEmojis(segments: List<Segment>) {
        for (seg in segments) {
            if (seg.imageUrl != null && !emojiCache.containsKey(seg.imageUrl)) {
                ensureEmojiPreload(seg.imageUrl)
            }
        }
    }

    private fun speedPxPerSec(speed: Float): Float {
        // 量纲换算：danmuSpeed（秒数 4–20）→ px/秒 ≈ 屏幕宽 / 秒数
        // 对齐 Dart duration 语义：越小越快（4 秒穿屏）
        return if (this.width > 0) {
            this.width / speed.coerceAtLeast(1f)
        } else {
            200f
        }
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
        // 对齐 Dart 弹幕显示"区域"：danmuArea 限定可用高度
        val area = appSettings.danmuArea.value.coerceIn(0.1f, 1f)
        val opacity = appSettings.danmuOpacity.value.coerceIn(0f, 1f)

        val usableHeight = (height * area).coerceAtLeast(0f)
        val lineHeight = usableHeight / lineCount

        for (i in tracks.indices) {
            val t = tracks[i] ?: continue
            val baseline = i * lineHeight + lineHeight * 0.8f
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
            }

            // 逐片段绘制：文本 → drawText；图片 → drawBitmap（图片底边对齐基线）
            var x = t.x
            for (seg in t.segments) {
                if (seg.text != null) {
                    if (t.strokeWidthPx > 0) {
                        canvas.drawText(seg.text, x, baseline, strokePaint)
                    }
                    canvas.drawText(seg.text, x, baseline, fillPaint)
                    x += seg.width
                } else if (seg.imageUrl != null) {
                    val bmp = seg.bitmap ?: emojiCache[seg.imageUrl]
                    if (bmp != null) {
                        // 图片底边对齐文本基线：top = baseline - imageHeight
                        val top = baseline - seg.imageHeight
                        canvas.drawBitmap(bmp, null, android.graphics.RectF(
                            x, top, x + seg.width, top + seg.imageHeight,
                        ), fillPaint)
                    } else {
                        // 未加载完成：绘制占位块
                        canvas.drawRect(
                            x, baseline - seg.imageHeight, x + seg.width, baseline,
                            fillPaint.apply { style = Paint.Style.FILL; color = Color.argb(100, 128, 128, 128) },
                        )
                        fillPaint.style = Paint.Style.FILL
                        fillPaint.color = t.color
                    }
                    x += seg.width
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        clear()
        ioScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onDetachedFromWindow()
    }
}

private fun dp(value: Int): Int =
    (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()