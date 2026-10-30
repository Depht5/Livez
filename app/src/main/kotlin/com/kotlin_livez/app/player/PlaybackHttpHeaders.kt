package com.kotlin_livez.app.player

/**
 * mpv `http-header-fields` 的序列化（纯函数，便于单测）。
 *
 * 背景（task 10-30-douyu-playback-header-leak）：该选项是 mpv 的**逗号分隔字符串列表**，
 * 且是跨 `loadfile` 持久的会话级状态。两个坑：
 *
 * 1. **值里的逗号必须转义**：虎牙播放头是
 *    `HYSDK(Windows, 30000002)_APP(pc_exe&7060000&official)_SDK(trans&2.32.3.5646)`，本身带逗号。
 *    不转义会被 mpv 当作列表分隔符拆成两条头，其中一条没有冒号（畸形头），douyucdn2 直接回
 *    `HTTP 400 Bad Request`（已用真实 CDN 重放证实：拆开的两行 → 400，完整单行 → 200）。
 *    mpv 列表选项的转义规则来自手册 “List Options”：`-set` 用 `\` 转义分隔符。
 * 2. **空头也必须写入**：调用方要无条件调用本函数并写入结果（空 map → 空串），否则上一个站点
 *    的 `referer`/`user-agent` 会留在 mpv 里，污染下一次开播（真机日志 29/29 复现）。
 */
internal object PlaybackHttpHeaders {

    /** 把请求头表编码成 mpv `http-header-fields` 可接受的字符串。 */
    fun encode(headers: Map<String, String>): String {
        if (headers.isEmpty()) return ""
        return headers.entries.joinToString(",") { (name, value) ->
            "${name.trim()}: ${escape(value)}"
        }
    }

    /** 转义 mpv 列表分隔符（逗号）与转义符本身（反斜杠必须先处理）。 */
    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace(",", "\\,")
}
