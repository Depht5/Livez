package com.kotlin_livez.core.danmaku

import com.kotlin_livez.core.common.CoreLog
import com.kotlin_livez.core.common.WebSocketUtil
import com.kotlin_livez.core.model.LiveMessage
import com.kotlin_livez.core.model.LiveMessageColor
import com.kotlin_livez.core.model.LiveMessageType
import com.kotlin_livez.core.model.tars.HYMsgItem
import com.kotlin_livez.core.model.tars.HYMessage
import com.kotlin_livez.core.model.tars.HYPushMessage
import com.kotlin_livez.core.model.tars.HYPushMessageV2
import com.kotlin_livez.core.model.tars.TarsInputStream
import com.kotlin_livez.core.model.tars.TarsOutputStream

/**
 * 虎牙弹幕连接参数。
 * 对应 Dart `HuyaDanmakuArgs`（lib/src/danmaku/huya_danmaku.dart）。
 */
data class HuyaDanmakuArgs(
    /** 房间 ayyuid */
    val ayyuid: Long,
    /** 顶级房间 ID */
    val topSid: Long,
    /** 子房间 ID */
    val subSid: Long,
) {
    override fun toString(): String {
        return """{"ayyuid":$ayyuid,"topSid":$topSid,"subSid":$subSid}"""
    }
}

/**
 * 虎牙弹幕实现。
 * 对应 Dart `HuyaDanmaku`：WebSocket 二进制帧为 Tars 编码，
 * 进房包 {tag0:int 命令, tag1:bytes 参数}，推送帧 {tag0:int 类型, tag1:bytes 载荷}。
 */
class HuyaDanmaku : LiveDanmaku() {

    override var heartbeatTime: Int = 60 * 1000

    /** 弹幕服务器地址（对应 Dart serverUrl）。 */
    val serverUrl: String = "wss://cdnws.api.huya.com"

    private var webSocketUtil: WebSocketUtil? = null

    // 每次连接仅记录一次的首帧/首推日志标志（start 时重置）
    private var firstPushLogged = false
    private var first1400Logged = false
    private var emptyChatDroppedLogged = false
    private val loggedCommandTypes = mutableSetOf<Long>()
    private var decodeErrorWindowStartedAt = 0L
    private var decodeErrorCount = 0
    private lateinit var danmakuArgs: HuyaDanmakuArgs

    /** 心跳数据：Dart 端 base64.decode("ABQdAAwsNgBM") 的预编码字节，原样发送。 */
    private val heartbeatData: ByteArray = byteArrayOf(
        0x00, 0x14, 0x1D, 0x00, 0x0C, 0x2C, 0x36, 0x00, 0x4C,
    )

    override suspend fun start(arg: Any?) {
        val rawArgs = arg as? HuyaDanmakuArgs ?: reportInvalidArgs("missing HuyaDanmakuArgs")
        val tid = if (rawArgs.topSid > 0L) rawArgs.topSid else rawArgs.subSid
        val sid = if (rawArgs.subSid > 0L) rawArgs.subSid else tid
        if (rawArgs.ayyuid <= 0L || tid <= 0L || sid <= 0L) {
            reportInvalidArgs(rawArgs.toString())
        }
        danmakuArgs = rawArgs.copy(topSid = tid, subSid = sid)
        CoreLog.d("HuyaDanmaku start: $danmakuArgs")
        firstPushLogged = false
        first1400Logged = false
        emptyChatDroppedLogged = false
        loggedCommandTypes.clear()
        decodeErrorWindowStartedAt = 0L
        decodeErrorCount = 0
        webSocketUtil?.close()
        webSocketUtil = WebSocketUtil(
            url = serverUrl,
            heartBeatTime = heartbeatTime.toLong(),
            // type 2 注册响应和 type 21 心跳 ACK 即使进房参数错误也会返回，不能视为
            // 弹幕订阅健康。只有 type 7/22 业务推送才能解除 watchdog/重置重连计数。
            autoMarkHealthyOnMessage = false,
            // 现场复现中成功订阅会在 3s 内收到业务帧，失败订阅则始终只有 type 2。
            // 尽快替换这种“传输已开、房间业务未订阅”的僵尸连接。
            firstMessageTimeoutMs = 20_000,
            messageIdleTimeoutMs = 150_000,
            // 虎牙使用自己的 Tars 心跳且不回复标准 WebSocket pong。OkHttp ping 会把每条
            // 健康连接在约 60s 后误杀，反复 TLS/进房并随机落入无业务订阅。
            pingIntervalMs = 0,
            // 设备采样确认坏会话 70%+ CPU 卡在 OkHttp MessageInflater.inflate；禁用虎牙
            // permessage-deflate 后由服务端发送原始 Tars 二进制帧，避免解压自旋无法退出。
            webSocketCompressionEnabled = false,
            onByteMessage = { decodeMessage(it) },
            onReady = {
                if (joinRoom()) {
                    onReady?.invoke()
                } else {
                    webSocketUtil?.forceReconnect("Huya join packet send failed")
                }
            },
            onHeartBeat = { heartbeat() },
            onReconnect = { onClose?.invoke("与服务器断开连接，正在尝试重连") },
            onClose = { onClose?.invoke("服务器连接失败$it") },
        )
        webSocketUtil?.connect()
    }

    private fun reportInvalidArgs(detail: String): Nothing {
        CoreLog.w("Huya 弹幕参数无效，拒绝发送空注册包: $detail")
        onClose?.invoke("虎牙弹幕参数无效，请刷新直播间")
        throw IllegalArgumentException("Invalid Huya danmaku args: $detail")
    }

    /** 进房：tag4 使用顶级频道 tid，tag5 使用实际子频道 sid。 */
    private fun joinRoom(): Boolean {
        val joinData = buildJoinData(danmakuArgs)
        CoreLog.d("Huya joinRoom: ayyuid=${danmakuArgs.ayyuid} topSid=${danmakuArgs.topSid} subSid=${danmakuArgs.subSid} bytes=${joinData.size}")
        return joinData.isNotEmpty() && webSocketUtil?.sendBytes(joinData) == true
    }

    /** 暴露给同模块测试，确保超过 Int 上限的 ID 仍按 int64 原值编码。 */
    internal fun buildJoinData(args: HuyaDanmakuArgs): ByteArray =
        getJoinData(args.ayyuid, args.topSid, args.subSid)

    /**
     * 构造进房数据（对应 Dart getJoinData）。
     * 参数结构：{ayyuid=0, true=1, ""=2, ""=3, tid=4, sid=5, 0=6, 0=7}；
     * 外层：{1=0, 参数bytes=1}。
     */
    private fun getJoinData(ayyuid: Long, tid: Long, sid: Long): ByteArray {
        return try {
            val oos = TarsOutputStream()
            oos.writeLong(ayyuid, 0)
            oos.writeBool(true, 1)
            oos.writeString("", 2)
            oos.writeString("", 3)
            oos.writeLong(tid, 4)
            oos.writeLong(sid, 5)
            oos.writeLong(0L, 6)
            oos.writeLong(0L, 7)

            val wscmd = TarsOutputStream()
            wscmd.writeLong(1L, 0)
            wscmd.writeBytes(oos.toByteArray(), 1)
            wscmd.toByteArray()
        } catch (e: Exception) {
            CoreLog.error(e)
            ByteArray(0)
        }
    }

    override fun heartbeat() {
        val socket = webSocketUtil ?: return
        if (!socket.sendBytes(heartbeatData)) {
            socket.forceReconnect("Huya heartbeat send failed")
        }
    }

    override fun stop() {
        onMessage = null
        onClose = null
        onReady = null
        webSocketUtil?.close()
        webSocketUtil = null
    }

    /**
     * 解码弹幕消息（对应 Dart decodeMessage）。
     * 外层：{type=0, payload=1}；type==7 时 payload 为 HYPushMessage（Tars 结构）。
     * 其中 uri==1400 为聊天弹幕，uri==8006 为在线人数。
     */
    internal fun decodeMessage(data: ByteArray) {
        if (data.size > 4 * 1024 * 1024) {
            logDecodeError(
                "Huya 弹幕帧超过 4MiB 应用上限: bytes=${data.size}",
                IllegalArgumentException("oversized Huya WebSocket frame"),
            )
            return
        }
        try {
            if (!firstPushLogged) {
                firstPushLogged = true
                CoreLog.d("Huya 首条下行 Tars 帧到达")
            }
            var stream = TarsInputStream(data)
            val type = stream.readLong(0, false)
            when (type) {
                7L -> {
                    stream = TarsInputStream(stream.readBytes(1, true))
                    val push = HYPushMessage().apply { readFrom(stream) }
                    // 容器完整即证明房间业务订阅在推流；URI 是否为客户端已支持类型
                    // 只决定是否产出 UI 消息，不能将合法的未知 URI 误判为僵尸连接。
                    webSocketUtil?.markMessageHealthy()
                    decodePushItem(push.uri, push.msg)
                }

                22L -> {
                    stream = TarsInputStream(stream.readBytes(1, true))
                    val push = HYPushMessageV2().apply { readFrom(stream) }
                    webSocketUtil?.markMessageHealthy()
                    for (item: HYMsgItem in push.msgItems) {
                        try {
                            decodePushItem(item.uri, item.msg)
                        } catch (e: Exception) {
                            // type 22 是批量帧：单条损坏不应丢掉整批其余弹幕。
                            logDecodeError(
                                "Huya 批量推送子项解析失败: uri=${item.uri} bytes=${item.msg.size}",
                                e,
                            )
                        }
                    }
                }

                else -> {
                    if (loggedCommandTypes.size < 16 && loggedCommandTypes.add(type)) {
                        CoreLog.d("Huya 控制帧 commandType=$type")
                    }
                }
            }
        } catch (e: Exception) {
            // 解析失败必须返回 OkHttp reader 线程；TarsInputStream 自身也保证游标单调前进，
            // 避免畸形帧形成无法被 close() 中断的永久忙循环。
            logDecodeError("Huya 弹幕帧解析失败: bytes=${data.size}", e)
        }
    }

    /** 坏 schema 若持续到达，限制堆栈格式化/logcat I/O，避免日志本身变成功耗源。 */
    private fun logDecodeError(message: String, error: Exception) {
        val now = System.currentTimeMillis()
        if (decodeErrorWindowStartedAt == 0L || now - decodeErrorWindowStartedAt >= 60_000L) {
            if (decodeErrorCount > 1) {
                CoreLog.w("Huya 上一解析错误窗口已抑制 ${decodeErrorCount - 1} 条重复日志")
            }
            decodeErrorWindowStartedAt = now
            decodeErrorCount = 1
            CoreLog.e(message, error)
        } else {
            decodeErrorCount++
            if (decodeErrorCount == 2) {
                CoreLog.w("Huya 持续解析错误，同一 60s 窗口的后续堆栈已抑制")
            }
        }
    }

    private fun decodePushItem(uri: Long, payload: ByteArray) {
        when (uri) {
            1400L -> {
                if (!first1400Logged) {
                    first1400Logged = true
                    CoreLog.d("Huya 首条聊天推送 uri=1400")
                }
                val messageNotice = HYMessage().apply {
                    readFrom(TarsInputStream(payload))
                }
                if (!hasVisibleText(messageNotice.content)) {
                    if (!emptyChatDroppedLogged) {
                        emptyChatDroppedLogged = true
                        CoreLog.w("Huya 已丢弃空白/零宽聊天推送")
                    }
                    return
                }
                val color = messageNotice.bulletFormat.fontColor
                onMessage?.invoke(
                    LiveMessage(
                        type = LiveMessageType.chat,
                        color = if (color <= 0) {
                            LiveMessageColor.white
                        } else {
                            LiveMessageColor.numberToColor(color)
                        },
                        message = messageNotice.content,
                        userName = messageNotice.userInfo.nickName,
                    ),
                )
            }

            8006L -> {
                val online = TarsInputStream(payload).readLong(0, false)
                onMessage?.invoke(
                    LiveMessage(
                        type = LiveMessageType.online,
                        data = online,
                        color = LiveMessageColor.white,
                        message = "",
                        userName = "",
                    ),
                )
            }

            // 6111/6892 等其他合法房间事件暂无 UI 模型，忽略内容即可。
            else -> Unit
        }
    }

    private fun hasVisibleText(text: String): Boolean = text.any { char ->
        !char.isWhitespace() &&
            char != '\u200B' && char != '\u200C' && char != '\u200D' && char != '\u2060' && char != '\uFEFF'
    }
}
