package com.kotlin_livez.app.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 弹幕屏蔽预设 —— 对应 Dart `DanmuShieldPreset`
 * （lib/models/danmu_shield_preset.dart）。
 */
@Serializable
data class DanmuShieldPreset(
    val name: String,
    val keywords: List<String>,
    val users: List<String>,
    val userGroups: Map<String, List<String>> = emptyMap(),
) {
    /** 对应 Dart toJson：不含 name（name 是 preset 的键）。 */
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("keywords", JsonArray(keywords.map { JsonPrimitive(it) }))
        put("users", JsonArray(users.map { JsonPrimitive(it) }))
        put(
            "userGroups",
            buildJsonObject {
                userGroups.forEach { (key, list) ->
                    put(key, JsonArray(list.map { JsonPrimitive(it) }))
                }
            },
        )
    }

    companion object {
        /** 对应 Dart 反序列化（宽容：缺失回退空）。 */
        fun fromJson(json: JsonElement?): DanmuShieldPreset {
            val obj = json as? JsonObject ?: return DanmuShieldPreset("", emptyList(), emptyList())
            val keywords = obj["keywords"]?.let { parseStringList(it) } ?: emptyList()
            val users = obj["users"]?.let { parseStringList(it) } ?: emptyList()
            val userGroups = obj["userGroups"]?.let { parseStringMap(it) } ?: emptyMap()
            return DanmuShieldPreset("", keywords, users, userGroups)
        }

        private fun parseStringList(e: JsonElement): List<String> =
            (e as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                ?: emptyList()

        private fun parseStringMap(e: JsonElement): Map<String, List<String>> {
            val obj = e as? JsonObject ?: return emptyMap()
            return obj.mapNotNull { (key, value) ->
                val list = parseStringList(value)
                if (list.isEmpty()) null else key to list
            }.toMap()
        }

        val json = Json { ignoreUnknownKeys = true }
    }
}