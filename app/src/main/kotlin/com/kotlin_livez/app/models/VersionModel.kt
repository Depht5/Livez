package com.kotlin_livez.app.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 版本信息 —— 对应 Dart `VersionModel`（lib/models/version_model.dart）。
 */
@Serializable
data class VersionModel(
    val version: String = "",
    val versionNum: Int = 0,
    val versionDesc: String = "",
    val downloadUrl: String = "",
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("version", version)
        put("version_num", versionNum)
        put("version_desc", versionDesc)
        put("download_url", downloadUrl)
    }

    companion object {
        /** 对应 Dart fromJson（asT 语义）。 */
        fun fromJson(json: JsonElement?): VersionModel {
            val obj = json as? JsonObject ?: return VersionModel()
            fun str(key: String): String =
                (obj[key] as? JsonPrimitive)?.content?.takeIf { it != "null" } ?: ""
            fun int(key: String): Int =
                str(key).toIntOrNull() ?: 0
            return VersionModel(
                version = str("version"),
                versionNum = int("version_num"),
                versionDesc = str("version_desc"),
                downloadUrl = str("download_url"),
            )
        }
    }
}