package com.kotlin_livez.app.player

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
    /**
     * 缓存前探量（毫秒）：`demuxer-cache-time`（**缓存末端的时间戳**，与 position 同一时间轴）
     * 减去当前播放位置，即「缓存内仍可播的时长」，供播放停滞看门狗判断「缓冲是否已耗尽」。
     *
     * 注意 mpv 的 `demuxer-cache-time` 本身是时间戳而非剩余量（media_kit 的
     * `PlayerState.buffer` 同为时间戳，Dart 看门狗用 `buffer - position` 求前探量），
     * 因此不能把它直接与阈值比较——播放 10 分钟后该值约为 600000ms，比较必然为假。
     *
     * 0 既表示缓存已耗尽（前探量为 0/负），也表示 mpv 尚未上报该属性
     * （属性不可用时回调无值，按 0 处理）；两种情形在看门狗判据里都按 `<= 1000L` 处理，
     * 等价于保守回退到只认 `pausedForCache`，不会因为拿不到缓存量而让看门狗失效。
     */
    val bufferMs: Long = 0,
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

    /**
     * MPVLib 0.4.x 是进程级静态单例，而 Compose 转场可能让旧、新页面短暂共存。
     * 会话号保证迟到的旧页面回调不能释放或改绑新页面正在使用的播放器。
     */
    private val lifecycleLock = Any()
    private var nextSessionId = 0L
    private var activeSessionId: Long? = null
    private var surfaceAttached = false

    /** 初始化时实际使用的 vo（clamp 后）；attachSurface 恢复用（见 attachSurface 根因说明）。 */
    private var voOption: String? = null

    /**
     * 引擎就绪守卫：未初始化（尚未 create）或已销毁（destroy 后 created=false）时
     * 拒绝所有 set_property/command 调用。
     *
     * 根因说明（真机闪退：反复进出直播间偶现 "set_property called but mpv is not initialized"
     * 后 native 崩溃）：异步加载链路可能在页面释放会话后才执行到 JNI 调用点。此守卫把
     * 该场景从 native 崩溃降级为 Log.w 安全返回（UI 层最多表现为无声画，不闪退）。
     */
    private fun ensureReady(op: String): Boolean {
        if (!created) {
            com.kotlin_livez.app.app.Log.w("播放器未初始化，忽略 $op 调用（已销毁或尚未 initialize）")
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

    /**
     * mpv `demuxer-cache-time` 原值（缓存末端的时间戳，毫秒，与 position 同一时间轴）；
     * 属性不可用时为 0。对外暴露的前探量 = 该值 - position（见 [PlayerState.bufferMs]）。
     */
    private var demuxerCachePositionMs = 0L

    /** 缓存前探量（毫秒，负值按 0 处理）= 缓存末端时间戳 - 播放位置。 */
    private fun bufferLeadMs(positionMs: Long): Long =
        (demuxerCachePositionMs - positionMs).coerceAtLeast(0L)

    private val observer = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) {
            // 无值回调（如 track-list 变化）忽略；属性变为「不可用」时 mpv 也会走这里，
            // demuxer-cache-time 不可用按 0（缓存已耗尽/未知）处理，避免沿用上一次的陈旧值。
            if (property == "demuxer-cache-time") {
                demuxerCachePositionMs = 0L
                _state.value = _state.value.copy(bufferMs = 0L)
            }
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
                    // 位置推进时同步刷新前探量（缓存末端不变而位置前进 → 前探量随之缩小）。
                    val positionMs = value * 1000
                    _state.value = _state.value.copy(
                        positionMs = positionMs,
                        bufferMs = bufferLeadMs(positionMs),
                    )
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
                "eof-reached" -> {
                    // 仅真 EOF 视为播放结束（对应 media_kit 以 eof-reached 判定 completed）。
                    // 根因说明（task 09-12-refresh-crash 无限刷新）：mpv loadfile 替换旧文件
                    // 也会触发 END_FILE（reason=stop），而 MPVLib JNI 的事件回调不携带 reason
                    // 载荷；若在 END_FILE 事件里一律按 completed 处理，刷新会被误判为"播放
                    // 结束" → mediaEnd 无限重试重拉流。eof-reached 属性只在真 EOF 翻 true，
                    // 替换/停止/错误型 END_FILE 不再误判。
                    if (value) {
                        _state.value = _state.value.copy(completed = true, playing = false)
                        _completedEvents.tryEmit(true)
                    }
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
                "time-pos" -> {
                    // 位置推进时同步刷新前探量（缓存末端不变而位置前进 → 前探量随之缩小）。
                    val positionMs = (value * 1000).toLong()
                    _state.value = _state.value.copy(
                        positionMs = positionMs,
                        bufferMs = bufferLeadMs(positionMs),
                    )
                }
                "duration" -> {
                    val ms = (value * 1000).toLong()
                    _state.value = _state.value.copy(durationMs = ms)
                }
                "video-params/aspect" -> {
                    _state.value = _state.value.copy(aspect = value)
                    emitVideoParams()
                }
                // 缓存末端时间戳（秒 → 毫秒）：仅记录原值，前探量 = 该值 - 播放位置
                // （mpv 该属性是时间戳而非剩余量，见 PlayerState.bufferMs 说明）。
                "demuxer-cache-time" -> {
                    demuxerCachePositionMs = (value * 1000).toLong()
                    _state.value = _state.value.copy(
                        bufferMs = bufferLeadMs(_state.value.positionMs),
                    )
                }
            }
        }

        override fun event(eventId: Int) {
            when (eventId) {
                MPVLib.MPV_EVENT_SHUTDOWN ->
                    com.kotlin_livez.app.app.Log.w("[mpv-event] shutdown")
                MPVLib.MPV_EVENT_END_FILE ->
                    // completed 判定改走 eof-reached 属性（见 eventProperty "eof-reached"
                    // 根因说明）：此事件不区分 EOF/替换/停止/错误，不能直接当播放结束。
                    com.kotlin_livez.app.app.Log.i("[mpv-event] end-file")
                MPVLib.MPV_EVENT_START_FILE -> {
                    // playing 同步（task player-ui-polish：刷新/重开流后按钮与播放状态一致）：
                    // 旧实现只复位 completed，playing 依赖 mpv pause 属性事件——刷新时 stop()
                    // 触发 END_FILE 已把 playing 置 false，新流 start-file 后若无 pause 属性
                    // 变化事件，按钮会停在「暂停态」而实际在播放。新文件开始即视为播放中，
                    // 后续 pause 事件仍可纠正。
                    // 注意：不在此复位 width/height/aspect（task 09-12-refresh-crash）——
                    // 置 null 会让全屏 letterbox 布局瞬间回退铺满再缩回（右侧黑条闪失）；
                    // 同会话 loadfile 保留上一画尺寸，新流 video-params 变化时自然覆盖，
                    // 会话级复位由 releaseSession 的整体 state 重置承担。
                    _state.value = _state.value.copy(completed = false, playing = true)
                    _completedEvents.tryEmit(false)
                    com.kotlin_livez.app.app.Log.i("[mpv-event] start-file")
                }
                MPVLib.MPV_EVENT_FILE_LOADED ->
                    com.kotlin_livez.app.app.Log.i("[mpv-event] file-loaded")
                MPVLib.MPV_EVENT_VIDEO_RECONFIG ->
                    com.kotlin_livez.app.app.Log.i("[mpv-event] video-reconfig")
            }
        }
    }

    private val logObserver = object : MPVLib.LogObserver {
        override fun logMessage(prefix: String, level: Int, text: String) {
            _logEvents.tryEmit("$prefix: $text")
            val message = text.trim()
            // mpv 级别 30 及以下为 warn/error/fatal；Surface/MediaCodec 关键词额外保留。
            if (message.isNotEmpty() && level <= 30) {
                com.kotlin_livez.app.app.Log.w("[mpv][$prefix][$level] $message")
            } else if (message.isNotEmpty() && (
                    message.contains("surface", ignoreCase = true) ||
                        message.contains("mediacodec", ignoreCase = true) ||
                        message.contains("Opening failed", ignoreCase = true) ||
                        message.contains("Video: no video", ignoreCase = true)
                    )
            ) {
                // 高频 native 诊断仅进 Logcat，避免持续扩张应用内 debugLogs 列表。
                android.util.Log.d(TAG, "[mpv][$prefix][$level] $message")
            }
        }
    }

    fun initialize(
        context: Context,
        configDir: String,
        cacheDir: String,
        config: MpvInitConfig,
    ): Long {
        val sessionId = synchronized(lifecycleLock) {
            val id = ++nextSessionId
            val previous = activeSessionId
            activeSessionId = id
            if (previous != null) {
                com.kotlin_livez.app.app.Log.w("播放器会话被接管：old=$previous new=$id")
            }
            id
        }
        if (created) return sessionId
        created = true
        _initError.value = null
        try {
            doInitialize(context, configDir, cacheDir, config)
            com.kotlin_livez.app.app.Log.i("播放器初始化完成：session=$sessionId")
        } catch (e: Throwable) {
            // Java 层异常（如 UnsatisfiedLinkError 缺 so）→ 记录而不是让进程闪退
            created = false
            runCatching { MPVLib.removeObserver(observer) }
            runCatching { MPVLib.removeLogObserver(logObserver) }
            val message = "播放器初始化失败：${e.message ?: e.javaClass.simpleName}"
            _initError.value = message
            try {
                _errorEvents.tryEmit(message)
            } catch (_: Exception) {
            }
            com.kotlin_livez.app.app.Log.logPrint(e)
        }
        return sessionId
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
        // 不设 force-seekable（task 08-29-playback-stutter）：直播流本质不可 seek，强制可 seek
        // 会让 mpv 在缓冲恢复时对底层流发起内部 seek（日志 `Stream is not seekable`），缓存状态
        // 整体复位 100%->0% → 停播回填 1-2s + 音频 underrun（偶发卡顿根因）。原为照搬
        // media_kit 默认配置的历史遗留；Kotlin 端无 seek 功能，换线/清晰度均为 loadfile。

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
        // shutdown 退出流程；在同一页面内切线/重试时，后续 loadfile 会发到已退出的
        // core 并被静默丢弃。idle=yes 使会话在无文件时保持待命，可反复 loadfile。
        MPVLib.setOptionString("idle", "yes")
    }

    private fun observeProperties() {
        val props = arrayOf(
            "time-pos" to MPVLib.MPV_FORMAT_DOUBLE,
            "duration" to MPVLib.MPV_FORMAT_DOUBLE,
            "pause" to MPVLib.MPV_FORMAT_FLAG,
            "paused-for-cache" to MPVLib.MPV_FORMAT_FLAG,
            // 缓存前探量：部分流/节点可能不上报（unavailable），此时走无值回调按 0 处理。
            "demuxer-cache-time" to MPVLib.MPV_FORMAT_DOUBLE,
            "eof-reached" to MPVLib.MPV_FORMAT_FLAG,
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

    fun attachSurface(sessionId: Long, surface: android.view.Surface): Boolean {
        if (!isActiveSession(sessionId, "attachSurface")) return false
        if (!ensureReady("attachSurface")) return false
        if (!surface.isValid) {
            com.kotlin_livez.app.app.Log.w(
                "拒绝绑定无效 Surface：session=$sessionId surface=${System.identityHashCode(surface)}",
            )
            return false
        }
        return runCatching {
            // MPVLib 的 Surface 绑定不是 swap。若存在旧绑定，先关闭 VO 再解绑，避免
            // MediaCodec 继续持有已经销毁的旧 Surface。随后必须先 attach 新 Surface，
            // 再恢复 VO；原实现的相反顺序会让解码器在重进时仍关联旧 Surface。
            if (surfaceAttached) {
                MPVLib.setPropertyString("vo", "null")
                MPVLib.setOptionString("force-window", "no")
                MPVLib.detachSurface()
                surfaceAttached = false
            }
            MPVLib.attachSurface(surface)
            surfaceAttached = true
            MPVLib.setOptionString("force-window", "yes")
            voOption?.let { MPVLib.setPropertyString("vo", it) }
            com.kotlin_livez.app.app.Log.i(
                "Surface 已绑定：session=$sessionId surface=${System.identityHashCode(surface)}",
            )
            true
        }.onFailure { error ->
            com.kotlin_livez.app.app.Log.e("Surface 绑定失败：session=$sessionId", error)
        }.getOrDefault(false)
    }

    /**
     * 上报 SurfaceView 尺寸（对应 mpv `android-surface-size`）。
     *
     * 根因说明（真机闪退：重进直播间后全屏旋转崩溃）：原实现由 `MpvVideoView.surfaceChanged`
     * 直接裸调 `MPVLib.setPropertyString`——该入口没有 `ensureReady` 守卫。页面会话已经
     * 释放时，旋转触发的 size 变化会对已释放的 mpv 实例发起 JNI 调用，
     * native use-after-free 直接 SIGSEGV。
     * 统一收口到本方法：先过 `ensureReady`（未初始化安全返回），`runCatching` 仅兜 Java 异常。
     */
    fun setSurfaceSize(sessionId: Long, width: Int, height: Int) {
        if (!isActiveSession(sessionId, "setSurfaceSize")) return
        if (!ensureReady("setSurfaceSize")) return
        runCatching { MPVLib.setPropertyString("android-surface-size", "${width}x$height") }
    }

    /** SurfaceView 已销毁；真正释放由当前页面的 [releaseSession] 完成。 */
    fun surfaceDestroyed(sessionId: Long, surface: android.view.Surface) {
        if (!isActiveSession(sessionId, "surfaceDestroyed")) return
        com.kotlin_livez.app.app.Log.i(
            "Surface 已销毁：session=$sessionId surface=${System.identityHashCode(surface)}",
        )
        // 后台播放关键（task fullscreen-status-follow-style-background-play）：Activity 停止
        // 时 SurfaceView 的 surface 被系统销毁，mediacodec_embed 继续向失效 surface 送帧会让
        // 解码器报错并在 ~10s 后中止整个播放（切后台"声音必停"的根因）。切 vo=null 后 mpv
        // 以纯音频续播（mpv-android 同款处理）；surfaceCreated → attachSurface 会重挂新
        // surface 并恢复 vo，画面随之恢复。
        if (surfaceAttached) {
            runCatching { MPVLib.setPropertyString("vo", "null") }
                .onFailure { com.kotlin_livez.app.app.Log.e("切 vo=null 失败", it) }
            surfaceAttached = false
        }
    }

    private fun isActiveSession(sessionId: Long, op: String): Boolean {
        val active = synchronized(lifecycleLock) { activeSessionId }
        if (active == sessionId) return true
        com.kotlin_livez.app.app.Log.w("忽略过期播放器会话调用：op=$op session=$sessionId active=$active")
        return false
    }

    /** 打开媒体（对应 Dart player.open(Media)。httpHeaders 转 http-header-fields）。 */
    fun open(url: String, httpHeaders: Map<String, String> = emptyMap()) {
        if (!ensureReady("open")) return
        com.kotlin_livez.app.app.Log.i("提交 loadfile：surfaceAttached=$surfaceAttached")
        if (httpHeaders.isNotEmpty()) {
            val joined = httpHeaders.entries.joinToString(",") { "${it.key}: ${it.value}" }
            runCatching { MPVLib.setPropertyString("http-header-fields", joined) }
        }
        // 对齐 media_kit open(play: true)：mpv 的 pause 属性跨 loadfile 持久保留，
        // 暂停中刷新/切线/重试会让新流以暂停态启动，且 START_FILE 乐观置位使按钮
        // 显示"播放中"（需点两次才能恢复）。open 即视为开始播放，显式清除 pause。
        MPVLib.setPropertyBoolean("pause", false)
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

    fun releaseSession(sessionId: Long) {
        synchronized(lifecycleLock) {
            if (activeSessionId != sessionId) {
                com.kotlin_livez.app.app.Log.w(
                    "忽略过期播放器会话释放：session=$sessionId active=$activeSessionId",
                )
                return
            }
            activeSessionId = null
        }
        if (!created) return
        // 先置 false，让并发迟到调用在进入 JNI 前被 ensureReady 拒绝。
        created = false
        surfaceAttached = false
        MPVLib.removeObserver(observer)
        MPVLib.removeLogObserver(logObserver)
        runCatching { MPVLib.destroy() }
            .onFailure { com.kotlin_livez.app.app.Log.e("播放器销毁失败：session=$sessionId", it) }
        // 缓存末端时间戳随会话复位（state 整体复位后前探量自然为 0）。
        demuxerCachePositionMs = 0L
        _state.value = PlayerState()
        com.kotlin_livez.app.app.Log.i("播放器会话已释放：session=$sessionId")
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
