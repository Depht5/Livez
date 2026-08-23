package dev.livefork.app.services

import dev.livefork.core.site.KuaishouSite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 快手账号服务 —— 对应 Dart `KuaishouAccountService`
 * （lib/services/kuaishou_account_service.dart）。
 */
class KuaishouAccountService(
    private val localStorage: LocalStorageService,
) {
    @JvmField
    var cookie: String = ""

    @JvmField
    var kww: String = ""

    private val _hasCookie = MutableStateFlow(false)
    val hasCookie: StateFlow<Boolean> = _hasCookie

    private val _cookieExpiresAtMs = MutableStateFlow(0L)
    val cookieExpiresAtMs: StateFlow<Long> = _cookieExpiresAtMs

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 对应 Dart onInit。 */
    fun init() {
        cookie = localStorage.getValue(LocalStorageService.kKuaishouCookie, "")
        kww = localStorage.getValue(LocalStorageService.kKuaishouKww, "")
        _cookieExpiresAtMs.value =
            localStorage.getValue(LocalStorageService.kKuaishouCookieExpiresAt, 0)
        _hasCookie.value = cookie.isNotEmpty()
        setSite()
    }

    fun setSite() {
        val site = dev.livefork.core.LiveSiteRegistry.find("kuaishou") as? KuaishouSite ?: return
        site.customCookie = cookie
        site.customKww = kww
    }

    fun setCookie(cookie: String, kww: String? = null, expiresAtMs: Long? = null) {
        this.cookie = cookie
        kww?.let { this.kww = it }
        ioScope.launch {
            localStorage.setValue(LocalStorageService.kKuaishouCookie, cookie)
            localStorage.setValue(LocalStorageService.kKuaishouKww, this@KuaishouAccountService.kww)
            localStorage.setValue(
                LocalStorageService.kKuaishouCookieExpiresAt,
                expiresAtMs ?: 0L,
            )
        }
        _cookieExpiresAtMs.value = expiresAtMs ?: 0L
        _hasCookie.value = cookie.isNotEmpty()
        setSite()
    }

    fun clearCookie() {
        cookie = ""
        kww = ""
        ioScope.launch {
            localStorage.setValue(LocalStorageService.kKuaishouCookie, "")
            localStorage.setValue(LocalStorageService.kKuaishouKww, "")
            localStorage.setValue(LocalStorageService.kKuaishouCookieExpiresAt, 0)
        }
        _cookieExpiresAtMs.value = 0L
        _hasCookie.value = false
        setSite()
    }
}