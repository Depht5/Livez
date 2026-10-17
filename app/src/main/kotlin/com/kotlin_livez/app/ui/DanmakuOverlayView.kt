package com.kotlin_livez.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import coil.decode.DataSource
import coil.request.ImageRequest
import com.kotlin_livez.app.app.Constant
import com.kotlin_livez.app.app.controller.AppSettingsController
import com.kotlin_livez.app.di.ServiceContainer
import com.kotlin_livez.core.model.LiveMessage
import com.kotlin_livez.core.model.LiveMessageSpan
import com.kotlin_livez.core.model.LiveMessageType
import com.kotlin_livez.app.ui.theme.ImageError
import kotlin.math.ceil

/**
 * 视频浮层滚动弹幕 —— 对标 Flutter `canvas_danmaku` 的浮层渲染
 * （`live_room_controller.dart` `_scheduleOverlayDanmaku` + `addDanmaku`）。
 * 消息来自弹幕流（View 层增量收集后 enqueue）；录制参数直读 [AppSettingsController]。
 * 渲染：**每条弹幕预渲染成一张位图**（描边 + 填充 + 已就绪表情图一次性光栅化，
 * 见 [buildItemBitmap]），onDraw 每帧每条只需 1 次 `drawBitmap`；位图缺失/超长（> 2048px）
 * 时回退为逐片段原位绘制（见 [drawItemInline]，与位图光栅共用 [drawSegments]）。
 * 从右向左滚动，完全走出左边界后从列表移除并回收位图。
 * `danmuRenderEmoji` 开 → 渲染 spans/imageUrls 内联表情图。
 * 实现为**非交互层**（不拦截点击），保证全屏/控制层手势可用；
 * 不可见（视图 GONE / 应用退后台）时停掉帧驱动并在 [enqueue] 短路丢弃，杜绝后台空转耗电。
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
 *    本实现已对齐变速模型 + 追及校验（见 canEnterTrack）。（等速模型时期此校验
 *    恒通过曾被省略，变速后恢复。）
 * 4. 全部轨道均不可入轨时：**静默丢弃新弹幕**（selfSend 强制插首轨 / massiveMode
 *    随机插轨仅用于用户自发消息，直播间聊天不走这两条路径）→ 绝不顶掉在飞弹幕。
 * 5. 轨道数 = floor(可视高 × area / (fontSize×lineHeight))；Dart 层由
 *    `resolveDanmuTargetLineCount` 等把 danmuLineCount 设置折算成 area/lineHeight 传入；
 *    本重写以「设置行数 clamp(1, floor(层高×area/行高))」作为轨道行数（对齐 Dart，
 *    行数随视口高度变化，横竖屏自动不同）。
 *
 * 与原版的已知行为差异（有意保留）：
 * 1. 无 selfSend / massiveMode 特性（无自发送弹幕浮层回显需求），全满一律丢弃。
 * 2. 表情图为独立 bitmap 片段绘制（非原版 Paragraph 内联占位），观感一致。
 * 3. 视口尺寸变化（全屏切换）时已对齐原版：不清空、在飞弹幕继续推进，
 *    速度按当前视口宽每帧折算（原版 painter 每帧 size.width 参与 distance 计算）。
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
        /** 绘制宽度（文本 measureText / 图片缩放后宽 + [gapBefore]） */
        var width: Float,
        /** 图片位图（null = 尚未加载，绘制占位）；表情异步到位后由 [onEmojiLoaded] 补齐 */
        var bitmap: Bitmap?,
        /** 图片显示高度（文本片段忽略） */
        val imageHeight: Float,
        /**
         * 该片段前的间隙（仅图文混合的非首片段加 2dp，对齐既有构建逻辑）。
         * 单独存一份的原因：表情真实宽高比要在解码后才可知，届时需按「真实宽 + gapBefore」
         * 重算 [width]，不能只改位图。
         */
        val gapBefore: Float = 0f,
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
        /** 起始 x = 视口宽度（右侧外部），随 step 递减 */
        var x: Float,
        /** 弹幕时长（danmuSpeed 秒，入轨时设置快照；全屏切换后速度随视口宽动态折算） */
        val durationSec: Float,
        val imageHeightPx: Float,
    ) {
        /** 弹幕总宽（片段宽之和，用于出界判定与轨道布局）。 */
        var measuredWidth: Float = 0f

        /** 单条预渲染位图（描边 + 填充 + 已就绪表情），null = 未构建/构建失败 → onDraw 回退原位绘制。 */
        var cachedBitmap: Bitmap? = null

        /** [cachedBitmap] 在视图坐标系中的顶边（= 该项内容上边界，为表情上溢预留，见 [buildItemBitmap]）。 */
        var cachedTop: Float = 0f

        /**
         * [cachedBitmap] 构建时的绘制几何代次；与当前 [geometryGeneration] 不符说明设置/视口已变，
         * 位图（含烘进去的字号/描边/字重/透明度/轨距）必须重建。无位图时为 -1。
         */
        var cachedBitmapGeneration: Long = -1L

        /**
         * 已为哪个代次**尝试过**构建（成功与否都记录）。
         * 作用：构建失败（尺寸不可用）或超长跳过缓存时，避免每帧重复尝试分配。
         */
        var bitmapAttemptGeneration: Long = Long.MIN_VALUE
    }

    /** 在飞弹幕扁平列表（对齐 canvas_danmaku：轨道只是行号，同轨可同时多条）。 */
    private val items = ArrayList<TrackItem>()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 复用容器：`Paint.getFontMetrics(容器)` 填充既有实例，避免 onDraw 每项一次 FontMetrics 分配。 */
    private val fontMetricsReuse = Paint.FontMetrics()

    /** 复用矩形：表情 dstRect（原实现每帧每表情都 `RectF(...)` 新建一个，是 onDraw 的主要分配）。 */
    private val emojiDstRect = RectF()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingRunnables = ArrayList<Runnable>()

    private var animator: ValueAnimator? = null
    private var lastFrameTime = 0L

    /* ---------- 表情图缓存（LRU，上限 64）+ 在飞请求去重 ---------- */

    // 线程安全口径：本 map 为 accessOrder=true 的 LinkedHashMap——连 get() 都会改内部
    // 链表结构；一旦有第二个线程读/写（历史上 ensureEmojiPreload 曾在 Dispatchers.IO 写入，
    // 主线程绘制/测宽读取），无同步并发读写可能损坏桶结构（症状可为静默错乱）。
    // 改用 Coil enqueue 后写入已落在主线程回调，但读写在哪个线程仍不作假设 —— 所有访问
    // 统一持锁；锁内只有 O(1) map/set 操作，无绘制/解码等重活，不构成主线程瓶颈。
    private val emojiCache = object : LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > 64
    }

    /**
     * 并发去重集合：同一 url 只在飞一次（原实现每张表情都新建 ImageLoader 且同步 execute，
     * 同一表情在短时间内被多条弹幕引用时会重复解码）。请求在 onSuccess / onError 回调里移除。
     *
     * **锁边界**：[inFlightEmojiUrls] 与 [emojiCache] **共用 [emojiCacheLock] 这一把锁**
     * （不引入第二把锁 → 无嵌套、无死锁面）；所有访问都在锁内、且锁内只做 O(1) 集合操作，
     * 绝不持锁调用 Coil/解码/绘制。
     */
    private val inFlightEmojiUrls = HashSet<String>()

    /** [emojiCache] 与 [inFlightEmojiUrls] 的统一锁（读写在哪个线程发生不确定，必须全部持锁）。 */
    private val emojiCacheLock = Any()

    /* ---------- 绘制几何缓存（设置/视口变化才重算，onDraw 零分配的前提） ---------- */

    /** 绘制几何代次：设置或视口变化时 +1 → 所有 [TrackItem.cachedBitmap] 失效重建。 */
    private var geometryGeneration = 0L

    /** 上次快照的「设置 + 视口」键（见 [computeGeometryKey]），不变则几何零成本复用。 */
    private var geometryKey = Long.MIN_VALUE

    /** 当前设置下的弹幕字号（px）。 */
    private var geomTextSizePx = 0f

    /** 单行文本高（含描边，px）= estimateDanmuTextHeight。 */
    private var geomItemHeightPx = 0f

    /** 轨间距（轨道节距，px）= 单行高 × resolveDanmuLineHeight；位图行高与定位基准。 */
    private var geomTrackPitchPx = 0f

    /** 字重档位缓存键（-1 表示尚未构建；仅档位变化才重建 Typeface）。 */
    private var cachedTypefaceBucket = -1
    private var cachedTypeface: Typeface = Typeface.DEFAULT

    /**
     * 构造期（超类构造函数内）可能先回调可见性钩子，此时成员尚未初始化 —— 用此标志短路，
     * 避免 `items` 等字段的未初始化访问。
     */
    private var fieldsReady = false

    init {
        // 非交互层：不消费触摸事件
        isClickable = false
        isFocusable = false
        fieldsReady = true
    }

    /* ---------- 对外接口 ---------- */

    /** 入队一条弹幕（延迟 + 色值换算在这里做，对齐 Dart `_scheduleOverlayDanmaku`）。 */
    fun enqueue(msg: LiveMessage) {
        if (!danmakuEnabled) return
        // 不可见/应用退后台（浮层 GONE、窗口不可见）时直接丢弃：既不推进轨道也不起动画，
        // 杜绝后台空转（对齐本任务「后台休眠」要求；恢复可见后仍在飞的弹幕继续推进）。
        if (!isShown || windowVisibility != View.VISIBLE) return
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
        // 诊断日志已移除（弹幕轨道模型对齐 canvas_danmaku 后问题关闭）
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
        // 回收点 ②：清空（切房间/关弹幕）—— 逐条回收单条位图缓存
        for (t in items) recycleItemBitmap(t)
        items.clear()
        stopAnimator()
        invalidate()
    }

    /** 销毁（onDetachedFromWindow 已自动清理；宿主可再显式调用）。 */
    fun release() {
        clear()
    }

    /* ---------- 弹幕入轨 ---------- */

    /**
     * 目标行数 —— 复用 [AppSettingsController.resolveDanmuTargetLineCount]
     * （对齐 Dart resolveDanmuTargetLineCount）：设置行数被**视口高度**钳制到
     * `floor(层高 × area / 弹幕行高)` 上限。竖屏视口高 → 行数多；全屏横屏视口矮 →
     * 行数自动变少（「横竖屏不同行数」的实现）。0 = 不显示（设置行数 ≤ 0）。
     * 单位口径：fontSize/viewportHeight 均以 px 传入（与绘制一致）。
     */
    private fun resolveDanmuLineCount(): Int =
        appSettings.resolveDanmuTargetLineCount(
            viewportHeight = height.toFloat(),
            fontSize = appSettings.danmuSize.value * resources.displayMetrics.density,
        )

    /** 弹幕文本行像素高 —— 复用 [AppSettingsController.estimateDanmuTextHeight]（px 口径）。 */
    private fun danmakuLineHeightPx(): Float =
        appSettings.estimateDanmuTextHeight(
            fontSize = appSettings.danmuSize.value * resources.displayMetrics.density,
        )

    /**
     * 绘制几何快照键 —— 由「弹幕设置 + 视口高度」派生；任一项变化都必须重算几何
     * 并让所有单条位图失效（字号/字重/描边/透明度都烘在位图里，行数/区域影响轨距，
     * 视口高变化会让轨距与轨道 y 位置整体改变）。
     *
     * 说明：这里**不含** [resolveDanmuLineCount] 的显式调用——行数只在入轨
     * （[addToTrack] / [canEnterTrack]）时需要，绘制只关心由
     * `resolveDanmuLineHeight` 派生的轨距（它内部已按行数求解）。原 onDraw 每帧调用
     * 一次 resolveDanmuLineCount() 且结果未被使用，是纯粹的每帧浪费。
     */
    private fun computeGeometryKey(): Long {
        var key = height.toLong()
        key = key * 31 + appSettings.danmuSize.value.toRawBits()
        key = key * 31 + appSettings.danmuStrokeWidth.value.toRawBits()
        key = key * 31 + appSettings.danmuFontWeight.value
        key = key * 31 + appSettings.danmuOpacity.value.toRawBits()
        key = key * 31 + appSettings.danmuArea.value.toRawBits()
        key = key * 31 + appSettings.danmuLineCount.value
        key = key * 31 + (if (appSettings.danmuRenderEmoji.value) 1 else 0)
        // 气泡开关本视图目前不消费（danmuBubble 只作用于聊天列表，已 grep 核实），
        // 但一并入键：日后若本视图接入气泡背景绘制，缓存会自动失效而不会读到陈旧位图。
        key = key * 31 + (if (appSettings.danmuBubble.value) 1 else 0)
        return key
    }

    /**
     * 刷新绘制几何快照（设置/视口不变时零成本返回，仅键比较）。
     * 键变化 → 代次 +1 → 所有 [TrackItem.cachedBitmap] 视为陈旧，由 [ensureItemBitmap]
     * 在**绘制前**（step 的动画相位 / addToTrack / 表情回调）回收并重建，
     * 因此 onDraw 自身不需要任何分配。
     */
    private fun refreshGeometryIfNeeded() {
        val key = computeGeometryKey()
        if (key == geometryKey) return
        geometryKey = key
        geomTextSizePx = appSettings.danmuSize.value * resources.displayMetrics.density
        geomItemHeightPx = danmakuLineHeightPx()
        val viewH = height.toFloat()
        val lineHeightFactor = if (viewH <= 0f || geomItemHeightPx <= 0f) {
            1f
        } else {
            appSettings.resolveDanmuLineHeight(
                viewportHeight = viewH,
                fontSize = geomTextSizePx,
            )
        }
        geomTrackPitchPx = geomItemHeightPx * lineHeightFactor
        geometryGeneration++
    }

    /**
     * 字重 → Typeface 缓存（对齐既有档位分支：7-9 粗 / 5-6 中 / 其余默认）。
     * 原实现每条弹幕都 `Typeface.create(...)`；Typeface 只与字重档位有关，
     * 档位不变时复用同一实例。
     */
    private fun typefaceForWeight(weight: Int): Typeface {
        val bucket = when (weight) {
            in 7..9 -> 2
            in 5..6 -> 1
            else -> 0
        }
        if (bucket != cachedTypefaceBucket) {
            cachedTypeface = when (bucket) {
                2 -> Typeface.create("sans-serif-medium", Typeface.BOLD)
                1 -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
                else -> Typeface.DEFAULT
            }
            cachedTypefaceBucket = bucket
        }
        return cachedTypeface
    }

    private fun addToTrack(msg: LiveMessage) {
        if (!danmakuEnabled) return
        // 延迟任务（postDelayed 最长 6s）可能在退后台后触发：与 enqueue 同一套不可见短路，
        // 避免后台入轨并起动画空转。可见性恢复后在飞的弹幕继续推进，无需补发。
        if (!isShown || windowVisibility != View.VISIBLE) return
        // 对齐 Dart：显示几行 = 0 → 不显示弹幕；行数按视口高度钳制（全屏时自动变少）
        val lineCount = resolveDanmuLineCount()
        if (lineCount <= 0) return

        // 几何快照先于建位图刷新（字号/轨距/透明度都是位图烘焙的输入）
        refreshGeometryIfNeeded()

        val size = appSettings.danmuSize.value
        val textSizePx = size * resources.displayMetrics.density
        val font = typefaceForWeight(appSettings.danmuFontWeight.value)
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
        // 且（变速模型下）不会在旧弹幕出界前追上它，而非旧实现的"等前一条完全走出
        // 左边界才释放轨道"——后者导致高弹幕量下轨道恒满，新弹幕被"全满选 x 最小"
        // 策略顶掉刚出发的在飞弹幕。
        var target = -1
        for (track in 0 until lineCount) {
            if (canEnterTrack(track, itemWidth)) {
                target = track
                break
            }
        }
        if (target == -1) {
            // 全满兜底：对齐原版 = 静默丢弃新弹幕，绝不顶掉在飞弹幕
            // （原版 selfSend/massiveMode 强插路径仅用于用户自发消息，聊天弹幕不适用）
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
            durationSec = appSettings.danmuSpeed.value.coerceAtLeast(1f),
            imageHeightPx = imageHeightPx,
        )
        item.measuredWidth = itemWidth

        items.add(item)
        // 单条位图预渲染（几何已在上方刷新；构建失败或超长 → onDraw 回退原位逐片段绘制）
        ensureItemBitmap(item)
        // 预取未加载的表情图（异步；就绪后重建该项位图并重绘，见 onEmojiLoaded）
        preloadEmojis(segments)
        ensureAnimator()
    }

    /**
     * 轨道可入轨判定 —— 对齐 canvas_danmaku `_scrollCanAddToTrack` 两条条件：
     * 1. 候选轨道上每一条在飞弹幕都满足「右边缘(x+width) ≤ 视口宽度」才允许入轨
     *    （新弹幕起点 x=视口宽度，恰好接在前一条尾部之后，入场瞬间不重叠）；
     * 2. 追及校验：速度 ∝ 弹幕宽（px/s = (viewWidth+width)/duration，宽弹幕更快），
     *    若前一条比新弹幕窄（更慢），新弹幕可能在旧弹幕出界前追上它 → 拒绝入轨。
     *    （等速模型时期此校验恒通过被省略；对齐原版变速模型后必须恢复。）
     */
    private fun canEnterTrack(track: Int, newDanmakuWidth: Float): Boolean {
        val viewW = width.toFloat()
        for (item in items) {
            if (item.track != track) continue
            if (viewW - (item.x + item.measuredWidth) < 0f) {
                return false
            }
            // 追及校验（Dart 原式移植）：
            // 前一条更窄 → 更慢；检查新弹幕是否会在其出界前追上
            if (item.measuredWidth < newDanmakuWidth) {
                val prevPassed = 1f - ((viewW - item.x) / (item.measuredWidth + viewW))
                val newThreshold = viewW / (viewW + newDanmakuWidth)
                if (prevPassed > newThreshold) {
                    return false
                }
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
                        // gapBefore 单独存：表情真实宽高比解码后才可知，届时按
                        // 「真实宽 + gapBefore」重算 width（不能只重建位图）
                        val gapBefore = if (out.isNotEmpty()) gap else 0f
                        out.add(
                            Segment(
                                text = null,
                                imageUrl = url,
                                width = scaled.first + gapBefore,
                                bitmap = synchronized(emojiCacheLock) { emojiCache[url] },
                                imageHeight = scaled.second,
                                gapBefore = gapBefore,
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
                                // 与 spans 分支不同：此分支既有逻辑不加片段间隙（保持不变）
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

    /**
     * 异步预取表情图（Coil 解码 → [emojiCache]）；同轨弹幕持有 bitmap 引用，出界随条目释放。
     *
     * 根因修正（原实现）：`ioScope.launch { ImageLoader(context).execute(request) }` 对**每张**
     * 表情都 `ImageLoader(context)` 新建一个加载器（各自带内存缓存与 OkHttp 客户端，随即被丢弃）
     * 且用**同步** `execute` 阻塞 IO 线程，随后还要 `copy(ARGB_8888)` 再拷一份位图（峰值双份内存）。
     * 现改为全局单例 `coil.Coil.imageLoader(context).enqueue(request)`：
     * - 单例加载器自带内存缓存/线程池/OkHttp 客户端，不再逐图新建；
     * - enqueue 不阻塞调用线程（回调落在主线程，见 [onEmojiLoaded]）；
     * - 不再 copy：解码位图直接用作绘制源（`allowHardware(false)` 保证是软件位图，
     *   光栅到本视图自己的 ARGB_8888 位图与画布时不会被拒），峰值内存回到单份。
     * - 共享 loader 的内存缓存是全应用共用的：表情位图入的是同一个有界池，但本视图
     *   [emojiCache] 上限 64 条、单图约 16KB（峰值几十 KB ~ 1MB），远小于共享池容量，
     *   不会把封面图挤出去，故**不**为表情请求单独设置 MemoryCachePolicy。
     *
     * `allowHardware(false)` **必须保留**：位图后续要参与软件 Canvas 绘制
     * （[buildItemBitmap] 的光栅与 onDraw 回退路径），硬件位图会被软件绘制拒绝。
     */
    private fun ensureEmojiPreload(url: String) {
        // 去重：已缓存 或 同一 url 已在飞 → 直接返回（两套集合同锁，见 inFlightEmojiUrls）
        synchronized(emojiCacheLock) {
            if (emojiCache.containsKey(url) || !inFlightEmojiUrls.add(url)) return
        }
        val request = ImageRequest.Builder(context)
            .data(url)
            .allowHardware(false)
            .target(
                onSuccess = { drawable ->
                    // Coil target 回调在主线程（RealImageLoader 在 Dispatchers.Main.immediate 上分发）
                    val bitmap = (drawable as? BitmapDrawable)?.bitmap
                    synchronized(emojiCacheLock) { inFlightEmojiUrls.remove(url) }
                    if (bitmap != null) {
                        synchronized(emojiCacheLock) { emojiCache[url] = bitmap }
                        // 重算引用该表情的弹幕宽度 + 重建其位图 + 重绘，合并到这一处
                        //（原实现的 mainHandler.post { invalidate() } 一并并入）
                        onEmojiLoaded(url, bitmap)
                    }
                },
                onError = {
                    // 加载失败：只清在飞标记（后续该 url 可再次尝试；绘制仍走占位块）
                    synchronized(emojiCacheLock) { inFlightEmojiUrls.remove(url) }
                },
            )
            .build()
        coil.Coil.imageLoader(context).enqueue(request)
    }

    /**
     * 表情解码完成后的善后（主线程回调内执行，不再额外 post）：
     * ① 重算引用该 url 的片段宽度与条目总宽 —— 占位时按**方形**宽（= 图高）计入，
     *    真实宽按原图宽高比、限宽 120dp，故 `seg.width` / `item.width` 都会变；
     *    出屏判定（step 的 `x + measuredWidth <= 0`）与轨道入轨判定
     *    （canEnterTrack 的 `viewWidth - (x + width) >= 0`）都实时读这两个字段，
     *    因此重算即生效，不需要额外的"出屏重判定"步骤。
     * ② 重建位图（占位块 → 真实表情），并 invalidate 重绘。
     */
    private fun onEmojiLoaded(url: String, bitmap: Bitmap) {
        for (t in items) {
            var hit = false
            for (seg in t.segments) {
                if (seg.imageUrl != url) continue
                hit = true
                seg.bitmap = bitmap
                // 真实宽 = 按真实宽高比缩放（受 120dp 上限约束）+ 该片段前间隙
                val realWidth = scaledImageSize(url, t.textSizePx, seg.imageHeight).first
                seg.width = realWidth + seg.gapBefore
            }
            if (!hit) continue
            // 总宽重算（片段宽可能变大或变小）
            t.measuredWidth = t.segments.sumOf { it.width.toDouble() }.toFloat()
            // 位图内容已变（表情就位）→ 作废重建（旧位图丢引用交 GC：不在三个回收点内）
            dropItemBitmap(t)
            ensureItemBitmap(t)
        }
        invalidate()
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

    /* ---------- 单条位图缓存（预渲染 + 回收） ---------- */

    /** 位图缓存宽度上限：超出不做缓存，回退 onDraw 原位绘制（implement.md 回退路径）。 */
    private val maxCachedItemWidthPx = 2048f

    /**
     * 确保单条的位图缓存与当前几何代次一致（仅在主线程调用：addToTrack / step /
     * 表情回调；onDraw 自身**不**构建，避免绘制期分配）。
     * 返回是否已有可用位图；false → onDraw 走 [drawItemInline] 回退路径。
     *
     * 失败/超长都记入 [TrackItem.bitmapAttemptGeneration]，同一代次内不再重试
     * （否则 step 每帧都会重建失败项 → 逐帧分配）。
     */
    private fun ensureItemBitmap(item: TrackItem): Boolean {
        val bitmap = item.cachedBitmap
        if (bitmap != null && item.cachedBitmapGeneration == geometryGeneration) return true
        if (bitmap == null && item.bitmapAttemptGeneration == geometryGeneration) return false
        if (bitmap != null) {
            // 代次不符：旧位图不再可用。这里**只丢引用不回收** —— 本路径不在
            // design §2.3 的三个回收点（出屏 / clear / onDetachedFromWindow）内，
            // 而条目仍在屏上，旧位图刚被上一帧录制引用过，立即 recycle 会与
            // 录制的帧竞争（见 [recycleLater]）；交 GC 回收最安全。
            dropItemBitmap(item)
        }
        // 标记"本代次已尝试"（必须在 dropItemBitmap 之后赋值，否则会被它清掉 →
        // 超长/构建失败的条目会每帧重试）
        item.bitmapAttemptGeneration = geometryGeneration
        // 宽度取条目总宽（本实现的字段名是 measuredWidth，即设计稿里的 item.width）
        if (item.measuredWidth > maxCachedItemWidthPx) {
            // 超长弹幕：位图太宽（内存 + GL 纹理上限）→ 主动回退原位逐片段绘制
            return false
        }
        val built = buildItemBitmap(item)
        item.cachedBitmap = built
        item.cachedBitmapGeneration = if (built != null) geometryGeneration else -1L
        return built != null
    }

    /**
     * 仅作废单条的位图缓存引用（清"已尝试"标记 → 下次 [ensureItemBitmap] 必然重建），
     * **不回收**：用于重建路径（几何代次变化 / 表情就位），位图交 GC。
     */
    private fun dropItemBitmap(item: TrackItem) {
        item.cachedBitmap = null
        item.cachedBitmapGeneration = -1L
        item.bitmapAttemptGeneration = Long.MIN_VALUE
    }

    /**
     * 作废并回收单条的位图缓存 —— 只用于 design §2.3 的三个回收点
     * （出屏移除 / clear 清空 / onDetachedFromWindow），回收走 [recycleLater] 延后一帧。
     */
    private fun recycleItemBitmap(item: TrackItem) {
        val bitmap = item.cachedBitmap
        dropItemBitmap(item)
        if (bitmap != null) recycleLater(bitmap)
    }

    /**
     * 单条弹幕预渲染位图 —— 把原先 onDraw 每帧执行的「描边 + 填充 + 已就绪表情图」
     * 一次性光栅化到一张 ARGB_8888 位图，onDraw 每帧每条只需 1 次 `drawBitmap`
     * （每帧 2N 次 drawText + N 次 drawBitmap 降为 N 次纹理贴图）。
     *
     * 尺寸：
     * - 宽 = `ceil(item.measuredWidth).coerceIn(1, 4096)`（条目总宽 = 各片段宽之和）；
     * - 高 = 内容高度（以**轨距**为基准行高，再向上/下扩到该项内容边界），
     *   同时写入 [TrackItem.cachedTop] 供 onDraw 定位。
     *
     * **为什么高不是恰好轨距**：表情片段「底边对齐文本基线」而表情高 = 字号×1.2 > 文本高，
     * 故表情顶边会高于行顶（16sp@3x 约 8px；[TrackItem.cachedTop] 因此可能为负 = 超出视口
     * 上边界，与既有行为一致——那时本来就被 View 边界裁掉）。若把位图顶边固定为行顶、
     * 高度固定为轨距，**每一行**的表情顶部都会被裁掉这一截（观感回归），故顶边取内容上边界。
     *
     * 透明度：danmuOpacity 直接烘进位图的 Paint.alpha（本文件不存在 saveLayerAlpha /
     * setLayerType，弹幕整体半透明不需要硬件图层）。
     */
    private fun buildItemBitmap(item: TrackItem): Bitmap? {
        val lineHeight = geomTrackPitchPx
        // 尺寸不可用（宽度未测/轨距为 0）→ 不建位图，onDraw 走回退路径
        if (item.measuredWidth <= 0f || lineHeight <= 0f) return null

        // 与 [drawItemInline] 同源的基线与文字度量（字号/字重取该项入轨时的快照）
        fillPaint.textSize = item.textSizePx
        fillPaint.typeface = item.font
        fillPaint.getFontMetrics(fontMetricsReuse)
        val halfStroke = item.strokeWidthPx / 2f
        val baseline = (item.track * lineHeight + geomItemHeightPx * 0.8f)
            .coerceAtLeast(-fontMetricsReuse.top + halfStroke)

        var contentTop = baseline + fontMetricsReuse.top - halfStroke
        var contentBottom = baseline + fontMetricsReuse.bottom + halfStroke
        for (seg in item.segments) {
            if (seg.imageUrl == null) continue
            // 图片底边对齐基线 → 图片顶 = 基线 - 图高（可能高于文字顶）
            val imageTop = baseline - seg.imageHeight
            if (imageTop < contentTop) contentTop = imageTop
            if (baseline > contentBottom) contentBottom = baseline
        }

        val widthPx = ceil(item.measuredWidth).toInt().coerceIn(1, 4096)
        val heightPx = ceil(contentBottom - contentTop).toInt().coerceIn(1, 4096)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val alpha = (appSettings.danmuOpacity.value.coerceIn(0f, 1f) * 255f).toInt()

        fillPaint.color = item.color
        fillPaint.alpha = alpha
        fillPaint.textSize = item.textSizePx
        fillPaint.typeface = item.font
        if (item.strokeWidthPx > 0f) {
            strokePaint.color = item.strokeColor
            strokePaint.alpha = alpha
            strokePaint.textSize = item.textSizePx
            strokePaint.typeface = item.font
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = item.strokeWidthPx
        }
        // 位图坐标系：原点 = 该项内容左上角（x 相对条左缘 = 0，y 相对 contentTop = 0）
        drawSegments(canvas, item, 0f, baseline - contentTop, alpha)
        item.cachedTop = contentTop
        return bitmap
    }

    /**
     * 逐片段绘制（位图光栅与 onDraw 回退路径**共用同一实现**，保证两条路径观感一致）：
     * 文本 → 描边 + 填充 drawText；图片 → drawBitmap（底边对齐基线）；未加载 → 灰色占位块。
     * [startX] 为起始 x（位图为 0，原位绘制为 item.x），[baseY] 为文本基线。
     * 全程复用成员 Paint / [emojiDstRect]，零对象分配。
     */
    private fun drawSegments(canvas: Canvas, item: TrackItem, startX: Float, baseY: Float, alpha: Int) {
        var x = startX
        for (seg in item.segments) {
            val text = seg.text
            if (text != null) {
                if (item.strokeWidthPx > 0f) {
                    canvas.drawText(text, x, baseY, strokePaint)
                }
                canvas.drawText(text, x, baseY, fillPaint)
                x += seg.width
            } else if (seg.imageUrl != null) {
                val bmp = seg.bitmap ?: synchronized(emojiCacheLock) { emojiCache[seg.imageUrl] }
                if (bmp != null) {
                    // 图片底边对齐文本基线：top = 基线 - imageHeight
                    val top = baseY - seg.imageHeight
                    emojiDstRect.set(x, top, x + seg.width, top + seg.imageHeight)
                    canvas.drawBitmap(bmp, null, emojiDstRect, fillPaint)
                } else {
                    // 未加载完成：绘制占位块（半透明灰，保持既有观感）
                    fillPaint.style = Paint.Style.FILL
                    fillPaint.color = emojiPlaceholderColor
                    canvas.drawRect(x, baseY - seg.imageHeight, x + seg.width, baseY, fillPaint)
                    // 复位：占位块改过 color（连带 alpha），后续片段必须回到该项色值与整体不透明度
                    fillPaint.style = Paint.Style.FILL
                    fillPaint.color = item.color
                    fillPaint.alpha = alpha
                }
                x += seg.width
            }
        }
    }

    /**
     * 回退路径：原位逐片段绘制 —— 位图缓存缺失（构建失败/超长弹幕/几何代次刚变化）时使用，
     * 与 [buildItemBitmap] 共用同一套基线与 [drawSegments]，保证两条路径观感一致。
     *
     * 度量读取用 [fontMetricsReuse] 容器（`Paint.getFontMetrics(容器)`）——原实现
     * `fillPaint.fontMetrics` 每次调用都新建一个 FontMetrics 对象，逐项逐帧分配。
     */
    private fun drawItemInline(canvas: Canvas, item: TrackItem, alpha: Int) {
        fillPaint.color = item.color
        fillPaint.alpha = alpha
        fillPaint.textSize = item.textSizePx
        fillPaint.typeface = item.font
        fillPaint.getFontMetrics(fontMetricsReuse)
        // 基线公式与既有实现一致：轨道位置 × 轨距 + 行高×0.8，再钳制到文本不越上边界
        // （根因修复：行高低于字高时首轨基线小于「文本顶 + 描边半径」）
        val baseline = (item.track * geomTrackPitchPx + geomItemHeightPx * 0.8f)
            .coerceAtLeast(-fontMetricsReuse.top + item.strokeWidthPx / 2f)
        if (item.strokeWidthPx > 0f) {
            strokePaint.color = item.strokeColor
            strokePaint.alpha = alpha
            strokePaint.textSize = item.textSizePx
            strokePaint.typeface = item.font
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = item.strokeWidthPx
        }
        drawSegments(canvas, item, item.x, baseline, alpha)
    }

    /**
     * 回收单条位图 —— **延后一帧**（post 到主线程消息队列）而非就地 `recycle()`。
     *
     * 为什么延后：本 View 在硬件加速下，刚刚录制的这一帧里 `canvas.drawBitmap` 会把位图
     * 记录进该帧的 RenderNode/DisplayList；**立即 recycle 一个仍被已录制帧引用的位图**
     * 可能抛 `Canvas: trying to use a recycled bitmap`。post 出去的 runnable 在本次
     * 帧遍历（含 onDraw 录制）结束之后、下一帧重新录制之前执行，而三个回收点
     * （出屏 / clear / onDetachedFromWindow）都伴随 invalidate 或整帧重建，
     * 下一帧一定不再引用旧位图 → 回收策略不变、竞争窗口收窄到最小。
     */
    private fun recycleLater(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        mainHandler.post { if (!bitmap.isRecycled) bitmap.recycle() }
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

    /* ---------- 离屏/后台休眠（本任务新增：原来这两个钩子完全不存在） ---------- */

    /**
     * 窗口可见性变化（应用退后台 → INVISIBLE）：不可见时停掉帧驱动，避免后台空转耗电；
     * 恢复可见且仍有在飞弹幕时重新启动（在飞条目原地冻结，回来后继续推进，不清轨）。
     */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (!fieldsReady) return
        syncAnimatorWithVisibility()
    }

    /** 视图可见性变化（浮层 GONE / 祖先隐藏）：语义同 [onWindowVisibilityChanged]。 */
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!fieldsReady) return
        syncAnimatorWithVisibility()
    }

    private fun syncAnimatorWithVisibility() {
        val shown = isShown && windowVisibility == View.VISIBLE
        if (shown) {
            // 恢复可见：有活跃弹幕才重启（空轨道不空转）
            if (items.isNotEmpty()) ensureAnimator()
        } else {
            stopAnimator()
        }
    }

    private fun step(dtSec: Float) {
        // 设置/视口变化 → 重算几何（并让所有单条位图视为陈旧，下方统一重建）
        refreshGeometryIfNeeded()
        var dirty = false
        // 对齐 Dart scroll_danmaku_painter：速度 = (当前视口宽 + 弹幕宽) / 时长，
        // **每帧用当前视口宽折算**——全屏切换后视口变宽，在飞弹幕速度随之更新
        //（原版 painter 每帧 size.width 参与 distance 计算；非入轨时固化）。
        val viewW = width.toFloat()
        // 倒序遍历：移除元素只影响其后索引，倒序安全
        for (i in items.indices.reversed()) {
            val t = items[i]
            t.x -= (viewW + t.measuredWidth) / t.durationSec * dtSec
            if (t.x + t.measuredWidth <= 0f) {
                // 完全走出左边界 → 移除并回收单条位图（轨道是逻辑行号，无需"释放"动作）
                recycleItemBitmap(t)
                items.removeAt(i)
            }
            dirty = true
        }
        // 重建本帧缺失/失效的位图 —— 动画相位早于同帧 onDraw，因此 onDraw 只贴图不分配
        for (t in items) ensureItemBitmap(t)
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
        // 视口变化：**不清空**（对齐原项目 canvas_danmaku——尺寸变化时轨道与在飞弹幕
        // 继续推进，速度按当前视口宽每帧折算）。切房间/关闭弹幕的清空由外部 clear() 负责。
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (items.isEmpty()) return

        // 绘制几何：设置/视口不变时零成本复用（原实现每帧 resolveDanmuLineCount +
        // 新建 Paint 估算行高 → 每帧隐式分配）。行距对齐 Dart（与 addToTrack 同源）：
        // 行距 = 弹幕行高 × resolveDanmuLineHeight（clamp 1..3）——行数设置得少时行距拉大、
        // 弹幕疏朗；行数与视口上限一致时行距 ≈ 行高。
        refreshGeometryIfNeeded()
        val opacity = (appSettings.danmuOpacity.value.coerceIn(0f, 1f) * 255f).toInt()

        // 每帧每条：**1 次 drawBitmap**（描边 + 填充 + 已就绪表情在入轨/表情就绪时已烘进位图）。
        // 位图缺失或失效（超长弹幕、构建失败、几何刚变化）→ 原位逐片段绘制回退路径。
        for (t in items) {
            val bitmap = t.cachedBitmap
            if (bitmap != null && t.cachedBitmapGeneration == geometryGeneration) {
                canvas.drawBitmap(bitmap, t.x, t.cachedTop, null)
            } else {
                drawItemInline(canvas, t, opacity)
            }
        }
    }

    override fun onDetachedFromWindow() {
        // 回收点 ③：脱离窗口（离房/视图销毁）—— 清空轨道并回收全部单条位图
        clear()
        super.onDetachedFromWindow()
    }
}

/** 表情未加载时的占位块颜色（半透明灰，与既有观感一致）。 */
private val emojiPlaceholderColor = Color.argb(100, 128, 128, 128)

private fun dp(value: Int): Int =
    (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()