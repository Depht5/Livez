package dev.livefork.core.model

/**
 * 同步进度（本地/远程同步）。
 * 对应 Dart `SyncProgress`（lib/src/model/sync_progress.dart）。
 */
data class SyncProgress(
    val stage: String,
    val current: Int = 0,
    val total: Int = 0,
    val message: String = "",
) {
    val percent: Double get() = if (total <= 0) 0.0 else (current.toDouble() / total).coerceIn(0.0, 1.0)
    val isIndeterminate: Boolean get() = total <= 0
    val displayMessage: String
        get() = when {
            message.isNotEmpty() -> message
            total > 0 -> "$stage $current/$total"
            else -> stage
        }
}

/** 同步进度回调，对应 Dart typedef SyncProgressCallback。 */
fun interface SyncProgressCallback {
    fun onProgress(progress: SyncProgress)
}
