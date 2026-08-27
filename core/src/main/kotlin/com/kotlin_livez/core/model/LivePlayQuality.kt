package com.kotlin_livez.core.model

/**
 * 播放清晰度。
 * 对应 Dart `LivePlayQuality`（lib/src/model/live_play_quality.dart）。
 * `data` 为站点自定义信息，故用 `Any?`。
 */
data class LivePlayQuality(
    /** 清晰度 */
    val quality: String,
    /** 清晰度信息 */
    val data: Any? = null,
    val sort: Int = 0,
) : Comparable<LivePlayQuality> {
    override fun compareTo(other: LivePlayQuality): Int = other.sort.compareTo(sort)
}
