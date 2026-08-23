package dev.livefork.app.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import dev.livefork.app.app.controller.AppSettingsController
import dev.livefork.app.di.ServiceContainer
import dev.livefork.app.services.MpvOptionsService

/**
 * 设置页 —— 对应 Flutter `settings_page.dart`（核心播放/弹幕/画质项）。
 * 完整设置分项（屏蔽词、快捷访问、站点排序等）后续逐步接入。
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private val settings: AppSettingsController get() = ServiceContainer.appSettings
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(0xF5, 0xF5, 0xF5))
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(content)
        setContentView(root)

        buildPlayerSection()
        buildDanmakuSection()
        buildQualitySection()
        buildOtherSection()
    }

    private fun buildPlayerSection() {
        content.addView(SettingRows.sectionTitle(this, "播放"))

        content.addView(SettingRows.clickRow(this, "mpv 档位", settings.mpvProfile.value) {
            val labels = arrayOf("流畅", "均衡", "画质")
            val values = arrayOf("performance", "balanced", "quality")
            val current = values.indexOf(settings.mpvProfile.value).coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle("mpv 档位")
                .setSingleChoiceItems(labels, current) { dialog, which ->
                    settings.setMpvProfile(values[which])
                    dialog.dismiss()
                    refreshSubtitleLabels()
                }
                .show()
        })

        content.addView(SettingRows.switchRow(
            this, "硬件解码", "使用硬件解码加速播放",
            checked = settings.hardwareDecode.value,
            onChange = { settings.setHardwareDecode(it) },
        ))

        content.addView(SettingRows.switchRow(
            this, "兼容模式", "mediacodec_embed 输出，兼容性优先",
            checked = settings.playerCompatMode.value,
            onChange = { settings.setPlayerCompatMode(it) },
        ))

        content.addView(SettingRows.switchRow(
            this, "自定义输出", "自定义视频/音频输出驱动",
            checked = settings.customPlayerOutput.value,
            onChange = { settings.setCustomPlayerOutput(it) },
        ))

        content.addView(SettingRows.switchRow(
            this, "后台播放", "退出直播间后继续播放",
            checked = settings.allowBackgroundPlayback.value,
            onChange = { settings.setAllowBackgroundPlayback(it) },
        ))

        content.addView(SettingRows.switchRow(
            this, "自动横屏", "进入直播间自动横屏",
            checked = settings.autoFullScreen.value,
            onChange = { settings.setAutoFullScreen(it) },
        ))

        content.addView(SettingRows.switchRow(
            this, "手势控制", "单击显示控制栏 / 双击播放暂停 / 左右滑动调音量亮度",
            checked = settings.playerGestureControlEnable.value,
            onChange = { settings.setPlayerGestureControlEnable(it) },
        ))
    }

    private fun buildDanmakuSection() {
        content.addView(SettingRows.sectionTitle(this, "弹幕"))

        content.addView(SettingRows.switchRow(
            this, "启用弹幕",
            subtitle = "",
            checked = settings.danmuEnable.value,
            onChange = { settings.setDanmuEnable(it) },
        ))

        content.addView(SettingRows.switchRow(
            this, "渲染表情", "弹幕中的表情图片",
            checked = settings.danmuRenderEmoji.value,
            onChange = { settings.setDanmuRenderEmoji(it) },
        ))

        content.addView(SettingRows.switchRow(
            this, "弹幕屏蔽", "启用屏蔽过滤",
            checked = settings.danmuShieldEnable.value,
            onChange = { settings.setDanmuShieldEnable(it) },
        ))

        content.addView(SettingRows.switchRow(
            this, "关键词屏蔽",
            subtitle = "",
            checked = settings.danmuKeywordShieldEnable.value,
            onChange = { settings.setDanmuKeywordShieldEnable(it) },
        ))

        content.addView(SettingRows.switchRow(
            this, "用户屏蔽",
            subtitle = "",
            checked = settings.danmuUserShieldEnable.value,
            onChange = { settings.setDanmuUserShieldEnable(it) },
        ))

        content.addView(SettingRows.clickRow(this, "屏蔽词管理", "共 ${settings.shieldList.value.size} 个") {
            Router.toast("屏蔽词管理页将在后续版本接入")
        })
    }

    private fun buildQualitySection() {
        content.addView(SettingRows.sectionTitle(this, "画质"))

        content.addView(SettingRows.clickRow(this, "清晰度档位", qualityLevelLabel(settings.qualityLevel.value)) {
            val labels = arrayOf("最低", "中间", "最高")
            AlertDialog.Builder(this)
                .setTitle("清晰度档位")
                .setSingleChoiceItems(labels, settings.qualityLevel.value.coerceIn(0, 2)) { dialog, which ->
                    settings.setQualityLevel(which)
                    dialog.dismiss()
                    refreshSubtitleLabels()
                }
                .show()
        })
    }

    private fun buildOtherSection() {
        content.addView(SettingRows.sectionTitle(this, "其他"))

        content.addView(SettingRows.switchRow(
            this, "日志输出",
            subtitle = "",
            checked = settings.logEnable.value,
            onChange = { settings.setLogEnable(it) },
        ))

        content.addView(SettingRows.switchRow(
            this, "贡献榜", "直播间显示贡献榜",
            checked = settings.contributionRankEnable.value,
            onChange = { settings.setContributionRankEnable(it) },
        ))

        content.addView(SettingRows.clickRow(
            this,
            if (dev.livefork.app.services.LogCaptureService.isCapturing) "停止抓取日志" else "抓取日志",
            if (dev.livefork.app.services.LogCaptureService.isCapturing) "采集中，点击停止并导出" else "手动开关：开启后写入 logcat 文件，用于排查闪退",
        ) {
            if (dev.livefork.app.services.LogCaptureService.isCapturing) {
                dev.livefork.app.services.LogCaptureService.stop()
                val path = dev.livefork.app.services.LogCaptureService.lastLogPath()
                Router.toast("已停止，日志文件：${path ?: "未知路径"}")
            } else {
                ensureWritePermissionIfNeeded()
                dev.livefork.app.services.LogCaptureService.start(this)
                val path = dev.livefork.app.services.LogCaptureService.lastLogPath()
                Router.toast("开始抓取：${path ?: "启动失败"}")
            }
            refreshSubtitleLabels()
        })
    }

    /** Android 9- 直接写共享存储需要 WRITE_EXTERNAL_STORAGE。 */
    private fun ensureWritePermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 29) return
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                1001,
            )
        }
    }

    private fun qualityLevelLabel(level: Int): String = when (level) {
        0 -> "最低"
        2 -> "最高"
        else -> "中间"
    }

    /** 刷新档位相关行副标题（简化：重建页面）。 */
    private fun refreshSubtitleLabels() {
        // 简化实现：重建 UI 以反映档位/画质副标题
        content.removeAllViews()
        buildPlayerSection()
        buildDanmakuSection()
        buildQualitySection()
        buildOtherSection()
    }
}