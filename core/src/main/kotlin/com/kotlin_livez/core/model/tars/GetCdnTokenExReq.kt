package com.kotlin_livez.core.model.tars

/**
 * getCdnTokenInfoEx 请求结构。
 * 对应 Dart `GetCdnTokenExReq`（lib/src/model/tars/get_cdn_token_ex_req.dart）。
 * 字段标签号与 Dart 完全一致：sFlvUrl=0、sStreamName=1、iLoopTime=2、tId=3、iAppId=4。
 * 站点实际使用时会设置 `tId.sHuYaUA` 与 `sStreamName`。
 */
data class GetCdnTokenExReq(
    /** tag 0 */
    var sFlvUrl: String = "",
    /** tag 1 */
    var sStreamName: String = "",
    /** tag 2 */
    var iLoopTime: Int = 0,
    /** tag 3 */
    var tId: HuyaUserId = HuyaUserId(),
    /** tag 4 */
    var iAppId: Int = 66,
) : TarsStruct() {

    override fun writeTo(os: TarsOutputStream) {
        os.writeString(sFlvUrl, 0)
        os.writeString(sStreamName, 1)
        os.writeInt(iLoopTime, 2)
        os.writeTarsStruct(tId, 3)
        os.writeInt(iAppId, 4)
    }

    override fun readFrom(ins: TarsInputStream) {
        sFlvUrl = ins.readString(0, false)
        sStreamName = ins.readString(1, false)
        iLoopTime = ins.readInt(2, false)
        tId = ins.readTarsStruct(HuyaUserId(), 3, false) as HuyaUserId
        iAppId = ins.readInt(4, false)
    }
}