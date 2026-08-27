package com.kotlin_livez.core.common

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import java.net.URI
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

    /**
     * Rhino 兼容化预处理：脚本资源与 Dart 同源（可随时重新提取），但 Rhino 1.7.15 解析不了
     * Dart 侧 dart_quickjs 支持的若干语法，此处加载时在内存中转换语义等价形式：
     * 1) 函数形参默认值（ES6；Rhino 报 `missing ) after formal parameters`，如
     *    `function f(a, num = null)` / `suffix = "cus"` / `Arguments = [0, 1, 14]`）
     *    → 移除默认值并**在函数体开头注入 `if (x === undefined) x = <默认值>;`**（保持语义）；
     * 2) 顶层 `return X;`（Rhino 报 `invalid return`，quickjs 允许）→ 改表达式语句；
     * 3) `let` → `var`（Rhino 1.7.15 存在 let 重声明/作用域缺陷）。
     */
    private fun sanitizeForRhino(raw: String): String {
        var s = raw
        s = s.replace(Regex("""\blet\s"""), "var ")
        // 仅处理函数头参数列表区间，避免误伤普通赋值（如 for 初始化）
        s = Regex("""(function\s+\w+\s*\()([^)]*?)(\))\s*(\{)""").replace(s) { m ->
            val injections = mutableListOf<String>()
            val params = Regex("""\b(\w+)\s*=\s*(?:"[^"]*"|'[^']*'|\[[^\]]*\]|[^,)]+)""")
                .replace(m.groupValues[2]) { pm ->
                    val name = pm.groupValues[1]
                    val value = pm.groupValues[0].substringAfter('=').trim()
                    injections += "if ($name===undefined) $name=$value;"
                    name
                }
            val injected = if (injections.isEmpty()) "" else " " + injections.joinToString(" ")
            m.groupValues[1] + params + m.groupValues[3] + m.groupValues[4] + injected
        }
        // 顶层 return 表达式; → 表达式;（Rhino 不允许顶层 return）
        s = s.replace(Regex("""(?m)^return\s+(.+);\s*$"""), "$1;")
        return s
    }

    private val abogusScript: String by lazy { sanitizeForRhino(loadScript("abogus.js")) }
    private val webMsSdkScript: String by lazy { sanitizeForRhino(loadScript("webms_sdk.js")) }

    /** 执行 JS 表达式。脚本先行求值，之后对 scope 求 expression。 */
    private fun evalJs(script: String, expression: String): String {
        val cx = Context.enter()
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
        val uri = URI(url)
        val queryParameters = LinkedHashMap<String, String>()
        uri.rawQuery?.split("&")?.forEach { pair ->
            val idx = pair.indexOf('=')
            val k = if (idx >= 0) pair.substring(0, idx) else pair
            val v = if (idx >= 0) pair.substring(idx + 1) else ""
            queryParameters[k] = v
        }
        val effectiveMsToken = msToken?.trim()?.takeIf { it.isNotEmpty() } ?: generateMsToken(107)
        queryParameters["msToken"] = effectiveMsToken
        queryParameters.remove("a_bogus")
        val query = queryParameters.entries.joinToString("&") { (k, v) -> "$k=$v" }
        val unsignedUrl = uri.replaceQuery(query).toString()
        val expr = "getABogus('$query', '$userAgent')"
        val aBogus = evalJs(abogusScript, expr)
        queryParameters["a_bogus"] = aBogus
        val signedQuery = queryParameters.entries.joinToString("&") { (k, v) -> "$k=$v" }
        uri.replaceQuery(signedQuery).toString()
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

    private fun URI.replaceQuery(query: String): URI =
        URI(scheme, authority, path, query, fragment)
}