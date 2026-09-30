package com.kotlin_livez.app.modules.mine.account

import com.kotlin_livez.app.app.Constant
import com.kotlin_livez.app.app.Log
import com.kotlin_livez.app.services.BiliBiliAccountService
import com.kotlin_livez.app.services.DouyinAccountService
import com.kotlin_livez.app.services.DouyuAccountService
import com.kotlin_livez.core.common.DouyinCookieHelper
import com.kotlin_livez.core.site.DouyinSite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 账号设置控制器 —— 对应 Dart `AccountController`
 * （lib/modules/mine/account/account_controller.dart）。
 * 对话框、底部弹层、文件选择（SAF）、打开系统浏览器均为 UI 层职责；
 * 此处提供 Cookie 归一化/过期解析/汇总文案等数据逻辑。
 */
class AccountController(
    private val bilibiliAccount: BiliBiliAccountService,
    private val douyinAccount: DouyinAccountService,
    private val douyuAccount: DouyuAccountService,
) {

    companion object {
        private const val DOUYIN_HOME_URL = "https://www.douyin.com/"
        private val RFC_1123_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US)

        /** 归一化 Cookie 输入：捕获 "Cookie:" 头行（对应 Dart _normalizeCookieInput）。 */
        fun normalizeCookieInput(input: String): String {
            val text = input.trim()
            if (text.isEmpty()) return ""
            for (line in text.split(Regex("\\r?\\n"))) {
                val item = line.trim()
                if (item.lowercase(Locale.ROOT).startsWith("cookie:")) {
                    return item.substring(item.indexOf(':') + 1).trim()
                }
            }
            return text
        }

        /** 解析 Cookie 为键值 Map（对应 Dart _parseCookieMap）。 */
        fun parseCookieMap(cookie: String): Map<String, String> {
            val result = linkedMapOf<String, String>()
            for (part in cookie.split(";")) {
                val item = part.trim()
                if (item.isEmpty()) continue
                val separatorIndex = item.indexOf('=')
                if (separatorIndex <= 0) continue
                val key = item.substring(0, separatorIndex).trim()
                val value = item.substring(separatorIndex + 1).trim()
                if (key.isNotEmpty()) result[key] = value
            }
            return result
        }

        private fun decodeCookieComponent(value: String): String {
            return runCatching { java.net.URLDecoder.decode(value, "UTF-8") }
                .getOrElse { java.net.URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }
                .let { decoded ->
                    // URLDecoder 会把 "+" 解释为空格，需还原（镜像 Dart Uri.decodeQueryComponent 语义）
                    decoded.replace("+", " ")
                }
        }

        private fun tryParseCookieDate(value: String): java.time.LocalDateTime? {
            val normalized = value.replace("+", " ").replace("-", " ")
            return try {
                ZonedDateTime.parse(normalized, RFC_1123_FORMATTER).toLocalDateTime()
            } catch (_: Exception) {
                try {
                    java.time.LocalDateTime.parse(
                        normalized,
                        DateTimeFormatter.ofPattern("yyyy MM dd HH:mm:ss"),
                    )
                } catch (_: Exception) {
                    null
                }
            }
        }

        /** 解析抖音 Cookie 过期时间（对应 Dart _parseDouyinCookieExpiry）。 */
        fun parseDouyinCookieExpiry(input: String): ZonedDateTime? {
            val cookie = (DouyinCookieHelper.extractCookieFromHeaderText(input) ?: input).trim()
            val cookieMap = parseCookieMap(cookie)
            val sidGuard = cookieMap["sid_guard"] ?: return null
            if (sidGuard.isEmpty()) return null

            val decoded = decodeCookieComponent(sidGuard)
            val parts = decoded.split("|")
            if (parts.size >= 3 || parts.size == 2) {
                val loginTime = parts.getOrNull(1)?.toLongOrNull()
                val maxAgeSeconds = parts.getOrNull(2)?.toLongOrNull()
                if (loginTime != null && maxAgeSeconds != null) {
                    val loginAt = if (loginTime > 1000000000000L) {
                        Instant.ofEpochMilli(loginTime).atZone(ZoneId.of("UTC"))
                    } else {
                        Instant.ofEpochSecond(loginTime).atZone(ZoneId.of("UTC"))
                    }
                    return loginAt.plusSeconds(maxAgeSeconds).withZoneSameInstant(ZoneId.systemDefault())
                }
            }
            if (parts.size >= 4) {
                val date = parts.getOrNull(3)?.let { tryParseCookieDate(it) }
                if (date != null) {
                    return date.atZone(ZoneId.systemDefault())
                }
            }
            return null
        }

        fun formatDurationShort(duration: java.time.Duration): String {
            val days = duration.toDays()
            val hours = duration.toHours() % 24
            val minutes = duration.toMinutes() % 60
            return when {
                days > 0 -> "$days 天 $hours 小时"
                hours > 0 -> "$hours 小时 $minutes 分钟"
                else -> "${duration.toMinutes()} 分钟"
            }
        }

        fun formatDateTimeMinute(date: ZonedDateTime): String {
            fun two(value: Long): String = value.toString().padStart(2, '0')
            return "${date.year}-${two(date.monthValue.toLong())}-${two(date.dayOfMonth.toLong())} " +
                "${two(date.hour.toLong())}:${two(date.minute.toLong())}"
        }
    }

    /** 抖音 Cookie 汇总文案刷新 tick（UI 层监听以刷新文本）。 */
    private val _douyinCookieCountdownTick = MutableStateFlow(0)
    val douyinCookieCountdownTick: StateFlow<Int> = _douyinCookieCountdownTick

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var countdownJob: Job? = null

    init {
        countdownJob = scope.launch {
            while (true) {
                delay(60 * 1000L)
                _douyinCookieCountdownTick.value += 1
            }
        }
    }

    fun dispose() {
        countdownJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
    }

    /* ---------- B 站 ---------- */

    fun logoutBilibili() {
        bilibiliAccount.logout()
    }

    /** 输入 Cookie 后登录（对应 Dart doBiliBiliCookieLogin 的落库部分）。 */
    suspend fun doBiliBiliCookieLogin(cookie: String) {
        if (cookie.isEmpty()) return
        bilibiliAccount.setCookie(cookie)
        bilibiliAccount.loadUserInfo()
    }

    /* ---------- 抖音 ---------- */

    /** 清除抖音自定义 Cookie（对应 Dart clearDouyinCookie 的落库部分）。 */
    fun clearDouyinCookie() {
        if (!douyinAccount.hasCookie.value) return
        douyinAccount.clearCookie()
        _douyinCookieCountdownTick.value += 1
        Log.d("Toast: 已清除自定义 Cookie，将使用默认 ttwid")
    }

    /** 保存/清除抖音 Cookie（对应 Dart doDouyinCookieConfig 确认部分）。 */
    fun applyDouyinCookieInput(input: String) {
        val text = input.trim()
        if (text.isEmpty()) {
            douyinAccount.clearCookie()
            _douyinCookieCountdownTick.value += 1
            Log.d("Toast: 已清除自定义 Cookie，将使用默认 ttwid")
            return
        }
        val cookie = DouyinCookieHelper.normalizeInput(text)
        douyinAccount.setCookie(cookie)
        _douyinCookieCountdownTick.value += 1
        if (DouyinCookieHelper.isOnlyTtwid(cookie)) {
            Log.d("Toast: 已保存 ttwid；搜索仍可能需要完整登录 Cookie")
        } else {
            Log.d("Toast: 抖音 Cookie 已保存")
        }
    }

    /** 从文件内容导入抖音 Cookie（对应 Dart importDouyinCookieFromFile 的解析部分）。 */
    fun importDouyinCookie(content: String) {
        val input = content.trim()
        if (input.isEmpty()) {
            Log.d("Toast: Cookie 文件内容为空")
            return
        }
        val cookie = DouyinCookieHelper.normalizeInput(input)
        douyinAccount.setCookie(cookie)
        _douyinCookieCountdownTick.value += 1
        if (DouyinCookieHelper.isOnlyTtwid(cookie)) {
            Log.d("Toast: 已导入 ttwid；搜索仍可能需要完整登录 Cookie")
        } else {
            Log.d("Toast: 已从文件导入抖音 Cookie")
        }
    }

    /** 当前抖音 Cookie 展示文本（供"查看当前 Cookie"对话框）。 */
    fun currentDouyinCookie(): String = douyinAccount.cookie

    /** 抖音 Cookie 汇总文案（对应 Dart getDouyinCookieSummaryText）。 */
    fun getDouyinCookieSummaryText(): String {
        _douyinCookieCountdownTick.value
        douyinAccount.hasCookie.value
        val cookie = douyinAccount.cookie
        if (cookie.isEmpty()) return "使用默认 ttwid，搜索受限时可配置完整 Cookie"
        val expiry = parseDouyinCookieExpiry(cookie)
        if (expiry == null) return "已自定义（${cookie.length} 字符），有效期无法判断"
        val remain = java.time.Duration.between(ZonedDateTime.now(), expiry)
        if (remain.isNegative) return "已自定义（${cookie.length} 字符），可解析有效期已过"
        return "已自定义（${cookie.length} 字符），预计剩余 ${formatDurationShort(remain)}"
    }

    /** 抖音 Cookie 编辑框下方的有效期提示（对应 Dart _getDouyinCookieExpiryText）。 */
    fun getDouyinCookieExpiryText(input: String): String {
        val cookie = (DouyinCookieHelper.extractCookieFromHeaderText(input) ?: input).trim()
        if (cookie.isEmpty()) return "当前使用默认 ttwid，无法判断搜索登录态有效期。"
        if (DouyinCookieHelper.isOnlyTtwid(DouyinCookieHelper.normalizeInput(cookie))) {
            return "当前仅为 ttwid，无法判断搜索登录态有效期；主播 / 房间搜索仍可能需要完整 Cookie。"
        }
        val expiry = parseDouyinCookieExpiry(cookie)
        if (expiry == null) {
            return "未从 Cookie 中解析到到期时间；Request Headers 不包含标准 Expires，实际有效期以抖音服务端为准。"
        }
        val remain = java.time.Duration.between(ZonedDateTime.now(), expiry)
        val expireAt = formatDateTimeMinute(expiry)
        if (remain.isNegative) {
            return "可解析到期时间已过：$expireAt；如果搜索失败，请重新获取 Cookie。"
        }
        return "Cookie 预计剩余 ${formatDurationShort(remain)}，到期时间 $expireAt；" +
            "退出登录、改密或风控可能提前失效。"
    }

    /** 恢复默认 ttwid 的显示文本（对应 Dart 对话框恢复按钮）。 */
    fun defaultTtwidDisplayText(): String {
        var defaultValue = DouyinSite.kDefaultCookie
        if (defaultValue.startsWith("ttwid=") && !defaultValue.contains(";")) {
            defaultValue = defaultValue.substring(6)
        }
        return defaultValue
    }

    /* ---------- 斗鱼 ---------- */

    /**
     * 保存/清除斗鱼 Cookie（对应 Dart `douyuTap` 的保存与清除分支：
     * 归一化输入 → `setCookie`，空输入即清除并回落匿名播放）。
     *
     * @return 供 UI 层展示的提示文案（弹层/Toast 属 View 层职责）
     */
    fun applyDouyuCookieInput(input: String): String {
        val cookie = normalizeCookieInput(input)
        douyuAccount.setCookie(cookie)
        return if (cookie.isEmpty()) "已清除斗鱼 Cookie，将使用匿名播放" else "斗鱼 Cookie 已保存"
    }

    /** 当前斗鱼 Cookie（供配置对话框预填，对应 Dart `TextEditingController(text: account.cookie)`）。 */
    fun currentDouyuCookie(): String = douyuAccount.cookie

    /**
     * 斗鱼 Cookie 汇总文案（对应 Dart `getDouyuCookieSummaryText`）。
     * 读取 `hasCookie` 而非仅 `cookie`，保证保存/清除后文案即时刷新。
     */
    fun getDouyuCookieSummaryText(): String {
        val hasCookie = douyuAccount.hasCookie.value
        val cookie = douyuAccount.cookie
        return if (!hasCookie || cookie.isEmpty()) {
            "未配置，匿名高画质可能约 10 分钟断流"
        } else {
            "已配置登录 Cookie（${cookie.length} 字符）"
        }
    }
}