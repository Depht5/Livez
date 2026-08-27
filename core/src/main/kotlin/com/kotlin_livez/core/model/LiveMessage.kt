package com.kotlin_livez.core.model

/**
 * 弹幕消息类型。
 * 对应 Dart `LiveMessageType`（lib/src/model/live_message.dart）。
 */
enum class LiveMessageType {
    /** 聊天 */
    chat,

    /** 礼物（暂时不支持） */
    gift,

    /** 在线人数 */
    online,

    /** 醒目留言 */
    superChat,
}

/**
 * 弹幕/聊天消息。
 * 对应 Dart `LiveMessage`。`data` 对应 Dart 的 `dynamic`（在线人数时为人气值 Long）。
 */
data class LiveMessage(
    /** 消息类型 */
    val type: LiveMessageType,
    /** 用户名 */
    val userName: String,
    /** 信息 */
    val message: String,
    /** 数据（Type=online 时为人气值） */
    val data: Any? = null,
    /** 弹幕颜色 */
    val color: LiveMessageColor,
    /** 弹幕内表情图片地址 */
    val imageUrls: List<String>? = null,
    /** 富文本消息片段（按平台顺序保存文字和图片，便于插回原位置） */
    val spans: List<LiveMessageSpan>? = null,
)

/**
 * 富文本片段：文字或图片二选一。
 * 对应 Dart `LiveMessageSpan`。
 */
data class LiveMessageSpan(
    val text: String? = null,
    val imageUrl: String? = null,
) {
    val isImage: Boolean get() = !imageUrl.isNullOrBlank()
    val isText: Boolean get() = !text.isNullOrEmpty()
}

/**
 * RGB 颜色，用于弹幕颜色。
 * 对应 Dart `LiveMessageColor`。
 */
data class LiveMessageColor(
    val r: Int,
    val g: Int,
    val b: Int,
) {
    companion object {
        val white = LiveMessageColor(255, 255, 255)

        /** 将整数颜色值转为 RGB（支持 4/6/8 位十六进制）。 */
        fun numberToColor(intColor: Int): LiveMessageColor {
            var obj = intColor.toString(16)
            if (obj.length == 4) {
                obj = "00$obj"
            }
            return when (obj.length) {
                6 -> LiveMessageColor(
                    obj.substring(0, 2).toInt(16),
                    obj.substring(2, 4).toInt(16),
                    obj.substring(4, 6).toInt(16),
                )
                8 -> LiveMessageColor(
                    obj.substring(2, 4).toInt(16),
                    obj.substring(4, 6).toInt(16),
                    obj.substring(6, 8).toInt(16),
                )
                else -> white
            }
        }
    }

    override fun toString(): String {
        return "#" +
            r.toString(16).padStart(2, '0') +
            g.toString(16).padStart(2, '0') +
            b.toString(16).padStart(2, '0')
    }
}

/**
 * 醒目留言（SC）。
 * 对应 Dart `LiveSuperChatMessage`。
 */
data class LiveSuperChatMessage(
    val id: String? = null,
    val userName: String,
    val face: String,
    val message: String,
    val price: Int,
    val startTime: java.time.Instant,
    val endTime: java.time.Instant,
    val backgroundColor: String,
    val backgroundBottomColor: String,
)
