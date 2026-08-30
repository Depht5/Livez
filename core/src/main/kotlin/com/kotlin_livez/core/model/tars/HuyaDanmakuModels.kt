package com.kotlin_livez.core.model.tars

/** 单个虎牙 type22 批次的应用层上限，防止畸形帧无界排队 UI 回调。 */
private const val HUYA_MAX_BATCH_ITEMS = 2_048

/**
 * 虎牙弹幕 WebSocket 推送消息模型。
 * 对应 Dart `model/tars/huya_danmaku.dart` 中的 HYPushMessage / HYSender / HYMessage / HYBulletFormat。
 * 字段标签以虎牙 Tars schema 为准；HYSender.lMid 修正为 tag 1。
 */
class HYPushMessage : TarsStruct() {
    /** tag 0 */
    var pushType: Int = 0

    /** tag 1：消息类型标识（1400=聊天弹幕，8006=在线人数） */
    var uri: Long = 0L

    /** tag 2：内嵌消息体（Tars 编码） */
    var msg: ByteArray = ByteArray(0)

    /** tag 3 */
    var protocolType: Int = 0

    override fun writeTo(os: TarsOutputStream) {
        os.writeInt(pushType, 0)
        os.writeLong(uri, 1)
        os.writeBytes(msg, 2)
        os.writeInt(protocolType, 3)
    }

    override fun readFrom(ins: TarsInputStream) {
        pushType = ins.readInt(0, false)
        uri = ins.readLong(1, false)
        msg = ins.readBytes(2, false)
        protocolType = ins.readInt(3, false)
    }
}

/**
 * 虎牙批量推送（WebSocket command type 22）。
 *
 * 新旧边缘节点可能分别发送 type 7 和 type 22；忽略 type 22 会表现为连接成功、
 * 心跳正常但整批弹幕被静默丢弃。
 */
class HYPushMessageV2 : TarsStruct() {
    /** tag 0 */
    var groupId: String = ""

    /** tag 1 */
    var msgItems: List<HYMsgItem> = emptyList()

    override fun writeTo(os: TarsOutputStream) {
        os.writeString(groupId, 0)
        os.writeList(msgItems, 1)
    }

    override fun readFrom(ins: TarsInputStream) {
        groupId = ins.readString(0, false)
        msgItems = ins.readStructList(1, false, maxSize = HUYA_MAX_BATCH_ITEMS) { HYMsgItem() }
    }
}

/** type 22 批量推送中的单条消息。 */
class HYMsgItem : TarsStruct() {
    /** tag 0 */
    var uri: Long = 0L

    /** tag 1 */
    var msg: ByteArray = ByteArray(0)

    /** tag 2 */
    var msgId: Long = 0L

    override fun writeTo(os: TarsOutputStream) {
        os.writeLong(uri, 0)
        os.writeBytes(msg, 1)
        os.writeLong(msgId, 2)
    }

    override fun readFrom(ins: TarsInputStream) {
        uri = ins.readLong(0, false)
        msg = ins.readBytes(1, false)
        msgId = ins.readLong(2, false)
    }
}

/**
 * 弹幕发送者信息。
 * 虎牙 Tars schema：uid=0、lMid=1、nickName=2、gender=3。
 */
class HYSender : TarsStruct() {
    /** tag 0 */
    var uid: Long = 0L

    /** tag 1 */
    var lMid: Long = 0L

    /** tag 2 */
    var nickName: String = ""

    /** tag 3 */
    var gender: Int = 0

    override fun writeTo(os: TarsOutputStream) {
        os.writeLong(uid, 0)
        os.writeLong(lMid, 1)
        os.writeString(nickName, 2)
        os.writeInt(gender, 3)
    }

    override fun readFrom(ins: TarsInputStream) {
        uid = ins.readLong(0, false)
        lMid = ins.readLong(1, false)
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
        os.writeTarsStruct(userInfo, 0)
        os.writeString(content, 3)
        os.writeTarsStruct(bulletFormat, 6)
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
        os.writeInt(fontColor, 0)
        os.writeInt(fontSize, 1)
        os.writeInt(textSpeed, 2)
        os.writeInt(transitionType, 3)
    }

    override fun readFrom(ins: TarsInputStream) {
        fontColor = ins.readInt(0, false)
        fontSize = ins.readInt(1, false)
        textSpeed = ins.readInt(2, false)
        transitionType = ins.readInt(3, false)
    }
}
