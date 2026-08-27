package com.kotlin_livez.app.modules.search

import com.kotlin_livez.app.app.Site
import com.kotlin_livez.app.app.Sites
import com.kotlin_livez.app.app.controller.BasePageController
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
        return if (_searchMode.value == 1) {
            site.liveSite.searchAnchors(keyword, page = page).items
        } else {
            site.liveSite.searchRooms(keyword, page = page).items
        }
    }

    fun clear() {
        pageEmpty.value = false
        list.value = emptyList()
    }
}