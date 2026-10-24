package com.kotlin_livez.app.services

import com.kotlin_livez.app.models.FollowUser
import com.kotlin_livez.app.models.FollowUserTag
import com.kotlin_livez.app.models.History
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * 批量导入服务 —— 对应 Dart `BulkDataImportService`
 * （lib/services/bulk_data_import_service.dart, 601 行）。
 * 完整移植：屏蔽词 / 关注 / 标签 / 历史四类导入，按数据规模选择分批策略。
 */

/** 数据规模（对应 Dart BulkDataScale）。 */
enum class BulkDataScale(val label: String) {
    normal("normal"),
    medium("medium"),
    large("large"),
    huge("huge"),
}

/** 批量导入策略（对应 Dart BulkDataPolicy）。 */
data class BulkDataPolicy(
    val count: Int,
    val scale: BulkDataScale,
    val dbBatchSize: Int,
    val yieldEvery: Int,
)

/** 批量导入结果（对应 Dart BulkImportResult）。 */
data class BulkImportResult(
    val total: Int,
    val imported: Int,
    val skipped: Int,
    val policy: BulkDataPolicy,
) {
    val logSummary: String
        get() = "total=$total imported=$imported skipped=$skipped scale=${policy.scale.label}"
}

/** 同步进度（对应 Dart SyncProgress）。 */
class SyncProgress(
    val stage: String,
    val current: Int = 0,
    val total: Int = 0,
    val message: String = "",
) {
    val percent: Double get() = if (total <= 0) 0.0 else (current.toDouble() / total).coerceIn(0.0, 1.0)

    /** total<=0 视为无总量（进度条走不确定样式）。 */
    val isIndeterminate: Boolean get() = total <= 0

    val displayMessage: String
        get() = when {
            message.isNotEmpty() -> message
            total > 0 -> "$stage $current/$total"
            else -> stage
        }
}

/** 同步进度回调（对应 Dart SyncProgressCallback）。 */
fun interface SyncProgressCallback {
    fun onProgress(progress: SyncProgress)
}

object BulkDataImportService {

    const val mediumThreshold = 300
    const val largeThreshold = 1000
    const val hugeThreshold = 3000

    fun policyForCount(count: Int): BulkDataPolicy = when {
        count > hugeThreshold -> BulkDataPolicy(count, BulkDataScale.huge, 200, 100)
        count > largeThreshold -> BulkDataPolicy(count, BulkDataScale.large, 400, 200)
        count > mediumThreshold -> BulkDataPolicy(count, BulkDataScale.medium, 800, 300)
        else -> BulkDataPolicy(count, BulkDataScale.normal, 1200, 0)
    }

    /**
     * 导入关注列表（对应 Dart importFollowUsers）。
     * [syncTagsFromUserField] 对应 Dart syncTagsFromUserField（标签字段迁移）。
     */
    suspend fun importFollowUsers(
        data: List<JsonElement>,
        syncTagsFromUserField: Boolean = false,
        overwrite: Boolean = false,
        onProgress: SyncProgressCallback? = null,
    ): BulkImportResult {
        val policy = policyForCount(data.size)
        onProgress?.onProgress(SyncProgress("导入关注", 0, data.size))
        val db = com.kotlin_livez.app.di.ServiceContainer.dbService
        if (overwrite) {
            db.clearFollow()
        }
        val users = data.mapNotNull { FollowUser.fromJson(it) }
            .filter { it.siteId.isNotEmpty() && it.roomId.isNotEmpty() }

        var imported = 0
        var skipped = 0
        var processed = 0
        for (user in users) {
            db.addFollow(user.copy(liveStatus = 0, liveStartTime = null))
            imported++
            processed++
            if (policy.yieldEvery > 0 && processed % policy.yieldEvery == 0) {
                delay(1)
            }
            if (onProgress != null && processed % policy.dbBatchSize == 0) {
                onProgress.onProgress(SyncProgress("导入关注", processed, data.size))
            }
        }
        skipped = data.size - users.size
        onProgress?.onProgress(SyncProgress("导入关注", imported, imported))
        return BulkImportResult(data.size, imported, skipped, policy)
    }

    /** 导入关注标签（对应 Dart importFollowTags）。 */
    suspend fun importFollowTags(
        data: List<JsonElement>,
        overwrite: Boolean = false,
        onProgress: SyncProgressCallback? = null,
    ): BulkImportResult {
        val policy = policyForCount(data.size)
        onProgress?.onProgress(SyncProgress("导入标签", 0, data.size))
        val db = com.kotlin_livez.app.di.ServiceContainer.dbService
        val tags = data.mapNotNull { FollowUserTag.fromJson(it) }
            .filter { it.id.isNotEmpty() && it.tag.isNotEmpty() }
        if (overwrite) {
            db.updateFollowTagOrder(emptyList())
        }
        var imported = 0
        for (tag in tags) {
            db.upsertFollowTags(listOf(tag))
            imported++
            if (policy.yieldEvery > 0 && imported % policy.yieldEvery == 0) {
                delay(1)
            }
            if (onProgress != null && imported % policy.dbBatchSize == 0) {
                onProgress.onProgress(SyncProgress("导入标签", imported, data.size))
            }
        }
        val skipped = data.size - tags.size
        onProgress?.onProgress(SyncProgress("导入标签", imported, imported))
        return BulkImportResult(data.size, imported, skipped, policy)
    }

    /** 导入历史（对应 Dart importHistories）。 */
    suspend fun importHistories(
        data: List<JsonElement>,
        overwrite: Boolean = false,
        onProgress: SyncProgressCallback? = null,
    ): BulkImportResult {
        val policy = policyForCount(data.size)
        onProgress?.onProgress(SyncProgress("导入历史", 0, data.size))
        val db = com.kotlin_livez.app.di.ServiceContainer.dbService
        val histories = data.mapNotNull { History.fromJson(it) }
            .filter { it.roomId.isNotEmpty() }
        if (overwrite) {
            db.clearHistories()
        }
        var imported = 0
        for (history in histories) {
            db.addOrUpdateHistory(history)
            imported++
            if (policy.yieldEvery > 0 && imported % policy.yieldEvery == 0) {
                delay(1)
            }
            if (onProgress != null && imported % policy.dbBatchSize == 0) {
                onProgress.onProgress(SyncProgress("导入历史", imported, data.size))
            }
        }
        val skipped = data.size - histories.size
        onProgress?.onProgress(SyncProgress("导入历史", imported, imported))
        return BulkImportResult(data.size, imported, skipped, policy)
    }

    /** 导入屏蔽值（对应 Dart importShieldValues）。 */
    suspend fun importShieldValues(
        rawValues: List<String>,
        overwrite: Boolean,
        onProgress: SyncProgressCallback? = null,
    ): BulkImportResult {
        val policy = policyForCount(rawValues.size)
        val db = com.kotlin_livez.app.di.ServiceContainer.dbService
        val localStorage = com.kotlin_livez.app.di.ServiceContainer.localStorage
        onProgress?.onProgress(SyncProgress("导入屏蔽", 0, rawValues.size))
        val existing = if (overwrite) emptyList() else localStorage.getAllShield().values.map { it.trim() }
        val values = rawValues.map { it.trim() }
            .filter { it.isNotEmpty() && it !in existing }
        var imported = 0
        for (value in values) {
            // 屏蔽值写入 shieldBox（对应 Dart shieldBox.putAll）
            localStorage.setShield(value, value)
            imported++
            if (policy.yieldEvery > 0 && imported % policy.yieldEvery == 0) {
                delay(1)
            }
        }
        val skipped = rawValues.size - values.size
        onProgress?.onProgress(SyncProgress("导入屏蔽", imported, imported))
        return BulkImportResult(rawValues.size, imported, skipped, policy)
    }
}