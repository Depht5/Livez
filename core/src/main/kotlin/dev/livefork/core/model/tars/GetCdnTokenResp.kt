package dev.livefork.core.model.tars

/**
 * getCdnToken 响应结构（旧版，站点当前未使用，保留移植）。
 * 对应 Dart `GetCdnTokenResp`（lib/src/model/tars/get_cdn_token_resp.dart）。
 * 字段标签号与 Dart 完全一致：url=0、cdnType=1、streamName=2、presenterUid=3、
 * antiCode=4、sTime=5、flvAntiCode=6、hlsAntiCode=7。
 */
data class GetCdnTokenResp(
    /** tag 0 */
    var url: String = "",
    /** tag 1 */
    var cdnType: String = "",
    /** tag 2 */
    var streamName: String = "",
    /** tag 3 */
    var presenterUid: Long = 0L,
    /** tag 4 */
    var antiCode: String = "",
    /** tag 5 */
    var sTime: String = "",
    /** tag 6 */
    var flvAntiCode: String = "",
    /** tag 7 */
    var hlsAntiCode: String = "",
) : TarsStruct() {

    override fun writeTo(os: TarsOutputStream) {
        os.writeString(url, 0)
        os.writeString(cdnType, 1)
        os.writeString(streamName, 2)
        os.writeLong(presenterUid, 3)
        os.writeString(antiCode, 4)
        os.writeString(sTime, 5)
        os.writeString(flvAntiCode, 6)
        os.writeString(hlsAntiCode, 7)
    }

    override fun readFrom(ins: TarsInputStream) {
        url = ins.readString(0, false)
        cdnType = ins.readString(1, false)
        streamName = ins.readString(2, false)
        presenterUid = ins.readLong(3, false)
        antiCode = ins.readString(4, false)
        sTime = ins.readString(5, false)
        flvAntiCode = ins.readString(6, false)
        hlsAntiCode = ins.readString(7, false)
    }
}