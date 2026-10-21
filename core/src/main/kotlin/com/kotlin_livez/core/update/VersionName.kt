package com.kotlin_livez.core.update

/**
 * 版本名解析与比较 —— 应用内更新检查的唯一口径。
 *
 * 本项目的发布版本名有两种形态（见 `.trellis/spec/kotlin_re/release-and-versioning.md`）：
 * - 功能版本：`0.2.1`（新功能 / 用户可见行为变化 → 修订号 +1）
 * - 修复版本：`0.2.0.261021`（纯修复 / 优化 → 最近功能版本 + `.YYMMDD`）
 *
 * 解析规则：去前导 `v`/`V`、截断 `-` 或 `+` 之后、按 `.` 逐段取数字前缀；比较时缺段补 0、逐段整数比较。
 * 因此 `0.2.0.261021 > 0.2.0`（同基线的修复版更新），`0.2.1 > 0.2.0.261021`（功能版本更高）。
 * 纯字符串比较会把这些关系判错（`0.2.10` 会被判成小于 `0.2.9`），故必须走本工具。
 */
object VersionName {

    /**
     * 解析为数字段。
     *
     * 无法解析的段按 0 处理（如 `beta`），空串返回空列表；不抛异常 —— 输入来自远端仓库，
     * 必须对畸形 tag 保持容错。
     */
    fun segments(raw: String): List<Int> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        val noPrefix = trimmed.removePrefix("v").removePrefix("V")
        val core = noPrefix.substringBefore('-').substringBefore('+').trim()
        if (core.isEmpty()) return emptyList()
        return core.split('.').map { part ->
            val digits = part.takeWhile { it.isDigit() }
            if (digits.isEmpty()) 0 else digits.toIntOrNull() ?: 0
        }
    }

    /** 归一化文本（去 `v` 前缀与 `-`/`+` 后缀），用于和 release tag 做等值比较。 */
    fun normalize(raw: String): String = segments(raw).joinToString(".")

    /** 展示用版本名：统一补 `v` 前缀（`0.2.0` / `v0.2.0` → `v0.2.0`）；空输入返回空串。 */
    fun display(raw: String): String {
        val normalized = normalize(raw)
        return if (normalized.isEmpty()) "" else "v$normalized"
    }

    /** 比较两个版本名：`a` 比 `b` 新返回 1，相等返回 0，更旧返回 -1。 */
    fun compare(a: String, b: String): Int {
        val left = segments(a)
        val right = segments(b)
        val size = maxOf(left.size, right.size)
        for (index in 0 until size) {
            val cmp = left.getOrElse(index) { 0 }.compareTo(right.getOrElse(index) { 0 })
            if (cmp != 0) return if (cmp > 0) 1 else -1
        }
        return 0
    }

    /** `candidate` 是否比 `current` 新（更新检查的判定入口）。 */
    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0
}
