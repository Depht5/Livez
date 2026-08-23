package dev.livefork.core.model

/**
 * 类目房间分页结果。
 * 对应 Dart `LiveCategoryResult`（lib/src/model/live_category_result.dart）。
 */
data class LiveCategoryResult(
    val hasMore: Boolean,
    val items: List<LiveRoomItem>,
)
