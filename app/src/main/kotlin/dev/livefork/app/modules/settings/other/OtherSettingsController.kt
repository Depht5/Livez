package dev.livefork.app.modules.settings.other

import dev.livefork.app.app.Log
import dev.livefork.app.app.controller.AppSettingsController
import dev.livefork.app.app.controller.BaseController
import dev.livefork.app.services.LocalStorageService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 日志文件信息 —— 对应 Dart `LogFileModel`（other_settings_controller.dart 尾部）。
 */
data class LogFileModel(
    val name: String,
    val path: String,
    val timeMillis: Long,
    val size: Long,
)

/**
 * 其他设置控制器 —— 对应 Dart `OtherSettingsController`
 * （lib/modules/settings/other/other_settings_controller.dart）。
 * 文件分享/保存/选择与 mpv conf 导入走 Android SAF（UI 层），此处提供数据与设置写入；
 * 配置包导入导出依赖 ProfileBackupService（未移植时留 TODO）。
 */
class OtherSettingsController(
    private val appSettings: AppSettingsController,
    private val localStorage: LocalStorageService,
) : BaseController() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 日志文件列表（对应 Dart logFiles） */
    val logFiles = MutableStateFlow<List<LogFileModel>>(emptyList())
    val logFilesState: StateFlow<List<LogFileModel>> = logFiles

    /** 视频输出驱动选项（冻结，对应 Dart videoOutputDrivers） */
    val videoOutputDrivers: Map<String, String> = linkedMapOf(
        "gpu" to "gpu",
        "gpu-next" to "gpu-next",
        "xv" to "xv (X11 only)",
        "x11" to "x11 (X11 only)",
        "vdpau" to "vdpau (X11 only)",
        "direct3d" to "direct3d (Windows only)",
        "sdl" to "sdl",
        "dmabuf-wayland" to "dmabuf-wayland",
        "vaapi" to "vaapi",
        "null" to "null",
        "libmpv" to "libmpv",
        "mediacodec_embed" to "mediacodec_embed (Android only)",
    )

    /** 音频输出驱动选项（冻结，对应 Dart audioOutputDrivers） */
    val audioOutputDrivers: Map<String, String> = linkedMapOf(
        "null" to "null (No audio output)",
        "pulse" to "pulse (Linux, uses PulseAudio)",
        "pipewire" to "pipewire (Linux, via Pulse compatibility or native)",
        "alsa" to "alsa (Linux only)",
        "oss" to "oss (Linux only)",
        "jack" to "jack (Linux/macOS, low-latency audio)",
        "directsound" to "directsound (Windows only)",
        "wasapi" to "wasapi (Windows only)",
        "winmm" to "winmm (Windows only, legacy API)",
        "audiounit" to "audiounit (iOS only)",
        "coreaudio" to "coreaudio (macOS only)",
        "opensles" to "opensles (Android only)",
        "audiotrack" to "audiotrack (Android only)",
        "aaudio" to "aaudio (Android only)",
        "pcm" to "pcm (Cross-platform)",
        "sdl" to "sdl (Cross-platform, via SDL library)",
        "openal" to "openal (Cross-platform, OpenAL backend)",
        "libao" to "libao (Cross-platform, uses libao library)",
        "auto" to "auto (Not available)",
    )

    /** 硬解选项（冻结，对应 Dart hardwareDecoder） */
    val hardwareDecoder: Map<String, String> = linkedMapOf(
        "no" to "no",
        "auto" to "auto",
        "auto-safe" to "auto-safe",
        "yes" to "yes",
        "auto-copy" to "auto-copy",
        "d3d11va" to "d3d11va",
        "d3d11va-copy" to "d3d11va-copy",
        "videotoolbox" to "videotoolbox",
        "videotoolbox-copy" to "videotoolbox-copy",
        "vaapi" to "vaapi",
        "vaapi-copy" to "vaapi-copy",
        "nvdec" to "nvdec",
        "nvdec-copy" to "nvdec-copy",
        "drm" to "drm",
        "drm-copy" to "drm-copy",
        "vulkan" to "vulkan",
        "vulkan-copy" to "vulkan-copy",
        "dxva2" to "dxva2",
        "dxva2-copy" to "dxva2-copy",
        "vdpau" to "vdpau",
        "vdpau-copy" to "vdpau-copy",
        "mediacodec" to "mediacodec",
        "mediacodec-copy" to "mediacodec-copy",
        "cuda" to "cuda",
        "cuda-copy" to "cuda-copy",
        "crystalhd" to "crystalhd",
        "rkmpp" to "rkmpp",
    )

    /** 读取日志目录（filesDir/log；对应 Dart loadLogFiles）。 */
    fun loadLogFiles() {
        scope.launch {
            val logDir = withContext(Dispatchers.IO) {
                File(dev.livefork.app.di.ServiceContainer.context().filesDir, "log")
            }
            val files = withContext(Dispatchers.IO) {
                if (!logDir.exists() || !logDir.isDirectory) {
                    emptyList()
                } else {
                    logDir.listFiles()?.toList() ?: emptyList()
                }
            }
            val models = files.mapNotNull { file ->
                if (!file.isFile) return@mapNotNull null
                LogFileModel(
                    name = file.name,
                    path = file.absolutePath,
                    timeMillis = file.lastModified(),
                    size = file.length(),
                )
            }.sortedByDescending { it.timeMillis }
            logFiles.value = models
        }
    }

    /** 开启/关闭日志（对应 Dart setLogEnable）。 */
    fun setLogEnable(e: Boolean) {
        appSettings.setLogEnable(e)
        if (e) {
            // 100ms 后刷新日志文件列表（镜像 Dart 延迟）
            scope.launch {
                delay(100)
                loadLogFiles()
            }
        }
    }

    /** 清空日志目录（对应 Dart cleanLog）。 */
    fun cleanLog() {
        if (appSettings.logEnable.value) {
            Log.d("Toast: 请先关闭日志记录")
            return
        }
        scope.launch {
            withContext(Dispatchers.IO) {
                val logDir = File(dev.livefork.app.di.ServiceContainer.context().filesDir, "log")
                if (logDir.exists()) logDir.deleteRecursively()
            }
            loadLogFiles()
        }
    }

    /** 重置所有配置为默认值（对应 Dart resetDefaultConfig；确认框由 UI 层弹出）。 */
    fun resetDefaultConfig() {
        localStorage.clearAllSettings()
        localStorage.clearShield()
        appSettings.reloadFromStorage()
        // LiveSubtitleService.stop() 待该服务移植
        Log.d("Toast: 重置成功,重启生效")
    }

    /* ---------- mpv 高级选项 / 导入配置（对应 Dart 相关方法） ---------- */

    fun setMpvAdvancedOptions(value: String) {
        appSettings.setMpvAdvancedOptions(value)
        Log.d("Toast: 已保存，重开直播间后生效")
    }

    fun setImportedMpvConfPath(path: String) {
        appSettings.setImportedMpvConfPath(path)
        Log.d("Toast: 已导入 mpv.conf，重开直播间后生效")
    }

    fun clearImportedMpvConf() {
        appSettings.setImportedMpvConfPath("")
        Log.d("Toast: 已清除导入配置")
    }

    /*
     * TODO(ProfileBackupService)：exportConfig / importConfig 依赖 ProfileBackupService
     * （exportProfileJson / importProfileJson / isSupportedProfileMap），该服务移植后补全；
     * 文件选择与进度对话框走 Android SAF 与 UI 层。
     */

    fun dispose() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}