package dev.livefork.app.ui

import android.view.MotionEvent

/**
 * 视频区触摸手势判定（单击/双击/滑动）。
 * 手写实现：android.view.GestureDetector 的 onScroll/onFling（带原始 float 参数）在
 * Kotlin 2.0.21 下覆盖解析异常（"overrides nothing"），故不继承 SimpleOnGestureListener。
 * 判定逻辑：
 * - 单击：up 时位移 < 阈值且间隔 < 长按阈值；
 * - 双击：两次单击间隔 < 300ms；（单击会延迟 ~300ms 确认，双击优先）
 * - 滑动：move 累计位移 > 阈值，按起始 x 分左右半屏（由回调决定亮度/音量）。
 */
class GestureRouter(
    private val onSingleTap: () -> Unit,
    private val onDoubleTap: () -> Unit,
    /** 参数：起始 x、累计 distanceY（down 到当前） */
    private val onScroll: (startX: Float, distanceY: Float) -> Unit,
) {
    companion object {
        private const val TAP_SLOP_DP = 12
        private const val SCROLL_SLOP_DP = 18
        private const val DOUBLE_TAP_INTERVAL_MS = 300L
        private const val LONG_PRESS_MS = 500L
    }

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var sliding = false
    private var totalDy = 0f

    /** 每触摸事件流调用（shouldReturn true 表示事件已消费）。 */
    fun onTouch(event: MotionEvent) {
        val density = android.content.res.Resources.getSystem().displayMetrics.density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                totalDy = 0f
                sliding = false
                downTime = System.currentTimeMillis()
            }

            MotionEvent.ACTION_MOVE -> {
                if (!sliding) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    val slop = SCROLL_SLOP_DP * density
                    if (Math.abs(dy) > slop || Math.abs(dx) > slop) {
                        sliding = true
                    }
                }
                if (sliding) {
                    // 每次 MOVE 的增量位移（等价 GestureDetector onScroll 的 distanceY）
                    val dy = event.y - downY
                    downY = event.y
                    onScroll(downX, dy)
                }
            }

            MotionEvent.ACTION_UP -> {
                if (sliding) {
                    sliding = false
                    return
                }
                val dx = event.x - downX
                val dy = event.y - downY
                val tapSlop = TAP_SLOP_DP * density
                val now = System.currentTimeMillis()
                val elapsed = now - downTime
                if (Math.abs(dx) < tapSlop && Math.abs(dy) < tapSlop && elapsed < LONG_PRESS_MS) {
                    // 单击候选；若距上次单击 < 300ms 且位置接近 → 双击
                    val doubleTap = now - lastTapTime < DOUBLE_TAP_INTERVAL_MS &&
                        Math.abs(event.x - lastTapX) < tapSlop * 2 &&
                        Math.abs(event.y - lastTapY) < tapSlop * 2
                    lastTapTime = now
                    lastTapX = event.x
                    lastTapY = event.y
                    if (doubleTap) {
                        onDoubleTap()
                        lastTapTime = 0L // 双击后重置，避免三击触发第二双击
                    } else {
                        onSingleTap()
                    }
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                sliding = false
            }
        }
    }
}