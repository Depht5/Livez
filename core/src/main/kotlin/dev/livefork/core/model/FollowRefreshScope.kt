package dev.livefork.core.model

/**
 * 关注刷新范围。
 * 对应 Dart `FollowRefreshScope`（lib/src/model/follow_refresh_scope.dart）。
 * 冻结字段值（如 stage 文案、scopeKey "all"）与 Dart 保持一致。
 */
data class FollowRefreshScope(
    val scopeKey: String,
    val includeAllNormals: Boolean,
    val automatic: Boolean,
    val allowBackgroundSpecials: Boolean,
    val stage: String,
    val backgroundStage: String,
) {
    companion object {
        fun all(automatic: Boolean = false) = FollowRefreshScope(
            scopeKey = "all",
            includeAllNormals = true,
            automatic = automatic,
            allowBackgroundSpecials = false,
            stage = "正在刷新关注状态",
            backgroundStage = "",
        )

        fun page(scopeKey: String, automatic: Boolean = false) = FollowRefreshScope(
            scopeKey = scopeKey,
            includeAllNormals = false,
            automatic = automatic,
            allowBackgroundSpecials = true,
            stage = "正在刷新关注状态",
            backgroundStage = "当前页已完成，后台补充特别关注",
        )
    }
}
