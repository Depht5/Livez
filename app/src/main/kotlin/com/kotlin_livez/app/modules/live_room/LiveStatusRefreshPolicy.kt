package com.kotlin_livez.app.modules.live_room

/**
 * 直播状态刷新策略 —— 对应 Dart `LiveStatusRefreshPolicy`
 * （lib/modules/live_room/live_status_refresh_policy.dart）。
 * 连续多次确认离线才判定下播，避免单次误报。
 */
class LiveStatusRefreshPolicy(
    val requiredOfflineConfirmations: Int = 3,
) {
    init {
        require(requiredOfflineConfirmations > 0)
    }

    private var consecutiveOfflineCountValue = 0
    val consecutiveOfflineCount: Int get() = consecutiveOfflineCountValue

    /** 确认离线：reportedLive 或存在播放证据时重置计数并返回 false。 */
    fun confirmOffline(
        reportedLive: Boolean,
        hasActivePlaybackEvidence: Boolean,
    ): Boolean {
        if (reportedLive || hasActivePlaybackEvidence) {
            reset()
            return false
        }
        consecutiveOfflineCountValue += 1
        return consecutiveOfflineCountValue >= requiredOfflineConfirmations
    }

    fun reset() {
        consecutiveOfflineCountValue = 0
    }
}