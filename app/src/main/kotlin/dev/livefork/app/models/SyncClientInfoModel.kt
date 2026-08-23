package dev.livefork.app.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 同步客户端信息 —— 对应 Dart `SyncClientInfoModel`
 * （lib/models/sync_client_info_model.dart）。
 */
@Serializable
data class SyncClientInfoModel(
    val id: String = "",
    val type: String = "",
    val name: String = "",
    val version: String = "",
    val address: String = "",
    val port: Int = 0,
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("version", version)
        put("address", address)
        put("port", port)
        put("type", type)
    }

    companion object {
        /** 对应 Dart fromJson（asT 语义：字段缺失/类型不符回退默认值）。 */
        fun fromJson(json: JsonElement?): SyncClientInfoModel {
            val obj = json as? JsonObject ?: return SyncClientInfoModel()
            fun str(key: String): String =
                (obj[key] as? JsonPrimitive)?.content?.takeIf { it != "null" } ?: ""
            fun int(key: String): Int =
                str(key).toIntOrNull() ?: 0
            return SyncClientInfoModel(
                id = str("id"),
                type = str("type"),
                name = str("name"),
                version = str("version"),
                address = str("address"),
                port = int("port"),
            )
        }
    }
}