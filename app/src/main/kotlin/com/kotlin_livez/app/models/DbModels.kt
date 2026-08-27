package com.kotlin_livez.app.models

import com.kotlin_livez.app.models.db.FollowUserEntity
import com.kotlin_livez.app.models.db.FollowUserTagEntity
import com.kotlin_livez.app.models.db.HistoryEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * 业务模型 —— 对应 Dart `models/follow_user_item.dart`（FollowUser）、
 * `models/db/follow_user.dart` 等 Hive 模型的 DTO 部分。
 * fromJson/toJson 采用与 Dart 一致的宽容解析语义（缺失字段回退默认值）。
 */

@Serializable
data class FollowUser(
    /** id = siteId_roomId；身份校正时可能更新（镜像 Dart 可变字段） */
    var id: String = "",
    /** 房间号；身份校正时可能更新（镜像 Dart 可变字段） */
    var roomId: String = "",
    val siteId: String = "",
    val userName: String = "",
    val face: String = "",
    /** epoch millis（Dart DateTime addTime） */
    val addTime: Long = 0L,
    /** 默认 "全部"；标签修改时可更新（镜像 Dart 可变字段） */
    var tag: String = "全部",
    /** 特别关注（镜像 Dart 可变字段） */
    var isSpecialFollow: Boolean = false,
    /** 房间标题（刷新时更新；镜像 Dart 可变字段） */
    var roomTitle: String = "",
    /** 房间封面（刷新时更新；镜像 Dart 可变字段） */
    var roomCover: String = "",
    /** epoch millis；未更新为 null */
    var previewUpdatedAt: Long? = null,

    /** 运行时直播状态（镜像 Dart Rx<int> liveStatus；不入库）：0=未知 1=未直播 2=直播中 */
    @Transient
    var liveStatus: Int = 0,

    /** 运行时开播时间（镜像 Dart Rx<DateTime?> liveStartTime；不入库） */
    @Transient
    var liveStartTime: Long? = null,
) {
    fun toEntity(): FollowUserEntity = FollowUserEntity(
        id = id,
        roomId = roomId,
        siteId = siteId,
        userName = userName,
        face = face,
        addTime = addTime,
        tag = tag,
        isSpecialFollow = isSpecialFollow,
        roomTitle = roomTitle,
        roomCover = roomCover,
        previewUpdatedAt = previewUpdatedAt,
    )

    /** 镜像 Dart FollowUser.fromJson：id 空则回退 siteId_roomId；tag 空回退 "全部"。 */
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("id", id)
        put("roomId", roomId)
        put("siteId", siteId)
        put("userName", userName)
        put("face", face)
        put("addTime", instantText(addTime))
        put("tag", tag)
        put("isSpecialFollow", isSpecialFollow)
        put("roomTitle", roomTitle)
        put("roomCover", roomCover)
        put(
            "previewUpdatedAt",
            previewUpdatedAt?.let { instantText(it) } ?: "",
        )
    }

    companion object {
        fun fromEntity(e: FollowUserEntity): FollowUser = FollowUser(
            id = e.id,
            roomId = e.roomId,
            siteId = e.siteId,
            userName = e.userName,
            face = e.face,
            addTime = e.addTime,
            tag = e.tag,
            isSpecialFollow = e.isSpecialFollow,
            roomTitle = e.roomTitle,
            roomCover = e.roomCover,
            previewUpdatedAt = e.previewUpdatedAt,
        )

        /** 镜像 Dart fromJson。json 为 JsonObject（或 JsonElement 对象）。 */
        fun fromJson(json: JsonElement?): FollowUser {
            val obj = json as? JsonObject ?: return FollowUser()
            val roomId = obj["roomId"]?.strTrim() ?: ""
            val siteId = obj["siteId"]?.strTrim() ?: ""
            val idCandidate = obj["id"]?.strTrim()
            val id = if (!idCandidate.isNullOrEmpty()) idCandidate else "${siteId}_$roomId"
            val tagCandidate = obj["tag"]?.strTrim()
            return FollowUser(
                id = id,
                roomId = roomId,
                siteId = siteId,
                userName = obj["userName"]?.strOrEmpty() ?: "",
                face = obj["face"]?.strOrEmpty() ?: "",
                addTime = parseInstant(obj["addTime"]) ?: System.currentTimeMillis(),
                tag = if (tagCandidate.isNullOrEmpty()) "全部" else tagCandidate,
                isSpecialFollow = obj["isSpecialFollow"].isTrueValue(),
                roomTitle = obj["roomTitle"]?.strOrEmpty() ?: "",
                roomCover = obj["roomCover"]?.strOrEmpty() ?: "",
                previewUpdatedAt = parseInstant(obj["previewUpdatedAt"]),
            )
        }
    }
}

@Serializable
data class History(
    /** id = siteId_roomId */
    val id: String = "",
    val roomId: String = "",
    val siteId: String = "",
    val userName: String = "",
    val face: String = "",
    /** epoch millis（Dart DateTime updateTime） */
    val updateTime: Long = 0L,
) {
    fun toEntity(): HistoryEntity = HistoryEntity(
        id = id,
        roomId = roomId,
        siteId = siteId,
        userName = userName,
        face = face,
        updateTime = updateTime,
    )

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("id", id)
        put("roomId", roomId)
        put("siteId", siteId)
        put("userName", userName)
        put("face", face)
        put("updateTime", instantText(updateTime))
    }

    companion object {
        fun fromEntity(e: HistoryEntity): History = History(
            id = e.id,
            roomId = e.roomId,
            siteId = e.siteId,
            userName = e.userName,
            face = e.face,
            updateTime = e.updateTime,
        )

        fun fromJson(json: JsonElement?): History {
            val obj = json as? JsonObject ?: return History()
            val roomId = obj["roomId"]?.strTrim() ?: ""
            val siteId = obj["siteId"]?.strTrim() ?: ""
            val idCandidate = obj["id"]?.strTrim()
            val id = if (!idCandidate.isNullOrEmpty()) idCandidate else "${siteId}_$roomId"
            return History(
                id = id,
                roomId = roomId,
                siteId = siteId,
                userName = obj["userName"]?.strOrEmpty() ?: "",
                face = obj["face"]?.strOrEmpty() ?: "",
                updateTime = parseInstant(obj["updateTime"]) ?: System.currentTimeMillis(),
            )
        }
    }
}

@Serializable
data class FollowUserTag(
    val id: String = "",
    /** 用户自定义标签名 */
    val tag: String = "",
    /** 所属关注用户 id 列表 */
    val userId: List<String> = emptyList(),
) {
    fun toEntity(): FollowUserTagEntity = FollowUserTagEntity(
        id = id,
        tag = tag,
        userIds = com.kotlin_livez.app.models.db.TagConverters().fromStringList(userId),
    )

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("id", id)
        put("tag", tag)
        put("userId", JsonArray(userId.map { JsonPrimitive(it) }))
    }

    fun copyWith(id: String? = null, tag: String? = null, userId: List<String>? = null): FollowUserTag =
        FollowUserTag(
            id = id ?: this.id,
            tag = tag ?: this.tag,
            userId = userId ?: this.userId,
        )

    companion object {
        fun fromEntity(e: FollowUserTagEntity): FollowUserTag = FollowUserTag(
            id = e.id,
            tag = e.tag,
            userId = com.kotlin_livez.app.models.db.TagConverters().toStringList(e.userIds),
        )

        fun fromJson(json: JsonElement?): FollowUserTag {
            val obj = json as? JsonObject ?: return FollowUserTag()
            val rawUserIds = obj["userId"]
            val userIds = (rawUserIds as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { it.strOrEmpty() }
                ?: emptyList()
            return FollowUserTag(
                id = obj["id"]?.strTrim() ?: "",
                tag = obj["tag"]?.strTrim() ?: "",
                userId = userIds,
            )
        }
    }
}

/* ---------- JsonElement 辅助（宽容解析，对应 Dart 的 asT/toString 语义） ---------- */

private fun JsonElement?.strTrim(): String? {
    val content = strOrEmpty() ?: return null
    return content.trim().takeIf { it.isNotEmpty() }
}

private fun JsonElement?.strOrEmpty(): String? =
    (this as? kotlinx.serialization.json.JsonPrimitive)?.content

private fun JsonElement?.isTrueValue(): Boolean {
    val content = strOrEmpty() ?: return false
    return content == "true" || content == "1"
}

private fun parseInstant(e: JsonElement?): Long? {
    val text = e?.strOrEmpty()?.trim() ?: return null
    if (text.isEmpty()) return null
    return try {
        // 兼容 ISO-8601 与本地 "2025-01-01 00:00:00.000"（省略毫秒）
        if (text.contains("T") || text.endsWith("Z")) {
            Instant.parse(text).toEpochMilli()
        } else {
            val normalized = text.replace(' ', 'T')
            val withZone = if (normalized.endsWith("Z")) normalized else "$normalized" + (if (normalized.contains(".")) "" else ".000") + "Z"
            Instant.parse(withZone).toEpochMilli()
        }
    } catch (_: Exception) {
        null
    }
}

private fun instantText(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()