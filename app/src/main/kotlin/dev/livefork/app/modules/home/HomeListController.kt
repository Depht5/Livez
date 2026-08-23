package dev.livefork.app.modules.home

import dev.livefork.app.app.Site
import dev.livefork.app.app.Sites
import dev.livefork.app.app.controller.BasePageController
import dev.livefork.core.model.LiveRoomItem

/**
 * 首页推荐列表控制器 —— 对应 Dart `HomeListController`
 * （lib/modules/home/home_list_controller.dart）。
 */
class HomeListController(val site: Site) : BasePageController<LiveRoomItem>() {
    override suspend fun getData(page: Int, pageSize: Int): List<LiveRoomItem> =
        site.liveSite.recommendRooms(page = page).items
}