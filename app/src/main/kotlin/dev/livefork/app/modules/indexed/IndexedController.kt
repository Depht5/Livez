package dev.livefork.app.modules.indexed

import dev.livefork.app.app.ALL_HOME_PAGES
import dev.livefork.app.app.EventBus
import dev.livefork.app.app.HomePageItem
import dev.livefork.app.app.controller.AppSettingsController
import dev.livefork.app.app.controller.BaseController

/**
 * 主框架控制器 —— 对应 Dart `IndexedController`
 * （lib/modules/indexed/indexed_controller.dart）。
 * 页面实例由 UI 层通过 [setIndex] 的 onPageCreated 回调惰性创建；
 * 首启提示（Dart showStatement）与直播间打开（路由）由 UI 层处理。
 */
class IndexedController(
    private val appSettings: AppSettingsController,
) : BaseController() {

    /** 首页导航项（对应 Dart items，按 homeSort 排序） */
    val items: List<HomePageItem> = appSettings.homeSort.value.mapNotNull { ALL_HOME_PAGES[it] }

    var index: Int = 0
        private set

    private val createdFlags = BooleanArray(items.size)

    /** 切换底部导航（对应 Dart setIndex）。 */
    fun setIndex(i: Int, onPageCreated: (HomePageItem) -> Unit) {
        if (i < 0 || i >= items.size) return
        if (!createdFlags[i]) {
            onPageCreated(items[i])
            createdFlags[i] = true
        } else {
            if (index == i) {
                EventBus.emit(EventBus.kBottomNavigationBarClicked, items[i].index)
            }
        }
        index = i
    }

    /** 是否首次启动（对应 Dart firstRun）。 */
    fun isFirstRun(): Boolean = appSettings.firstRun

    /** 标记非首次启动（对应 Dart setNoFirstRun）。 */
    fun markNotFirstRun() {
        appSettings.setNoFirstRun()
    }

    /** 消费待恢复的直播间（对应 Dart restorePendingLiveRoom 的取数部分）。 */
    fun consumePendingLastLiveRoom(): Map<String, String>? =
        appSettings.consumePendingLastLiveRoom()
}