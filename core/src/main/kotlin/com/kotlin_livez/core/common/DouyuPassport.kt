package com.kotlin_livez.core.common

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 斗鱼 Passport 凭据工具：扫码登录与播放 Cookie 续期。
 *
 * 对应 Dart `DouyuPassport`（lib/src/common/douyu_passport.dart），端点与流程见上游
 * 1.13.2 的同一实现（generateCode / japi/scan/auth 状态码 / 确认地址取凭据 / safeAuth 换票）。
 *
 * 设计要点（与上游逐条对齐）：
 * - 扫码在 Passport **专用会话**中完成，凭据（LTP0 / dy_did）由确认响应的 Set-Cookie 提供，
 *   不经过共享 HTTP 客户端与日志拦截器（本类自建 OkHttp，不带 [HttpClient] 的日志拦截器）；
 * - 手动逐跳跟随 302：凭据经 Set-Cookie 下发，自动跳转会丢掉中间响应头；
 * - 播放 Cookie 含 `acf_jwt_token`，过期时间在 JWT payload 的 `exp`（约 7 天）；
 * - 平台撤销授权或续期凭据失效时需要重新扫码，本工具不承诺永久登录。
 *
 * 并发约定：单个实例的 [generateQrCode] / [poll] 由调用方串行调用（UI 层 3s 轮询即天然串行），
 * 类内部不做锁；[refreshPlayCookie] 是无状态的静态调用，可并发。
 */
enum class DouyuQrStatus {
    /** 未扫码 */
    Waiting,

    /** 已扫码，等待斗鱼 APP 内确认 */
    Scanned,

    /** 二维码已失效，需要刷新 */
    Expired,

    /** 已确认登录，会话内已拿到 LTP0 / dy_did */
    Authorized,
}

/**
 * 斗鱼扫码登录会话（一次性）。
 *
 * 用法：`generateQrCode()` 拿到二维码内容 → 展示成二维码 → 每 3s [poll] 一次 →
 * 返回 [DouyuQrStatus.Authorized] 后读取 [ltp0] / [did] 去换播放 Cookie（[refreshPlayCookie]）。
 * 页面退出时调用 [close] 清空会话 Cookie。
 */
class DouyuPassport(client: OkHttpClient? = null) {

    /**
     * 专用客户端：**必须**禁用自动跳转（302 里的 Set-Cookie 才是凭据），
     * 也不挂日志拦截器。外部注入的 client（单测）同样被规整为不跟随跳转。
     */
    private val http: OkHttpClient = (client ?: defaultClient()).newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** Passport 会话内的小型 Cookie 罐：按名字小写归一覆盖，仅用于扫码确认链路。 */
    private val sessionCookies = LinkedHashMap<String, String>()

    /** 当前二维码对应的轮询 code（空串 = 无可轮询的码）。 */
    private var code = ""

    /** 会话内的续期凭据 `dy_did`（确认登录后可用）。 */
    val did: String get() = sessionCookies[COOKIE_DID].orEmpty()

    /** 会话内的续期凭据 `LTP0`（确认登录后可用）。 */
    val ltp0: String get() = sessionCookies[COOKIE_LTP0].orEmpty()

    /**
     * 生成登录二维码，返回二维码内容（斗鱼 APP 扫码后确认）。
     *
     * 官方页面生成二维码前先取设备号（`dy_did`），写进会话 Cookie 供后续请求带上；
     * 取设备号失败不阻断登录（回退为无 dy_did 的匿名会话，确认链路仍会下发新值）。
     */
    suspend fun generateQrCode(): String {
        sessionCookies.clear()
        code = ""
        val device = passportRequest(
            url = "$PASSPORT_BASE/lapi/did/api/get",
            query = mapOf("client_id" to "1", "callback" to "slive_did"),
        )
        val deviceId = device["data"]?.jsonObjectOrNull()?.get("did")?.contentOrEmpty().orEmpty()
        if (deviceId.isNotEmpty()) {
            sessionCookies[COOKIE_DID] = deviceId
        }
        val result = passportRequest(
            url = "$PASSPORT_BASE/scan/generateCode",
            form = mapOf("client_id" to "1", "isMultiAccount" to "0"),
        )
        val data = result["data"]?.jsonObjectOrNull()
        val qrUrl = data?.get("url")?.contentOrEmpty().orEmpty()
        val scanCode = data?.get("code")?.contentOrEmpty().orEmpty()
        if (result.errorCode() != 0 || qrUrl.isEmpty() || scanCode.isEmpty()) {
            throw CoreError("斗鱼二维码生成失败，请刷新重试")
        }
        code = scanCode
        return qrUrl
    }

    /**
     * 轮询扫码状态：`-2` 未扫 / `1` 已扫待确认 / `-1` 失效 / `0` 已确认。
     *
     * `0` 时斗鱼返回一个设置持久化 Passport Cookie 的地址（`data.url`），访问后
     * Set-Cookie 里带上 HttpOnly 的 `LTP0` 与 `dy_did`，两个都拿到才算登录成功。
     */
    suspend fun poll(): DouyuQrStatus {
        if (code.isEmpty()) return DouyuQrStatus.Expired
        val result = passportRequest(
            url = "$PASSPORT_BASE/japi/scan/auth",
            query = mapOf("code" to code, "time" to System.currentTimeMillis().toString()),
        )
        return when (result.errorCode()) {
            -2 -> DouyuQrStatus.Waiting
            1 -> DouyuQrStatus.Scanned
            -1 -> DouyuQrStatus.Expired
            0 -> confirmLogin(result)
            else -> throw CoreError("斗鱼暂时无法确认登录，请刷新重试")
        }
    }

    /** 释放会话：取消后续轮询并清空 Cookie / code（页面退出时调用）。 */
    fun close() {
        sessionCookies.clear()
        code = ""
    }

    /** 已确认登录：访问确认地址拿 Set-Cookie 里的续期凭据。 */
    private suspend fun confirmLogin(result: JsonObject): DouyuQrStatus {
        val confirmPath = result["data"]?.jsonObjectOrNull()?.get("url")?.contentOrEmpty().orEmpty()
        if (confirmPath.isEmpty()) {
            throw CoreError("斗鱼未返回登录确认地址，请刷新二维码重试")
        }
        val confirmation = passportRequest(
            url = resolveConfirmUrl(confirmPath),
            query = mapOf("callback" to "appClient_json_callback"),
        )
        if (confirmation.errorCode() != 0 || did.isEmpty() || ltp0.isEmpty()) {
            throw CoreError("斗鱼未返回续期凭据，请刷新二维码重试")
        }
        code = ""
        return DouyuQrStatus.Authorized
    }

    /**
     * Passport 请求（GET / POST 表单），手动跟随 3xx 并吸收每一跳的 Set-Cookie。
     *
     * - 每跳都校验 `https` + `*.douyu.com`（防开放重定向）；
     * - 响应可能是 JSON 或 JSONP（`slive_did({...})`），统一拆成 [JsonObject]；
     * - 只跟 3xx，200 即返回（确认地址链中途可能带 302）。
     */
    private suspend fun passportRequest(
        url: String,
        query: Map<String, String>? = null,
        form: Map<String, String>? = null,
    ): JsonObject = withContext(Dispatchers.IO) {
        var httpUrl = url.toHttpUrl()
        if (query != null) {
            httpUrl = httpUrl.newBuilder().apply {
                query.forEach { (key, value) -> addQueryParameter(key, value) }
            }.build()
        }
        var body: Map<String, String>? = form
        var hops = 0
        while (hops < MAX_HOPS) {
            hops++
            ensureDouyuUrl(httpUrl)
            val builder = Request.Builder()
                .url(httpUrl)
                .header("Referer", "$PASSPORT_BASE/")
                .header("Origin", PASSPORT_ORIGIN)
                .header("User-Agent", PASSPORT_USER_AGENT)
            val cookieHeader = cookieHeader()
            if (cookieHeader.isNotEmpty()) {
                builder.header("Cookie", cookieHeader)
            }
            val pendingBody = body
            val request = if (pendingBody == null) {
                builder.get().build()
            } else {
                val formBody = FormBody.Builder().apply {
                    pendingBody.forEach { (key, value) -> add(key, value) }
                }.build()
                builder.post(formBody).build()
            }
            val next: HttpUrl? = http.newCall(request).execute().use { response ->
                absorbSetCookies(response.headers.values("Set-Cookie"))
                val status = response.code
                if (status in 300..399) {
                    val location = response.header("Location")
                        ?: throw CoreError("斗鱼登录跳转失败")
                    body = null
                    resolveRedirect(httpUrl, location)
                } else if (status >= 400) {
                    throw CoreError("斗鱼登录请求失败：HTTP $status")
                } else {
                    return@withContext parseJsonPayload(response.body?.string().orEmpty())
                }
            }
            httpUrl = next ?: throw CoreError("斗鱼登录跳转失败")
        }
        throw CoreError("斗鱼登录跳转次数过多")
    }

    /** 响应体 → JSON：`{...}` 直接解析，JSONP 取括号内载荷（axiosJsonpCallback 等）。 */
    private fun parseJsonPayload(raw: String): JsonObject {
        val text = raw.trim()
        if (text.isEmpty()) throw CoreError("斗鱼登录响应无效")
        val payload = if (text.startsWith("{")) {
            text
        } else {
            val match = JSONP_PATTERN.find(text) ?: throw CoreError("斗鱼登录响应无效")
            match.groupValues[1].trim()
        }
        return runCatching { JSON.parseToJsonElement(payload).jsonObject }
            .getOrElse { throw CoreError("斗鱼登录响应无效") }
    }

    /** 累积 Set-Cookie：空值 / Max-Age<=0 / Expires 已过 视为删除，其余按名字大小写不敏感覆盖。 */
    private fun absorbSetCookies(rawList: List<String>) {
        for (raw in rawList) {
            val cookie = parseSetCookie(raw) ?: continue
            val key = cookie.name.lowercase()
            if (cookie.cleared) {
                sessionCookies.remove(key)
            } else {
                sessionCookies[key] = cookie.value
            }
        }
    }

    private fun cookieHeader(): String =
        sessionCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    private fun resolveConfirmUrl(path: String): String =
        runCatching { URI(PASSPORT_BASE).resolve(path).toString() }.getOrElse { path }

    private fun resolveRedirect(base: HttpUrl, location: String): HttpUrl {
        val resolved = base.resolve(location) ?: throw CoreError("斗鱼登录跳转失败")
        ensureDouyuUrl(resolved)
        return resolved
    }

    /** 只允许 https + douyu.com 子域（`attackdouyu.com` 这类不以 `.douyu.com` 结尾的会被拒绝）。 */
    private fun ensureDouyuUrl(url: HttpUrl) {
        val host = url.host
        if (url.scheme != "https" || !(host == "douyu.com" || host.endsWith(".douyu.com"))) {
            throw CoreError("斗鱼登录地址无效")
        }
    }

    /** Set-Cookie 的解析结果：`cleared` 为 true 表示该键应被删除。 */
    private class ParsedCookie(val name: String, val value: String, val cleared: Boolean)

    companion object {

        private const val PASSPORT_BASE = "https://passport.douyu.com"
        private const val PASSPORT_ORIGIN = "https://passport.douyu.com"

        /** 扫码链路最大跳数（与上游一致）。 */
        private const val MAX_HOPS = 5

        /** safeAuth 换票链最大跳数（与上游一致）。 */
        private const val RENEW_MAX_HOPS = 6

        private const val SAFE_AUTH_URL = "$PASSPORT_BASE/lapi/passport/iframe/safeAuth"

        private const val COOKIE_DID = "dy_did"
        private const val COOKIE_LTP0 = "ltp0"

        /** 斗鱼 Passport 请求 UA（对应 Dart `PlatformIdentity.passportUserAgent`）。 */
        private const val PASSPORT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /** JSONP 外壳：`cb({...});` */
        private val JSONP_PATTERN = Regex("^[\\w.\\$]+\\s*\\(([\\s\\S]*)\\)\\s*;?$")

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        /**
         * 用续期凭据换取新的播放 Cookie。
         *
         * safeAuth 不是"一次请求就下发 Cookie"：第一跳返回 302，Location 指向
         * `www.douyu.com/api/passport/login?...&loginType=safeAuth` 的一次性登录地址，
         * **真正的 acf_* 全套登录 Cookie 在第二跳（www 域）下发**，safeAuth 本身不重发 LTP0。
         * 因此这里手动逐跳跟随 302，并把每一跳的 Set-Cookie 累积起来（只跟 302，不跟 200 页面）。
         *
         * [client] 仅供单测注入；为空时内部自建（不经过 [HttpClient] 的日志拦截器）。
         */
        suspend fun refreshPlayCookie(
            dyDid: String,
            ltp0: String,
            client: OkHttpClient? = null,
        ): String = withContext(Dispatchers.IO) {
            if (dyDid.isEmpty() || ltp0.isEmpty()) {
                throw CoreError("斗鱼续期凭据缺失，请重新扫码登录")
            }
            val http = (client ?: defaultClient()).newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            val fields = LinkedHashMap<String, String>()
            var hops = 0
            var finalStatus = 0
            try {
                var url: HttpUrl = SAFE_AUTH_URL.toHttpUrl().newBuilder().apply {
                    addQueryParameter("client_id", "1")
                    val now = System.currentTimeMillis().toString()
                    addQueryParameter("t", now)
                    addQueryParameter("_", now)
                    addQueryParameter("callback", "axiosJsonpCallback")
                }.build()
                while (hops < RENEW_MAX_HOPS) {
                    hops++
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", PASSPORT_USER_AGENT)
                        .header("Referer", "https://www.douyu.com/")
                        .header("Origin", "https://www.douyu.com")
                        .header("Accept", "*/*")
                        .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                        // 每一跳都带上原始凭据：换票链后段靠 LTP0 认人
                        .header("Cookie", "dy_did=$dyDid; LTP0=$ltp0")
                        .get()
                        .build()
                    var nextUrl: HttpUrl? = null
                    http.newCall(request).execute().use { response ->
                        collectSetCookieFields(response.headers.values("Set-Cookie"), fields)
                        finalStatus = response.code
                        if (finalStatus in 300..399) {
                            val location = response.header("Location")
                            if (!location.isNullOrEmpty()) {
                                nextUrl = resolveRenewRedirect(url, location)
                            }
                        }
                    }
                    val next = nextUrl ?: break
                    url = next
                }
                val cookie = joinCookieFields(fields)
                if (!isRenewedCookieUsable(cookie)) {
                    CoreLog.d(
                        "斗鱼 safeAuth 未换到可用 Cookie：hops=$hops status=$finalStatus " +
                            "fields=${fields.keys.joinToString(",")}"
                    )
                    throw CoreError("斗鱼续期失败，请重新扫码登录")
                }
                cookie
            } catch (e: CoreError) {
                throw e
            } catch (e: IOException) {
                throw CoreError("斗鱼续期请求失败：${e.message ?: "网络异常"}")
            }
        }

        /** 累积每一跳的 Set-Cookie：空值 / Max-Age=0 视为删除，其余按名字大小写不敏感覆盖。 */
        private fun collectSetCookieFields(rawList: List<String>, fields: MutableMap<String, String>) {
            for (raw in rawList) {
                val cookie = parseSetCookie(raw) ?: continue
                fields.keys.filter { it.equals(cookie.name, ignoreCase = true) }
                    .forEach { fields.remove(it) }
                if (cookie.cleared) continue
                fields[cookie.name] = cookie.value
            }
        }

        private fun joinCookieFields(fields: Map<String, String>): String =
            fields.entries.joinToString("; ") { "${it.key}=${it.value}" }

        /**
         * 换票结果是否可用：优先用 `acf_jwt_token` 的 exp 判定；
         * 换票链也可能只下发 acf_* 基础登录字段，此时以 `acf_uid` + `acf_auth` 齐全为准。
         */
        private fun isRenewedCookieUsable(cookie: String): Boolean {
            if (cookie.isEmpty()) return false
            if (cookieValue(cookie, "acf_jwt_token").isNotEmpty()) {
                return !isCookieExpired(cookie)
            }
            return cookieValue(cookie, "acf_uid").isNotEmpty() &&
                cookieValue(cookie, "acf_auth").isNotEmpty()
        }

        /** 解析（可能为 `//host` 或相对形式的）Location，并限制在斗鱼域名内。 */
        private fun resolveRenewRedirect(base: HttpUrl, location: String): HttpUrl {
            val resolved = base.resolve(location) ?: throw CoreError("斗鱼登录跳转失败")
            val host = resolved.host
            if (resolved.scheme != "https" || !(host == "douyu.com" || host.endsWith(".douyu.com"))) {
                throw CoreError("斗鱼登录地址无效")
            }
            return resolved
        }

        /**
         * 播放 Cookie 是否已过期：以 `acf_jwt_token` 的 JWT exp 为准（约 7 天）。
         *
         * 与上游一致：没有 `acf_jwt_token` 视为过期——此时只会尝试用凭据换新，
         * 缺凭据则由调用方跳过（不发网络请求）。
         */
        fun isCookieExpired(cookie: String): Boolean {
            val jwt = cookieValue(cookie, "acf_jwt_token")
            if (jwt.isEmpty()) return true
            return try {
                val parts = jwt.split('.')
                if (parts.size < 2) return true
                val payload = parts[1]
                val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
                val decoded = String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
                val exp = JSON.parseToJsonElement(decoded)
                    .jsonObject["exp"]
                    ?.contentOrEmpty()
                    ?.toLongOrNull()
                if (exp == null) return true
                System.currentTimeMillis() / 1000 >= exp
            } catch (e: Exception) {
                true
            }
        }

        /** 从 Cookie 串中解析指定键的值（大小写不敏感；缺失返回空串）。 */
        fun cookieValue(cookie: String, name: String): String {
            for (part in cookie.split(";")) {
                val pair = part.trim()
                val idx = pair.indexOf('=')
                if (idx <= 0) continue
                if (pair.substring(0, idx).trim().equals(name, ignoreCase = true)) {
                    return pair.substring(idx + 1).trim()
                }
            }
            return ""
        }

        /**
         * 解析单条 Set-Cookie：`name=value` + `Max-Age` / `Expires` 属性。
         * 单条格式异常返回 null（调用方跳过，不中断登录流程）。
         */
        private fun parseSetCookie(raw: String): ParsedCookie? {
            val segments = raw.split(";")
            val first = segments.firstOrNull()?.trim().orEmpty()
            val eq = first.indexOf('=')
            if (eq <= 0) return null
            val name = first.substring(0, eq).trim()
            if (name.isEmpty()) return null
            val value = first.substring(eq + 1).trim()
            var cleared = value.isEmpty()
            for (i in 1 until segments.size) {
                val attr = segments[i].trim()
                val idx = attr.indexOf('=')
                if (idx <= 0) continue
                when (attr.substring(0, idx).trim().lowercase()) {
                    "max-age" -> {
                        val seconds = attr.substring(idx + 1).trim().toLongOrNull()
                        if (seconds != null && seconds <= 0L) cleared = true
                    }
                    "expires" -> {
                        val expires = parseHttpDate(attr.substring(idx + 1).trim())
                        if (expires != null && !expires.isAfter(Instant.now())) cleared = true
                    }
                }
            }
            return ParsedCookie(name, value, cleared)
        }

        private fun parseHttpDate(raw: String): Instant? = runCatching {
            ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        }.getOrNull()

        private fun JsonElement?.jsonObjectOrNull(): JsonObject? = this as? JsonObject

        private fun JsonElement?.contentOrEmpty(): String =
            (this as? JsonPrimitive)?.content?.trim().orEmpty()

        private fun JsonObject.errorCode(): Int =
            (this["error"] as? JsonPrimitive)?.content?.toIntOrNull() ?: Int.MIN_VALUE
    }
}
