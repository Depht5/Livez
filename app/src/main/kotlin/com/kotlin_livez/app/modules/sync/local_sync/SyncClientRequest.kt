package com.kotlin_livez.app.modules.sync.local_sync

import com.kotlin_livez.app.models.SyncClientInfoModel
import com.kotlin_livez.app.services.SyncClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 局域网同步请求客户端 —— 对应 Dart `SyncClientRequest`
 * （lib/requests/sync_client_request.dart, 180 行）。
 * 基于 OkHttp 手写 HTTP 客户端（Dart 端为 HttpClient.instance 封装）。
 */
class SyncClientRequest {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private fun baseUrl(client: SyncClient) = "http://${client.address}:${client.port}"

    /** GET /info（对应 Dart getClientInfo）。 */
    suspend fun getClientInfo(client: SyncClient): SyncClientInfoModel = withContext(Dispatchers.IO) {
        val url = "${baseUrl(client)}/info"
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("GET $url failed: ${resp.code}")
            }
            val body = resp.body?.string() ?: throw IllegalStateException("空响应")
            SyncClientInfoModel.fromJson(json.parseToJsonElement(body))
        }
    }

    /** POST /sync/follow（对应 Dart syncFollow）。 */
    suspend fun syncFollow(
        client: SyncClient,
        body: String,
        overlay: Boolean = false,
        extraQueryParameters: Map<String, String> = emptyMap(),
    ): Boolean = postJson(
        client, "follow", body,
        queryParameters = mapOf("overlay" to if (overlay) "1" else "0") + extraQueryParameters,
    )

    /** POST /sync/tag（对应 Dart syncTag）。 */
    suspend fun syncTag(
        client: SyncClient,
        body: String,
        overlay: Boolean = false,
        extraQueryParameters: Map<String, String> = emptyMap(),
    ): Boolean = postJson(
        client, "tag", body,
        queryParameters = mapOf("overlay" to if (overlay) "1" else "0") + extraQueryParameters,
    )

    /** POST /sync/history（对应 Dart syncHistory）。 */
    suspend fun syncHistory(
        client: SyncClient,
        body: String,
        overlay: Boolean = false,
        extraQueryParameters: Map<String, String> = emptyMap(),
    ): Boolean = postJson(
        client, "history", body,
        queryParameters = mapOf("overlay" to if (overlay) "1" else "0") + extraQueryParameters,
    )

    /** POST /sync/blocked_word（对应 Dart syncBlockedWord）。 */
    suspend fun syncBlockedWord(
        client: SyncClient,
        body: String,
        overlay: Boolean = false,
        extraQueryParameters: Map<String, String> = emptyMap(),
    ): Boolean = postJson(
        client, "blocked_word", body,
        queryParameters = mapOf("overlay" to if (overlay) "1" else "0") + extraQueryParameters,
    )

    /** POST /sync/profile（对应 Dart syncProfile）。 */
    suspend fun syncProfile(
        client: SyncClient,
        body: String,
        overlay: Boolean = false,
    ): Boolean = postJson(
        client, "profile", body,
        queryParameters = mapOf("overlay" to if (overlay) "1" else "0"),
    )

    /** POST /sync/account/bilibili（对应 Dart syncBiliAccount）。 */
    suspend fun syncBiliAccount(client: SyncClient, cookie: String): Boolean =
        postJson(client, "account/bilibili", buildJsonObject { put("cookie", cookie) }.toString())

    /** POST /sync/account/douyin（对应 Dart syncDouyinAccount）。 */
    suspend fun syncDouyinAccount(client: SyncClient, cookie: String): Boolean =
        postJson(client, "account/douyin", buildJsonObject { put("cookie", cookie) }.toString())

    /**
     * POST /sync/account/douyu（对应 Dart syncDouyuAccount）。
     * 续期凭据（ltp0/dyDid）为可选字段，仅在非空时携带；旧接收端只读 cookie 字段，协议向后兼容。
     */
    suspend fun syncDouyuAccount(
        client: SyncClient,
        cookie: String,
        ltp0: String = "",
        dyDid: String = "",
    ): Boolean = postJson(
        client, "account/douyu",
        buildJsonObject {
            put("cookie", cookie)
            if (ltp0.isNotEmpty()) put("ltp0", ltp0)
            if (dyDid.isNotEmpty()) put("dyDid", dyDid)
        }.toString(),
    )

    private suspend fun postJson(
        client: SyncClient,
        path: String,
        data: String,
        queryParameters: Map<String, String> = emptyMap(),
    ): Boolean = withContext(Dispatchers.IO) {
        val query = if (queryParameters.isEmpty()) "" else "?" + queryParameters.entries.joinToString("&") { (k, v) ->
            "$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
        val url = "${baseUrl(client)}/sync/$path$query"
        val body = data.toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()
        httpClient.newCall(request).execute().use { resp ->
            val respBody = resp.body?.string() ?: ""
            val parsed = runCatching { json.parseToJsonElement(respBody).jsonObject }.getOrNull()
            val status = parsed?.get("status")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content == "true" } ?: resp.isSuccessful
            if (!status) {
                val message = parsed?.get("message")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: "同步失败（${resp.code}）"
                throw IllegalStateException(message)
            }
            true
        }
    }
}

/** OkHttp 响应 JSON（对端均按 {"status": bool, "message": ...} 返回）。 */
@Suppress("unused")
private fun JsonObject.statusOk(): Boolean =
    (this["status"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"