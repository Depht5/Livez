package com.kotlin_livez.app.modules.mine.account.douyu

import com.kotlin_livez.app.app.Log
import com.kotlin_livez.app.di.ServiceContainer
import com.kotlin_livez.core.common.DouyuPassport
import com.kotlin_livez.core.common.DouyuQrStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 斗鱼扫码登录页状态（UI 层枚举，对应 Dart `DouyuQRStatus`）。 */
enum class DouyuQRStatus {
    /** 正在生成二维码 */
    Loading,

    /** 二维码已出，未扫码 */
    Unscanned,

    /** 已扫码，等待斗鱼 APP 内确认 */
    Scanned,

    /** 二维码已失效（斗鱼侧超时或已使用） */
    Expired,

    /** 生成失败 / 轮询出错（可重试） */
    Failed,
}

/**
 * 斗鱼扫码登录控制器 —— 对应 Dart `DouyuQRLoginController`
 * （lib/modules/mine/account/douyu/qr_login_controller.dart）。
 *
 * 流程：生成二维码内容（[DouyuPassport.generateQrCode]）→ 每 3s 轮询 → 确认后把
 * LTP0/dy_did 落库并换取播放 Cookie（[com.kotlin_livez.app.services.DouyuAccountService.applyQrCredentials]）。
 *
 * 与上游的一处刻意差异：轮询**一次异常即停**并显示「重试」，上游是每 3s 重复弹 toast；
 * 网络抖动时前者不打扰用户，且失败后仍可手动重试。
 */
class DouyuQRLoginController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val passport = DouyuPassport()
    private var pollJob: Job? = null

    /** 二维码内容（交给 [com.kotlin_livez.app.ui.components.QrCodeImage] 渲染）。 */
    val qrContent = MutableStateFlow("")

    /** 当前页面状态（驱动 UI 分支）。 */
    val status = MutableStateFlow(DouyuQRStatus.Loading)

    /** UI 层注入：toast 与登录成功后的页面返回。 */
    var onToast: ((String) -> Unit)? = null
    var onExit: (() -> Unit)? = null

    /** 生成二维码并开始轮询（页面进入 / 点「重试」「刷新二维码」时调用）。 */
    fun loadQrCode() {
        pollJob?.cancel()
        pollJob = null
        scope.launch {
            status.value = DouyuQRStatus.Loading
            try {
                val content = withContext(Dispatchers.IO) { passport.generateQrCode() }
                qrContent.value = content
                status.value = DouyuQRStatus.Unscanned
                startPoll()
            } catch (e: Exception) {
                Log.logPrint(e)
                onToast?.invoke(e.message ?: "斗鱼二维码加载失败")
                status.value = DouyuQRStatus.Failed
            }
        }
    }

    /** 3 秒轮询：单协程串行（delay 后再请求），因此不会出现并发 poll。 */
    private fun startPoll() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                val next = try {
                    withContext(Dispatchers.IO) { passport.poll() }
                } catch (e: Exception) {
                    Log.logPrint(e)
                    onToast?.invoke(e.message ?: "斗鱼扫码状态查询失败")
                    status.value = DouyuQRStatus.Failed
                    return@launch
                }
                when (next) {
                    DouyuQrStatus.Waiting -> Unit
                    DouyuQrStatus.Scanned -> status.value = DouyuQRStatus.Scanned
                    DouyuQrStatus.Expired -> {
                        status.value = DouyuQRStatus.Expired
                        return@launch
                    }
                    DouyuQrStatus.Authorized -> {
                        applyCredentials()
                        return@launch
                    }
                }
            }
        }
    }

    /** 确认后落库：先存续期凭据，再换播放 Cookie（换失败不影响凭据，取流时会自动续期）。 */
    private suspend fun applyCredentials() {
        try {
            ServiceContainer.douyuAccount.applyQrCredentials(passport)
            onToast?.invoke("斗鱼账号登录成功")
        } catch (e: Exception) {
            Log.logPrint(e)
            onToast?.invoke("登录凭据已保存，但播放 Cookie 换取失败，稍后取流会自动重试")
        } finally {
            onExit?.invoke()
        }
    }

    /** 页面退出：停轮询 + 清会话 Cookie（对应 Dart onClose）。 */
    fun close() {
        pollJob?.cancel()
        pollJob = null
        passport.close()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 3_000L
    }
}
