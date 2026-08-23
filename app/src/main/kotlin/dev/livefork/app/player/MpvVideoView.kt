package dev.livefork.app.player

import android.content.Context
import android.util.AttributeSet
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

    init {
        holder.addCallback(this)
    }

    /** 初始化 libmpv（对应 Dart MpvOptionsService.applyToPlayer + videoControllerConfiguration 应用）。 */
    fun initialize(configDir: String, cacheDir: String, config: MpvInitConfig) {
        if (initialized) return
        initialized = true
        PlayerEngine.instance.initialize(context, configDir, cacheDir, config)
    }

    /** 销毁（宿主 onDestroy 调用）。 */
    fun destroy() {
        holder.removeCallback(this)
        PlayerEngine.instance.destroy()
        initialized = false
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        PlayerEngine.instance.attachSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        runCatching {
            dev.jdtech.mpv.MPVLib.setPropertyString("android-surface-size", "${width}x$height")
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        PlayerEngine.instance.detachSurface()
    }
}