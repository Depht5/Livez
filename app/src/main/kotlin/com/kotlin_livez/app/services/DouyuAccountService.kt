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

    private val _hasCookie = MutableStateFlow(false)
    val hasCookie: StateFlow<Boolean> = _hasCookie

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 对应 Dart onInit：启动时读回本地 Cookie 并推送到站点实例。 */
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
