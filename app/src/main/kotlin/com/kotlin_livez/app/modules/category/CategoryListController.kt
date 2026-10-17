package com.kotlin_livez.app.modules.category

import com.kotlin_livez.app.app.Site
import com.kotlin_livez.app.app.Sites
import com.kotlin_livez.app.app.controller.BasePageController
import com.kotlin_livez.core.model.LiveCategory
import com.kotlin_livez.core.model.LiveSubCategory
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 类目列表控制器 —— 对应 Dart `CategoryListController`
 * （lib/modules/category/category_list_controller.dart）。
 */
class CategoryListController(val site: Site) : BasePageController<AppLiveCategory>() {
    override suspend fun getData(page: Int, pageSize: Int): List<AppLiveCategory> =
        site.liveSite.categories().map { AppLiveCategory(it) }
}

/**
 * 带"显示全部"展开状态的分类包装 —— 对应 Dart `AppLiveCategory`
 * （lib/modules/category/category_list_controller.dart）。
 * 注：core 的 `LiveCategory` 是 final data class，Kotlin 用组合而非继承。
 */
class AppLiveCategory(val category: LiveCategory) {
    val id: String get() = category.id
    val name: String get() = category.name
    val children: List<LiveSubCategory> get() = category.children

    /** 是否显示"全部"（对应 Dart showAll） */
    val showAll = MutableStateFlow(category.children.size < 19)

    /**
     * 取前 15 个子分类（对应 Dart take15）。
     *
     * 构造期缓存（task 10-17-ui-power-and-recomposition-governance）：`children` 来自 core 的
     * final data class `LiveCategory` 的不可变 val，`showAll` 只切换展开态、不改 `children`，
     * 故不存在陈旧风险；原为 getter，每次读取都重新 `take(15)` 并分配一个列表。
     */
    val take15: List<LiveSubCategory> = children.take(15)
}