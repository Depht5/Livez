package dev.livefork.app.services

import dev.livefork.app.app.Log
import dev.livefork.app.app.controller.AppSettingsController
import java.io.File

/**
 * mpv 选项计算服务 —— 对应 Dart `MpvOptionsService`
 * （lib/services/mpv_options_service.dart）。
 * 只保留 Android 分支行为；桌面/iOS 专属逻辑不移植。
 */
object MpvOptionsService {

    /** 档位标签（对应 Dart profileLabels；文案冻结）。 */
    val profileLabels: Map<String, String> = linkedMapOf(
        "performance" to "流畅",
        "balanced" to "均衡",
        "quality" to "画质",
    )

    /** 档位选项（对应 Dart desktopProfiles 的 Android 取值）。 */
    private val profiles: Map<String, Map<String, String>> = linkedMapOf(
        "performance" to linkedMapOf(
            "profile" to "fast",
            "hwdec" to "auto-safe",
            "vo" to "gpu",
            "scale" to "bilinear",
            "cscale" to "bilinear",
            "dscale" to "bilinear",
            "correct-downscaling" to "no",
            "sigmoid-upscaling" to "no",
            "deband" to "no",
        ),
        // Android 非 Windows：balanced 走 balancedDesktop
        "balancedDesktop" to linkedMapOf(
            "profile" to "gpu-hq",
            "hwdec" to "auto-safe",
            "vo" to "gpu",
            "scale" to "spline36",
            "cscale" to "spline36",
            "dscale" to "mitchell",
            "deband" to "no",
        ),
        "quality" to linkedMapOf(
            "profile" to "gpu-hq",
            "hwdec" to "auto-safe",
            "vo" to "gpu-next",
            "scale" to "ewa_lanczossharp",
            "cscale" to "ewa_lanczossoft",
            "dscale" to "mitchell",
            "correct-downscaling" to "yes",
            "sigmoid-upscaling" to "yes",
            "deband" to "yes",
        ),
    )

    /** 生效选项（对应 Dart effectiveOptions）。 */
    fun effectiveOptions(appSettings: AppSettingsController): Map<String, String> =
        effectiveOptionsWithSource(appSettings).options

    /** 生效选项 + 来源（对应 Dart effectiveOptionsWithSource）。 */
    fun effectiveOptionsWithSource(appSettings: AppSettingsController): MpvEffectiveOptions {
        val profile = appSettings.mpvProfile.value
        val profileOptions = profileOptionsForPlatform(profile)
        val options = LinkedHashMap<String, String>().apply { putAll(profileOptions) }
        val source = HashMap<String, String>()
        for (key in profileOptions.keys) source[key] = "profile:$profile"

        if (appSettings.customPlayerOutput.value) {
            val vo = appSettings.videoOutputDriver.value.trim()
            val hwdec = appSettings.videoHardwareDecoder.value.trim()
            val ao = appSettings.audioOutputDriver.value.trim()
            if (vo.isNotEmpty()) {
                options["vo"] = vo
                source["vo"] = "custom"
            }
            if (hwdec.isNotEmpty()) {
                options["hwdec"] = hwdec
                source["hwdec"] = "custom"
            }
            if (ao.isNotEmpty()) {
                options["ao"] = ao
                source["ao"] = "custom"
            }
        }

        val advancedOptions = parseOptions(appSettings.mpvAdvancedOptions.value)
        options.putAll(advancedOptions)
        for (key in advancedOptions.keys) source[key] = "advanced"

        val confOptions = parseConfFile(appSettings.importedMpvConfPath.value)
        options.putAll(confOptions)
        for (key in confOptions.keys) source[key] = "conf"

        return MpvEffectiveOptions(options, source)
    }

    /** 视频控制器配置（对应 Dart videoControllerConfiguration 的 Android 部分）。 */
    fun videoControllerConfiguration(appSettings: AppSettingsController): MpvVideoConfig {
        // 默认统一使用 mediacodec_embed / mediacodec（Android 首选软硬解通路，兼容性最好）；
        // 用户自定义输出驱动开启时才遵循档位/自定义 vo/hwdec。
        if (appSettings.customPlayerOutput.value) {
            val vo = appSettings.videoOutputDriver.value.trim()
            val hwdec = appSettings.videoHardwareDecoder.value.trim()
            if (vo.isNotEmpty() || hwdec.isNotEmpty()) {
                return MpvVideoConfig(
                    vo = vo.ifEmpty { "mediacodec_embed" },
                    hwdec = hwdec.ifEmpty { "mediacodec" },
                    enableHardwareAcceleration = appSettings.hardwareDecode.value,
                    androidAttachSurfaceAfterVideoParameters = true,
                )
            }
        }
        return MpvVideoConfig(
            vo = "mediacodec_embed",
            hwdec = "mediacodec",
            enableHardwareAcceleration = appSettings.hardwareDecode.value,
            // Fix Issue #57：安卓全屏后画面卡死 - 延迟 attach 避免 surface race condition
            androidAttachSurfaceAfterVideoParameters = true,
        )
    }

    /**
     * 计算需要应用到播放器的静态选项（对应 Dart applyToPlayer）。
     * vo/hwdec 由视频视图按 [MpvVideoConfig] 处理，此处移除。
     */
    fun applyToPlayerOptions(appSettings: AppSettingsController): Map<String, String> {
        val options = HashMap(effectiveOptions(appSettings))
        options.remove("vo")
        options.remove("hwdec")
        return options
    }

    /** 诊断摘要（对应 Dart diagnosticsSummary）。 */
    fun diagnosticsSummary(appSettings: AppSettingsController): String {
        val effective = effectiveOptionsWithSource(appSettings)
        val options = effective.options
        fun value(key: String): String {
            val optionValue = options[key]
            val src = effective.source[key]
            if (optionValue.isNullOrEmpty()) return "default"
            return if (src == null) optionValue else "$optionValue($src)"
        }

        return "profile=${appSettings.mpvProfile.value}, " +
            "hardwareDecode=${appSettings.hardwareDecode.value}, " +
            "vo=${value("vo")}, hwdec=${value("hwdec")}, " +
            "mpvOptions=${options.size}"
    }

    private fun profileOptionsForPlatform(profile: String): Map<String, String> {
        if (profile == "balanced") {
            return profiles["balancedDesktop"] ?: profiles["balanced"] ?: emptyMap()
        }
        return profiles[profile] ?: profiles["balancedDesktop"] ?: emptyMap()
    }

    /** 解析多行选项文本（对应 Dart parseOptions）。 */
    fun parseOptions(raw: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (rawLine in raw.split(Regex("\\r?\\n"))) {
            val line = stripComment(rawLine).trim()
            if (line.isEmpty()) continue
            val entry = parseLine(line) ?: continue
            result[entry.key] = entry.value
        }
        return result
    }

    /** 解析 mpv.conf 文件（对应 Dart parseConfFile）。 */
    fun parseConfFile(path: String): Map<String, String> {
        if (path.trim().isEmpty()) return emptyMap()
        return try {
            val file = File(path)
            if (!file.exists()) return emptyMap()
            parseOptions(file.readText())
        } catch (e: Exception) {
            Log.d("read mpv.conf failed: $e")
            emptyMap()
        }
    }

    private fun parseLine(line: String): Map.Entry<String, String>? {
        val normalized = if (line.startsWith("--")) line.substring(2).trimStart() else line
        val equalIndex = normalized.indexOf("=")
        if (equalIndex > 0) {
            return object : Map.Entry<String, String> {
                override val key: String = normalized.substring(0, equalIndex).trim()
                override val value: String = normalized.substring(equalIndex + 1).trim()
            }
        }
        val match = Regex("^([^\\s]+)\\s+(.+)$").find(normalized) ?: return null
        val key = match.groupValues[1].trim()
        val value = match.groupValues[2].trim()
        return object : Map.Entry<String, String> {
            override val key: String = key
            override val value: String = value
        }
    }

    private fun stripComment(line: String): String {
        val index = line.indexOf("#")
        return if (index < 0) line else line.substring(0, index)
    }
}

/** 生效选项 + 来源（对应 Dart MpvEffectiveOptions）。 */
class MpvEffectiveOptions(
    val options: Map<String, String>,
    val source: Map<String, String>,
)

/** 视频控制器配置（对应 Dart VideoControllerConfiguration 的 Android 字段）。 */
class MpvVideoConfig(
    val vo: String?,
    val hwdec: String?,
    val enableHardwareAcceleration: Boolean,
    val androidAttachSurfaceAfterVideoParameters: Boolean,
)