package com.kotlin_livez.app.services

import com.kotlin_livez.app.models.account.BiliBiliUserInfoModel
import com.kotlin_livez.core.common.CoreLog
import com.kotlin_livez.core.common.HttpClient
import com.kotlin_livez.core.common.str
import com.kotlin_livez.core.site.BiliBiliSite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * B 站账号服务 —— 对应 Dart `BiliBiliAccountService`
 * （lib/services/bilibili_account_service.dart）。
 * 状态以 StateFlow 镜像 Dart 的 .obs；登录失效提示文案冻结保留。
 */
class BiliBiliAccountService(
    private val localStorage: LocalStorageService,
) {
    private val _logined = MutableStateFlow(false)
    val logined: StateFlow<Boolean> = _logined

    private val _name = MutableStateFlow("未登录")
    val name: StateFlow<String> = _name

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @JvmField
    var cookie: String = ""

    @JvmField
    var uid: Long = 0L

    /** 启动时从本地读取 Cookie 并加载用户信息（对应 Dart onInit）。 */
    fun init() {
        cookie = localStorage.getValue(LocalStorageService.kBilibiliCookie, "")
        _logined.value = cookie.isNotEmpty()
        ioScope.launch { loadUserInfo() }
    }

    suspend fun loadUserInfo() {
        if (cookie.isEmpty()) return
        try {
            val result = HttpClient.getJson(
                "https://api.bilibili.com/x/member/web/account",
                header = mapOf("Cookie" to cookie),
            )
            val codeNode = (result as? kotlinx.serialization.json.JsonObject)?.get("code")
            if (codeNode?.jsonPrimitive?.content == "0") {
                val info = BiliBiliUserInfoModel.fromJson(
                    (result as? kotlinx.serialization.json.JsonObject)?.get("data")
                )
                _name.value = info.uname ?: "未登录"
                uid = info.mid ?: 0L
                setSite()
            } else {
                CoreLog.w("[BiliBiliAccount] 哔哩哔哩登录已失效，请重新登录")
                logout()
            }
        } catch (e: Exception) {
            CoreLog.w("[BiliBiliAccount] 获取哔哩哔哩用户信息失败，可前往账号管理重试：$e")
        }
    }

    fun setSite() {
        val site = findBiliSite() ?: return
        site.userId = uid
        site.cookie = cookie
    }

    fun setCookie(cookie: String) {
        this.cookie = cookie
        ioScope.launch { localStorage.setValue(LocalStorageService.kBilibiliCookie, cookie) }
        _logined.value = cookie.isNotEmpty()
    }

    fun logout() {
        cookie = ""
        uid = 0L
        _name.value = "未登录"
        setSite()
        ioScope.launch { localStorage.setValue(LocalStorageService.kBilibiliCookie, "") }
        _logined.value = false
    }

    private fun findBiliSite(): BiliBiliSite? =
        com.kotlin_livez.core.LiveSiteRegistry.find("bilibili") as? BiliBiliSite
}