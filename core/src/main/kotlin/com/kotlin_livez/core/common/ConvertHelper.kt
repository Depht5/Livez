package com.kotlin_livez.core.common

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 容错类型转换工具。
 * 对应 Dart `asT<T>`（lib/src/common/convert_helper.dart）：值类型匹配则返回，否则 null。
 */
inline fun <reified T> Any?.asType(): T? = this as? T

// ---------------------------------------------------------------------------
// JsonElement 便捷访问（对应 Dart 站点对 dynamic JSON 的 .toString()/.asT<> 访问）
// ---------------------------------------------------------------------------

/** 读取 JsonObject 字段；若词法路径（如 "data.list"）不存在返回 null。 */
fun JsonElement?.at(path: String): JsonElement? {
    var node = this ?: return null
    for (seg in path.split('.')) {
        node = (node as? JsonObject)?.get(seg) ?: return null
    }
    return node
}

/** 取字符串：JsonPrimitive 且非 null 则 toString，否则 ""（对应 Dart value.toString()）。 */
fun JsonElement?.str(): String {
    val primitive = this as? JsonPrimitive ?: return ""
    if (this is JsonNull) return ""
    return primitive.content
}

/** 取整型：带容错，解析失败返回 0。 */
fun JsonElement?.intv(): Int =
    (this as? JsonPrimitive)?.content?.trim()?.toIntOrNull() ?: 0

/**
 * 取 64 位整型：带容错，解析失败返回 0。
 *
 * 直播平台的用户/频道 ID 已普遍超过 Int 上限，不能先经过 [intv] 再转 Long，
 * 否则解析失败会静默变成 0，网络层仍可能返回“注册成功”但不会订阅到目标房间。
 */
fun JsonElement?.longv(): Long =
    (this as? JsonPrimitive)?.content?.trim()?.toLongOrNull() ?: 0L

/** 取布尔：容错解析。 */
fun JsonElement?.bool(): Boolean {
    val content = (this as? JsonPrimitive)?.content ?: return false
    return when (content.lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> content.toIntOrNull() != null && content.toInt() != 0
    }
}
