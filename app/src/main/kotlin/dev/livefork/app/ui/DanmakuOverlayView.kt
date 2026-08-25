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
import dev.livefork.app.ui.screens.DanmuDebug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 视频浮层滚动弹幕 —— 对标 Flutter `canvas_danmaku` 的浮层渲染
 * （`live_room_controller.dart` `_scheduleOverlayDanmaku` + `addDanmaku`）。
 * 消息来自弹幕流（View 层增量收集后 enqueue）；录制参数直读 [AppSettingsController]。
 * 渲染：Canvas 逐片段绘制（文本 描边+填充；表情图 内联 bitmap），从右向左滚动，
 * 完全走出左边界后从列表移除。`danmuRenderEmoji` 开 → 渲染 spans/imageUrls 内联表情图。
 * 实现为**非交互层**（不拦截点击），保证全屏/控制层手势可用。
 *
 * ## 与 canvas_danmaku 的轨道管理对比结论
 *
 * 参照源码：`dart_simple_live/third_party/canvas_danmaku`（pubspec.lock 锁定 v0.2.7 本地包）
 * 的 `danmaku_screen.dart` / `scroll_danmaku_painter.dart`。真机实证（tag=DanmuDebug）：
 * 旧实现「一槽一弹、完全出左边界才释放」导致轨道恒满，每条新弹幕都按"全满选 x 最小"
 * 顶掉刚出发的在飞弹幕（replaced=true 每小时数千次，prevX≈视口右侧 0.5 秒行程处）。
 *
 * 原版轨道管理语义（本次修复的对齐目标）：
 * 1. 轨道只是 y 行号列表，弹幕存于**单一扁平 List**；**同一条轨道可同时容纳多条弹幕**。
 * 2. 入轨条件（`_scrollCanAddToTrack`）：候选轨道上每一条在飞弹幕都满足
 *    `viewWidth - (x + width) >= 0`，即**前一条的右边缘已进入屏幕内**
 *    （新弹幕起点 x=viewWidth，恰接在其尾部之后，入场瞬间不重叠）——
 *    而不是等前一条完全走出左边界。这是旧实现"轨道永不空闲"的根因差异。
 * 3. 追及校验：原版速度 ∝ 弹幕宽度（px/s = (viewWidth+width)/duration，宽者快），
 *    若前一条比新弹幕窄（更慢），还需校验新弹幕不会在旧弹幕出界前追上它。
 * 4. 全部轨道均不可入轨时：**静默丢弃新弹幕**（selfSend 强制插首轨 / massiveMode
 *    随机插轨仅用于用户自发消息，直播间聊天不走这两条路径）→ 绝不顶掉在飞弹幕。
 * 5. 轨道数 = floor(可视高 × area / (fontSize×lineHeight))；Dart 层由
 *    `resolveDanmuTargetLineCount` 等把 danmuLineCount 设置折算成 area/lineHeight 传入；
 *    本重写直接以 danmuLineCount 作为轨道行数（视觉等效，不改传参）。
 *
 * 与原版的已知行为差异（有意保留）：
 * 1. 速度模型：原版宽弹幕更快；本实现所有弹幕等速（viewWidth/danmuSpeed）。
 *    等速下一旦前一条右边缘进入屏幕，两条弹幕间距单调不减，永不重叠，
 *    故原版第 3 条追及校验在本实现中恒通过，予以省略。
 * 2. 无 selfSend / massiveMode 特性（无自发送弹幕浮层回显需求），全满一律丢弃。
 * 3. 表情图为独立 bitmap 片段绘制（非原版 Paragraph 内联占位），观感一致。
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
        /** 所属轨道行号（对齐原版 yPosition；同轨可同时多条在飞） */
        val track: Int,
        /** 起始 x = 视口宽度（右侧外部） */
        var x: Float,
        /** px/秒 */
        val speed: Float,
        val imageHeightPx: Float,
    ) {
        /** 弹幕总宽（片段宽之和，用于出界判定与轨道布局）。 */
        var measuredWidth: Float = 0f
    }

    /** 在飞弹幕扁平列表（对齐 canvas_danmaku：轨道只是行号，同轨可同时多条）。 */
    private val items = ArrayList<TrackItem>()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingRunnables = ArrayList<Runnable>()

    private var animator: ValueAnimator? = null
    private var lastFrameTime = 0L

    /* ---------- 表情图缓存（LRU，上限 64） ---------- */

    // 线程安全修正：本 map 为 accessOrder=true 的 LinkedHashMap——连 get() 都会改内部
    // 链表结构；而 ensureEmojiPreload 在 Dispatchers.IO 写入、绘制/测宽在主线程读取，
    // 无同步并发读写可能损坏桶结构（症状可为静默错乱）。所有访问统一持锁；
    // 锁内只有 O(1) map 操作，无绘制/解码等重活，不构成主线程瓶颈。
    private val emojiCache = object : LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > 64
    }

    /** emojiCache 的统一锁（读写在哪个线程发生不确定，必须全部持锁）。 */
    private val emojiCacheLock = Any()

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
        // 诊断日志，定位后移除：观察入队节奏与延迟队列积压
        if (DanmuDebug.enabled) {
            android.util.Log.d(
                DanmuDebug.TAG,
                "enqueue: pending=${pendingRunnables.size} delay=${totalDelay}ms text=${msg.message.take(12)}",
            )
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
        // 诊断日志，定位后移除：任何非预期 clear 都会清掉在飞弹幕，需核对调用方
        if (DanmuDebug.enabled) {
            android.util.Log.d(DanmuDebug.TAG, "clear: 在飞弹幕=${items.size} pending=${pendingRunnables.size}", Throwable("clear 调用栈"))
        }
        pendingRunnables.forEach { mainHandler.removeCallbacks(it) }
        pendingRunnables.clear()
        items.clear()
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

        val itemWidth = segments.sumOf { it.width.toDouble() }.toFloat()

        // 轨道选择 —— 对齐 canvas_danmaku `_scrollCanAddToTrack`（根因修复）：
        // 按行号顺序找第一条可入轨的轨道。可入轨 = 该轨道上**每一条**在飞弹幕的右边缘
        // (x+width) 已进入屏幕内（前一条已左移到为新弹幕让出整个入口宽度），
        // 而非旧实现的"等前一条完全走出左边界才释放轨道"——后者导致高弹幕量下
        // 轨道恒满，新弹幕被"全满选 x 最小"策略顶掉刚出发的在飞弹幕。
        var target = -1
        for (track in 0 until lineCount) {
            if (canEnterTrack(track)) {
                target = track
                break
            }
        }
        val freeHit = target != -1
        if (!freeHit) {
            // 全满兜底：对齐原版 = 静默丢弃新弹幕，绝不顶掉在飞弹幕
            // （原版 selfSend/massiveMode 强插路径仅用于用户自发消息，聊天弹幕不适用）
            if (DanmuDebug.enabled) {
                android.util.Log.d(
                    DanmuDebug.TAG,
                    "addToTrack: drop(全满丢弃) freeHit=false items=${items.size} itemW=${itemWidth.toInt()} viewW=$width",
                )
            }
            return
        }

        // 起始位置：视口右侧外部（从屏幕右边缘外滑入）；注意用视口宽度 this.width，
        // 而非弹幕文本宽度，否则会在左半屏"突然出现"
        val startX = this.width.toFloat()
        val item = TrackItem(
            segments = segments,
            color = color,
            textSizePx = textSizePx,
            strokeWidthPx = strokeWidthPx,
            strokeColor = Color.BLACK,
            font = font,
            track = target,
            x = startX,
            speed = speedPxPerSec(appSettings.danmuSpeed.value),
            imageHeightPx = imageHeightPx,
        )
        item.measuredWidth = itemWidth

        // 诊断日志：freeHit=true 表示命中空闲判定入轨；false 只会走上面的丢弃分支。
        // 真机复验预期：freeHit=true 占比接近 100%，drop(全满丢弃) 仅在瞬间洪峰出现，
        // 且不再有任何"顶掉在飞弹幕"的行为。
        if (DanmuDebug.enabled) {
            val prev = items.lastOrNull { it.track == target }
            android.util.Log.d(
                DanmuDebug.TAG,
                "addToTrack: track=$target freeHit=$freeHit prevX=${prev?.x?.toInt() ?: -1} newX=${item.x.toInt()} itemW=${item.measuredWidth.toInt()} viewW=$width",
            )
        }
        items.add(item)
        // 预取未加载的表情图（异步；就绪后动画循环自然重绘出现）
        preloadEmojis(segments)
        ensureAnimator()
    }

    /**
     * 轨道可入轨判定 —— 对齐 canvas_danmaku `_scrollCanAddToTrack` 的主条件：
     * 候选轨道上每一条在飞弹幕都满足「右边缘(x+width) ≤ 视口宽度」才允许入轨
     * （新弹幕起点 x=视口宽度，恰好接在前一条尾部之后，入场瞬间不重叠）。
     *
     * 原版的第二条追及校验（前一条更窄→更慢，可能被更宽更快的新弹幕在出界前追上）
     * 在本实现中省略：本实现所有弹幕等速（见类 KDoc 差异 1），同轨前后两条的间距
     * 单调不减，追及重叠不可能发生。
     */
    private fun canEnterTrack(track: Int): Boolean {
        val viewW = this.width.toFloat()
        for (item in items) {
            if (item.track != track) continue
            if (viewW - (item.x + item.measuredWidth) < 0f) {
                return false
            }
        }
        return true
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
                                bitmap = synchronized(emojiCacheLock) { emojiCache[url] },
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
                                bitmap = synchronized(emojiCacheLock) { emojiCache[url] },
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
        val cached = synchronized(emojiCacheLock) { emojiCache[url] }
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
        if (synchronized(emojiCacheLock) { emojiCache.containsKey(url) }) return
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
                    synchronized(emojiCacheLock) { emojiCache[url] = bitmap }
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
            if (seg.imageUrl != null &&
                synchronized(emojiCacheLock) { !emojiCache.containsKey(seg.imageUrl) }
            ) {
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
        // 诊断日志，定位后移除：动画重启打点——若弹幕断流后出现本日志而无后续
        // addToTrack，说明是入队侧停了；有 addToTrack 却无本日志说明启动分支被跳过
        if (DanmuDebug.enabled) {
            android.util.Log.d(DanmuDebug.TAG, "animator: 启动")
        }
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
        // 诊断日志，定位后移除：动画停止打点（全轨道空时正常省电停止；若在飞弹幕
        // 仍存在时出现，说明轨道被异常清空）
        if (DanmuDebug.enabled) {
            android.util.Log.d(DanmuDebug.TAG, "animator: 停止（剩余在飞弹幕=${items.size}）")
        }
        animator?.cancel()
        animator = null
        lastFrameTime = 0L
    }

    private fun step(dtSec: Float) {
        var dirty = false
        // 倒序遍历：移除元素只影响其后索引，倒序安全
        for (i in items.indices.reversed()) {
            val t = items[i]
            t.x -= t.speed * dtSec
            if (t.x + t.measuredWidth <= 0f) {
                // 完全走出左边界 → 从列表移除（轨道是逻辑行号，无需"释放"动作）
                items.removeAt(i)
            }
            dirty = true
        }
        if (dirty) {
            invalidate()
        }
        // 全空 → 停动画省电
        if (items.isEmpty()) {
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
        if (items.isEmpty()) return

        val lineCount = appSettings.danmuLineCount.value.coerceAtLeast(1)
        // 对齐 Dart 弹幕显示"区域"：danmuArea 限定可用高度
        val area = appSettings.danmuArea.value.coerceIn(0.1f, 1f)
        val opacity = appSettings.danmuOpacity.value.coerceIn(0f, 1f)

        val usableHeight = (height * area).coerceAtLeast(0f)
        val lineHeight = usableHeight / lineCount

        for (t in items) {
            fillPaint.color = t.color
            fillPaint.alpha = (opacity * 255).toInt()
            fillPaint.textSize = t.textSizePx
            fillPaint.typeface = t.font

            // 根因修复（真机症状 2：最上方的弹幕超出播放器边界几个像素）：
            // 原基线 = trackIndex*lineHeight + lineHeight*0.8 固定比例，未校验文本 ascent——
            // 当行高（usableHeight/lineCount，随 danmuArea/danmuLineCount 设置变小）低于
            // 字高时，第一条轨道的基线小于「文本顶(+描边半径)」，字形越出视口上边界。
            // 最小修复：基线向下钳制到文本完整落在视口内的最小值；行高充足时比例不变。
            // （fontMetrics 是纯结构体读取，逐项读取开销可忽略，不影响绘制性能。）
            val minBaseline = -fillPaint.fontMetrics.top + t.strokeWidthPx / 2f
            val baseline = (t.track * lineHeight + lineHeight * 0.8f).coerceAtLeast(minBaseline)
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
                    val bmp = seg.bitmap ?: synchronized(emojiCacheLock) { emojiCache[seg.imageUrl] }
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