package com.kotlin_livez.app.modules.search

import com.kotlin_livez.app.app.Site
import com.kotlin_livez.app.app.Sites
import com.kotlin_livez.app.app.controller.AppSettingsController
import com.kotlin_livez.app.app.controller.BaseController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 搜索页控制器 —— 对应 Dart `AppSearchController`
 * （lib/modules/search/search_controller.dart）。
 * Tab 控件与输入框由页面层持有；[initialSiteId] 对应 Dart Get.arguments 中的 siteId。
 */
class AppSearchController(
    private val appSettings: AppSettingsController,
    initialSiteId: String? = null,
) : BaseController() {

    val sites: List<Site> = Sites.supportSites

    /** 当前选中 Tab 索引（对应 Dart index） */
    var index: Int = 0
        private set

    /** 搜索模式：0=直播间，1=主播（对应 Dart searchMode） */
    private val _searchMode = MutableStateFlow(0)
    val searchMode: StateFlow<Int> = _searchMode

    val siteControllers: Map<String, SearchListController> = sites.associate { it.id to SearchListController(it) }

    init {
        val resolvedIndex = sites.indexOfFirst { it.id == initialSiteId }
        index = if (resolvedIndex < 0) 0 else resolvedIndex
        if (sites.isNotEmpty()) {
            appSettings.setLastSearchSiteId(sites[index].id)
        }
    }

    /** 切换 Tab（对应 Dart tabController.animation 监听）。 */
    fun setTabIndex(newIndex: Int) {
        val target = newIndex.coerceIn(0, sites.size - 1)
        if (index == target) return
        index = target
        appSettings.setLastSearchSiteId(sites[index].id)
        val controller = siteControllers[sites[index].id] ?: return
        if (controller.list.value.isEmpty() &&
            !controller.pageEmpty.value &&
            controller.keyword.isNotEmpty()
        ) {
            controller.refreshData()
        }
    }

    fun setSearchMode(mode: Int) {
        _searchMode.value = mode
    }

    /** 发起搜索（对应 Dart doSearch）。 */
    fun doSearch(keyword: String) {
        if (keyword.isEmpty()) return
        for ((_, controller) in siteControllers) {
            controller.clear()
            controller.keyword = keyword
            controller.setSearchMode(_searchMode.value)
        }
        siteControllers[sites[index].id]?.refreshData()
    }

    fun dispose() {
        for ((_, controller) in siteControllers) {
            controller.dispose()
        }
    }
}