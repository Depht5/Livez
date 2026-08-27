package com.kotlin_livez.core.danmaku

import com.kotlin_livez.core.model.LiveMessage

/**
 * 弹幕连接契约。
 *
 * 对应 Dart 的 `LiveDanmaku`（`lib/src/interface/live_danmaku.dart`）。
 * 回调契约：onMessage / onClose / onReady，对应 Dart 的同名字段。
 */
open class LiveDanmaku {

    /** 收到消息回调 */
    var onMessage: ((LiveMessage) -> Unit)? = null

    /** 关闭回调（携带关闭原因） */
    var onClose: ((String) -> Unit)? = null

    /** 就绪回调 */
    var onReady: (() -> Unit)? = null

    /** 心跳时间（毫秒） */
    open var heartbeatTime: Int = 0

    /** 发生心跳 */
    open fun heartbeat() {}

    /** 开始接收信息 */
    open suspend fun start(args: Any? = null) {}

    /** 停止接收信息 */
    open suspend fun stop() {}
}
