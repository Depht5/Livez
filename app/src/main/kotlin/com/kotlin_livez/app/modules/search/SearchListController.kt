package com.kotlin_livez.app.modules.search

import com.kotlin_livez.app.app.Site
import com.kotlin_livez.app.app.Sites
import com.kotlin_livez.app.app.controller.BasePageController
import com.kotlin_livez.core.model.LiveAnchorItem
import com.kotlin_livez.core.model.LiveRoomItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 搜索结果列表控制器 —— 对应 Dart `SearchListController`
 * （lib/modules/search/search_list_controller.dart）。
 * 直播间/主播两种结果类型并存（Dart 中泛型为 dynamic），故 T=Any，UI 层按类型渲染。
 */
class SearchListController(val site: Site) : BasePageController<Any>() {
    var keyword: String = ""

    /** 搜索模式：0=直播间，1=主播（对应 Dart searchMode） */
    private val _searchMode = MutableStateFlow(0)
    val searchMode: StateFlow<Int> = _searchMode

    fun setSearchMode(mode: Int) {
        _searchMode.value = mode
    }

    override fun refreshData() {
        if (keyword.isEmpty()) return
        super.refreshData()
    }

    override suspend fun getData(page: Int, pageSize: Int): List<Any> {
        if (keyword.isEmpty()) return emptyList()
        val items: List<Any> = if (_searchMode.value == 1) {
            site.liveSite.searchAnchors(keyword, page = page).items
        } else {
            site.liveSite.searchRooms(keyword, page = page).items
        }
        // 对齐 Dart `_removeDuplicate`（search_list_controller.dart:39-44）：过滤空 roomId
        // 与「已加载过」的 roomId（翻页时结果排序会变，同一条目可能再次出现）。
        // **有意强于 Dart**：这里额外做页内去重（Dart 的 where 不更新 existing，同页重复会漏）。
        // 原因：共享元素 key = `live_cover_{siteId}_{roomId}`，同屏出现同 roomId 的两张卡会让
        // Compose 把它们的 sharedElement/sharedBounds 折叠成一份（见 ui-compose.md 新增条目）。
        val seen = list.value.mapNotNullTo(mutableSetOf()) { it.resultRoomId() }
        return items.filter { item ->
            val roomId = item.resultRoomId() ?: return@filter true   // 未知类型：无法判重，原样保留
            roomId.isNotEmpty() && seen.add(roomId)
        }
    }

    fun clear() {
        pageEmpty.value = false
        list.value = emptyList()
    }
}

/** 搜索结果中能参与去重的身份（直播间/主播都有 roomId；未知类型返回 null = 不参与判重）。 */
private fun Any.resultRoomId(): String? = when (this) {
    is LiveRoomItem -> roomId
    is LiveAnchorItem -> roomId
    else -> null
}