package dev.livefork.core.common

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerVolumePolicyTest {

    @Test
    fun muted_alwaysReturnsZero() {
        assertEquals(0.0, PlayerVolumePolicy.internalVolume(mobile = true, muted = true, persisted = 50.0))
        assertEquals(0.0, PlayerVolumePolicy.internalVolume(mobile = false, muted = true, persisted = 50.0))
    }

    @Test
    fun mobile_forcesHundred() {
        assertEquals(100.0, PlayerVolumePolicy.internalVolume(mobile = true, muted = false, persisted = 33.0))
    }

    @Test
    fun desktop_usesPersistedClamped() {
        assertEquals(33.0, PlayerVolumePolicy.internalVolume(mobile = false, muted = false, persisted = 33.0))
        assertEquals(100.0, PlayerVolumePolicy.internalVolume(mobile = false, muted = false, persisted = 400.0))
        assertEquals(0.0, PlayerVolumePolicy.internalVolume(mobile = false, muted = false, persisted = -10.0))
    }
}