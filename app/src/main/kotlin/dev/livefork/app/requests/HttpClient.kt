package dev.livefork.app.requests

import dev.livefork.app.app.Log
import dev.livefork.core.common.HttpLogSanitizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * app 侧 HTTP 客户端 —— 对应 Dart `HttpClient`（lib/requests/http_client.dart，Dio 封装）。
 * 用 OkHttp 镜像 getText/getJson/get/postJson/head；错误文案（_dioExceptionToString）冻结保留。
 */
class HttpClient private constructor() {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    /** GET 请求，返回字符串（对应 Dart getText）。 */
    fun getText(
        url: String,
        queryParameters: Map<String, Any?> = emptyMap(),
        header: Map<String, String> = emptyMap(),
    ): String {
        try {
            val result = execute("GET", url, queryParameters, header, null, false)
            return result
        } catch (e: Exception) {
            throw toHttpError(e, "GET")
        }
    }

    /** GET 请求，返回 JSON（对应 Dart getJson）。 */
    fun getJson(
        url: String,
        queryParameters: Map<String, Any?> = emptyMap(),
        header: Map<String, String> = emptyMap(),
    ): JsonElement? {
        try {
            val result = execute("GET", url, queryParameters, header, null, true)
            return parseJson(result)
        } catch (e: Exception) {
            throw toHttpError(e, "GET")
        }
    }

    /** GET 请求，返回原始字符串（无 JSON 解析包装）。 */
    fun get(
        url: String,
        queryParameters: Map<String, Any?> = emptyMap(),
        header: Map<String, String> = emptyMap(),
    ): String {
        try {
            return execute("GET", url, queryParameters, header, null, false)
        } catch (e: Exception) {
            throw toHttpError(e, "GET")
        }
    }

    /** POST 请求，返回 JSON（对应 Dart postJson）。 */
    fun postJson(
        url: String,
        queryParameters: Map<String, Any?> = emptyMap(),
        data: Any? = null,
        header: Map<String, String> = emptyMap(),
        formUrlEncoded: Boolean = false,
    ): JsonElement? {
        try {
            val body = buildBody(data, formUrlEncoded)
            val result = execute("POST", url, queryParameters, header, body, true)
            return parseJson(result)
        } catch (e: Exception) {
            throw toHttpError(e, "POST")
        }
    }

    /** HEAD 请求，返回状态码（对应 Dart head）。 */
    fun head(
        url: String,
        queryParameters: Map<String, Any?> = emptyMap(),
        header: Map<String, String> = emptyMap(),
    ): Int {
        try {
            val request = buildRequest("HEAD", url, queryParameters, header, null)
            client.newCall(request).execute().use { response ->
                logResponse("HEAD", response.code, url, System.currentTimeMillis())
                return response.code
            }
        } catch (e: Exception) {
            throw toHttpError(e, "HEAD")
        }
    }

    private fun execute(
        method: String,
        url: String,
        queryParameters: Map<String, Any?>,
        header: Map<String, String>,
        body: RequestBody?,
        wantJson: Boolean,
    ): String {
        val request = buildRequest(method, url, queryParameters, header, body)
        val start = System.currentTimeMillis()
        client.newCall(request).execute().use { response ->
            logResponse(method, response.code, url, start)
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw HttpResponseException(text, response.code)
            }
            return text
        }
    }

    private fun buildRequest(
        method: String,
        url: String,
        queryParameters: Map<String, Any?>,
        header: Map<String, String>,
        body: RequestBody?,
    ): Request {
        val base = Request.Builder()
            .url(withQuery(url, queryParameters))
            .headers(Headers.Builder().apply {
                header.forEach { (k, v) -> add(k, v) }
            }.build())
        return when (method) {
            "GET" -> base.get().build()
            "HEAD" -> base.head().build()
            "POST" -> base.post(body ?: "{}".toRequestBody("application/json".toMediaType())).build()
            else -> base.build()
        }
    }

    private fun withQuery(url: String, queryParameters: Map<String, Any?>): String {
        if (queryParameters.isEmpty()) return url
        val separator = if (url.contains("?")) "&" else "?"
        return url + separator + queryParameters.entries.joinToString("&") { (k, v) ->
            "$k=${java.net.URLEncoder.encode(v?.toString().orEmpty(), "UTF-8")}"
        }
    }

    private fun buildBody(data: Any?, formUrlEncoded: Boolean): RequestBody? {
        if (data == null) {
            return if (formUrlEncoded) FormBody.Builder().build() else null
        }
        return when (data) {
            is Map<*, *> -> {
                if (formUrlEncoded) {
                    val builder = FormBody.Builder()
                    data.forEach { (k, v) -> builder.add(k.toString(), v?.toString().orEmpty()) }
                    builder.build()
                } else {
                    buildJson(data).toRequestBody("application/json".toMediaType())
                }
            }
            is String -> {
                if (formUrlEncoded) {
                    FormBody.Builder().add("content", data).build()
                } else {
                    data.toRequestBody("application/json".toMediaType())
                }
            }
            else -> null
        }
    }

    private fun buildJson(data: Map<*, *>): String {
        val sb = StringBuilder("{")
        var first = true
        data.forEach { (k, v) ->
            if (!first) sb.append(",")
            first = false
            sb.append("\"").append(k).append("\":")
            sb.append(when (v) {
                is Number -> v.toString()
                is Boolean -> v.toString()
                null -> "null"
                else -> "\"" + v.toString().replace("\"", "\\\"") + "\""
            })
        }
        sb.append("}")
        return sb.toString()
    }

    private fun parseJson(text: String): JsonElement? {
        if (text.isBlank()) return null
        return try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            null
        }
    }

    private fun logResponse(method: String, code: Int, url: String, start: Long) {
        val time = System.currentTimeMillis() - start
        Log.i("【HTTP请求响应】 耗时:${time}ms\nRequest Method：$method\nRequest Code：$code\nRequest URL：${HttpLogSanitizer.redactUri(url)}")
    }

    private fun toHttpError(e: Exception, method: String): HttpError {
        if (e is HttpResponseException) {
            return HttpError(e.message.orEmpty(), e.statusCode)
        }
        val msg = when (e) {
            is java.net.SocketTimeoutException -> "${method}连接超时，请检查地址、端口和局域网是否互通"
            is java.net.ConnectException -> "${method}连接失败，请确认对方设备已打开局域网同步"
            else -> e.message?.takeIf { it.isNotEmpty() }
                ?: "发送${method}请求失败"
        }
        return HttpError(msg)
    }

    private class HttpResponseException(
        message: String,
        val statusCode: Int,
    ) : Exception(message)

    companion object {
        @Volatile
        private var instance: HttpClient? = null

        val INSTANCE: HttpClient
            get() = instance ?: synchronized(this) {
                instance ?: HttpClient().also { instance = it }
            }
    }
}