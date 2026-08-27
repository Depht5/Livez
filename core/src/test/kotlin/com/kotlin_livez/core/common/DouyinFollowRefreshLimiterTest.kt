package com.kotlin_livez.core.common

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DouyinFollowRefreshLimiterTest {

    @Test
    fun smallTarget_usesHighConcurrency() {
        val limiter = DouyinFollowRefreshLimiter.forTargetCount(10)
        assertEquals(4, limiter.initialConcurrency)
        assertEquals(Duration.ofMillis(60), limiter.initialInterval)
    }

    @Test
    fun largeTarget_usesSingleWorker() {
        val limiter = DouyinFollowRefreshLimiter.forTargetCount(1000)
        assertEquals(1, limiter.initialConcurrency)
        assertEquals(Duration.ofMillis(320), limiter.initialInterval)
    }

    @Test
    fun consecutiveSuccess_decreasesInterval() {
        val limiter = DouyinFollowRefreshLimiter.forTargetCount(10)
        for (i in 0 until 16) {
            limiter.onSuccess()
        }
        // 从 60ms 降到 minInterval 40ms（两次 20ms 步进后触底）
        assertEquals(Duration.ofMillis(40), limiter.currentIntervalValue)
    }

    @Test
    fun limited_pausesAndIncreasesInterval() {
        val limiter = DouyinFollowRefreshLimiter.forTargetCount(10)
        val first = limiter.onLimited()
        assertFalse(first) // 第一次限流只降速，不冷却
        assertEquals(Duration.ofMillis(180), limiter.currentIntervalValue)
        val second = limiter.onLimited()
        assertTrue(second) // 第二次限流进入冷却
        assertTrue(limiter.cooledDownValue)
    }

    @Test
    fun finish_reportContainsCounters() {
        val limiter = DouyinFollowRefreshLimiter.forTargetCount(10)
        limiter.onSuccess()
        limiter.onLimited()
        val summary = limiter.finish(targetCount = 10)
        assertEquals(10, summary.targetCount)
        assertEquals(1, summary.successCount)
        assertEquals(1, summary.limitedCount)
        assertFalse(summary.cooledDown)
    }
}