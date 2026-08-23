package dev.livefork.core.model

/**
 * 关注刷新进度。
 * 对应 Dart `FollowRefreshProgress`（lib/src/model/follow_refresh_progress.dart）。
 */
data class FollowRefreshProgress(
    val active: Boolean = false,
    val stage: String = "",
    val current: Int = 0,
    val total: Int = 0,
    val successCount: Int = 0,
    val failedCount: Int = 0,
    val deferredCount: Int = 0,
    val skippedCount: Int = 0,
    val automatic: Boolean = false,
    val scopeKey: String = "",
    val completed: Boolean = false,
    val background: Boolean = false,
    val detail: String = "",
) {
    /** 已完成（当前 + 跳过） */
    val resolvedCount: Int get() = current + skippedCount

    /** 剩余数量 */
    val remainingCount: Int get() = (total - resolvedCount).coerceAtLeast(0)

    /** 百分比 0..1 */
    val percent: Double get() = if (total <= 0) 0.0 else (resolvedCount.toDouble() / total).coerceIn(0.0, 1.0)

    /** 展示文本 */
    val displayText: String get() = if (total > 0) "$stage $current/$total" else stage

    /** 空档（对应 Dart FollowRefreshProgress.idle） */
    companion object {
        val IDLE = FollowRefreshProgress()
    }
}
