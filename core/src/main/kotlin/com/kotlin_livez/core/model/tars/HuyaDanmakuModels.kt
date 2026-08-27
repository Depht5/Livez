package com.kotlin_livez.core.model.tars

/**
 * 虎牙弹幕 WebSocket 推送消息模型。
 * 对应 Dart `model/tars/huya_danmaku.dart` 中的 HYPushMessage / HYSender / HYMessage / HYBulletFormat。
 * 字段标签号与 Dart 完全一致；其中 HYSender.lMid 在 Dart 中同样使用 tag 0（历史行为，保留）。
 */
class HYPushMessage : TarsStruct() {
    /** tag 0 */
    var pushType: Int = 0

    /** tag 1：消息类型标识（1400=聊天弹幕，8006=在线人数） */
    var uri: Int = 0

    /** tag 2：内嵌消息体（Tars 编码） */
    var msg: ByteArray = ByteArray(0)

    /** tag 3 */
    var protocolType: Int = 0

    override fun writeTo(os: TarsOutputStream) {
        // 只读结构，对应 Dart 空实现
    }

    override fun readFrom(ins: TarsInputStream) {
        pushType = ins.readInt(0, false)
        uri = ins.readInt(1, false)
        msg = ins.readBytes(2, false)
        protocolType = ins.readInt(3, false)
    }
}

/**
 * 弹幕发送者信息。
 * 对应 Dart `HYSender`：uid=0、lMid=0（Dart 源同样为 tag 0，保留）、nickName=2、gender=3。
 */
class HYSender : TarsStruct() {
    /** tag 0 */
    var uid: Long = 0L

    /** tag 0（与 Dart 一致：lMid 也读 tag 0） */
    var lMid: Long = 0L

    /** tag 2 */
    var nickName: String = ""

    /** tag 3 */
    var gender: Int = 0

    override fun writeTo(os: TarsOutputStream) {
        // 只读结构，对应 Dart 空实现
    }

    override fun readFrom(ins: TarsInputStream) {
        uid = ins.readLong(0, false)
        lMid = ins.readLong(0, false)
        nickName = ins.readString(2, false)
        gender = ins.readInt(3, false)
    }
}

/**
 * 聊天弹幕消息（uri=1400 时解析）。
 * 对应 Dart `HYMessage`：userInfo=0、content=3、bulletFormat=6。
 */
class HYMessage : TarsStruct() {
    /** tag 0 */
    var userInfo: HYSender = HYSender()

    /** tag 3 */
    var content: String = ""

    /** tag 6 */
    var bulletFormat: HYBulletFormat = HYBulletFormat()

    override fun writeTo(os: TarsOutputStream) {
        // 只读结构，对应 Dart 空实现
    }

    override fun readFrom(ins: TarsInputStream) {
        userInfo = ins.readTarsStruct(HYSender(), 0, false) as HYSender
        content = ins.readString(3, false)
        bulletFormat = ins.readTarsStruct(HYBulletFormat(), 6, false) as HYBulletFormat
    }
}

/**
 * 弹幕显示格式。
 * 对应 Dart `HYBulletFormat`：fontColor=0、fontSize=1、textSpeed=2、transitionType=3。
 */
class HYBulletFormat : TarsStruct() {
    /** tag 0：字体颜色 */
    var fontColor: Int = 0

    /** tag 1 */
    var fontSize: Int = 4

    /** tag 2 */
    var textSpeed: Int = 0

    /** tag 3 */
    var transitionType: Int = 1

    override fun writeTo(os: TarsOutputStream) {
        // 只读结构，对应 Dart 空实现
    }

    override fun readFrom(ins: TarsInputStream) {
        fontColor = ins.readInt(0, false)
        fontSize = ins.readInt(1, false)
        textSpeed = ins.readInt(2, false)
        transitionType = ins.readInt(3, false)
    }
}