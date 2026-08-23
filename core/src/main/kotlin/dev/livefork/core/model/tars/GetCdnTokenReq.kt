package dev.livefork.core.model.tars

/**
 * getCdnToken 请求结构（旧版，站点当前未使用，保留移植）。
 * 对应 Dart `GetCdnTokenReq`（lib/src/model/tars/get_cdn_token_req.dart）。
 * 字段标签号与 Dart 完全一致：url=0、cdnType=1、streamName=2、presenterUid=3。
 */
data class GetCdnTokenReq(
    /** tag 0 */
    var url: String = "",
    /** tag 1 */
    var cdnType: String = "",
    /** tag 2 */
    var streamName: String = "",
    /** tag 3 */
    var presenterUid: Long = 0L,
) : TarsStruct() {

    override fun writeTo(os: TarsOutputStream) {
        os.writeString(url, 0)
        os.writeString(cdnType, 1)
        os.writeString(streamName, 2)
        os.writeLong(presenterUid, 3)
    }

    override fun readFrom(ins: TarsInputStream) {
        url = ins.readString(0, false)
        cdnType = ins.readString(1, false)
        streamName = ins.readString(2, false)
        presenterUid = ins.readLong(3, false)
    }
}