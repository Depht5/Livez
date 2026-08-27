package com.kotlin_livez.app.modules.category.detail

import com.kotlin_livez.app.app.Site
import com.kotlin_livez.app.app.Sites
import com.kotlin_livez.app.app.controller.BasePageController
import com.kotlin_livez.core.model.LiveRoomItem
import com.kotlin_livez.core.model.LiveSubCategory

/**
 * 类目详情列表控制器 —— 对应 Dart `CategoryDetailController`
 * （lib/modules/category/detail/category_detail_controller.dart）。
 */
class CategoryDetailController(
    val site: Site,
    val subCategory: LiveSubCategory,
    val excludedRoomId: String? = null,
) : BasePageController<LiveRoomItem>() {
    override suspend fun getData(page: Int, pageSize: Int): List<LiveRoomItem> =
        site.liveSite.categoryRooms(subCategory, page = page)
            .items
            .filter { it.roomId != excludedRoomId }
}