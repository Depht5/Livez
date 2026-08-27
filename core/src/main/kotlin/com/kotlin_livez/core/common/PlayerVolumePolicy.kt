package com.kotlin_livez.core.common

/**
 * 播放器音量策略。
 * 对应 Dart `PlayerVolumePolicy`（lib/src/common/player_volume_policy.dart）。
 */
object PlayerVolumePolicy {
    fun internalVolume(mobile: Boolean, muted: Boolean, persisted: Double): Double {
        if (muted) return 0.0
        if (mobile) return 100.0
        return persisted.coerceIn(0.0, 100.0)
    }
}