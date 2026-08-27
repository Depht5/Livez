package com.kotlin_livez.app.modules.settings.indexed_settings

import com.kotlin_livez.app.app.controller.AppSettingsController
import com.kotlin_livez.app.app.controller.BaseController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 排序与全局开关设置控制器 —— 对应 Dart `IndexedSettingsController`
 * （lib/modules/settings/indexed_settings/indexed_settings_controller.dart）。
 * 排序拖拽（RecyclerView 的 move ops）由 UI 层传入 oldIndex/newIndex。
 */
class IndexedSettingsController(
    private val appSettings: AppSettingsController,
) : BaseController() {

    val siteSort = MutableStateFlow(appSettings.siteSort.value)
    val homeSort = MutableStateFlow(appSettings.homeSort.value)
    val liveRoomTabSort = MutableStateFlow(appSettings.liveRoomTabSort.value)
    val liveRoomQuickAccessSort = MutableStateFlow(appSettings.liveRoomQuickAccessSort.value)
    val liveRoomQuickAccessEnabled = MutableStateFlow(appSettings.liveRoomQuickAccessEnabled.value)
    val contributionRankEnable = MutableStateFlow(appSettings.contributionRankEnable.value)
    val liveEventFlowEnable = MutableStateFlow(appSettings.liveEventFlowEnable.value)

    private fun reorder(list: MutableStateFlow<List<String>>, oldIndex: Int, newIndex: Int) {
        var target = newIndex
        if (oldIndex < target) target -= 1
        val current = list.value.toMutableList()
        if (oldIndex < 0 || oldIndex >= current.size) return
        val item = current.removeAt(oldIndex)
        current.add(target.coerceIn(0, current.size), item)
        list.value = current
    }

    fun updateSiteSort(oldIndex: Int, newIndex: Int) {
        reorder(siteSort, oldIndex, newIndex)
        appSettings.setSiteSort(siteSort.value)
    }

    fun updateHomeSort(oldIndex: Int, newIndex: Int) {
        reorder(homeSort, oldIndex, newIndex)
        appSettings.setHomeSort(homeSort.value)
    }

    fun updateLiveRoomTabSort(oldIndex: Int, newIndex: Int) {
        reorder(liveRoomTabSort, oldIndex, newIndex)
        appSettings.setLiveRoomTabSort(liveRoomTabSort.value)
    }

    fun updateLiveRoomQuickAccessSort(oldIndex: Int, newIndex: Int) {
        reorder(liveRoomQuickAccessSort, oldIndex, newIndex)
        appSettings.setLiveRoomQuickAccessSort(liveRoomQuickAccessSort.value)
    }

    fun setLiveRoomQuickAccessEnabled(key: String, enabled: Boolean) {
        appSettings.setLiveRoomQuickAccessEnabled(key, enabled)
        liveRoomQuickAccessEnabled.value = appSettings.liveRoomQuickAccessEnabled.value
    }

    fun setContributionRankEnable(enabled: Boolean) {
        appSettings.setContributionRankEnable(enabled)
        contributionRankEnable.value = enabled
    }

    fun setLiveEventFlowEnable(enabled: Boolean) {
        appSettings.setLiveEventFlowEnable(enabled)
        liveEventFlowEnable.value = enabled
    }
}