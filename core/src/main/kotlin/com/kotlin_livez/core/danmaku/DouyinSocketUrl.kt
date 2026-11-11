package com.kotlin_livez.core.danmaku

import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * 抖音弹幕 WebSocket 地址构建（纯函数，可单测）。
 *
 * 存在的唯一原因：**OkHttp 的 [okhttp3.HttpUrl] 不认识 `ws`/`wss` 协议**。
 * `"wss://host/path".toHttpUrl()` 直接抛
 * `IllegalArgumentException: Expected URL scheme 'http' or 'https' but was 'wss'`
 * （注意 `Request.Builder().url("wss://…")` 反而能接受，所以此前只在参数化阶段炸）。
 *
 * 真机日志（2026-11-11）就是这一条：抖音进房后播放正常，弹幕一条都没有，
 * 异常冒到 loadData 的 catch 里，还把整间房标成了「加载失败」。
 *
 * 做法：用等价的 http(s) 地址承载参数编码（查询串编码规则相同），再把 scheme 换回 ws(s)。
 */
internal object DouyinSocketUrl {

    /** `wss://host/p` / `ws://host/p` / `https://host/p` / `host/p` → 可被 HttpUrl 解析的 http(s) 地址。 */
    fun toHttpUrlString(base: String): String = when {
        base.startsWith("wss://", ignoreCase = true) -> "https://" + base.substring("wss://".length)
        base.startsWith("ws://", ignoreCase = true) -> "http://" + base.substring("ws://".length)
        base.startsWith("https://", ignoreCase = true) -> base
        base.startsWith("http://", ignoreCase = true) -> base
        else -> "https://$base"
    }

    /** 原始地址是否应为加密连接（`ws://` 明文，其余按加密处理）。 */
    fun isSecure(base: String): Boolean = !base.startsWith("ws://", ignoreCase = true)

    /**
     * 以 [base] 为基底、用 [query] 与 [signature] 重建 WebSocket URL。
     *
     * 返回的地址已还原成 `ws(s)://`，可直接交给 OkHttp 的 `Request.Builder().url(...)`。
     */
    fun build(base: String, query: Map<String, String>, signature: String): String {
        val builder = toHttpUrlString(base).toHttpUrl().newBuilder().query(null)
        query.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        if (signature.isNotEmpty()) builder.addQueryParameter("signature", signature)
        val encoded = builder.build()
        return if (isSecure(base)) {
            "wss://" + encoded.toString().removePrefix("https://")
        } else {
            "ws://" + encoded.toString().removePrefix("http://")
        }
    }
}
