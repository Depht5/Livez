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

    /**
     * 销毁（宿主 onDispose 调用）。
     *
     * 根因说明（真机缺陷：退出直播间后立即重进 → 同房间只有声音无画面 /
     * 换直播间加载中断无画面无声）：
     * PlayerEngine 是进程级单例（Jellyfin `MPVLib` 全局单实例 JNI），其 surface 绑定
     * 全局唯一。原实现在这里调 `PlayerEngine.instance.detachSurface()`（含 vo=null、
     * force-window=no、surface 解绑）——而 Compose 导航 pop 的**转场动画结束后旧页面
     * onDispose 才执行**，此时新页面很可能已 surfaceCreated→attachSurface 并开始加载
     * 新流；旧页面的 detachSurface 会解除新页面刚绑定的 surface/正在进行的解码：
     * - 同房间立即重进：start-file 前被 detach → hwdec 无法建 mediacodec surface →
     *   `VO: [null]`，只有声音无画面；
     * - 换房间：start-file 加载中被 detach → `Opening failed` → end-file → 无画面无声。
     * （早期版本这里的 `PlayerEngine.destroy()` 把 mpv 核心整个销毁是同一类错误的极端版；
     * 本轮已改为 destroy 移出页面生命周期。）
     *
     * 修复：页面销毁只移除 holder 回调、**不触碰引擎**——旧 surface 由 SurfaceView/
     * 系统自动销毁；引擎保持「绑定旧 surface」状态，新页面 attachSurface 直接切换接管。
     * `detachSurface()` 仅保留给 surfaceDestroyed（前后台切换等）场景使用。
     */
    fun destroy() {
        holder.removeCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        PlayerEngine.instance.attachSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // 走 PlayerEngine 守卫入口（见 setSurfaceSize 根因说明：裸调 JNI 在引擎已销毁时 native 崩溃）
        PlayerEngine.instance.setSurfaceSize(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // 不再调用 PlayerEngine.detachSurface()。
        // 根因说明（真机「退出后 1~2 秒重进只有声音无画面」）：surfaceDestroyed 在
        // leaveRoom 的 GONE（SurfaceView 置 GONE 触发离屏）、页面离开、锁屏等任意
        // surface 生命周期点都会触发，而 PlayerEngine 的 surface 绑定全局唯一——
        // 旧页面 detach 后，新页面 attachSurface→open 衔接过紧时，mediacodec 解码器
        // 在 surface 上下文重建未完成时创建失败（日志：DR failed → 软件解码 →
        // hardware format not supported → Video: no video），只剩声音。
        // 引擎 surface 绑定改由各页面 attachSurface 接管/切换；误渲染到已销毁
        // surface 由 mpv 底层容错。detachSurface（停渲染）仍保留为 PlayerEngine API。
    }
}