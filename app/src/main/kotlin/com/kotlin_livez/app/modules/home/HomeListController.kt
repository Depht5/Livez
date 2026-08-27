package com.kotlin_livez.app.modules.home

import com.kotlin_livez.app.app.Site
import com.kotlin_livez.app.app.Sites
import com.kotlin_livez.app.app.controller.BasePageController
import com.kotlin_livez.core.model.LiveRoomItem

/**
 * 首页推荐列表控制器 —— 对应 Dart `HomeListController`
 * （lib/modules/home/home_list_controller.dart）。
 */
class HomeListController(val site: Site) : BasePageController<LiveRoomItem>() {
    override suspend fun getData(page: Int, pageSize: Int): List<LiveRoomItem> =
        site.liveSite.recommendRooms(page = page).items
}