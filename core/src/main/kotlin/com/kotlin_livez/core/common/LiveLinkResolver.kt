package com.kotlin_livez.core.common

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 单步重定向跟随抽象（便于单测注入假实现）。
 *
 * 语义与 Dart `ParseController.getLocation` 一致：从 [url] 开始跟随 3xx，直到拿到非 3xx 的
 * 落地地址；无法恢复（网络失败、无 `Location`、跳转环、超过跳数上限）返回空串。
 */
fun interface LiveLinkFetcher {
    suspend fun followRedirects(url: String): String
}

/**
 * 真实网络实现：独立 OkHttp 客户端 —— **不带任何 Cookie**（短链恢复不能依赖登录态，
 * Dart 侧同样显式清空请求头，见 `parse_controller_test.dart` 对 `cookie` 的断言）。
 */
class OkHttpLiveLinkFetcher(
    private val client: OkHttpClient = defaultClient(),
) : LiveLinkFetcher {

    override suspend fun followRedirects(url: String): String = withContext(Dispatchers.IO) {
        var current: HttpUrl = url.toHttpUrlOrNull() ?: return@withContext ""
        val visited = HashSet<String>()
        try {
            repeat(MAX_REDIRECTS) {
                if (!visited.add(current.toString())) return@withContext ""
                client.newCall(Request.Builder().url(current).get().build()).execute().use { res ->
                    if (res.code !in 300..399) return@withContext current.toString()
                    val location = res.header("Location")?.trim().orEmpty()
                    if (location.isEmpty()) return@withContext ""
                    current = current.resolve(location) ?: return@withContext ""
                }
            }
            CoreLog.w("短链解析：超过 $MAX_REDIRECTS 跳，放弃 $url")
            ""
        } catch (e: Exception) {
            CoreLog.e("短链解析失败：$url", e)
            ""
        }
    }

    companion object {
        /** 单个短链最多跟随 5 跳（与 Dart `_maxRedirects` 一致）。 */
        const val MAX_REDIRECTS = 5

        /** 不跟随重定向的客户端：手动读 `Location`，便于做环检测与白名单校验。 */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * 链接解析编排：纯解析 + 短链恢复（对应 Dart `ParseController.parse` 的递归部分）。
 *
 * 终止条件：
 * - [LiveUrlOutcome.Room] / [LiveUrlOutcome.Rejected] 直接返回；
 * - 短链每一层跟随一次重定向，嵌套深度超过 [maxDepth] 判失败（Dart `_maxParseDepth = 3`）。
 *
 * **深度上限是必需的**：短链地址本身也可能返回 200（跳转环的一种），
 * 没有上限会无限递归直到栈溢出（旧 Kotlin 实现的真实缺陷）。
 */
class LiveLinkResolver(
    private val fetcher: LiveLinkFetcher,
    private val maxDepth: Int = MAX_DEPTH,
) {

    suspend fun resolve(input: String): LiveUrlOutcome {
        val extracted = LiveUrlParser.extractHttpUrl(input)
        var current = if (extracted.isNotEmpty()) extracted else input
        var depth = 0
        while (true) {
            when (val outcome = LiveUrlParser.parse(current)) {
                is LiveUrlOutcome.Room -> return outcome
                is LiveUrlOutcome.Rejected -> return outcome
                is LiveUrlOutcome.ShortLink -> {
                    if (depth >= maxDepth) {
                        CoreLog.w("短链解析：嵌套超过 $maxDepth 层，放弃 $current")
                        return LiveUrlOutcome.Rejected(LiveUrlRejectReason.SHORT_LINK_FAILED)
                    }
                    depth++
                    val next = fetcher.followRedirects(outcome.url)
                    if (next.isEmpty()) {
                        return LiveUrlOutcome.Rejected(LiveUrlRejectReason.SHORT_LINK_FAILED)
                    }
                    current = next
                }
            }
        }
    }

    companion object {
        /** 短链嵌套上限（与 Dart `_maxParseDepth` 一致）。 */
        const val MAX_DEPTH = 3
    }
}
