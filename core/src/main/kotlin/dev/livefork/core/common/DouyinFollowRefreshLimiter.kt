package dev.livefork.core.common

import java.time.Duration
import java.time.Instant

/**
 * 抖音关注刷新限流配置。
 * 对应 Dart `DouyinFollowLimiterConfig`（lib/src/common/douyin_follow_refresh_limiter.dart）。
 */
data class DouyinFollowLimiterConfig(
    val initialConcurrency: Int,
    val initialInterval: Duration,
    val minInterval: Duration,
    val maxInterval: Duration = Duration.ofMillis(1200),
)

/**
 * 限流结束汇总。
 * 对应 Dart `DouyinFollowLimiterSummary`。
 */
data class DouyinFollowLimiterSummary(
    val targetCount: Int,
    val successCount: Int,
    val limitedCount: Int,
    val cooledDown: Boolean,
    val initialInterval: Duration,
    val finalInterval: Duration,
    val initialConcurrency: Int,
    val elapsed: Duration,
)

/**
 * 抖音关注刷新限流器：按目标数量选择并发/间隔，遇风控降速，连续成功提速。
 * 对应 Dart `DouyinFollowRefreshLimiter`。
 */
class DouyinFollowRefreshLimiter private constructor(
    val config: DouyinFollowLimiterConfig,
) {
    private val startNanos: Long = System.nanoTime()
    private val workerNextReadyAt = MutableList(config.initialConcurrency) { Instant.now() }
    private var successCount = 0
    private var limitedCount = 0
    private var consecutiveSuccess = 0
    private var currentInterval: Duration = config.initialInterval
    private var pausedUntil: Instant? = null
    private var cooledDown = false

    val initialConcurrency: Int get() = config.initialConcurrency
    val initialInterval: Duration get() = config.initialInterval
    val currentIntervalValue: Duration get() = currentInterval
    val successCountValue: Int get() = successCount
    val limitedCountValue: Int get() = limitedCount
    val cooledDownValue: Boolean get() = cooledDown

    companion object {
        fun forTargetCount(targetCount: Int): DouyinFollowRefreshLimiter = when {
            targetCount <= 20 -> DouyinFollowRefreshLimiter(
                DouyinFollowLimiterConfig(
                    initialConcurrency = 4,
                    initialInterval = Duration.ofMillis(60),
                    minInterval = Duration.ofMillis(40),
                )
            )
            targetCount <= 100 -> DouyinFollowRefreshLimiter(
                DouyinFollowLimiterConfig(
                    initialConcurrency = 3,
                    initialInterval = Duration.ofMillis(120),
                    minInterval = Duration.ofMillis(80),
                )
            )
            targetCount <= 300 -> DouyinFollowRefreshLimiter(
                DouyinFollowLimiterConfig(
                    initialConcurrency = 2,
                    initialInterval = Duration.ofMillis(220),
                    minInterval = Duration.ofMillis(140),
                )
            )
            else -> DouyinFollowRefreshLimiter(
                DouyinFollowLimiterConfig(
                    initialConcurrency = 1,
                    initialInterval = Duration.ofMillis(320),
                    minInterval = Duration.ofMillis(220),
                )
            )
        }
    }

    /** 请求前等待，直到该 worker 槽位空闲。 */
    suspend fun beforeRequest(workerIndex: Int) {
        if (workerNextReadyAt.isEmpty()) return
        val slotIndex = if (workerIndex >= 0) workerIndex % workerNextReadyAt.size else 0
        while (true) {
            val now = Instant.now()
            val paused = pausedUntil
            if (paused != null && now.isBefore(paused)) {
                kotlinx.coroutines.delay(Duration.between(now, paused).toMillis())
                continue
            }
            val nextReadyAt = workerNextReadyAt[slotIndex]
            if (now.isBefore(nextReadyAt)) {
                kotlinx.coroutines.delay(Duration.between(now, nextReadyAt).toMillis())
                continue
            }
            workerNextReadyAt[slotIndex] = Instant.now().plus(currentInterval)
            return
        }
    }

    fun onSuccess() {
        successCount += 1
        consecutiveSuccess += 1
        if (consecutiveSuccess >= 16) {
            consecutiveSuccess = 0
            currentInterval = decreaseInterval(currentInterval)
        }
    }

    /** @return true 表示已触发降级冷却（对应 Dart onLimited 返回 true）。 */
    fun onLimited(): Boolean {
        limitedCount += 1
        consecutiveSuccess = 0
        if (limitedCount >= 2) {
            cooledDown = true
            return true
        }
        currentInterval = increaseInterval(currentInterval)
        pausedUntil = Instant.now().plus(Duration.ofSeconds(15))
        for (i in workerNextReadyAt.indices) {
            workerNextReadyAt[i] = pausedUntil!!
        }
        return false
    }

    fun finish(targetCount: Int): DouyinFollowLimiterSummary =
        DouyinFollowLimiterSummary(
            targetCount = targetCount,
            successCount = successCount,
            limitedCount = limitedCount,
            cooledDown = cooledDown,
            initialInterval = config.initialInterval,
            finalInterval = currentInterval,
            initialConcurrency = config.initialConcurrency,
            elapsed = Duration.ofNanos(System.nanoTime() - startNanos),
        )

    private fun decreaseInterval(value: Duration): Duration {
        val next = value.minus(Duration.ofMillis(20))
        return if (next < config.minInterval) config.minInterval else next
    }

    private fun increaseInterval(value: Duration): Duration {
        val next = value.plus(Duration.ofMillis(120))
        return if (next > config.maxInterval) config.maxInterval else next
    }
}