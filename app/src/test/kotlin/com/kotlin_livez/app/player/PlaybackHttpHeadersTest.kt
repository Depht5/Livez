package com.kotlin_livez.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `http-header-fields` 序列化单测（task 10-30-douyu-playback-header-leak）。
 *
 * 背景：该选项是 mpv 的逗号分隔字符串列表，且跨 `loadfile` 持久。真机日志实证：
 * - 斗鱼开播时「mpv 里没有继承头」3 次全部成功；「继承到 B 站/虎牙头」29 次全部 HTTP 400；
 * - 虎牙 UA 自带逗号，未转义会被拆成两条头（其中一条无冒号），用真实 CDN 重放稳定 400。
 *
 * 这里钉住三件事：空表必须输出空串（用于清空 mpv 状态）、值内逗号必须转义、条目按顺序拼接。
 */
class PlaybackHttpHeadersTest {

    @Test
    fun encode_emptyMap_returnsEmptyString() {
        // 空串是"清空 mpv 现有头"的唯一手段：调用方必须无条件写入，否则上一站的头会残留
        assertEquals("", PlaybackHttpHeaders.encode(emptyMap()))
    }

    @Test
    fun encode_huyaUserAgent_escapesComma() {
        val ua = "HYSDK(Windows, 30000002)_APP(pc_exe&7060000&official)_SDK(trans&2.32.3.5646)"
        val encoded = PlaybackHttpHeaders.encode(mapOf("user-agent" to ua))

        assertEquals("user-agent: HYSDK(Windows\\, 30000002)_APP(pc_exe&7060000&official)_SDK(trans&2.32.3.5646)", encoded)
        // 唯一那个逗号必须带反斜杠（否则 mpv 会把它当条目分隔符，拆出无冒号的畸形头）
        val value = encoded.removePrefix("user-agent: ")
        assertEquals("值里应只有一个逗号", 1, value.count { it == ',' })
        assertTrue("逗号必须被转义", value.contains("Windows\\, 30000002"))
    }

    @Test
    fun encode_bilibiliHeaders_keepsTwoEntriesWithoutEscapes() {
        val encoded = PlaybackHttpHeaders.encode(
            mapOf(
                "referer" to "https://live.bilibili.com",
                "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0.0.0",
            ),
        )

        // B 站两条头都不含逗号：只应有一个分隔符（条目之间），值内不出现转义
        assertEquals(
            "referer: https://live.bilibili.com,user-agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0.0.0",
            encoded,
        )
        assertFalse(encoded.contains("\\"))
    }

    @Test
    fun encode_escapesBackslashBeforeComma() {
        // 先转义反斜杠再转义逗号，避免把 \(逗号) 写成 \\,（会被解析成"反斜杠 + 分隔符"）
        assertEquals("x: a\\\\b\\,c", PlaybackHttpHeaders.encode(mapOf("x" to "a\\b,c")))
    }

    @Test
    fun encode_trimsHeaderName() {
        assertEquals("user-agent: v", PlaybackHttpHeaders.encode(mapOf(" user-agent " to "v")))
    }
}
