package com.kotlin_livez.core.model

import kotlinx.serialization.Serializable

/**
 * 主播搜索结果项。
 * 对应 Dart `LiveAnchorItem`（lib/src/model/live_anchor_item.dart）。
 */
@Serializable
data class LiveAnchorItem(
    /** 房间ID */
    val roomId: String,
    /** 头像 */
    val avatar: String,
    /** 用户名 */
    val userName: String,
    /** 直播中 */
    val liveStatus: Boolean,
)
