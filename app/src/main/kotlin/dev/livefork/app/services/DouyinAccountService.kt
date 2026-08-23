package dev.livefork.app.services

import dev.livefork.core.site.DouyinSite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 抖音账号服务 —— 对应 Dart `DouyinAccountService`
 * （lib/services/douyin_account_service.dart）。
 */
class DouyinAccountService(
    private val localStorage: LocalStorageService,
) {
    @JvmField
    var cookie: String = ""

    private val _hasCookie = MutableStateFlow(false)
    val hasCookie: StateFlow<Boolean> = _hasCookie

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 对应 Dart onInit。 */
    fun init() {
        cookie = localStorage.getValue(LocalStorageService.kDouyinCookie, "")
        _hasCookie.value = cookie.isNotEmpty()
        setSite()
    }

    fun setSite() {
        val site = dev.livefork.core.LiveSiteRegistry.find("douyin") as? DouyinSite ?: return
        site.cookie = cookie
    }

    fun setCookie(cookie: String) {
        this.cookie = cookie
        ioScope.launch { localStorage.setValue(LocalStorageService.kDouyinCookie, cookie) }
        _hasCookie.value = cookie.isNotEmpty()
        setSite()
    }

    fun clearCookie() {
        cookie = ""
        ioScope.launch { localStorage.setValue(LocalStorageService.kDouyinCookie, "") }
        _hasCookie.value = false
        setSite()
    }
}