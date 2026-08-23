package dev.livefork.app.models.account

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * B 站用户信息 —— 对应 Dart `BiliBiliUserInfoModel`
 * （lib/models/account/bilibili_user_info_page.dart）。
 */
@Serializable
data class BiliBiliUserInfoModel(
    val mid: Long? = null,
    val uname: String? = null,
    val userid: String? = null,
    val sign: String? = null,
    val birthday: String? = null,
    val sex: String? = null,
    val nickFree: Boolean? = null,
    val rank: String? = null,
) {
    companion object {
        /** 对应 Dart fromJson：asT 宽容取值（类型不匹配回退 null）。 */
        fun fromJson(json: JsonElement?): BiliBiliUserInfoModel {
            val obj = json as? JsonObject ?: return BiliBiliUserInfoModel()
            fun str(key: String): String? =
                (obj[key] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.contentOrNull
                    ?.takeIf { it != "null" }
            fun long(key: String): Long? =
                str(key)?.toLongOrNull()
            fun bool(key: String): Boolean? =
                str(key)?.toBooleanStrictOrNull()
            return BiliBiliUserInfoModel(
                mid = long("mid"),
                uname = str("uname"),
                userid = str("userid"),
                sign = str("sign"),
                birthday = str("birthday"),
                sex = str("sex"),
                nickFree = bool("nick_free"),
                rank = str("rank"),
            )
        }
    }
}