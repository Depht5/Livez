package dev.livefork.app.modules.mine.history

import dev.livefork.app.app.controller.BasePageController
import dev.livefork.app.models.History
import dev.livefork.app.services.DBService

/**
 * 观看历史控制器 —— 对应 Dart `HistoryController`
 * （lib/modules/mine/history/history_controller.dart）。
 */
class HistoryController(
    private val dbService: DBService,
) : BasePageController<History>() {

    override suspend fun getData(page: Int, pageSize: Int): List<History> {
        if (page > 1) return emptyList()
        return dbService.getHistores()
    }

    /** 清空观看记录（确认框由 UI 层弹出，文案见 Dart 冻结值）。 */
    suspend fun clean() {
        dbService.clearHistories()
        refreshData()
    }

    suspend fun removeItem(item: History) {
        dbService.deleteHistory(item.id)
        refreshData()
    }
}