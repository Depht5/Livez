package dev.livefork.core.model.tars

/**
 * 虎牙用户标识结构。
 * 对应 Dart `HuyaUserId`（lib/src/model/tars/huya_user_id.dart）。
 * 字段标签号与 Dart 完全一致：lUid=0、sGuid=1、sToken=2、sHuYaUA=3、
 * sCookie=4、iTokenType=5、sDeviceInfo=6、sQIMEI=7。
 */
data class HuyaUserId(
    /** tag 0 */
    var lUid: Long = 0L,
    /** tag 1 */
    var sGuid: String = "",
    /** tag 2 */
    var sToken: String = "",
    /** tag 3 */
    var sHuYaUA: String = "",
    /** tag 4 */
    var sCookie: String = "",
    /** tag 5 */
    var iTokenType: Int = 0,
    /** tag 6 */
    var sDeviceInfo: String = "",
    /** tag 7 */
    var sQIMEI: String = "",
) : TarsStruct() {

    override fun writeTo(os: TarsOutputStream) {
        os.writeLong(lUid, 0)
        os.writeString(sGuid, 1)
        os.writeString(sToken, 2)
        os.writeString(sHuYaUA, 3)
        os.writeString(sCookie, 4)
        os.writeInt(iTokenType, 5)
        os.writeString(sDeviceInfo, 6)
        os.writeString(sQIMEI, 7)
    }

    override fun readFrom(ins: TarsInputStream) {
        lUid = ins.readLong(0, false)
        sGuid = ins.readString(1, false)
        sToken = ins.readString(2, false)
        sHuYaUA = ins.readString(3, false)
        sCookie = ins.readString(4, false)
        iTokenType = ins.readInt(5, false)
        sDeviceInfo = ins.readString(6, false)
        sQIMEI = ins.readString(7, false)
    }

    override fun toString(): String {
        return "HuyaUserId(lUid=$lUid, sGuid='$sGuid', sToken='$sToken', sHuYaUA='$sHuYaUA', sCookie='$sCookie', iTokenType=$iTokenType, sDeviceInfo='$sDeviceInfo', sQIMEI='$sQIMEI')"
    }
}