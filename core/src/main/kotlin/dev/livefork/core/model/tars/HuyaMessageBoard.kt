package dev.livefork.core.model.tars

/**
 * 虎牙留言板（超长留言/收益榜）Tars 模型。
 * 对应 Dart `model/tars/huya_message_board.dart`。
 * 字段标签号与 Dart 完全一致。
 */
class GetGameEventMessageBoardReq : TarsStruct() {
    /** tag 0：房间 pid */
    var lPid: Long = 0L

    /** tag 1 */
    var sOffset: String = ""

    /** tag 2 */
    var tId: HuyaUserId = HuyaUserId()

    /** tag 3 */
    var iMessageBoardScope: Int = 0

    /** tag 4 */
    var iPageSize: Int = 10

    override fun writeTo(os: TarsOutputStream) {
        os.writeLong(lPid, 0)
        os.writeString(sOffset, 1)
        os.writeTarsStruct(tId, 2)
        os.writeInt(iMessageBoardScope, 3)
        os.writeInt(iPageSize, 4)
    }

    override fun readFrom(ins: TarsInputStream) {
        lPid = ins.readLong(0, false)
        sOffset = ins.readString(1, false)
        tId = ins.readTarsStruct(HuyaUserId(), 2, false) as HuyaUserId
        iMessageBoardScope = ins.readInt(3, false)
        iPageSize = ins.readInt(4, false)
    }
}

class GetGameEventMessageBoardRsp : TarsStruct() {
    /** tag 1 */
    var tMessageBoardPanel: GameEventMessageBoardPanel = GameEventMessageBoardPanel()

    override fun writeTo(os: TarsOutputStream) {
        os.writeTarsStruct(tMessageBoardPanel, 1)
    }

    override fun readFrom(ins: TarsInputStream) {
        tMessageBoardPanel = ins.readTarsStruct(GameEventMessageBoardPanel(), 1, false) as GameEventMessageBoardPanel
    }
}

class GameEventMessageBoardPanel : TarsStruct() {
    /** tag 1 */
    var vGameEventMessageBoardInfo: List<GameEventMessageBoardInfo> = emptyList()

    override fun writeTo(os: TarsOutputStream) {
        os.writeList(vGameEventMessageBoardInfo, 1)
    }

    override fun readFrom(ins: TarsInputStream) {
        vGameEventMessageBoardInfo = ins.readStructList(1, false) { GameEventMessageBoardInfo() }
    }
}

class GameEventMessageBoardInfo : TarsStruct() {
    /** tag 0 */
    var tMessageUser: MessageUser = MessageUser()

    /** tag 1 */
    var sContent: String = ""

    /** tag 2 */
    var iCost: Long = 0L

    /** tag 4 */
    var iTotalSec: Long = 0L

    /** tag 5 */
    var iCountDown: Long = 0L

    /** tag 9 */
    var lMessageId: Long = 0L

    /** tag 12 */
    var iCostPay: Long = 0L

    override fun writeTo(os: TarsOutputStream) {
        os.writeTarsStruct(tMessageUser, 0)
        os.writeString(sContent, 1)
        os.writeLong(iCost, 2)
        os.writeLong(iTotalSec, 4)
        os.writeLong(iCountDown, 5)
        os.writeLong(lMessageId, 9)
        os.writeLong(iCostPay, 12)
    }

    override fun readFrom(ins: TarsInputStream) {
        tMessageUser = ins.readTarsStruct(MessageUser(), 0, false) as MessageUser
        sContent = ins.readString(1, false)
        iCost = ins.readLong(2, false)
        iTotalSec = ins.readLong(4, false)
        iCountDown = ins.readLong(5, false)
        lMessageId = ins.readLong(9, false)
        iCostPay = ins.readLong(12, false)
    }
}

class MessageUser : TarsStruct() {
    /** tag 1 */
    var sNick: String = ""

    /** tag 2 */
    var sAvatar: String = ""

    override fun writeTo(os: TarsOutputStream) {
        os.writeString(sNick, 1)
        os.writeString(sAvatar, 2)
    }

    override fun readFrom(ins: TarsInputStream) {
        sNick = ins.readString(1, false)
        sAvatar = ins.readString(2, false)
    }
}