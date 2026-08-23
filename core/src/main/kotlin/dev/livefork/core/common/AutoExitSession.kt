package dev.livefork.core.common

import java.time.Duration
import java.time.Instant

/**
 * 自动退出来源。
 * 对应 Dart `AutoExitSource`（lib/src/common/auto_exit_session.dart）。
 */
enum class AutoExitSource { none, global, roomOverride }

/**
 * 自动退出会话：全局与单房间两种倒计时。
 * 对应 Dart `AutoExitSession`。
 */
class AutoExitSession {
    var source: AutoExitSource = AutoExitSource.none
    var deadline: Instant? = null
    var globalDeadline: Instant? = null

    val enabled: Boolean get() = source != AutoExitSource.none && deadline != null

    /** 活动截止时间是否已到达。 */
    fun isDue(now: Instant): Boolean {
        val currentDeadline = deadline
        return enabled && currentDeadline != null && !currentDeadline.isAfter(now)
    }

    fun startGlobal(now: Instant, minutes: Int) {
        val duration = Duration.ofMinutes(normalizeMinutes(minutes).toLong())
        globalDeadline = now.plus(duration)
        deadline = globalDeadline
        source = AutoExitSource.global
    }

    fun startRoomOverride(now: Instant, minutes: Int) {
        deadline = now.plus(Duration.ofMinutes(normalizeMinutes(minutes).toLong()))
        source = AutoExitSource.roomOverride
    }

    fun stop() {
        source = AutoExitSource.none
        deadline = null
        globalDeadline = null
    }

    fun remaining(now: Instant): Duration {
        val currentDeadline = deadline ?: return Duration.ZERO
        val value = Duration.between(now, currentDeadline)
        return if (value.isNegative) Duration.ZERO else value
    }

    fun globalRemaining(now: Instant): Duration {
        val currentDeadline = globalDeadline ?: return Duration.ZERO
        val value = Duration.between(now, currentDeadline)
        return if (value.isNegative) Duration.ZERO else value
    }

    private fun normalizeMinutes(minutes: Int): Int = minutes.coerceIn(1, 24 * 60)
}