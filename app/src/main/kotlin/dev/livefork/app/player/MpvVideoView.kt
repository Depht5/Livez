package dev.livefork.app.player

import android.content.Context
import android.util.AttributeSet
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * libmpv 视频渲染视图 —— 对应 Dart `VideoController` 附着的渲染层（media_kit Video）。
 * 生命周期：由宿主调用 [initialize]（create+init），surface 出现时自动 attach。
 */
class MpvVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private var initialized = false
    private var sessionId: Long? = null
    private var attachedSurface: Surface? = null

    init {
        holder.addCallback(this)
    }

    /** 初始化 libmpv（对应 Dart MpvOptionsService.applyToPlayer + videoControllerConfiguration 应用）。 */
    fun initialize(configDir: String, cacheDir: String, config: MpvInitConfig) {
        if (initialized) return
        initialized = true
        val id = PlayerEngine.instance.initialize(context, configDir, cacheDir, config)
        sessionId = id
        // 极少数设备可能在 DisposableEffect 执行前已创建 Surface；主动补一次绑定。
        if (holder.surface.isValid) attachSurface(id, holder.surface)
    }

    /**
     * 销毁本 View 拥有的播放器会话。先移除 holder 回调，再按会话号释放 MPVLib；
     * 若这是转场中迟到的旧页面，PlayerEngine 会拒绝释放已被新页面接管的会话。
     */
    fun destroy() {
        holder.removeCallback(this)
        attachedSurface = null
        val id = sessionId
        sessionId = null
        initialized = false
        if (id != null) PlayerEngine.instance.releaseSession(id)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val id = sessionId ?: return
        attachSurface(id, holder.surface)
    }

    private fun attachSurface(sessionId: Long, surface: Surface) {
        if (attachedSurface === surface && surface.isValid) return
        if (PlayerEngine.instance.attachSurface(sessionId, surface)) {
            attachedSurface = surface
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // 走 PlayerEngine 守卫入口（见 setSurfaceSize 根因说明：裸调 JNI 在引擎已销毁时 native 崩溃）
        sessionId?.let { PlayerEngine.instance.setSurfaceSize(it, width, height) }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        attachedSurface = null
        sessionId?.let { PlayerEngine.instance.surfaceDestroyed(it, holder.surface) }
    }
}
