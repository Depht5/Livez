package dev.livefork.core.model

/**
 * 搜索结果。
 * 对应 Dart `LiveSearchRoomResult` / `LiveSearchAnchorResult`
 * （lib/src/model/live_search_result.dart）。
 */
data class LiveSearchRoomResult(
    val hasMore: Boolean,
    val items: List<LiveRoomItem>,
)

data class LiveSearchAnchorResult(
    val hasMore: Boolean,
    val items: List<LiveAnchorItem>,
)
