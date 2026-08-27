package com.kotlin_livez.core.model.tars

/**
 * getCdnTokenInfoEx 响应结构。
 * 对应 Dart `GetCdnTokenExResp`（lib/src/model/tars/get_cdn_token_ex_resp.dart）。
 * 字段标签号与 Dart 完全一致：sFlvToken=0、iExpireTime=1。
 */
data class GetCdnTokenExResp(
    /** tag 0 */
    var sFlvToken: String = "",
    /** tag 1 */
    var iExpireTime: Int = 0,
) : TarsStruct() {

    override fun writeTo(os: TarsOutputStream) {
        os.writeString(sFlvToken, 0)
        os.writeInt(iExpireTime, 1)
    }

    override fun readFrom(ins: TarsInputStream) {
        sFlvToken = ins.readString(0, false)
        iExpireTime = ins.readInt(1, false)
    }
}