package com.kotlin_livez.app.app

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 通用工具 —— 对应 Dart `Utils`（lib/app/utils.dart）中与 UI 解耦的核心函数；
 * 弹窗/剪贴板/权限等 UI 相关方法由 UI 层（Compose/View）按需实现。
 */
object Utils {

    /** 对应 Dart dateFormat：MM-dd HH:mm */
    val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    /** 对应 Dart dateFormatWithYear：yyyy-MM-dd HH:mm */
    val dateFormatWithYear: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** 对应 Dart timeFormat：HH:mm:ss（Log 使用） */
    val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** 对应 Dart parseTime：今天→HH:mm，今年→MM-dd HH:mm，往年→yyyy-MM-dd HH:mm。 */
    fun parseTime(epochMillis: Long?): String {
        if (epochMillis == null) return ""
        val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())
        val now = LocalDateTime.now()
        return when {
            dt.year == now.year && dt.month == now.month && dt.dayOfMonth == now.dayOfMonth ->
                "%02d:%02d".format(dt.hour, dt.minute)
            dt.year == now.year -> dt.format(dateFormat)
            else -> dt.format(dateFormatWithYear)
        }
    }

    /**
     * 完整时刻文案（`yyyy-MM-dd HH:mm`，如 `2026-10-17 21:05`）——
     * 观看记录等需要**绝对时间**的场景（task 10-17-ui-material3-details-and-redesign）。
     *
     * 本函数是全仓唯一共享出口：页面/控制器一律复用它，不要再内联
     * `SimpleDateFormat("yyyy-MM-dd HH:mm")` 或自建同模式的 `DateTimeFormatter`。
     * 格式与 [dateFormatWithYear] 同源（同一模式常量，不存在第二份格式字符串）。
     *
     * 注：Dart 参考的历史页用的是 `Utils.parseTime`（今天→HH:mm 的相对格式）；
     * 本函数按任务要求提供固定完整格式。
     */
    fun formatDateTime(epochMillis: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())
            .format(dateFormatWithYear)

    /** 对应 Dart parseVersion：如 "1.2.8" → 010208。 */
    fun parseVersion(version: String): Int {
        var num = ""
        for (item in version.split('.')) {
            num += item.padStart(2, '0')
        }
        return num.toIntOrNull() ?: 0
    }

    /** 对应 Dart onlineToString：万位以上显示 "x.x万"。 */
    fun onlineToString(num: Int): String {
        if (num >= 10000) {
            val value = num / 10000.0
            return String.format(java.util.Locale.ROOT, "%.1f万", value)
        }
        return num.toString()
    }

    /** 对应 Dart parseFileSize。 */
    fun parseFileSize(size: Long): String {
        if (size < 1024) return "$size B"
        if (size < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.2f KB", size / 1024.0)
        if (size < 1024 * 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.2f MB", size / 1024.0 / 1024.0)
        }
        return String.format(
            java.util.Locale.ROOT,
            "%.2f GB",
            size / 1024.0 / 1024.0 / 1024.0,
        )
    }

    /** 对应 Dart isRegexFormat：以 / 开头结尾且长度 > 2 视为正则。 */
    fun isRegexFormat(keyword: String): Boolean =
        keyword.startsWith('/') && keyword.endsWith('/') && keyword.length > 2

    /** 对应 Dart removeRegexFormat：去掉首尾斜杠。 */
    fun removeRegexFormat(keyword: String): String = keyword.substring(1, keyword.length - 1)

    /**
     * 对应 Dart convertHexColor：返回 ARGB Int。
     * 4 位 → 补 00 当作 6 位；8 位按 AARRGGBB 解析；非法回退白色(0xFFFFFFFF)。
     */
    fun convertHexColor(hexColor: String): Int {
        var hex = hexColor.replace("#", "")
        if (hex.length == 4) {
            hex = "00$hex"
        }
        return when (hex.length) {
            6 -> {
                val r = hex.substring(0, 2).toInt(16)
                val g = hex.substring(2, 4).toInt(16)
                val b = hex.substring(4, 6).toInt(16)
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            8 -> {
                val a = hex.substring(0, 2).toInt(16)
                val r = hex.substring(2, 4).toInt(16)
                val g = hex.substring(4, 6).toInt(16)
                val b = hex.substring(6, 8).toInt(16)
                (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            else -> 0xFFFFFFFF.toInt()
        }
    }
}