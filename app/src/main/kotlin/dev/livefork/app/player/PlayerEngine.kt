package dev.livefork.app.player

import android.content.Context
import android.graphics.Bitmap
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 播放器状态（对应 media_kit `Player.state` 的核心字段）。
 */
data class PlayerState(
    val playing: Boolean = false,
    val completed: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    /** mpv `video-params/aspect`：显示宽高比（DAR，已含变形源像素比折算），未知/未取到为 0 或 null */
    val aspect: Double? = null,
    val volume: Double = 100.0,
    val rate: Double = 1.0,
    val mediaTitle: String? = null,
    val speed: Double = 1.0,
    val pausedForCache: Boolean = false,
)

/**
 * libmpv 播放内核封装 —— 对应 Dart `PlayerMixin.player`（media_kit Player(media_kit) on mpv）。
 * 底层为 Jellyfin 维护的 `dev.jdtech.mpv.MPVLib`（与 mpv-android 同构 JNI API，全局单实例）。
 * 事件在 JNI 线程回调，经 [MutableStateFlow]/[MutableSharedFlow] 桥接到主协程流。
 */
class PlayerEngine private constructor() {

    companion object {
        val instance = PlayerEngine()
        private const val TAG = "PlayerEngine"
    }

    private var created = false

    /** 初始化时实际使用的 vo（clamp 后）；attachSurface 恢复用（见 attachSurface 根因说明）。 */
    private var voOption: String? = null

    /**
     * 引擎就绪守卫：未初始化（尚未 create）或已销毁（destroy 后 created=false）时
     * 拒绝所有 set_property/command 调用。
     *
     * 根因说明（真机闪退：反复进出直播间偶现 "set_property called but mpv is not initialized"
     * 后 native 崩溃）：PlayerEngine 为全局单例，而 destroy 由直播间页面的 onDispose 驱动；
     * 页面切换转场期间「新页面组合/旧页面销毁」交错，异步加载链路（loadPlayUrl → open →
     * set_property）可能在引擎被另一路 destroy 之后才执行到 JNI 调用点。此守卫把该场景从
     * native 崩溃降级为 Log.w 安全返回（UI 层最多表现为无声画，不闪退）。
     */
    private fun ensureReady(op: String): Boolean {
        if (!created) {
            dev.livefork.app.app.Log.w("播放器未初始化，忽略 $op 调用（已销毁或尚未 initialize）")
            return false
        }
        return true
    }

    /** 初始化失败原因（native/Java 异常时设置；UI 层据此提示而不是崩溃）。 */
    private val _initError = MutableStateFlow<String?>(null)
    val initError: StateFlow<String?> = _initError

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    private val _errorEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val errorEvents: SharedFlow<String> = _errorEvents

    private val _playingEvents = MutableSharedFlow<Boolean>(extraBufferCapacity = 16)
    val playingEvents: SharedFlow<Boolean> = _playingEvents

    private val _completedEvents = MutableSharedFlow<Boolean>(extraBufferCapacity = 16)
    val completedEvents: SharedFlow<Boolean> = _completedEvents

    private val _widthEvents = MutableSharedFlow<Int>(extraBufferCapacity = 16)
    val widthEvents: SharedFlow<Int> = _widthEvents

    private val _heightEvents = MutableSharedFlow<Int>(extraBufferCapacity = 16)
    val heightEvents: SharedFlow<Int> = _heightEvents

    private val _videoParamsEvents = MutableSharedFlow<Map<String, Any?>>(extraBufferCapacity = 16)
    val videoParamsEvents: SharedFlow<Map<String, Any?>> = _videoParamsEvents

    private val _logEvents = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val logEvents: SharedFlow<String> = _logEvents

    private val observer = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) {
            // 无值回调（如 track-list 变化）忽略
        }

        override fun eventProperty(property: String, value: Long) {
            when (property) {
                "video-params/w" -> {
                    _state.value = _state.value.copy(width = value.toInt())
                    _widthEvents.tryEmit(value.toInt())
                    emitVideoParams()
                }
                "video-params/h" -> {
                    _state.value = _state.value.copy(height = value.toInt())
                    _heightEvents.tryEmit(value.toInt())
                    emitVideoParams()
                }
                "time-pos" -> {
                    _state.value = _state.value.copy(positionMs = value * 1000)
                }
                "duration" -> _state.value = _state.value.copy(durationMs = value * 1000)
            }
        }

        override fun eventProperty(property: String, value: Boolean) {
            when (property) {
                "pause" -> {
                    _state.value = _state.value.copy(playing = !value)
                    _playingEvents.tryEmit(!value)
                }
                "paused-for-cache" -> {
                    _state.value = _state.value.copy(pausedForCache = value)
                    _playingEvents.tryEmit(!_state.value.playing && !value)
                }
            }
        }

        override fun eventProperty(property: String, value: String) {
            when (property) {
                "media-title" -> _state.value = _state.value.copy(mediaTitle = value)
                "speed" -> _state.value = _state.value.copy(speed = value.toDoubleOrNull() ?: 1.0)
            }
        }

        override fun eventProperty(property: String, value: Double) {
            when (property) {
                "time-pos" -> _state.value = _state.value.copy(positionMs = (value * 1000).toLong())
                "duration" -> {
                    val ms = (value * 1000).toLong()
                    _state.value = _state.value.copy(durationMs = ms)
                }
                "video-params/aspect" -> {
                    _state.value = _state.value.copy(aspect = value)
                    emitVideoParams()
                }
            }
        }

        override fun event(eventId: Int) {
            when (eventId) {
                MPVLib.MPV_EVENT_END_FILE -> {
                    _state.value = _state.value.copy(completed = true, playing = false)
                    _completedEvents.tryEmit(true)
                }
                MPVLib.MPV_EVENT_START_FILE -> {
                    _state.value = _state.value.copy(completed = false)
                    _completedEvents.tryEmit(false)
                }
            }
        }
    }

    private val logObserver = object : MPVLib.LogObserver {
        override fun logMessage(prefix: String, level: Int, text: String) {
            _logEvents.tryEmit("$prefix: $text")
        }
    }

    fun initialize(
        context: Context,
        configDir: String,
        cacheDir: String,
        config: MpvInitConfig,
    ) {
        if (created) return
        created = true
        try {
            doInitialize(context, configDir, cacheDir, config)
        } catch (e: Throwable) {
            // Java 层异常（如 UnsatisfiedLinkError 缺 so）→ 记录而不是让进程闪退
            created = false
            val message = "播放器初始化失败：${e.message ?: e.javaClass.simpleName}"
            _initError.value = message
            try {
                _errorEvents.tryEmit(message)
            } catch (_: Exception) {
            }
            dev.livefork.app.app.Log.logPrint(e)
        }
    }

    private fun doInitialize(
        context: Context,
        configDir: String,
        cacheDir: String,
        config: MpvInitConfig,
    ) {
        MPVLib.addObserver(observer)
        MPVLib.addLogObserver(logObserver)

        MPVLib.create(context)
        MPVLib.setOptionString("config", "yes")
        MPVLib.setOptionString("config-dir", configDir)
        MPVLib.setOptionString("gpu-shader-cache-dir", cacheDir)
        MPVLib.setOptionString("icc-cache-dir", cacheDir)

        // 视频输出与硬解（默认 mediacodec_embed / mediacodec；Android 兼容性最佳）
        MPVLib.setOptionString("gpu-context", "android")
        MPVLib.setOptionString("opengl-es", "yes")
        val vo = (config.vo ?: "mediacodec_embed")
            .let { if (it == "gpu-next") "gpu" else it }
        voOption = vo
        MPVLib.setOptionString("vo", vo)
        MPVLib.setOptionString("hwdec", config.hwdec ?: "mediacodec")
        MPVLib.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        MPVLib.setOptionString("ao", "audiotrack,opensles")
        // 直播流场景禁用 TLS 证书校验：Jellyfin libmpv AAR 内置 mbedtls CA 库不完整，
        // 对部分 CDN（如 B 站 bilivideo.com）证书链验证失败导致流打不开（logs: flags 8 / not trusted）。
        MPVLib.setOptionString("tls-verify", "no")
        MPVLib.setOptionString("input-default-bindings", "no")
        // 移动端限制 demuxer 缓存
        MPVLib.setOptionString("demuxer-max-bytes", "${64 * 1024 * 1024}")
        MPVLib.setOptionString("demuxer-max-back-bytes", "${64 * 1024 * 1024}")
        // media_kit 仓库更新导致的临时解决办法
        MPVLib.setOptionString("force-seekable", "yes")

        // 静态选项（对应 Dart MpvOptionsService.applyToPlayer；不含 vo/hwdec）
        for ((key, value) in config.options) {
            MPVLib.setOptionString(key, value)
        }

        // 观察关键属性
        observeProperties()

        MPVLib.init()

        MPVLib.setOptionString("force-window", "no")
        // idle=yes（根因说明：真机「退出直播间后立刻重进无画面/无声」）：
        // 原为 idle=once，播放结束（退出直播间时 player.stop()）后 mpv 核心自动进入
        // shutdown 退出流程；本引擎为全局单例（destroy 不再随页面销毁），重进时
        // loadfile 发到已退出的 core 被静默丢弃 → 无任何加载日志、无画面无声。
        // idle=yes 使 core 在无文件时保持待命，可反复 loadfile。
        MPVLib.setOptionString("idle", "yes")
    }

    private fun observeProperties() {
        val props = arrayOf(
            "time-pos" to MPVLib.MPV_FORMAT_DOUBLE,
            "duration" to MPVLib.MPV_FORMAT_DOUBLE,
            "pause" to MPVLib.MPV_FORMAT_FLAG,
            "paused-for-cache" to MPVLib.MPV_FORMAT_FLAG,
            "speed" to MPVLib.MPV_FORMAT_STRING,
            "media-title" to MPVLib.MPV_FORMAT_STRING,
            "video-params/w" to MPVLib.MPV_FORMAT_INT64,
            "video-params/h" to MPVLib.MPV_FORMAT_INT64,
            "video-params/aspect" to MPVLib.MPV_FORMAT_DOUBLE,
            "volume" to MPVLib.MPV_FORMAT_DOUBLE,
        )
        for ((name, format) in props) {
            runCatching { MPVLib.observeProperty(name, format) }
        }
    }

    private fun emitVideoParams() {
        val s = _state.value
        if (s.width != null && s.height != null) {
            _videoParamsEvents.tryEmit(
                mapOf("width" to s.width, "height" to s.height),
            )
        }
    }

    fun attachSurface(surface: android.view.Surface) {
        if (!ensureReady("attachSurface")) return
        runCatching {
            // 根因说明（真机「重进直播间无画面」第二因素）：detachSurface 会把 vo 置为
            // "null" 以停渲染；重进后新 surfaceCreated 若只 force-window=yes + attachSurface，
            // vo 残留 null → 即使流加载成功也没有视频输出。这里恢复初始化时记录的 vo。
            voOption?.let { MPVLib.setPropertyString("vo", it) }
            MPVLib.setOptionString("force-window", "yes")
            // 先解绑旧 surface 再 attach 新的（根因说明见下）
            MPVLib.detachSurface()
            MPVLib.attachSurface(surface)
        }
    }

    /**
     * 上报 SurfaceView 尺寸（对应 mpv `android-surface-size`）。
     *
     * 根因说明（真机闪退：重进直播间后全屏旋转崩溃）：原实现由 `MpvVideoView.surfaceChanged`
     * 直接裸调 `MPVLib.setPropertyString`——该入口没有 `ensureReady` 守卫，一旦引擎已被
     * `PlayerEngine.destroy()` 销毁（旧页面 onDispose 晚于新页面组合的转场时序），旋转触发的
     * size 变化会对已释放的 mpv 实例发起 JNI 调用，native use-after-free 直接 SIGSEGV。
     * 统一收口到本方法：先过 `ensureReady`（未初始化安全返回），`runCatching` 仅兜 Java 异常。
     */
    fun setSurfaceSize(width: Int, height: Int) {
        if (!ensureReady("setSurfaceSize")) return
        runCatching { MPVLib.setPropertyString("android-surface-size", "${width}x$height") }
    }

    fun detachSurface() {
        if (!ensureReady("detachSurface")) return
        runCatching {
            // 根因说明（真机「退出立即重进无画面/无画面无声」）：
            // 原实现在这里 `setPropertyString("vo", "null")`——vo 是引擎全局属性，
            // detachSurface 由 surfaceDestroyed 或页面 onDispose 驱动；若恰好晚于新页面
            // attachSurface 的 vo 恢复，会把 vo 改回 null，新页面 loadfile 时 hwdec 无法
            // 创建 mediacodec surface → `VO: [null]`（只有声音无画面）。surface 解绑
            // 本身由 `MPVLib.detachSurface()` 完成，不再额外改 vo。
            MPVLib.setOptionString("force-window", "no")
            MPVLib.detachSurface()
        }
    }

    /** 打开媒体（对应 Dart player.open(Media)。httpHeaders 转 http-header-fields）。 */
    fun open(url: String, httpHeaders: Map<String, String> = emptyMap()) {
        if (!ensureReady("open")) return
        if (httpHeaders.isNotEmpty()) {
            val joined = httpHeaders.entries.joinToString(",") { "${it.key}: ${it.value}" }
            runCatching { MPVLib.setPropertyString("http-header-fields", joined) }
        }
        MPVLib.command(arrayOf("loadfile", url))
    }

    fun play() {
        if (!ensureReady("play")) return
        _state.value = _state.value.copy(playing = true)
        MPVLib.setPropertyBoolean("pause", false)
    }

    fun pause() {
        if (!ensureReady("pause")) return
        _state.value = _state.value.copy(playing = false)
        MPVLib.setPropertyBoolean("pause", true)
    }

    fun stop() {
        if (!ensureReady("stop")) return
        MPVLib.command(arrayOf("stop"))
    }

    fun seek(positionMs: Long) {
        if (!ensureReady("seek")) return
        MPVLib.setPropertyDouble("time-pos", positionMs / 1000.0)
    }

    fun setVolume(volume: Double) {
        if (!ensureReady("setVolume")) return
        val clamped = volume.coerceIn(0.0, 100.0)
        _state.value = _state.value.copy(volume = clamped)
        MPVLib.setPropertyDouble("volume", clamped)
    }

    fun setRate(rate: Double) {
        if (!ensureReady("setRate")) return
        _state.value = _state.value.copy(rate = rate)
        MPVLib.setPropertyDouble("speed", rate)
    }

    fun setMuted(muted: Boolean) {
        if (!ensureReady("setMuted")) return
        MPVLib.setPropertyBoolean("mute", muted)
    }

    /** 设置 mpv 原生属性（对应 Dart nativePlayer.setProperty）。 */
    fun setProperty(name: String, value: String) {
        if (!ensureReady("setProperty/$name")) return
        runCatching { MPVLib.setPropertyString(name, value) }
    }

    fun setPropertyBoolean(name: String, value: Boolean) {
        if (!ensureReady("setPropertyBoolean/$name")) return
        runCatching { MPVLib.setPropertyBoolean(name, value) }
    }

    /** 截图（对应 Dart player.screenshot()，经 mpv screenshot-to-file 落盘后读取）。 */
    fun screenshot(cacheDir: String): Bitmap? {
        if (!ensureReady("screenshot")) return null
        val file = java.io.File(cacheDir, "sl_screenshot_${System.currentTimeMillis()}.png")
        return try {
            MPVLib.command(arrayOf("screenshot-to-file", file.absolutePath, "video"))
            if (file.exists()) {
                val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                file.delete()
                bmp
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 读取属性（未初始化时安全返回 null，见 [ensureReady] 根因说明）。 */
    fun getPropertyString(name: String): String? =
        if (!ensureReady("getPropertyString/$name")) null
        else runCatching { MPVLib.getPropertyString(name) }.getOrNull()

    fun getPropertyInt(name: String): Int? =
        if (!ensureReady("getPropertyInt/$name")) null
        else runCatching { MPVLib.getPropertyInt(name) }.getOrNull()

    fun getPropertyDouble(name: String): Double? =
        if (!ensureReady("getPropertyDouble/$name")) null
        else runCatching { MPVLib.getPropertyDouble(name) }.getOrNull()

    fun getPropertyBoolean(name: String): Boolean? =
        if (!ensureReady("getPropertyBoolean/$name")) null
        else runCatching { MPVLib.getPropertyBoolean(name) }.getOrNull()

    fun destroy() {
        MPVLib.removeObserver(observer)
        MPVLib.removeLogObserver(logObserver)
        runCatching { MPVLib.destroy() }
        created = false
    }
}

/** 播放器初始化配置。 */
class MpvInitConfig(
    /** 视频输出驱动（对应 Dart VideoControllerConfiguration.vo） */
    val vo: String?,
    /** 硬解（对应 Dart hwdec） */
    val hwdec: String?,
    /** 静态 mpv 选项（对应 Dart MpvOptionsService.applyToPlayer 结果） */
    val options: Map<String, String>,
)