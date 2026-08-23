package dev.livefork.core.danmaku.proto

import dev.livefork.core.common.ProtoReader

/**
 * 抖音弹幕协议手写实现（不依赖 protobuf 生成器）。
 *
 * 字段编号与类型以 `dart_simple_live/simple_live_core/lib/src/danmaku/proto/douyin.proto`
 * 为权威来源，类型映射与 `douyin.pb.dart` 对齐：
 * uint64/int64 → Long，uint32/int32 → Int，string → String，
 * bytes → ByteArray，bool → Boolean，repeated → List<T>，嵌入消息 → 对应 data class（可空）。
 *
 * 只实现了 `douyin_danmaku.dart` 实际消费的消息（PushFrame/Response/Message/ChatMessage/
 * User/Image/Text 富文本/RoomUserSeqMessage/GiftMessage 等）；未列出的字段在解析时跳过。
 */

/** PushFrame 帧头（每条 WebSocket 二进制消息的第一层）。字段编号见 douyin.proto。 */
data class PushFrame(
    val seqId: Long = 0L,
    val logId: Long = 0L,
    val service: Long = 0L,
    val method: Long = 0L,
    val headersList: List<HeadersList> = emptyList(),
    val payloadEncoding: String = "",
    val payloadType: String = "",
    val payload: ByteArray = ByteArray(0),
) {
    companion object {
        fun fromBytes(bytes: ByteArray): PushFrame = from(ProtoReader(bytes))

        fun from(reader: ProtoReader): PushFrame {
            var seqId = 0L
            var logId = 0L
            var service = 0L
            var method = 0L
            val headersList = mutableListOf<HeadersList>()
            var payloadEncoding = ""
            var payloadType = ""
            var payload = ByteArray(0)
            while (!reader.isAtEnd()) {
                val tag = reader.readTag()
                when (tag.fieldNumber) {
                    1 -> if (tag.wireType == 0) seqId = reader.readVarint() else reader.skipField(tag.wireType)
                    2 -> if (tag.wireType == 0) logId = reader.readVarint() else reader.skipField(tag.wireType)
                    3 -> if (tag.wireType == 0) service = reader.readVarint() else reader.skipField(tag.wireType)
                    4 -> if (tag.wireType == 0) method = reader.readVarint() else reader.skipField(tag.wireType)
                    5 -> if (tag.wireType == 2) headersList.add(reader.readMessage { HeadersList.from(it) }) else reader.skipField(tag.wireType)
                    6 -> if (tag.wireType == 2) payloadEncoding = reader.readString() else reader.skipField(tag.wireType)
                    7 -> if (tag.wireType == 2) payloadType = reader.readString() else reader.skipField(tag.wireType)
                    8 -> if (tag.wireType == 2) payload = reader.readBytes() else reader.skipField(tag.wireType)
                    else -> reader.skipField(tag.wireType)
                }
            }
            return PushFrame(
                seqId = seqId,
                logId = logId,
                service = service,
                method = method,
                headersList = headersList,
                payloadEncoding = payloadEncoding,
                payloadType = payloadType,
                payload = payload,
            )
        }
    }

    /**
     * 序列化为字节（对应 Dart `obj.writeToBuffer()`）。
     * 心跳/进房只带 payloadType='hb'，ack 额外带 logId + payload(internalExt)。
     */
    fun writeToBuffer(): ByteArray {
        val writer = dev.livefork.core.common.ProtoWriter()
        if (seqId != 0L) writer.writeUInt64(1, seqId)
        if (logId != 0L) writer.writeUInt64(2, logId)
        if (service != 0L) writer.writeUInt64(3, service)
        if (method != 0L) writer.writeUInt64(4, method)
        for (header in headersList) writer.writeBytes(5, header.writeToBuffer())
        if (payloadEncoding.isNotEmpty()) writer.writeString(6, payloadEncoding)
        if (payloadType.isNotEmpty()) writer.writeString(7, payloadType)
        if (payload.isNotEmpty()) writer.writeBytes(8, payload)
        return writer.toByteArray()
    }
}

/** HeadersList（PushFrame.headersList 元素，key/value 对）。 */
data class HeadersList(
    val key: String = "",
    val value: String = "",
) {
    companion object {
        fun from(reader: ProtoReader): HeadersList {
            var key = ""
            var value = ""
            while (!reader.isAtEnd()) {
                val tag = reader.readTag()
                when (tag.fieldNumber) {
                    1 -> if (tag.wireType == 2) key = reader.readString() else reader.skipField(tag.wireType)
                    2 -> if (tag.wireType == 2) value = reader.readString() else reader.skipField(tag.wireType)
                    else -> reader.skipField(tag.wireType)
                }
            }
            return HeadersList(key = key, value = value)
        }
    }

    fun writeToBuffer(): ByteArray {
        val writer = dev.livefork.core.common.ProtoWriter()
        if (key.isNotEmpty()) writer.writeString(1, key)
        if (value.isNotEmpty()) writer.writeString(2, value)
        return writer.toByteArray()
    }
}

/** Response（PushFrame.payload 解压后的 IM 响应）。字段编号见 douyin.proto。 */
data class Response(
    val messagesList: List<Message> = emptyList(),
    val cursor: String = "",
    val fetchInterval: Long = 0L,
    val now: Long = 0L,
    val internalExt: String = "",
    val fetchType: Int = 0,
    val heartbeatDuration: Long = 0L,
    val needAck: Boolean = false,
    val pushServer: String = "",
    val liveCursor: String = "",
    val historyNoMore: Boolean = false,
) {
    companion object {
        fun fromBytes(bytes: ByteArray): Response = from(ProtoReader(bytes))

        fun from(reader: ProtoReader): Response {
            val messagesList = mutableListOf<Message>()
            var cursor = ""
            var fetchInterval = 0L
            var now = 0L
            var internalExt = ""
            var fetchType = 0
            var heartbeatDuration = 0L
            var needAck = false
            var pushServer = ""
            var liveCursor = ""
            var historyNoMore = false
            while (!reader.isAtEnd()) {
                val tag = reader.readTag()
                when (tag.fieldNumber) {
                    1 -> if (tag.wireType == 2) messagesList.add(reader.readMessage { Message.from(it) }) else reader.skipField(tag.wireType)
                    2 -> if (tag.wireType == 2) cursor = reader.readString() else reader.skipField(tag.wireType)
                    3 -> if (tag.wireType == 0) fetchInterval = reader.readVarint() else reader.skipField(tag.wireType)
                    4 -> if (tag.wireType == 0) now = reader.readVarint() else reader.skipField(tag.wireType)
                    5 -> if (tag.wireType == 2) internalExt = reader.readString() else reader.skipField(tag.wireType)
                    6 -> if (tag.wireType == 0) fetchType = reader.readInt32() else reader.skipField(tag.wireType)
                    7 -> reader.skipField(tag.wireType) // map<string,string> routeParams，未使用
                    8 -> if (tag.wireType == 0) heartbeatDuration = reader.readVarint() else reader.skipField(tag.wireType)
                    9 -> if (tag.wireType == 0) needAck = reader.readBoolean() else reader.skipField(tag.wireType)
                    10 -> if (tag.wireType == 2) pushServer = reader.readString() else reader.skipField(tag.wireType)
                    11 -> if (tag.wireType == 2) liveCursor = reader.readString() else reader.skipField(tag.wireType)
                    12 -> if (tag.wireType == 0) historyNoMore = reader.readBoolean() else reader.skipField(tag.wireType)
                    else -> reader.skipField(tag.wireType)
                }
            }
            return Response(
                messagesList = messagesList,
                cursor = cursor,
                fetchInterval = fetchInterval,
                now = now,
                internalExt = internalExt,
                fetchType = fetchType,
                heartbeatDuration = heartbeatDuration,
                needAck = needAck,
                pushServer = pushServer,
                liveCursor = liveCursor,
                historyNoMore = historyNoMore,
            )
        }
    }
}

/** Message（Response.messagesList 元素：method 分发键 + 载荷）。 */
data class Message(
    val method: String = "",
    val payload: ByteArray = ByteArray(0),
    val msgId: Long = 0L,
    val msgType: Int = 0,
    val offset: Long = 0L,
    val needWrdsStore: Boolean = false,
    val wrdsVersion: Long = 0L,
    val wrdsSubKey: String = "",
) {
    companion object {
        fun from(reader: ProtoReader): Message {
            var method = ""
            var payload = ByteArray(0)
            var msgId = 0L
            var msgType = 0
            var offset = 0L
            var needWrdsStore = false
            var wrdsVersion = 0L
            var wrdsSubKey = ""
            while (!reader.isAtEnd()) {
                val tag = reader.readTag()
                when (tag.fieldNumber) {
                    1 -> if (tag.wireType == 2) method = reader.readString() else reader.skipField(tag.wireType)
                    2 -> if (tag.wireType == 2) payload = reader.readBytes() else reader.skipField(tag.wireType)
                    3 -> if (tag.wireType == 0) msgId = reader.readVarint() else reader.skipField(tag.wireType)
                    4 -> if (tag.wireType == 0) msgType = reader.readInt32() else reader.skipField(tag.wireType)
                    5 -> if (tag.wireType == 0) offset = reader.readVarint() else reader.skipField(tag.wireType)
                    6 -> if (tag.wireType == 0) needWrdsStore = reader.readBoolean() else reader.skipField(tag.wireType)
                    7 -> if (tag.wireType == 0) wrdsVersion = reader.readVarint() else reader.skipField(tag.wireType)
                    8 -> if (tag.wireType == 2) wrdsSubKey = reader.readString() else reader.skipField(tag.wireType)
                    else -> reader.skipField(tag.wireType)
                }
            }
            return Message(
                method = method,
                payload = payload,
                msgId = msgId,
                msgType = msgType,
                offset = offset,
                needWrdsStore = needWrdsStore,
                wrdsVersion = wrdsVersion,
                wrdsSubKey = wrdsSubKey,
            )
        }
    }
}

/** 聊天消息（WebcastChatMessage）。content 为纯文本，rtfContent 为富文本（含表情/图片）。
 * 字段编号见 douyin.proto 的 ChatMessage。 */
data class ChatMessage(
    val common: Common? = null,
    val user: User? = null,
    val content: String = "",
    val visibleToSender: Boolean = false,
    val backgroundImage: Image? = null,
    val fullScreenTextColor: String = "",
    val backgroundImageV2: Image? = null,
    val giftImage: Image? = null,
    val agreeMsgId: Long = 0L,
    val priorityLevel: Int = 0,
    val eventTime: Long = 0L,
    val sendReview: Boolean = false,
    val fromIntercom: Boolean = false,
    val intercomHideUserCard: Boolean = false,
    val chatBy: String = "",
    val individualChatPriority: Int = 0,
    val rtfContent: Text? = null,
) {
    companion object {
        fun fromBytes(bytes: ByteArray): ChatMessage = from(ProtoReader(bytes))

        fun from(reader: ProtoReader): ChatMessage {
            var common: Common? = null
            var user: User? = null
            var content = ""
            var visibleToSender = false
            var backgroundImage: Image? = null
            var fullScreenTextColor = ""
            var backgroundImageV2: Image? = null
            var giftImage: Image? = null
            var agreeMsgId = 0L
            var priorityLevel = 0
            var eventTime = 0L
            var sendReview = false
            var fromIntercom = false
            var intercomHideUserCard = false
            var chatBy = ""
            var individualChatPriority = 0
            var rtfContent: Text? = null
            while (!reader.isAtEnd()) {
                val tag = reader.readTag()
                when (tag.fieldNumber) {
                    1 -> if (tag.wireType == 2) common = reader.readMessage { Common.from(it) } else reader.skipField(tag.wireType)
                    2 -> if (tag.wireType == 2) user = reader.readMessage { User.from(it) } else reader.skipField(tag.wireType)
                    3 -> if (tag.wireType == 2) content = reader.readString() else reader.skipField(tag.wireType)
                    4 -> if (tag.wireType == 0) visibleToSender = reader.readBoolean() else reader.skipField(tag.wireType)
                    5 -> if (tag.wireType == 2) backgroundImage = reader.readMessage { Image.from(it) } else reader.skipField(tag.wireType)
                    6 -> if (tag.wireType == 2) fullScreenTextColor = reader.readString() else reader.skipField(tag.wireType)
                    7 -> if (tag.wireType == 2) backgroundImageV2 = reader.readMessage { Image.from(it) } else reader.skipField(tag.wireType)
                    9 -> if (tag.wireType == 2) giftImage = reader.readMessage { Image.from(it) } else reader.skipField(tag.wireType)
                    11 -> if (tag.wireType == 0) agreeMsgId = reader.readVarint() else reader.skipField(tag.wireType)
                    12 -> if (tag.wireType == 0) priorityLevel = reader.readInt32() else reader.skipField(tag.wireType)
                    15 -> if (tag.wireType == 0) eventTime = reader.readVarint() else reader.skipField(tag.wireType)
                    16 -> if (tag.wireType == 0) sendReview = reader.readBoolean() else reader.skipField(tag.wireType)
                    17 -> if (tag.wireType == 0) fromIntercom = reader.readBoolean() else reader.skipField(tag.wireType)
                    18 -> if (tag.wireType == 0) intercomHideUserCard = reader.readBoolean() else reader.skipField(tag.wireType)
                    20 -> if (tag.wireType == 2) chatBy = reader.readString() else reader.skipField(tag.wireType)
                    21 -> if (tag.wireType == 0) individualChatPriority = reader.readInt32() else reader.skipField(tag.wireType)
                    22 -> if (tag.wireType == 2) rtfContent = reader.readMessage { Text.from(it) } else reader.skipField(tag.wireType)
                    else -> reader.skipField(tag.wireType)
                }
            }
            return ChatMessage(
                common = common,
                user = user,
                content = content,
                visibleToSender = visibleToSender,
                backgroundImage = backgroundImage,
                fullScreenTextColor = fullScreenTextColor,
                backgroundImageV2 = backgroundImageV2,
                giftImage = giftImage,
                agreeMsgId = agreeMsgId,
                priorityLevel = priorityLevel,
                eventTime = eventTime,
                sendReview = sendReview,
                fromIntercom = fromIntercom,
                intercomHideUserCard = intercomHideUserCard,
                chatBy = chatBy,
                individualChatPriority = individualChatPriority,
                rtfContent = rtfContent,
            )
        }
    }

    /** 对应 Dart `hasRtfContent()`。 */
    val hasRtfContent: Boolean get() = rtfContent != null

    /** 发消息用户昵称（对应 Dart `chatMessage.user.nickName`，缺失为空）。 */
    val nickName: String get() = user?.nickName ?: ""
}

/** 在线人数消息（WebcastRoomUserSeqMessage）。totalUser 即当前在线人数（int64）。 */
data class RoomUserSeqMessage(
    val common: Common? = null,
    val ranksList: List<RoomUserSeqMessageContributor> = emptyList(),
    val total: Long = 0L,
    val popStr: String = "",
    val seatsList: List<RoomUserSeqMessageContributor> = emptyList(),
    val popularity: Long = 0L,
    val totalUser: Long = 0L,
    val totalUserStr: String = "",
    val totalStr: String = "",
) {
    companion object {
        fun fromBytes(bytes: ByteArray): RoomUserSeqMessage = from(ProtoReader(bytes))

        fun from(reader: ProtoReader): RoomUserSeqMessage {
            var common: Common? = null
            val ranksList = mutableListOf<RoomUserSeqMessageContributor>()
            var total = 0L
            var popStr = ""
            val seatsList = mutableListOf<RoomUserSeqMessageContributor>()
            var popularity = 0L
            var totalUser = 0L
            var totalUserStr = ""
            var totalStr = ""
            while (!reader.isAtEnd()) {
                val tag = reader.readTag()
                when (tag.fieldNumber) {
                    1 -> if (tag.wireType == 2) common = reader.readMessage { Common.from(it) } else reader.skipField(tag.wireType)
                    2 -> if (tag.wireType == 2) ranksList.add(reader.readMessage { RoomUserSeqMessageContributor.from(it) }) else reader.skipField(tag.wireType)
                    3 -> if (tag.wireType == 0) total = reader.readVarint() else reader.skipField(tag.wireType)
                    4 -> if (tag.wireType == 2) popStr = reader.readString() else reader.skipField(tag.wireType)
                    5 -> if (tag.wireType == 2) seatsList.add(reader.readMessage { RoomUserSeqMessageContributor.from(it) }) else reader.skipField(tag.wireType)
                    6 -> if (tag.wireType == 0) popularity = reader.readVarint() else reader.skipField(tag.wireType)
                    7 -> if (tag.wireType == 0) totalUser = reader.readVarint() else reader.skipField(tag.wireType)
                    8 -> if (tag.wireType == 2) totalUserStr = reader.readString() else reader.skipField(tag.wireType)
                    9 -> if (tag.wireType == 2) totalStr = reader.readString() else reader.skipField(tag.wireType)
                    else -> reader.skipField(tag.wireType)
                }
            }
            return RoomUserSeqMessage(
                common = common,
                ranksList = ranksList,
                total = total,
                popStr = popStr,
                seatsList = seatsList,
                popularity = popularity,
                totalUser = totalUser,
                totalUserStr = totalUserStr,
                totalStr = totalStr,
            )
        }
    }
}

/** RoomUserSeqMessage 排行榜条目。 */
data class RoomUserSeqMessageContributor(
    val score: Long = 0L,
    val user: User? = null,
    val rank: Long = 0L,
    val delta: Long = 0L,
    val isHidden: Boolean = false,
    val scoreDescription: String = "",
    val exactlyScore: String = "",
) {
    companion object {
        fun from(reader: ProtoReader): RoomUserSeqMessageContributor {
            var score = 0L
            var user: User? = null
            var rank = 0L
            var delta = 0L
            var isHidden = false
            var scoreDescription = ""
            var exactlyScore = ""
            while (!reader.isAtEnd()) {
                val tag = reader.readTag()
                when (tag.fieldNumber) {
                    1 -> if (tag.wireType == 0) score = reader.readVarint() else reader.skipField(tag.wireType)
                    2 -> if (tag.wireType == 2) user = reader.readMessage { User.from(it) } else reader.skipField(tag.wireType)
                    3 -> if (tag.wireType == 0) rank = reader.readVarint() else reader.skipField(tag.wireType)
                    4 -> if (tag.wireType == 0) delta = reader.readVarint() else reader.skipField(tag.wireType)
                    5 -> if (tag.wireType == 0) isHidden = reader.readBoolean() else reader.skipField(tag.wireType)
                    6 -> if (tag.wireType == 2) scoreDescription = reader.readString() else reader.skipField(tag.wireType)
                    7 -> if (tag.wireType == 2) exactlyScore = reader.readString() else reader.skipField(tag.wireType)
                    else -> reader.skipField(tag.wireType)
                }
            }
            return RoomUserSeqMessageContributor(
                score = score,
                user = user,
                rank = rank,
                delta = delta,
                isHidden = isHidden,
                scoreDescription = scoreDescription,
                exactlyScore = exactlyScore,
            )
        }
    }
}

/**
 * 礼物消息（WebcastGiftMessage）。当前仅"记录/跳过"（不产生 LiveMessage），
 * 保留常见字段供日志与后续扩展。
 */
data class GiftMessage(
    val common: Common? = null,
    val giftId: Long = 0L,
    val fanTicketCount: Long = 0L,
    val repeatCount: Long = 0L,
    val comboCount: Long = 0L,
    val user: User? = null,
    val toUser: User? = null,
    val groupId: Long = 0L,
    val gift: GiftStruct? = null,
    val logId: String = "",
    val sendType: Long = 0L,
    val sendTime: Long = 0L,
) {
    companion object {
        fun fromBytes(bytes: ByteArray): GiftMessage = from(ProtoReader(bytes))

        fun from(reader: ProtoReader): GiftMessage {
            var common: Common? = null
            var giftId = 0L
            var fanTicketCount = 0L
            var repeatCount = 0L
            var comboCount = 0L
            var user: User? = null
            var toUser: User? = null
            var groupId = 0L
            var gift: GiftStruct? = null
            var logId = ""
            var sendType = 0L
            var sendTime = 0L
            while (!reader.isAtEnd()) {
                val tag = reader.readTag()
                when (tag.fieldNumber) {
                    1 -> if (tag.wireType == 2) common = reader.readMessage { Common.from(it) } else reader.skipField(tag.wireType)
                    2 -> if (tag.wireType == 0) giftId = reader.readVarint() else reader.skipField(tag.wireType)
                    3 -> if (tag.wireType == 0) fanTicketCount = reader.readVarint() else reader.skipField(tag.wireType)
                    5 -> if (tag.wireType == 0) repeatCount = reader.readVarint() else reader.skipField(tag.wireType)
                    6 -> if (tag.wireType == 0) comboCount = reader.readVarint() else reader.skipField(tag.wireType)
                    7 -> if (tag.wireType == 2) user = reader.readMessage { User.from(it) } else reader.skipField(tag.wireType)
                    8 -> if (tag.wireType == 2) toUser = reader.readMessage { User.from(it) } else reader.skipField(tag.wireType)
                    11 -> if (tag.wireType == 0) groupId = reader.readVarint() else reader.skipField(tag.wireType)
                    15 -> if (tag.wireType == 2) gift = reader.readMessage { GiftStruct.from(it) } else reader.skipField(tag.wireType)
                    16 -> if (tag.wireType == 2) logId = reader.readString() else reader.skipField(tag.wireType)
                    17 -> if (tag.wireType == 0) sendType = reader.readVarint() else reader.skipField(tag.wireType)
                    33 -> if (tag.wireType == 0) sendTime = reader.readVarint() else reader.skipField(tag.wireType)
                    else -> reader.skipField(tag.wireType)
                }
            }
            return GiftMessage(
                common = common,
                giftId = giftId,
                fanTicketCount = fanTicketCount,
                repeatCount = repeatCount,
                comboCount = comboCount,
                user = user,
                toUser = toUser,
                groupId = groupId,
                gift = gift,
                logId = logId,
                sendType = sendType,
                sendTime = sendTime,
            )
        }
    }
}

/** GiftStruct（礼物结构，仅保留名称/描述等基础字段）。 */
data class GiftStruct(
    val image: Image? = null,
    val describe: String = "",
    val id: Long = 0L,
    val name: String = "",
) {
    companion object {
        fun from(reader: ProtoReader): GiftStruct {
            var image: Image? = null
            var describe = ""
            var id = 0L
            var name = ""
            while (!reader.isAtEnd()) {
                val tag = reader.readTag()
                when (tag.fieldNumber) {
                    1 -> if (tag.wireType == 2) image = reader.readMessage { Image.from(it) } else reader.skipField(tag.wireType)
                    2 -> if (tag.wireType == 2) describe = reader.readString() else reader.skipField(tag.wireType)
                    5 -> if (tag.wireType == 0) id = reader.readVarint() else reader.skipField(tag.wireType)
                    16 -> if (tag.wireType == 2) name = reader.readString() else reader.skipField(tag.wireType)
                    else -> reader.skipField(tag.wireType)
                }
            }
            return GiftStruct(image = image, describe = describe, id = id, name = name)
        }
    }
}

/** 用户信息（仅保留常用字段；nickName 为弹幕显示名）。 */
data class User(
    val id: Long = 0L,
    val shortId: Long = 0L,
    val nickName: String = "",
    val gender: Int = 0,
    val signature: String = "",
    val level: Int = 0,
    val birthday: Long = 0L,
    val avatarThumb: Image? = null,
    val avatarMedium: Image? = null,
    val avatarLarge: Image? = null,
    val verified: Boolean = false,
    val city: String = "",
    val displayId: String = "",
    val secUid: String = "",
    val idStr: String = "",
) {
    companion object {
        fun from(reader: ProtoReader): User {
            var id = 0L
            var shortId = 0L
            var nickName = ""
            var gender = 0
            var signature = ""
            var level = 0
            var birthday = 0L
            var avatarThumb: Image? = null
            var avatarMedium: Image? = null
            var avatarLarge: Image? = null
            var verified = false
            var city = ""
            var displayId = ""
            var secUid = ""
            var idStr = ""
            while (!reader.isAtEnd()) {
                val tag = reader.readTag()
                when (tag.fieldNumber) {
                    1 -> if (tag.wireType == 0) id = reader.readVarint() else reader.skipField(tag.wireType)
                    2 -> if (tag.wireType == 0) shortId = reader.readVarint() else reader.skipField(tag.wireType)
                    3 -> if (tag.wireType == 2) nickName = reader.readString() else reader.skipField(tag.wireType)
                    4 -> if (tag.wireType == 0) gender = reader.readInt32() else reader.skipField(tag.wireType)
                    5 -> if (tag.wireType == 2) signature = reader.readString() else reader.skipField(tag.wireType)
                    6 -> if (tag.wireType == 0) level = reader.readInt32() else reader.skipField(tag.wireType)
                    7 -> if (tag.wireType == 0) birthday = reader.readVarint() else reader.skipField(tag.wireType)
                    9 -> if (tag.wireType == 2) avatarThumb = reader.readMessage { Image.from(it) } else reader.skipField(tag.wireType)
                    10 -> if (tag.wireType == 2) avatarMedium = reader.readMessage { Image.from(it) } else reader.skipField(tag.wireType)
                    11 -> if (tag.wireType == 2) avatarLarge = reader.readMessage { Image.from(it) } else reader.skipField(tag.wireType)
                    12 -> if (tag.wireType == 0) verified = reader.readBoolean() else reader.skipField(tag.wireType)
                    14 -> if (tag.wireType == 2) city = reader.readString() else reader.skipField(tag.wireType)
                    38 -> if (tag.wireType == 2) displayId = reader.readString() else reader.skipField(tag.wireType)
                    46 -> if (tag.wireType == 2) secUid = reader.readString() else reader.skipField(tag.wireType)
                    1028 -> if (tag.wireType == 2) idStr = reader.readString() else reader.skipField(tag.wireType)
                    else -> reader.skipField(tag.wireType)
                }
            }
            return User(
                id = id,
                shortId = shortId,
                nickName = nickName,
                gender = gender,
                signature = signature,
                level = level,
                birthday = birthday,
                avatarThumb = avatarThumb,
                avatarMedium = avatarMedium,
                avatarLarge = avatarLarge,
                verified = verified,
                city = city,
                displayId = displayId,
                secUid = secUid,
                idStr = idStr,
            )
        }
    }
}

/** 图片（表情/头像/礼物图）。Dart 弹幕用 urlListList/openWebUrl/uri/content 提取表情 URL。 */
data class Image(
    val urlListList: List<String> = emptyList(),
    val uri: String = "",
    val height: Long = 0L,
    val width: Long = 0L,
    val avgColor: String = "",
    val imageType: Int = 0,
    val openWebUrl: String = "",
    val content: ImageContent? = null,
    val isAnimated: Boolean = false,
) {
    companion object {
        fun from(reader: ProtoReader): Image {
            val urlListList = mutableListOf<String>()
            var uri = ""
            var height = 0L
            var width = 0L
            var avgColor = ""
            var imageType = 0
            var openWebUrl = ""
            var content: ImageContent? = null
            var isAnimated = false
            while (!reader.isAtEnd()) {
                val tag = reader.readTag()
                when (tag.fieldNumber) {
                    1 -> if (tag.wireType == 2) urlListList.add(reader.readString()) else reader.skipField(tag.wireType)
                    2 -> if (tag.wireType == 2) uri = reader.readString() else reader.skipField(tag.wireType)
                    3 -> if (tag.wireType == 0) height = reader.readVarint() else reader.skipField(tag.wireType)
                    4 -> if (tag.wireType == 0) width = reader.readVarint() else reader.skipField(tag.wireType)
                    5 -> if (tag.wireType == 2) avgColor = reader.readString() else reader.skipField(tag.wireType)
                    6 -> if (tag.wireType == 0) imageType = reader.readInt32() else reader.skipField(tag.wireType)
                    7 -> if (tag.wireType == 2) openWebUrl = reader.readString() else reader.skipField(tag.wireType)
                    8 -> if (tag.wireType == 2) content = reader.readMessage { ImageContent.from(it) } else reader.skipField(tag.wireType)
                    9 -> if (tag.wireType == 0) isAnimated = reader.readBoolean() else reader.skipField(tag.wireType)
                    else -> reader.skipField(tag.wireType)
                }
            }
            return Image(
                urlListList = urlListList,
                uri = uri,
                height = height,
                width = width,
                avgColor = avgColor,
                imageType = imageType,
                openWebUrl = openWebUrl,
                content = content,
                isAnimated = isAnimated,
            )
        }
    }
}

/** ImageContent（图片内容说明，弹幕表情回退文本来自 alternativeText/name）。 */
data class ImageContent(
    val name: String = "",
    val fontColor: String = "",
    val level: Long = 0L,
    val alternativeText: String = "",
) {
    companion object {
        fun from(reader: ProtoReader): ImageContent {
            var name = ""
            var fontColor = ""
            var level = 0L
            var alternativeText = ""
            while (!reader.isAtEnd()) {
                val tag = reader.readTag()
                when (tag.fieldNumber) {
                    1 -> if (tag.wireType == 2) name = reader.readString() else reader.skipField(tag.wireType)
                    2 -> if (tag.wireType == 2) fontColor = reader.readString() else reader.skipField(tag.wireType)
                    3 -> if (tag.wireType == 0) level = reader.readVarint() else reader.skipField(tag.wireType)
                    4 -> if (tag.wireType == 2) alternativeText = reader.readString() else reader.skipField(tag.wireType)
                    else -> reader.skipField(tag.wireType)
                }
            }
            return ImageContent(
                name = name,
                fontColor = fontColor,
                level = level,
                alternativeText = alternativeText,
            )
        }
    }
}

/** 富文本 Text（rtfContent）。piecesList 按顺序组成消息内容（文字/图片/引用）。 */
data class Text(
    val key: String = "",
    val defaultPatter: String = "",
    val piecesList: List<TextPiece> = emptyList(),
) {
    companion object {
        fun from(reader: ProtoReader): Text {
            var key = ""
            var defaultPatter = ""
            val piecesList = mutableListOf<TextPiece>()
            while (!reader.isAtEnd()) {
                val tag = reader.readTag()
                when (tag.fieldNumber) {
                    1 -> if (tag.wireType == 2) key = reader.readString() else reader.skipField(tag.wireType)
                    2 -> if (tag.wireType == 2) defaultPatter = reader.readString() else reader.skipField(tag.wireType)
                    3 -> reader.skipField(tag.wireType) // defaultFormat（TextFormat），未使用
                    4 -> if (tag.wireType == 2) piecesList.add(reader.readMessage { TextPiece.from(it) }) else reader.skipField(tag.wireType)
                    else -> reader.skipField(tag.wireType)
                }
            }
            return Text(key = key, defaultPatter = defaultPatter, piecesList = piecesList)
        }
    }
}

/** TextPiece：富文本片段，一次最多携带一种值（文字/用户/礼物/爱心/引用/图片）。 */
data class TextPiece(
    val type: Boolean = false,
    val stringValue: String = "",
    val userValue: TextPieceUser? = null,
    val giftValue: TextPieceGift? = null,
    val heartValue: TextPieceHeart? = null,
    val patternRefValue: TextPiecePatternRef? = null,
    val imageValue: TextPieceImage? = null,
) {
    companion object {
        fun from(reader: ProtoReader): TextPiece {
            var type = false
            var stringValue = ""
            var userValue: TextPieceUser? = null
            var giftValue: TextPieceGift? = null
            var heartValue: TextPieceHeart? = null
            var patternRefValue: TextPiecePatternRef? = null
            var imageValue: TextPieceImage? = null
            while (!reader.isAtEnd()) {
                val tag = reader.readTag()
                when (tag.fieldNumber) {
                    1 -> if (tag.wireType == 0) type = reader.readBoolean() else reader.skipField(tag.wireType)
                    2 -> reader.skipField(tag.wireType) // format（TextFormat），未使用
                    3 -> if (tag.wireType == 2) stringValue = reader.readString() else reader.skipField(tag.wireType)
                    4 -> if (tag.wireType == 2) userValue = reader.readMessage { TextPieceUser.from(it) } else reader.skipField(tag.wireType)
                    5 -> if (tag.wireType == 2) giftValue = reader.readMessage { TextPieceGift.from(it) } else reader.skipField(tag.wireType)
                    6 -> if (tag.wireType == 2) heartValue = reader.readMessage { TextPieceHeart.from(it) } else reader.skipField(tag.wireType)
                    7 -> if (tag.wireType == 2) patternRefValue = reader.readMessage { TextPiecePatternRef.from(it) } else reader.skipField(tag.wireType)
                    8 -> if (tag.wireType == 2) imageValue = reader.readMessage { TextPieceImage.from(it) } else reader.skipField(tag.wireType)
                    else -> reader.skipField(tag.wireType)
                }
            }
            return TextPiece(
                type = type,
                stringValue = stringValue,
                userValue = userValue,
                giftValue = giftValue,
                heartValue = heartValue,
                patternRefValue = patternRefValue,
                imageValue = imageValue,
            )
        }
    }
}

/** TextPieceImage：图片片段（弹幕表情）。 */
data class TextPieceImage(
    val image: Image? = null,
    val scalingRate: Float = 0f,
) {
    companion object {
        fun from(reader: ProtoReader): TextPieceImage {
            var image: Image? = null
            var scalingRate = 0f
            while (!reader.isAtEnd()) {
                val tag = reader.readTag()
                when (tag.fieldNumber) {
                    1 -> if (tag.wireType == 2) image = reader.readMessage { Image.from(it) } else reader.skipField(tag.wireType)
                    2 -> if (tag.wireType == 5) scalingRate = reader.readFloat() else reader.skipField(tag.wireType)
                    else -> reader.skipField(tag.wireType)
                }
            }
            return TextPieceImage(image = image, scalingRate = scalingRate)
        }
    }
}

/** TextPiecePatternRef：图案引用片段（defaultPattern 为兜底文案，如 [表情]）。 */
data class TextPiecePatternRef(
    val key: String = "",
    val defaultPattern: String = "",
) {
    companion object {
        fun from(reader: ProtoReader): TextPiecePatternRef {
            var key = ""
            var defaultPattern = ""
            while (!reader.isAtEnd()) {
                val tag = reader.readTag()
                when (tag.fieldNumber) {
                    1 -> if (tag.wireType == 2) key = reader.readString() else reader.skipField(tag.wireType)
                    2 -> if (tag.wireType == 2) defaultPattern = reader.readString() else reader.skipField(tag.wireType)
                    else -> reader.skipField(tag.wireType)
                }
            }
            return TextPiecePatternRef(key = key, defaultPattern = defaultPattern)
        }
    }
}

/** TextPieceHeart：爱心片段。 */
data class TextPieceHeart(
    val color: String = "",
) {
    companion object {
        fun from(reader: ProtoReader): TextPieceHeart {
            var color = ""
            while (!reader.isAtEnd()) {
                val tag = reader.readTag()
                when (tag.fieldNumber) {
                    1 -> if (tag.wireType == 2) color = reader.readString() else reader.skipField(tag.wireType)
                    else -> reader.skipField(tag.wireType)
                }
            }
            return TextPieceHeart(color = color)
        }
    }
}

/** TextPieceGift：礼物片段。 */
data class TextPieceGift(
    val giftId: Long = 0L,
    val nameRef: PatternRef? = null,
) {
    companion object {
        fun from(reader: ProtoReader): TextPieceGift {
            var giftId = 0L
            var nameRef: PatternRef? = null
            while (!reader.isAtEnd()) {
                val tag = reader.readTag()
                when (tag.fieldNumber) {
                    1 -> if (tag.wireType == 0) giftId = reader.readVarint() else reader.skipField(tag.wireType)
                    2 -> if (tag.wireType == 2) nameRef = reader.readMessage { PatternRef.from(it) } else reader.skipField(tag.wireType)
                    else -> reader.skipField(tag.wireType)
                }
            }
            return TextPieceGift(giftId = giftId, nameRef = nameRef)
        }
    }
}

/** TextPieceUser：用户片段。 */
data class TextPieceUser(
    val user: User? = null,
    val withColon: Boolean = false,
) {
    companion object {
        fun from(reader: ProtoReader): TextPieceUser {
            var user: User? = null
            var withColon = false
            while (!reader.isAtEnd()) {
                val tag = reader.readTag()
                when (tag.fieldNumber) {
                    1 -> if (tag.wireType == 2) user = reader.readMessage { User.from(it) } else reader.skipField(tag.wireType)
                    2 -> if (tag.wireType == 0) withColon = reader.readBoolean() else reader.skipField(tag.wireType)
                    else -> reader.skipField(tag.wireType)
                }
            }
            return TextPieceUser(user = user, withColon = withColon)
        }
    }
}

/** PatternRef（礼物名引用）。 */
data class PatternRef(
    val key: String = "",
    val defaultPattern: String = "",
) {
    companion object {
        fun from(reader: ProtoReader): PatternRef {
            var key = ""
            var defaultPattern = ""
            while (!reader.isAtEnd()) {
                val tag = reader.readTag()
                when (tag.fieldNumber) {
                    1 -> if (tag.wireType == 2) key = reader.readString() else reader.skipField(tag.wireType)
                    2 -> if (tag.wireType == 2) defaultPattern = reader.readString() else reader.skipField(tag.wireType)
                    else -> reader.skipField(tag.wireType)
                }
            }
            return PatternRef(key = key, defaultPattern = defaultPattern)
        }
    }
}

/** Common（消息共有头，仅保留基础字段；Dart 弹幕未直接消费颜色等）。 */
data class Common(
    val method: String = "",
    val msgId: Long = 0L,
    val roomId: Long = 0L,
    val createTime: Long = 0L,
    val describe: String = "",
) {
    companion object {
        fun from(reader: ProtoReader): Common {
            var method = ""
            var msgId = 0L
            var roomId = 0L
            var createTime = 0L
            var describe = ""
            while (!reader.isAtEnd()) {
                val tag = reader.readTag()
                when (tag.fieldNumber) {
                    1 -> if (tag.wireType == 2) method = reader.readString() else reader.skipField(tag.wireType)
                    2 -> if (tag.wireType == 0) msgId = reader.readVarint() else reader.skipField(tag.wireType)
                    3 -> if (tag.wireType == 0) roomId = reader.readVarint() else reader.skipField(tag.wireType)
                    4 -> if (tag.wireType == 0) createTime = reader.readVarint() else reader.skipField(tag.wireType)
                    7 -> if (tag.wireType == 2) describe = reader.readString() else reader.skipField(tag.wireType)
                    else -> reader.skipField(tag.wireType)
                }
            }
            return Common(
                method = method,
                msgId = msgId,
                roomId = roomId,
                createTime = createTime,
                describe = describe,
            )
        }
    }
}