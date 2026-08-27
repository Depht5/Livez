package com.kotlin_livez.app.modules.sync.remote_sync.webdav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * WebDAV 客户端 —— 对应 Dart `webdav_client.dart`（package:webdav_client 封装）。
 * 用 OkHttp 手写 OPTIONS/PROPFIND/MKCOL/PUT/GET 五个基础操作；
 * 根目录固定 `/simple_live_app`，备份文件 `backup.zip`。
 */
class WebDAVClient(
    webDAVUri: String,
    user: String,
    password: String,
) {
    private val baseUrl = webDAVUri.trimEnd('/')
    private val credentials = if (user.isNotEmpty()) {
        Credentials.basic(user, password, Charsets.UTF_8)
    } else {
        null
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 强制统一根路径（对应 Dart root）。 */
    val root: String = "/simple_live_app"

    val backupFile: String get() = "$root/backup.zip"

    private fun newRequest(method: String, path: String): Request {
        val builder = Request.Builder()
            .url("$baseUrl$path")
            .method(method, null)
        credentials?.let { builder.header("Authorization", it) }
        builder.header("accept-charset", "utf-8")
        builder.header("Content-Type", "text/xml")
        return builder.build()
    }

    private suspend fun execute(request: Request): okhttp3.Response = withContext(Dispatchers.IO) {
        httpClient.newCall(request).execute()
    }

    /** 连通性测试（对应 Dart ping → client.ping）。 */
    suspend fun ping(): Boolean = try {
        execute(newRequest("OPTIONS", "/")).use { it.isSuccessful }
    } catch (e: Exception) {
        false
    }

    /** 创建目录（对应 Dart mkdir；已存在时忽略错误）。 */
    suspend fun mkdir(path: String) {
        try {
            execute(newRequest("MKCOL", path)).use { resp ->
                LogCompat.d("MKCOL $path -> ${resp.code}")
            }
        } catch (e: Exception) {
            LogCompat.d("MKCOL $path 已存在或失败: $e")
        }
    }

    /** 上传（对应 Dart write）。 */
    suspend fun write(path: String, data: ByteArray): Boolean {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .put(data.toRequestBodyCompat())
        credentials?.let { request.header("Authorization", it) }
        request.header("accept-charset", "utf-8")
        val resp = execute(request.build())
        resp.use {
            LogCompat.d("PUT $path -> ${it.code}")
            return it.isSuccessful
        }
    }

    /** 下载（对应 Dart read）。 */
    suspend fun read(path: String): ByteArray {
        val resp = execute(newRequest("GET", path))
        resp.use {
            if (!it.isSuccessful) {
                throw IOException("GET $path failed: ${it.code}")
            }
            return it.body?.bytes() ?: ByteArray(0)
        }
    }
}

/** 轻量日志（避免引入 app.Log 依赖，供本文件内调试用）。 */
private object LogCompat {
    fun d(msg: String) {
        com.kotlin_livez.app.app.Log.d(msg)
    }
}

/** ByteArray → RequestBody（OkHttp4 兼容）。 */
private fun ByteArray.toRequestBodyCompat(): okhttp3.RequestBody =
    okhttp3.RequestBody.create("application/octet-stream".toMediaType(), this)