package com.kotlin_livez.app.models.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Room 实体 —— 对应 Dart Hive 模型（lib/models/db/ 下），@HiveType。
 * 时间一律用 epoch millis(Long) 存储；键值按 Dart 语义：FollowUser/History 的
 * id = "${siteId}_${roomId}"。
 */

/** 关注用户（Dart @HiveType(typeId: 1) FollowUser）。 */
@Entity(tableName = "follow_user")
data class FollowUserEntity(
    /** id = siteId_roomId */
    @PrimaryKey val id: String,
    val roomId: String,
    val siteId: String,
    val userName: String,
    val face: String,
    /** epoch millis（Dart DateTime addTime） */
    val addTime: Long,
    /** 默认 "全部" */
    val tag: String = "全部",
    val isSpecialFollow: Boolean = false,
    val roomTitle: String = "",
    val roomCover: String = "",
    /** epoch millis；未更新为 null */
    val previewUpdatedAt: Long? = null,
)

/** 观看历史（Dart @HiveType(typeId: 2) History）。 */
@Entity(tableName = "history")
data class HistoryEntity(
    /** id = siteId_roomId */
    @PrimaryKey val id: String,
    val roomId: String,
    val siteId: String,
    val userName: String,
    val face: String,
    /** epoch millis（Dart DateTime updateTime） */
    val updateTime: Long,
)

/** 关注标签（Dart @HiveType(typeId: 3) FollowUserTag）。 */
@Entity(tableName = "follow_user_tag")
data class FollowUserTagEntity(
    @PrimaryKey val id: String,
    /** 用户自定义标签名 */
    val tag: String,
    /** 所属关注用户 id 列表（JSON 数组字符串，经 [TagConverters] 转换） */
    val userIds: String = "[]",
)

/** Room 类型转换器：List<String> ↔ JSON 字符串（镜像 Hive 直接存 List）。 */
class TagConverters {
    @TypeConverter
    fun fromStringList(value: List<String>): String =
        TAG_JSON.encodeToString(value)

    @TypeConverter
    fun toStringList(value: String): List<String> =
        if (value.isBlank()) emptyList() else TAG_JSON.decodeFromString(value)

    private companion object {
        val TAG_JSON = Json { ignoreUnknownKeys = true }
    }
}