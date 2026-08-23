package dev.livefork.app.modules.category.detail

import dev.livefork.app.app.Site
import dev.livefork.app.app.Sites
import dev.livefork.app.app.controller.BasePageController
import dev.livefork.core.model.LiveRoomItem
import dev.livefork.core.model.LiveSubCategory

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