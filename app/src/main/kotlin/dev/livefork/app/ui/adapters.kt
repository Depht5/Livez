package dev.livefork.app.ui

/**
 * 简单条目（标题 + 可选副标题）—— MineView 功能入口行在用。
 *
 * 注：本文件原有的 LiveRoomAdapter / GridSpacingDecoration（分类详情 RecyclerView 用）
 * 已随 Batch 3 分类详情迁移 Compose 一并删除（零引用确认后清理）。
 */
data class MenuItem(
    val title: String,
    val subtitle: String = "",
)
