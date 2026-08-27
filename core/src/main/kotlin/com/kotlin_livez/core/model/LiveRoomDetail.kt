package com.kotlin_livez.core.model

/**
 * 直播间详情。
 * 对应 Dart `LiveRoomDetail`（lib/src/model/live_room_detail.dart）。
 * `data` / `danmakuData` 为附加信息（各站点自定义），对应 Dart 的 `dynamic`，故用 `Any?`。
 */
data class LiveRoomDetail(
    /** 房间ID */
    val roomId: String,
    /** 房间标题 */
    val title: String,
    /** 封面 */
    val cover: String,
    /** 用户名 */
    val userName: String,
    /** 头像 */
    val userAvatar: String,
    /** 在线 */
    val online: Int,
    /** 介绍 */
    val introduction: String? = null,
    /** 公告 */
    val notice: String? = null,
    /** 状态 */
    val status: Boolean,
    /** 附加信息 */
    val data: Any? = null,
    /** 弹幕附加信息 */
    val danmakuData: Any? = null,
    /** 是否录播 */
    val isRecord: Boolean = false,
    /** 链接 */
    val url: String,
    /** 显示时间 */
    val showTime: String? = null,
    /** 当前直播间所属分区 ID */
    val categoryId: String? = null,
    /** 当前直播间所属分区名称 */
    val categoryName: String? = null,
    /** 当前直播间所属父分区 ID */
    val categoryParentId: String? = null,
    /** 当前直播间所属父分区名称 */
    val categoryParentName: String? = null,
    /** 当前直播间所属分区图标 */
    val categoryPic: String? = null,
)
