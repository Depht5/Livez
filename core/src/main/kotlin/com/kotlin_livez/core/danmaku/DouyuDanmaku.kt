package com.kotlin_livez.core.danmaku

import com.kotlin_livez.core.common.CoreLog
import com.kotlin_livez.core.common.WebSocketUtil
import com.kotlin_livez.core.common.intv
import com.kotlin_livez.core.common.str
import com.kotlin_livez.core.model.LiveMessage
import com.kotlin_livez.core.model.LiveMessageColor
import com.kotlin_livez.core.model.LiveMessageType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 斗鱼弹幕实现。
 * 对应 Dart `DouyuDanmaku`（lib/src/danmaku/douyu_danmaku.dart）：自定义二进制帧协议
 * （689 消息类型、包长冗余头、尾部 0 字节）、STT 文本协议解析、心跳 mrkl、
 * 进房 loginreq/joingroup。弹幕服务器为 danmuproxy.douyu.com 8501~8506。
 */
class DouyuDanmaku : LiveDanmaku() {

    override var heartbeatTime: Int = 45 * 1000

    private var webSocketUtil: WebSocketUtil? = null

    /** 弹幕服务器候选地址（对应 Dart serverUrls 静态列表）。 */
    companion object {
        val serverUrls: List<String> =
            List(6) { index -> "wss://danmuproxy.douyu.com:${8501 + index}" }
    }

    /** 默认连接地址（对应 Dart serverUrl，取第一台）。 */
    var serverUrl: String = serverUrls.first()

    override suspend fun start(arg: Any?) {
        webSocketUtil = WebSocketUtil(
            url = serverUrl,
            backupUrls = serverUrls.filter { it != serverUrl },
            shuffleUrls = true,
            maxConnectAttempts = 5,
            connectTimeoutMs = 3_000,
            reconnectDelayMs = 5_000,
            heartBeatTime = heartbeatTime.toLong(),
            // danmuproxy 不实现 RFC 6455 ping/pong（对传输层 ping 的回应帧碎片化，
            // okio 报 "Control frames must be final" 并判死连接）：5f16784 引入的默认
            // 30s ping 使斗鱼每 ~35s 断开重连（真机 logcat 佐证）。业务层 mrkl 心跳
            // （45s）足以维持连接，与 Dart 参照一致（dart:io WebSocket 无传输层 ping）；
            // 对齐虎牙 a63fdfa 的处理。
            pingIntervalMs = 0,
            onByteMessage = { decodeMessage(it) },
            onReady = {
                onReady?.invoke()
                joinRoom(arg?.toString() ?: "")
            },
            onHeartBeat = { heartbeat() },
            onReconnect = { onClose?.invoke("与服务器断开连接，正在尝试重连") },
            onClose = {
                CoreLog.w("[DouyuDanmaku] 弹幕连接失败：$it")
                onClose?.invoke("服务器连接失败$it")
            },
        )
        webSocketUtil?.connect()
    }

    /** 进房：先 loginreq 再 joingroup（对应 Dart joinRoom）。 */
    private fun joinRoom(roomId: String) {
        webSocketUtil?.sendBytes(serializeDouyu("type@=loginreq/roomid@=$roomId/"))
        webSocketUtil?.sendBytes(serializeDouyu("type@=joingroup/rid@=$roomId/gid@=-9999/"))
    }

    override fun heartbeat() {
        webSocketUtil?.sendBytes(serializeDouyu("type@=mrkl/"))
    }

    override fun stop() {
        onMessage = null
        onClose = null
        onReady = null
        webSocketUtil?.close()
        webSocketUtil = null
    }

    /** 解析二进制帧并分派消息（对应 Dart decodeMessage）。 */
    private fun decodeMessage(data: ByteArray) {
        try {
            val result = deserializeDouyu(data) ?: return
            val jsonData = sttToJObject(result)
            if (jsonData !is JsonObject) return

            val type = jsonData["type"].str()
            // 斗鱼好像不会返回人气值
            if (type == "chatmsg") {
                // 屏蔽阴间弹幕
                if (jsonData["dms"] == null || jsonData["dms"] is JsonNull) {
                    return
                }
                val col = jsonData["col"].intv()
                val liveMsg = LiveMessage(
                    type = LiveMessageType.chat,
                    userName = jsonData["nn"].str(),
                    message = jsonData["txt"].str(),
                    color = getColor(col),
                )
                onMessage?.invoke(liveMsg)
            }
        } catch (e: Exception) {
            CoreLog.error(e)
        }
    }

    /**
     * 序列化发送帧（对应 Dart serializeDouyu，逐字节一致）：
     * [int32 包长][int32 包长][int16 689][byte 0][byte 0][body][byte 0]，
     * 其中包长 = 4 + 4 + body.length + 1（与 Dart 写法一致）。
     */
    private fun serializeDouyu(body: String): ByteArray {
        return try {
            val clientSendToServer = 689
            val buffer = body.toByteArray(Charsets.UTF_8)
            val totalLength = 4 + 4 + buffer.size + 1
            val out = ByteBuffer
                .allocate(4 + 4 + 2 + 1 + 1 + buffer.size + 1)
                .order(ByteOrder.LITTLE_ENDIAN)
            out.putInt(totalLength)
            out.putInt(totalLength)
            out.putShort(clientSendToServer.toShort())
            out.put(0)
            out.put(0)
            out.put(buffer)
            out.put(0)
            out.array()
        } catch (e: Exception) {
            CoreLog.error(e)
            ByteArray(0)
        }
    }

    /**
     * 反序列化接收帧（对应 Dart deserializeDouyu）：读取包长与负载，返回 body 文本。
     * 包长异常（0/负/超界）视为无效帧返回 null。
     */
    private fun deserializeDouyu(data: ByteArray): String? {
        return try {
            val reader = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val fullMsgLength = reader.int // fullMsgLength
            reader.int // fullMsgLength2（丢弃）
            val bodyLength = fullMsgLength - 9
            reader.short // packType（丢弃）
            reader.get() // encrypted（丢弃）
            reader.get() // reserved（丢弃）
            if (bodyLength < 0 || bodyLength > reader.remaining()) {
                return null
            }
            val bytes = ByteArray(bodyLength)
            reader.get(bytes)
            reader.get() // 固定为 0
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            CoreLog.error(e)
            null
        }
    }

    /**
     * 辣鸡 STT 文本协议解析（对应 Dart sttToJObject）：
     * `//` 分隔数组、`@=` 键值对象、`@A=` 转义、`@S`→`/`、`@A`→`@`。
     */
    private fun sttToJObject(str: String): JsonElement {
        if (str.contains("//")) {
            val result = mutableListOf<JsonElement>()
            for (field in str.split("//")) {
                if (field.isEmpty()) continue
                result.add(sttToJObject(field))
            }
            return JsonArray(result)
        }
        if (str.contains("@=")) {
            val obj = LinkedHashMap<String, JsonElement>()
            for (field in str.split('/')) {
                if (field.isEmpty()) continue
                val tokens = field.split("@=")
                if (tokens.size < 2) continue
                val k = tokens[0]
                val v = unscapeSlashAt(tokens[1])
                obj[k] = sttToJObject(v)
            }
            return JsonObject(obj)
        } else if (str.contains("@A=")) {
            return sttToJObject(unscapeSlashAt(str))
        } else {
            return JsonPrimitive(unscapeSlashAt(str))
        }
    }

    /** 反转义（对应 Dart unscapeSlashAt）。 */
    private fun unscapeSlashAt(str: String): String =
        str.replace("@S", "/").replace("@A", "@")

    /** 弹幕颜色（对应 Dart getColor）。 */
    private fun getColor(type: Int): LiveMessageColor = when (type) {
        1 -> LiveMessageColor(255, 0, 0)
        2 -> LiveMessageColor(30, 135, 240)
        3 -> LiveMessageColor(122, 200, 75)
        4 -> LiveMessageColor(255, 127, 0)
        5 -> LiveMessageColor(155, 57, 244)
        6 -> LiveMessageColor(255, 105, 180)
        else -> LiveMessageColor.white
    }
}
