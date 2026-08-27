package com.kotlin_livez.core.common

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoExitSessionTest {

    private val now: Instant = Instant.parse("2025-01-01T00:00:00Z")

    @Test
    fun disabledInitially() {
        val s = AutoExitSession()
        assertFalse(s.enabled)
        assertFalse(s.isDue(now))
        assertEquals(Duration.ZERO, s.remaining(now))
        assertEquals(Duration.ZERO, s.globalRemaining(now))
    }

    @Test
    fun globalSession_isDueAfterDeadline() {
        val s = AutoExitSession()
        s.startGlobal(now = now, minutes = 5)
        assertTrue(s.enabled)
        assertEquals(AutoExitSource.global, s.source)
        assertFalse(s.isDue(now.plus(Duration.ofMinutes(4))))
        assertTrue(s.isDue(now.plus(Duration.ofMinutes(6))))
        assertEquals(Duration.ofMinutes(5), s.remaining(now))
        assertEquals(Duration.ofMinutes(5), s.globalRemaining(now))
    }

    @Test
    fun roomOverride_overridesDeadline() {
        val s = AutoExitSession()
        s.startGlobal(now = now, minutes = 60)
        s.startRoomOverride(now = now, minutes = 3)
        assertEquals(AutoExitSource.roomOverride, s.source)
        assertEquals(Duration.ofMinutes(3), s.remaining(now))
        // 全局截止不变
        assertEquals(Duration.ofMinutes(60), s.globalRemaining(now))
    }

    @Test
    fun stop_clearsEverything() {
        val s = AutoExitSession()
        s.startGlobal(now = now, minutes = 10)
        s.stop()
        assertFalse(s.enabled)
        assertEquals(Duration.ZERO, s.remaining(now))
        assertEquals(Duration.ZERO, s.globalRemaining(now))
    }

    @Test
    fun minutes_clampedToWholeDay() {
        val s = AutoExitSession()
        s.startGlobal(now = now, minutes = 100000)
        assertEquals(Duration.ofMinutes((24 * 60).toLong()), s.globalRemaining(now))
        s.startGlobal(now = now, minutes = 0)
        assertEquals(Duration.ofMinutes(1), s.globalRemaining(now))
    }
}