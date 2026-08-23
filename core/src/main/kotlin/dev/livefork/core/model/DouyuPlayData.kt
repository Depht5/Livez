package dev.livefork.core.model

/**
 * 斗鱼播放清晰度附加信息。
 * 对应 Dart `DouyuPlayData`（定义于 douyu_site.dart）：请求播放链接所需的
 * 分辨率档位（rate）与候选 CDN 名称列表。
 */
data class DouyuPlayData(
    /** 分辨率档位（getH5Play 的 rate 参数） */
    val rate: Int,
    /** 候选 CDN 名称列表 */
    val cdns: List<String>,
)