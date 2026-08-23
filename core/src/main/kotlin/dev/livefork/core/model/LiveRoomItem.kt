package dev.livefork.core.model

import kotlinx.serialization.Serializable

/**
 * 直播间卡片项。
 * 对应 Dart `LiveRoomItem`（lib/src/model/live_room_item.dart）。
 */
@Serializable
data class LiveRoomItem(
    /** 房间ID */
    val roomId: String,
    /** 标题 */
    val title: String,
    /** 封面 */
    val cover: String,
    /** 用户名 */
    val userName: String,
    /** 人气/在线人数 */
    val online: Int = 0,
)
