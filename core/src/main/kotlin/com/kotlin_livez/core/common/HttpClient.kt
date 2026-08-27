package com.kotlin_livez.core.common

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * HTTP 客户端（OkHttp 封装）。
 * 对应 Dart `HttpClient`（lib/src/common/http_client.dart）——单例，getText/getBytes/getJson/
 * postJson/head 语义一一对应。JSON 以 `JsonElement` 返回（对应 Dart 的 dynamic JSON），
 * 站点可用 `jsonObject[...]` 导航。
 */
object HttpClient {

    val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(HttpLogInterceptor())
            .build()
    }

    private fun buildUrl(url: String, query: Map<String, Any?>?): okhttp3.HttpUrl {
        val builder = url.toHttpUrl().newBuilder()
        query?.forEach { (k, v) ->
            if (v != null) builder.addQueryParameter(k, v.toString())
        }
        return builder.build()
    }

    private fun buildRequest(
        url: String,
        query: Map<String, Any?>?,
        header: Map<String, Any?>?,
    ): Request.Builder {
        val rb = Request.Builder().url(buildUrl(url, query))
        header?.forEach { (k, v) -> if (v != null) rb.header(k, v.toString()) }
        return rb
    }

    private fun throwIfBad(response: Response) {
        if (response.isSuccessful) return
        throw CoreError(response.message, statusCode = response.code)
    }

    /** Get 请求，返回文本串。 */
    suspend fun getText(
        url: String,
        query: Map<String, Any?>? = null,
        header: Map<String, Any?>? = null,
    ): String = withContext(Dispatchers.IO) {
        try {
            val req = buildRequest(url, query, header).get().build()
            client.newCall(req).execute().use { res ->
                throwIfBad(res)
                res.body?.string() ?: ""
            }
        } catch (e: CoreError) {
            throw e
        } catch (e: Exception) {
            throw CoreError("发送GET请求失败")
        }
    }

    /** Get 请求，返回原始二进制（用于 protobuf / 压缩包等）。 */
    suspend fun getBytes(
        url: String,
        query: Map<String, Any?>? = null,
        header: Map<String, Any?>? = null,
    ): ByteArray = withContext(Dispatchers.IO) {
        try {
            val req = buildRequest(url, query, header).get().build()
            client.newCall(req).execute().use { res ->
                throwIfBad(res)
                res.body?.bytes() ?: ByteArray(0)
            }
        } catch (e: CoreError) {
            throw e
        } catch (e: Exception) {
            throw CoreError("发送GET二进制请求失败")
        }
    }

    /** Get 请求，返回 JSON。 */
    suspend fun getJson(
        url: String,
        query: Map<String, Any?>? = null,
        header: Map<String, Any?>? = null,
    ): JsonElement? = withContext(Dispatchers.IO) {
        try {
            val req = buildRequest(url, query, header).get().build()
            client.newCall(req).execute().use { res ->
                throwIfBad(res)
                val text = res.body?.string() ?: return@use null
                parseJson(text)
            }
        } catch (e: CoreError) {
            throw e
        } catch (e: Exception) {
            throw CoreError("发送GET请求失败")
        }
    }

    /** Post 请求，返回 JSON。 */
    suspend fun postJson(
        url: String,
        query: Map<String, Any?>? = null,
        data: Any? = null,
        header: Map<String, Any?>? = null,
        formUrlEncoded: Boolean = false,
    ): JsonElement? = withContext(Dispatchers.IO) {
        try {
            val body: RequestBody? = when {
                data == null -> null
                formUrlEncoded -> {
                    val sb = StringBuilder()
                    (data as? Map<*, *>)?.forEach { (k, v) ->
                        if (sb.isNotEmpty()) sb.append('&')
                        sb.append(k).append('=').append(java.net.URLEncoder.encode(v.toString(), "UTF-8"))
                    }
                    sb.toString().toRequestBody("application/x-www-form-urlencoded".toMediaType())
                }
                else -> data.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            }
            val reqBuilder = buildRequest(url, query, header)
            if (body != null) reqBuilder.post(body) else reqBuilder.post(ByteArray(0).toRequestBody())
            val req = reqBuilder.build()
            client.newCall(req).execute().use { res ->
                throwIfBad(res)
                val text = res.body?.string() ?: return@use null
                parseJson(text)
            }
        } catch (e: CoreError) {
            throw e
        } catch (e: Exception) {
            throw CoreError("发送POST请求失败")
        }
    }

    /** Head 请求，返回 Response（即使非 2xx 也返回，对应 Dart head 的行为）。 */
    suspend fun head(
        url: String,
        query: Map<String, Any?>? = null,
        header: Map<String, Any?>? = null,
    ): Response? = withContext(Dispatchers.IO) {
        try {
            val req = buildRequest(url, query, header).head().build()
            client.newCall(req).execute().use { it }
        } catch (e: Exception) {
            throw CoreError("发送HEAD请求失败")
        }
    }

    private fun parseJson(text: String): JsonElement? =
        try {
            json.parseToJsonElement(text)
        } catch (_: Exception) {
            null
        }
}

/** 请求日志拦截器，镜像 Dart `CustomInterceptor` 的脱敏输出。 */
class HttpLogInterceptor : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): Response {
        val request = chain.request()
        val start = System.currentTimeMillis()
        if (CoreLog.requestLogType == RequestLogType.all) {
            CoreLog.i(
                "[HTTP Request] [${request.method}]\nRequest URL：${HttpLogSanitizer.redactUri(request.url.toString())}" +
                    "\nRequest Headers：${HttpLogSanitizer.redact(request.headers.toMultimap())}"
            )
        } else if (CoreLog.requestLogType == RequestLogType.short) {
            CoreLog.i("[HTTP Request] [${request.method}] ${HttpLogSanitizer.redactUri(request.url.toString())}")
        }
        val response = chain.proceed(request)
        val time = System.currentTimeMillis() - start
        if (CoreLog.requestLogType == RequestLogType.all) {
            CoreLog.i(
                "[HTTP Response] [time:${time}ms] [${response.code}] ${HttpLogSanitizer.redactUri(request.url.toString())}"
            )
        } else if (CoreLog.requestLogType == RequestLogType.short) {
            CoreLog.i("[HTTP Response] [time:${time}ms] [${response.code}] ${HttpLogSanitizer.redactUri(request.url.toString())}")
        }
        return response
    }
}
