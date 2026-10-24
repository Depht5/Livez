package com.kotlin_livez.app.services

import com.kotlin_livez.app.app.Constant
import com.kotlin_livez.app.app.Sites
import com.kotlin_livez.core.common.CoreLog
import com.kotlin_livez.core.common.DouyuPassport
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
 * 斗鱼支持两条登录路径：**扫码登录**（[DouyuPassport]，拿 LTP0 + dy_did 后自动换取播放
 * Cookie）与**手动粘贴 Cookie**。匿名播放仍可用，但斗鱼会对匿名高画质流做时长限制
 * （约 10 分钟后断流）；配置登录后的 www.douyu.com 完整 Cookie 可恢复高画质长期播放。
 *
 * 续期凭据（LTP0 + dy_did）随 Cookie 一起落盘：播放 Cookie 内含 `acf_jwt_token`
 * （有效期约 7 天），过期后由 [renewCookie] 经 Passport safeAuth 换新，无需重新扫码；
 * 手动粘贴的 Cookie 若内含同名键也会自动提取，因此粘贴路径同样具备自动续期。
 */
class DouyuAccountService(
    private val localStorage: LocalStorageService,
) {
    /** 当前 Cookie（纯字段，与 Dart `cookie` 一致；UI 订阅 [hasCookie] 触发重组）。 */
    // @JvmField：与下方 setCookie(...) 的 JVM 签名冲突，需抑制 getter/setter 生成
    //（同 BiliBiliAccountService/DouyinAccountService）。
    @JvmField
    var cookie: String = ""

    /**
     * 本机稳定设备号（32 位小写 hex，持久化于 [LocalStorageService.kDouyuDeviceId]）。
     * 由 [setSite] 注入 [DouyuSite.deviceId]，用于签名 did 与 Cookie 的 dy_did/acf_did：
     * 匿名共用固定假设备号会被斗鱼判定为异常并周期性掐流。
     */
    // @JvmField：与下方 setCookie(...) 同类问题，避免字段访问器与同名 JVM 签名冲突。
    @JvmField
    var deviceId: String = ""

    /**
     * 续期凭据 `LTP0`：扫码确认时由 Passport 下发（HttpOnly），手动 Cookie 内含则反解。
     * 与 [dyDid] 一起决定"能否在播放 Cookie 过期后自动换票"。
     */
    @JvmField
    var ltp0: String = ""

    /**
     * 续期凭据 `dy_did`：Passport 侧的设备号，**与 [deviceId] 不是同一个值**
     * （一个是斗鱼 Passport 的扫码设备身份，一个是本站用于防掐流的签名设备号）。
     */
    @JvmField
    var dyDid: String = ""

    /** 续期状态：重入保护 + 失败冷却（对应 Dart `_renewing` / `_lastRenewFailureAt`）。 */
    private val renewLock = Any()
    private var renewing = false
    private var lastRenewFailureAt = 0L

    private val _hasCookie = MutableStateFlow(false)
    val hasCookie: StateFlow<Boolean> = _hasCookie

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 对应 Dart onInit：启动时读回本地 Cookie / 设备号 / 续期凭据并推送到站点实例。 */
    fun init() {
        cookie = localStorage.getValue(LocalStorageService.kDouyuCookie, "")
        ltp0 = localStorage.getValue(LocalStorageService.kDouyuLtp0, "")
        dyDid = localStorage.getValue(LocalStorageService.kDouyuPassportDid, "")
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
        // 取流前的自动续期钩子：站点判定 Cookie 过期时回调（对应 Dart `onCookieExpired` 接线）。
        site.onCookieExpired = { renewCookie() }
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

    /**
     * 设置 Cookie 的统一入口（手动粘贴 / 扫码 / 备份恢复）。
     *
     * Cookie 非空时尽力提取续期凭据：显式传入的优先，否则从 Cookie 串反解；**反解为空时保留
     * 旧凭据**（对齐 Dart `_storeCredential` 的空值提前返回语义，避免一条不含 LTP0 的 Cookie
     * 把可用的续期能力抹掉）。清空 Cookie 时凭据一并清除。
     */
    fun setCookie(value: String, ltp0: String? = null, dyDid: String? = null) {
        val normalized = value.trim()
        cookie = normalized
        ioScope.launch { localStorage.setValue(LocalStorageService.kDouyuCookie, normalized) }
        _hasCookie.value = normalized.isNotEmpty()
        if (normalized.isEmpty()) {
            clearCredential(LocalStorageService.kDouyuLtp0) { this.ltp0 = it }
            clearCredential(LocalStorageService.kDouyuPassportDid) { this.dyDid = it }
        } else {
            val explicitLtp0 = ltp0?.trim().orEmpty()
            storeCredential(
                if (explicitLtp0.isNotEmpty()) explicitLtp0
                else DouyuPassport.cookieValue(normalized, "LTP0"),
                LocalStorageService.kDouyuLtp0,
            ) { this.ltp0 = it }
            val explicitDid = dyDid?.trim().orEmpty()
            storeCredential(
                if (explicitDid.isNotEmpty()) explicitDid
                else DouyuPassport.cookieValue(normalized, "dy_did"),
                LocalStorageService.kDouyuPassportDid,
            ) { this.dyDid = it }
        }
        setSite()
    }

    fun clearCookie() = setCookie("")

    /**
     * 播放 Cookie 过期时由 core 回调：用 LTP0 + dy_did 经 safeAuth 换新 Cookie。
     *
     * 无凭据 / 已在续期 / 处于失败冷却期时直接返回 null（不发网络请求），
     * 避免每次取流都打一趟 Passport。成功写回 Cookie，失败进入 [RENEW_FAILURE_BACKOFF_MS] 冷却。
     */
    private suspend fun renewCookie(): String? {
        val currentLtp0 = ltp0
        val currentDid = dyDid
        if (currentLtp0.isEmpty() || currentDid.isEmpty()) return null
        val allowed = synchronized(renewLock) {
            val coolingDown = lastRenewFailureAt != 0L &&
                System.currentTimeMillis() - lastRenewFailureAt < RENEW_FAILURE_BACKOFF_MS
            if (renewing || coolingDown) {
                false
            } else {
                renewing = true
                true
            }
        }
        if (!allowed) return null
        return try {
            val renewed = DouyuPassport.refreshPlayCookie(dyDid = currentDid, ltp0 = currentLtp0)
            // setCookie 会重新提取/落盘凭据（safeAuth 可能顺带刷新其中的键）
            setCookie(renewed, ltp0 = currentLtp0, dyDid = currentDid)
            CoreLog.i("[DouyuAccount] 斗鱼播放 Cookie 已自动续期")
            renewed
        } catch (e: Exception) {
            synchronized(renewLock) { lastRenewFailureAt = System.currentTimeMillis() }
            CoreLog.w("[DouyuAccount] 斗鱼 Cookie 自动续期失败：${e.message}")
            null
        } finally {
            synchronized(renewLock) { renewing = false }
        }
    }

    /**
     * 扫码确认成功后落库：先保存续期凭据，再换取播放 Cookie。
     *
     * 换取失败时凭据已保存，之后取流会自动续期 —— 因此异常抛给页面提示即可，
     * 不当作"登录失败"（对齐 Dart `applyQrCredentials`）。
     */
    suspend fun applyQrCredentials(passport: DouyuPassport) {
        storeCredential(passport.ltp0, LocalStorageService.kDouyuLtp0) { this.ltp0 = it }
        storeCredential(passport.did, LocalStorageService.kDouyuPassportDid) { this.dyDid = it }
        val renewed = DouyuPassport.refreshPlayCookie(dyDid = passport.did, ltp0 = passport.ltp0)
        setCookie(renewed, ltp0 = passport.ltp0, dyDid = passport.did)
    }

    /** 写凭据：空值直接返回（保留旧值，对齐 Dart `_storeCredential`）。 */
    private fun storeCredential(value: String, storageKey: String, assign: (String) -> Unit) {
        val normalized = value.trim()
        if (normalized.isEmpty()) return
        assign(normalized)
        ioScope.launch { localStorage.setValue(storageKey, normalized) }
    }

    /** 清凭据：内存与本地一起清空（退出登录时用）。 */
    private fun clearCredential(storageKey: String, assign: (String) -> Unit) {
        assign("")
        ioScope.launch { localStorage.setValue(storageKey, "") }
    }

    private companion object {
        /** 续期失败后的冷却时长（5 分钟，对应 Dart `_renewFailureBackoff`）。 */
        const val RENEW_FAILURE_BACKOFF_MS = 5 * 60 * 1000L
    }
}
