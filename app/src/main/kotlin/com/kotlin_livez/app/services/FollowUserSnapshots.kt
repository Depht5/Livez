package com.kotlin_livez.app.services

import com.kotlin_livez.app.models.FollowUser

/**
 * 关注列表的不可变快照辅助（task 10-20-follow-startup-status-refresh）。
 *
 * 背景：`FollowService._followList` 是 `MutableStateFlow<List<FollowUser>>`，列表相等按元素
 * `equals`（data class）比较。过去刷新流程原地修改已发布对象的字段（`item.liveStatus = ...`），
 * 新旧列表共享同一批引用 ⇒ StateFlow 比较「同一对象和自己」恒等、不发射，UI 不重组。
 * 统一改为「按 id 取当前项 → `copy()` 出新快照 → 本函数替换发布」：
 * - 已发布的旧快照保持旧值，新旧列表因此可按值比较出差异并发射；
 * - 替换按 id 落到**当前**列表，即使刷新期间列表被重新加载，也不会写回孤儿对象。
 */
object FollowUserSnapshots {

    /**
     * 用 [updated] 替换 [items] 中 id == [oldId] 的项，返回新列表。
     * 不修改 [items] 及其元素；未找到时原样返回同一实例（调用方据此判断项已被移出列表）。
     * 元素顺序保持；重复 id 只替换第一个（与现有列表去重不变量一致）。
     */
    fun replaceById(
        items: List<FollowUser>,
        updated: FollowUser,
        oldId: String = updated.id,
    ): List<FollowUser> {
        val index = items.indexOfFirst { it.id == oldId }
        if (index < 0) return items
        return items.toMutableList().also { it[index] = updated }
    }
}
