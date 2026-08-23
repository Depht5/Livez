package dev.livefork.app.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import dev.livefork.app.app.controller.AppSettingsController
import dev.livefork.app.di.ServiceContainer
import dev.livefork.app.modules.live_room.LiveRoomController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 直播间弹层（`⋮` 菜单 + 各设置 sheet）—— 对应 Flutter `live_room_controller.dart`
 * `showQualitySheet`/`showPlayUrlsSheet`/`showDanmuSettingsSheet`/`showLiveSettingsSheet`/
 * `showDanmuShield`/`showPlayerSettingsSheet` 的 Android 简化版。
 * 全部为 View 层弹层（项目惯例：dialogs/sheets 留 View 层）；数据与动作由
 * [LiveRoomController]/[AppSettingsController] 提供。
 */
object LiveRoomSheets {

    /* ---------- 入口：⋮ 菜单 ---------- */

    /** `⋮` 菜单：清晰度/线路/弹幕设置/直播设置/屏蔽/复制/分享/刷新。 */
    fun showMenuSheet(activity: android.app.Activity, controller: LiveRoomController) {
        val items = mutableListOf(
            "清晰度",
            "播放线路",
            "弹幕设置",
            "直播设置",
            "弹幕屏蔽",
            "复制链接",
            "复制播放直链",
            "分享",
            "刷新直播间",
        )
        AlertDialog.Builder(activity)
            .setTitle("直播间菜单")
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "清晰度" -> showQualitySheet(activity, controller)
                    "播放线路" -> showPlayUrlsSheet(activity, controller)
                    "弹幕设置" -> showDanmuSettingsSheet(activity)
                    "直播设置" -> showLiveSettingsSheet(activity, controller)
                    "弹幕屏蔽" -> showDanmuShieldSheet(activity)
                    "复制链接" -> copyToClipboard(activity, controller.copyUrl() ?: "", "已复制直播间链接")
                    "复制播放直链" -> copyPlayUrl(activity, controller)
                    "分享" -> share(activity, controller)
                    "刷新直播间" -> controller.refreshRoom()
                }
            }
            .show()
    }

    /* ---------- 清晰度 / 线路 ---------- */

    fun showQualitySheet(activity: android.app.Activity, controller: LiveRoomController) {
        val qualites = controller.qualites.value
        if (qualites.isEmpty()) {
            Router.toast("暂无清晰度信息")
            return
        }
        val names = qualites.map { it.quality }
        val checked = controller.currentQuality.coerceIn(0, qualites.size - 1)
        AlertDialog.Builder(activity)
            .setTitle("清晰度")
            .setSingleChoiceItems(names.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                if (which != controller.currentQuality) {
                    controller.currentQuality = which
                    controller.getPlayUrl()
                }
            }
            .show()
    }

    fun showPlayUrlsSheet(activity: android.app.Activity, controller: LiveRoomController) {
        val urls = controller.playUrls.value
        if (urls.isEmpty()) {
            Router.toast("暂无播放线路")
            return
        }
        val labels = urls.mapIndexed { i, _ -> "线路${i + 1}" }
        val checked = controller.currentLineIndex.coerceIn(0, urls.size - 1)
        AlertDialog.Builder(activity)
            .setTitle("播放线路")
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                if (which != controller.currentLineIndex) {
                    controller.changePlayLine(which)
                }
            }
            .show()
    }

    /* ---------- 弹幕设置 ---------- */

    /** 弹幕设置：字号/速度/行数/透明度/描边/上下边距/延迟/表情渲染（即时持久化）。 */
    fun showDanmuSettingsSheet(activity: android.app.Activity) {
        val settings = ServiceContainer.appSettings
        val dialog = BottomSheetDialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }
        root.addView(sheetTitle(activity, "弹幕设置"))

        fun row(
            label: String,
            current: Int,
            min: Int,
            max: Int,
            onChange: (Int) -> Unit,
        ) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val labelText = TextView(activity).apply {
                text = label
                textSize = 14f
                setTextColor(Color.parseColor("#333333"))
            }
            row.addView(labelText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val valueText = TextView(activity).apply {
                text = "$current"
                textSize = 13f
                setTextColor(Color.parseColor("#888888"))
            }
            row.addView(valueText, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(8) })
            root.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) })
            val bar = SeekBar(activity).apply {
                this.max = max - min
                progress = (current - min)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            val v = min + progress
                            valueText.text = "$v"
                            onChange(v)
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar) {}
                })
            }
            root.addView(bar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }

        // 字号 8–32（Float，显示为 1 位小数）
        fun floatRow(
            label: String,
            current: Float,
            min: Float,
            max: Float,
            step: Float,
            onChange: (Float) -> Unit,
        ) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val labelText = TextView(activity).apply {
                text = label
                textSize = 14f
                setTextColor(Color.parseColor("#333333"))
            }
            row.addView(labelText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val valueText = TextView(activity).apply {
                text = String.format("%.1f", current)
                textSize = 13f
                setTextColor(Color.parseColor("#888888"))
            }
            row.addView(valueText, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(8) })
            root.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) })
            val steps = ((max - min) / step).toInt()
            val bar = SeekBar(activity).apply {
                this.max = steps
                progress = ((current - min) / step).toInt().coerceIn(0, steps)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            val v = min + progress * step
                            valueText.text = String.format("%.1f", v)
                            onChange(v)
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar) {}
                })
            }
            root.addView(bar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }

        floatRow("字号", settings.danmuSize.value, 8f, 32f, 1f) { settings.setDanmuSize(it) }
        floatRow("速度", settings.danmuSpeed.value, 1f, 20f, 1f) { settings.setDanmuSpeed(it) }
        row("行数", settings.danmuLineCount.value, 1, 12) { settings.setDanmuLineCount(it) }
        floatRow("透明度", settings.danmuOpacity.value, 0.2f, 1f, 0.05f) { settings.setDanmuOpacity(it) }
        floatRow("描边", settings.danmuStrokeWidth.value, 0f, 6f, 0.5f) { settings.setDanmuStrokeWidth(it) }
        floatRow("上边距", settings.danmuTopMargin.value, 0f, 200f, 5f) { settings.setDanmuTopMargin(it) }
        floatRow("下边距", settings.danmuBottomMargin.value, 0f, 200f, 5f) { settings.setDanmuBottomMargin(it) }
        row("延迟(ms)", settings.danmuDelayMs.value.coerceIn(0, 5000), 0, 5000) {
            settings.setDanmuDelayMs(it, controllerSiteIdForDelay())
        }

        // 表情渲染开关
        val emojiRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val emojiLabel = TextView(activity).apply {
            text = "渲染表情"
            textSize = 14f
            setTextColor(Color.parseColor("#333333"))
        }
        emojiRow.addView(emojiLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val emojiSwitch = android.widget.Switch(activity).apply {
            isChecked = settings.danmuRenderEmoji.value
            setOnCheckedChangeListener { _, checked -> settings.setDanmuRenderEmoji(checked) }
        }
        emojiRow.addView(emojiSwitch, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(emojiRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(14) })

        dialog.setContentView(root)
        dialog.show()
    }

    /** 延迟按当前站点设置需要 siteId；此处返回空（全局设置），站点级延迟由设置页处理。 */
    private fun controllerSiteIdForDelay(): String? = null

    /* ---------- 直播设置 ---------- */

    /** 直播设置：自动退出开关 + 分钟数 + 刷新。 */
    fun showLiveSettingsSheet(activity: android.app.Activity, controller: LiveRoomController) {
        val settings = ServiceContainer.appSettings
        val dialog = BottomSheetDialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }
        root.addView(sheetTitle(activity, "直播设置"))

        // 自动退出开关
        val exitRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val exitLabel = TextView(activity).apply {
            text = "自动退出直播间"
            textSize = 14f
            setTextColor(Color.parseColor("#333333"))
        }
        exitRow.addView(exitLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val exitSwitch = android.widget.Switch(activity).apply {
            isChecked = controller.autoExitEnable.value
            setOnCheckedChangeListener { _, checked -> controller.setAutoExitEnabled(checked) }
        }
        exitRow.addView(exitSwitch, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(exitRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        // 退出分钟数（5–180；房间级时长，对齐 Dart AutoExitSession）
        val minuteLabel = TextView(activity).apply {
            text = "退出时长：${settings.roomAutoExitDuration.value} 分钟"
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
        }
        root.addView(minuteLabel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) })
        val minuteBar = SeekBar(activity).apply {
            max = 175
            progress = (settings.roomAutoExitDuration.value - 5).coerceIn(0, 175)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val minutes = 5 + progress
                        minuteLabel.text = "退出时长：$minutes 分钟"
                        settings.setRoomAutoExitDuration(minutes)
                        settings.setAutoExitDuration(minutes)
                        controller.setAutoExitMinutes(minutes)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }
        root.addView(minuteBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        // 刷新
        root.addView(clickRow(activity, "刷新直播间") { controller.refreshRoom() })

        dialog.setContentView(root)
        dialog.show()
    }

    /* ---------- 弹幕屏蔽 ---------- */

    /** 弹幕屏蔽：屏蔽开关 + 关键词增删 + 临时静音清空。 */
    fun showDanmuShieldSheet(activity: android.app.Activity) {
        val settings = ServiceContainer.appSettings
        val dialog = BottomSheetDialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }
        root.addView(sheetTitle(activity, "弹幕屏蔽"))

        // 屏蔽总开关
        root.addView(switchRow(activity, "启用弹幕屏蔽", settings.danmuShieldEnable.value) {
            settings.setDanmuShieldEnable(it)
        })
        // 关键词屏蔽
        root.addView(switchRow(activity, "关键词屏蔽", settings.danmuKeywordShieldEnable.value) {
            settings.setDanmuKeywordShieldEnable(it)
        })
        // 用户屏蔽
        root.addView(switchRow(activity, "用户屏蔽", settings.danmuUserShieldEnable.value) {
            settings.setDanmuUserShieldEnable(it)
        })

        // 关键词列表（现有关键词；点击删除）
        val keywordCount = TextView(activity).apply {
            text = "已屏蔽关键词 ${settings.shieldList.value.size} 个："
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
        }
        root.addView(keywordCount, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) })
        val keywordList = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        fun refreshKeywords() {
            keywordList.removeAllViews()
            for (word in settings.shieldList.value) {
                val item = TextView(activity).apply {
                    text = "✕ $word"
                    textSize = 13f
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    setOnClickListener { settings.removeShieldList(word); refreshKeywords() }
                }
                keywordList.addView(item, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
            }
        }
        refreshKeywords()
        root.addView(keywordList, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        // 新增关键词
        val input = EditText(activity).apply {
            hint = "输入要屏蔽的关键词"
            textSize = 14f
        }
        root.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })
        root.addView(clickRow(activity, "添加屏蔽词") {
            val word = input.text.toString().trim()
            if (word.isEmpty()) {
                Router.toast("请输入关键词")
            } else {
                settings.addShieldList(word)
                input.text.clear()
                refreshKeywords()
            }
        })

        dialog.setContentView(root)
        dialog.show()
    }

    /* ---------- 复制 / 分享 ---------- */

    private fun copyToClipboard(activity: android.app.Activity, text: String, toast: String) {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("livefork", text))
        Router.toast(toast)
    }

    private fun copyPlayUrl(activity: android.app.Activity, controller: LiveRoomController) {
        CoroutineScope(Dispatchers.Main).launch {
            val url = withContext(Dispatchers.IO) { controller.copyPlayUrl() }
            if (url == null) {
                Router.toast("无法复制播放直链")
            } else {
                copyToClipboard(activity, url, "已复制播放直链")
            }
        }
    }

    private fun share(activity: android.app.Activity, controller: LiveRoomController) {
        val url = controller.shareUrl()
        if (url.isNullOrEmpty()) {
            Router.toast("暂时没有可分享的链接")
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        activity.startActivity(Intent.createChooser(intent, "分享直播间"))
    }

    /* ---------- 小部件 ---------- */

    private fun sheetTitle(activity: android.app.Activity, text: String): TextView =
        TextView(activity).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.parseColor("#333333"))
        }

    private fun clickRow(activity: android.app.Activity, text: String, onClick: () -> Unit): TextView =
        TextView(activity).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.parseColor("#333333"))
            setPadding(0, dp(14), 0, dp(2))
            setOnClickListener { onClick() }
        }

    private fun switchRow(
        activity: android.app.Activity,
        label: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val labelText = TextView(activity).apply {
            text = label
            textSize = 14f
            setTextColor(Color.parseColor("#333333"))
        }
        row.addView(labelText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val switch = android.widget.Switch(activity).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
        }
        row.addView(switch, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        return row
    }
}

private fun dp(value: Int): Int =
    (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()