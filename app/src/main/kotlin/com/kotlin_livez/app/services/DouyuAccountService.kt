package com.kotlin_livez.app.services

import com.kotlin_livez.app.app.Constant
import com.kotlin_livez.app.app.Sites
import com.kotlin_livez.core.site.DouyuSite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * 斗鱼账号服务 —— 对应 Dart `DouyuAccountService`
 * （lib/services/douyu_account_service.dart）。
 *
 * 斗鱼是唯一只需粘贴 Cookie 的平台（无网页登录、无扫码）：匿名播放仍可用，
 * 但斗鱼会对匿名高画质流做时长限制（约 10 分钟后断流）；配置登录后的
 * www.douyu.com 完整 Cookie 可恢复高画质长期播放。
 */
class DouyuAccountService(
    private val localStorage: LocalStorageService,
) {
    /** 当前 Cookie（纯字段，与 Dart `cookie` 一致；UI 订阅 [hasCookie] 触发重组）。 */
    // @JvmField：与下方 setCookie(String) 的 JVM 签名冲突（setCookie(Ljava/lang/String;)V），
    // 需抑制 getter/setter 生成（同 BiliBiliAccountService/DouyinAccountService）。
    @JvmField
    var cookie: String = ""

    /**
     * 本机稳定设备号（32 位小写 hex，持久化于 [LocalStorageService.kDouyuDeviceId]）。
     * 由 [setSite] 注入 [DouyuSite.deviceId]，用于签名 did 与 Cookie 的 dy_did/acf_did：
     * 匿名共用固定假设备号会被斗鱼判定为异常并周期性掐流。
     */
    // @JvmField：与下方 setCookie(String) 同类问题，避免字段访问器与同名 JVM 签名冲突。
    @JvmField
    var deviceId: String = ""

    private val _hasCookie = MutableStateFlow(false)
    val hasCookie: StateFlow<Boolean> = _hasCookie

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 对应 Dart onInit：启动时读回本地 Cookie/设备号并推送到站点实例。 */
    fun init() {
        cookie = localStorage.getValue(LocalStorageService.kDouyuCookie, "")
        _hasCookie.value = cookie.trim().isNotEmpty()
        setSite()
    }

    fun setSite() {
        // 站点实例唯一源 = app 层 Sites（对齐 Dart `Sites.allSites[kDouyu]?.liveSite`）：
        // 播放路径读的正是这一份实例，写别处会导致 Cookie 静默不生效。
        val site = Sites.allSites[Constant.kDouyu]?.liveSite as? DouyuSite ?: return
        site.cookie = cookie
        // 设备号必须注入同一份实例（禁止新建 DouyuSite()），否则签名 did 与请求头 Cookie 不同源。
        site.deviceId = ensureDeviceId()
    }

    /**
     * 取本机设备号：内存已生成/读回即返回；否则读持久化值；都没有则随机生成 32 位小写 hex
     * 并异步持久化（对齐 Dart `DouyuAccountService` 的本机设备号语义）。
     */
    private fun ensureDeviceId(): String {
        if (deviceId.isNotEmpty()) return deviceId
        val saved = localStorage.getValue(LocalStorageService.kDouyuDeviceId, "").trim()
        if (saved.isNotEmpty()) {
            deviceId = saved
            return deviceId
        }
        val generated = generateHexDeviceId(32)
        deviceId = generated
        ioScope.launch { localStorage.setValue(LocalStorageService.kDouyuDeviceId, generated) }
        return deviceId
    }

    /** 生成指定长度的小写 16 进制随机串（对应 Dart generateRandomString 的字符集）。 */
    private fun generateHexDeviceId(length: Int): String {
        val chars = "0123456789abcdef"
        val random = SecureRandom()
        return buildString(length) {
            repeat(length) { append(chars[random.nextInt(chars.length)]) }
        }
    }

    fun setCookie(value: String) {
        val normalized = value.trim()
        cookie = normalized
        ioScope.launch { localStorage.setValue(LocalStorageService.kDouyuCookie, normalized) }
        _hasCookie.value = normalized.isNotEmpty()
        setSite()
    }

    fun clearCookie() = setCookie("")
}
