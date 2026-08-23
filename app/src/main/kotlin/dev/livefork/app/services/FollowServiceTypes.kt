package dev.livefork.app.services

import dev.livefork.app.models.FollowUser
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 关注刷新辅助类型 —— 对应 Dart `follow_service.dart` 文件尾部私有类型
 * （_FollowRefreshItemOutcome/_FollowRefreshItemResult/_RefreshTargetPolicyResult/
 * _PersistedFollowRefreshTaskState）。Kotlin 中为 internal 以便同模块服务引用。
 */

/** 单项刷新结果分类（对应 Dart _FollowRefreshItemOutcome）。 */
internal enum class _FollowRefreshItemOutcome {
    success,
    failed,
    deferred,
    skipped,
}

/** 单项刷新结果（对应 Dart _FollowRefreshItemResult）。 */
internal class _FollowRefreshItemResult(
    val outcome: _FollowRefreshItemOutcome,
    val limited: Boolean = false,
    val keepPending: Boolean = false,
    val pauseRemaining: Boolean = false,
)

/** 刷新目标策略结果（对应 Dart _RefreshTargetPolicyResult）。 */
internal class _RefreshTargetPolicyResult(
    val allowedTargets: List<FollowUser>,
    val deferredTargets: List<FollowUser>,
    val toastMessage: String = "",
)

/** 续跑刷新任务持久化状态（对应 Dart _PersistedFollowRefreshTaskState）。 */
internal class _PersistedFollowRefreshTaskState(
    val scopeKey: String,
    val total: Int,
    val successCount: Int,
    val failedCount: Int,
    val deferredCount: Int,
    val orderedKeys: List<String>,
    val pendingKeys: List<String>,
) {
    companion object {
        fun fromJson(state: JsonObject, targets: JsonObject): _PersistedFollowRefreshTaskState {
            fun readList(value: Any?): List<String> {
                val array = value as? kotlinx.serialization.json.JsonArray ?: return emptyList()
                return array.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            }

            fun intOf(key: String): Int =
                (state[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0

            return _PersistedFollowRefreshTaskState(
                scopeKey = (state["scopeKey"]?.jsonPrimitive?.content) ?: "",
                total = intOf("total"),
                successCount = intOf("successCount"),
                failedCount = intOf("failedCount"),
                deferredCount = intOf("deferredCount"),
                orderedKeys = readList(targets["orderedKeys"]),
                pendingKeys = readList(targets["pendingKeys"]),
            )
        }
    }
}