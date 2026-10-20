package com.kotlin_livez.app.services

import com.kotlin_livez.app.app.Log
import com.kotlin_livez.app.app.controller.AppSettingsController
import java.io.File

/**
 * mpv 选项计算服务 —— 对应 Dart `MpvOptionsService`
 * （lib/services/mpv_options_service.dart）。
 * 只保留 Android 分支行为；桌面/iOS 专属逻辑不移植。
 */
object MpvOptionsService {

    /** 档位标签（对应 Dart profileLabels；三档：低功耗 / 均衡 / 画质）。 */
    val profileLabels: Map<String, String> = linkedMapOf(
        "performance" to "低功耗",
        "balanced" to "均衡",
        "quality" to "画质",
    )

    /** 档位说明文案（设置页单选弹窗副标题，用户核定文案）。 */
    val profileDescriptions: Map<String, String> = linkedMapOf(
        "performance" to "mediacodec_embed + mediacodec 硬件零拷贝直通，绕过 GPU 着色，功耗与发热最低",
        "balanced" to "vo=gpu + fast 轻量 GPU 渲染，支持软解自动兜底，兼顾流畅度与兼容性",
        "quality" to "vo=gpu + gpu-hq 高阶着色器缩放与去色带，画面更锐利但功耗较高",
    )

    /** 档位行副标题：档位名 + 核心渲染通道。 */
    fun profileSubtitle(profile: String): String = when (profile) {
        "performance" -> "低功耗（mediacodec_embed + mediacodec）"
        "balanced" -> "均衡（vo=gpu + fast）"
        "quality" -> "画质（vo=gpu + gpu-hq）"
        else -> profileLabels[profile] ?: profile
    }

    /** 档位选项（Android 三档；vo 与 hwdec 完全由档位决定）。 */
    private val profiles: Map<String, Map<String, String>> = linkedMapOf(
        // 低功耗：MediaCodec 零拷贝直通渲染，绕过 GPU 着色（档位的缩放参数不参与）
        "performance" to linkedMapOf(
            "vo" to "mediacodec_embed",
            "hwdec" to "mediacodec",
        ),
        // 均衡（默认）：轻量 GPU 渲染，软解自动兜底，兼顾流畅与兼容
        "balanced" to linkedMapOf(
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
        // 画质：gpu-hq 高阶着色器缩放与去色带，画面更锐利但功耗较高
        "quality" to linkedMapOf(
            "profile" to "gpu-hq",
            "hwdec" to "auto-safe",
            "vo" to "gpu",
            "scale" to "spline36",
            "cscale" to "spline36",
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
        val profileOptions = profileOptions(profile)
        val options = LinkedHashMap<String, String>().apply { putAll(profileOptions) }
        val source = HashMap<String, String>()
        for (key in profileOptions.keys) source[key] = "profile:$profile"

        // vo / hwdec 完全由档位表决定（「视频输出驱动」「视频硬件解码器」「兼容模式」已移除）；
        // 仅保留音频输出驱动对 ao 的覆盖。
        val ao = appSettings.audioOutputDriver.value.trim()
        if (ao.isNotEmpty()) {
            options["ao"] = ao
            source["ao"] = "custom"
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
        // vo / hwdec 直接由当前档位表决定（「兼容模式」「视频输出驱动」
        // 「视频硬件解码器」已移除，不再有覆盖分支）。
        val profileOptions = profileOptions(appSettings.mpvProfile.value)
        val vo = profileOptions["vo"] ?: "gpu"
        val hwdec = profileOptions["hwdec"] ?: "auto-safe"
        return MpvVideoConfig(
            vo = vo,
            hwdec = hwdec,
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
            "vo=${value("vo")}, hwdec=${value("hwdec")}, " +
            "mpvOptions=${options.size}"
    }

    /**
     * 档位参数表（Android 三档）；未知档位回退默认「均衡」。
     *
     * `internal` 仅为单元测试直接校验三档 `vo`/`hwdec` 契约留出只读入口，
     * 生产代码请走 [effectiveOptionsWithSource] / [videoControllerConfiguration]。
     */
    internal fun profileOptions(profile: String): Map<String, String> =
        profiles[profile] ?: profiles["balanced"] ?: emptyMap()

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
    val androidAttachSurfaceAfterVideoParameters: Boolean,
)