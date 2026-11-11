package com.kotlin_livez.core.common

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import java.security.MessageDigest
import kotlin.random.Random

/**
 * 抖音签名工具（a_bogus / msToken / webcast 签名）。
 *
 * 对应 Dart `DouyinSign`（lib/src/scripts/douyin_sign.dart）。Dart 端用 dart_quickjs 执行内嵌
 * JS 脚本（kABogus / kWebMsSDK），此处以 Rhino 执行从 Dart 提取的脚本资源
 * （core/src/main/resources/douyin/abogus.js、webms_sdk.js），行为保持一致。
 */
object DouyinSign {

    val defaultUserAgent: String =
        "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.5845.97 Safari/537.36 Core/1.116.567.400 QQBrowser/19.7.6764.400"

    private fun loadScript(name: String): String =
        DouyinSign::class.java.getResourceAsStream("/douyin/$name")
            ?.readBytes()
            ?.toString(Charsets.UTF_8)
            ?: ""

    private val abogusScript: String by lazy { RhinoJsCompat.sanitize(loadScript("abogus.js")) }
    private val webMsSdkScript: String by lazy { RhinoJsCompat.sanitize(loadScript("webms_sdk.js")) }

    /** 执行 JS 表达式。脚本先行求值，之后对 scope 求 expression。 */
    private fun evalJs(script: String, expression: String): String {
        // 必须走 RhinoJsCompat 的上下文：TypedArray 需为小端，否则签名无效（见该类注释）
        val cx = RhinoJsCompat.enterContext()
        return try {
            cx.optimizationLevel = -1 // 解释模式，规避 Rhino 对大型脚本的编译问题
            cx.languageVersion = Context.VERSION_ES6
            val scope: Scriptable = cx.initStandardObjects()
            cx.evaluateString(scope, "var console={error:function(){},log:function(){}};", "<init>", 1, null)
            cx.evaluateString(scope, script, "<douyin_sign>", 1, null)
            val result = cx.evaluateString(scope, expression, "<douyin_sign>", 1, null)
            Context.toString(result)
        } finally {
            Context.exit()
        }
    }

    /** 给 URL 加 msToken + a_bogus 后返回新 URL。对应 Dart getAbogusUrlWithMsToken。 */
    suspend fun getAbogusUrlWithMsToken(
        url: String,
        userAgent: String,
        msToken: String? = null,
    ): String = withContext(Dispatchers.Default) {
        // 必须用 HttpUrl 解析/重建：Dart 侧是 `Uri.parse(...).queryParameters` 解码再
        // `replace(queryParameters:)` 编码一次。旧实现取 `rawQuery`（已是编码态）后调
        // `java.net.URI.replaceQuery`（会再次转义 `%`）—— 真机日志里 host/im_path/
        // browser_version 全部变成 `%253A` 这种**双重编码**，抖音 IM 预取直接返回空响应。
        val httpUrl = url.toHttpUrlOrNull() ?: return@withContext url
        val builder = httpUrl.newBuilder().query(null)
        for (index in 0 until httpUrl.querySize) {
            val name = httpUrl.queryParameterName(index)
            if (name == "a_bogus") continue
            builder.addQueryParameter(name, httpUrl.queryParameterValue(index))
        }
        val effectiveMsToken = msToken?.trim()?.takeIf { it.isNotEmpty() } ?: generateMsToken(107)
        builder.addQueryParameter("msToken", effectiveMsToken)
        val unsigned = builder.build()
        // a_bogus 的 JS 入参是**编码态**查询串（对应 Dart `unsignedUrl.split('?').last()`）
        val query = unsigned.encodedQuery.orEmpty()
        val aBogus = evalJs(abogusScript, "getABogus('$query', '$userAgent')")
        unsigned.newBuilder()
            .addQueryParameter("a_bogus", aBogus)
            .build()
            .toString()
    }

    /** 对应 Dart getSignature（按默认参数生成 webcast 签名）。 */
    suspend fun getSignature(roomId: String, uniqueId: String): String =
        getSignatureForParams(getDefaultSignatureParams(roomId, uniqueId))

    /** 对应 Dart getSignatureForParams。 */
    suspend fun getSignatureForParams(params: Map<String, Any?>): String =
        withContext(Dispatchers.Default) {
            val msStub = getMsStubFromParams(params)
            var signature = evalJs(webMsSdkScript, "getMSSDKSignature('$msStub','$defaultUserAgent')")
            // 如果 signature 中包含 - 或 =，重新生成（对应 Dart 循环）
            var guard = 0
            while ((signature.contains('-') || signature.contains('=')) && guard < 5) {
                signature = evalJs(webMsSdkScript, "getMSSDKSignature('$msStub','$defaultUserAgent')")
                guard++
            }
            signature
        }

    /** 对应 Dart getMsStub。 */
    suspend fun getMsStub(roomId: String, uniqueId: String): String =
        getMsStubFromParams(getDefaultSignatureParams(roomId, uniqueId))

    fun getDefaultSignatureParams(roomId: String, uniqueId: String): Map<String, Any?> =
        linkedMapOf(
            "live_id" to "1",
            "aid" to "6383",
            "version_code" to 180800,
            "webcast_sdk_version" to "1.3.0",
            "room_id" to roomId,
            "sub_room_id" to "",
            "sub_channel_id" to "",
            "did_rule" to "3",
            "user_unique_id" to uniqueId,
            "device_platform" to "web",
            "device_type" to "",
            "ac" to "",
            "identity" to "audience",
        )

    fun getMsStubFromParams(params: Map<String, Any?>): String {
        val sigParams = params.entries.joinToString(",") { (k, v) -> "$k=$v" }
        return md5(sigParams)
    }

    /** 生成随机 msToken。对应 Dart generateMsToken。 */
    fun generateMsToken(length: Int): String {
        val characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return buildString {
            repeat(length) {
                append(characters[Random.nextInt(characters.length)])
            }
        }
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}