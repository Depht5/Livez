package com.kotlin_livez.core.model

/**
 * 播放链接。
 * 对应 Dart `LivePlayUrl`（lib/src/model/live_play_url.dart）。
 */
data class LivePlayUrl(
    /** 播放地址 */
    val urls: List<String>,
    /** 请求头 */
    val headers: Map<String, String>? = null,
)
