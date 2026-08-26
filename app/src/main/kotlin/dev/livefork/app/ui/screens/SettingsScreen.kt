package dev.livefork.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.livefork.app.di.ServiceContainer
import dev.livefork.app.services.LogCaptureService
import dev.livefork.app.ui.Router
import dev.livefork.app.ui.components.SettingClickRow
import dev.livefork.app.ui.components.SettingSectionTitle
import dev.livefork.app.ui.components.SettingSwitchRow

/**
 * 设置页（Compose 版）—— 对应 Dart `settings_page.dart`（核心播放/弹幕/画质项），
 * 替换现版 View 体系 `ui/SettingsActivity.kt`，注册为 "settings" 路由。
 *
 * 结构镜像：surfaceContainerLow 底（对应原 #F5F5F5）+ 可滚动列 + 四个分组（播放/弹幕/画质/其他），
 * 行组件复用 ui/components/SettingRows.kt（Compose 版）。
 * 全部条目直接绑定 [AppSettingsController] 的 StateFlow 与 setter；
 * 声明式订阅自动刷新行副标题，替代旧版「改档位后整页重建」的简化实现。
 * 完整设置分项（屏蔽词、快捷访问、站点排序等）后续逐步接入（对齐现版范围）。
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val settings = ServiceContainer.appSettings
    val context = LocalContext.current
    // 宿主 Activity（权限请求用；非 Activity 场景传 null 由工具函数跳过）
    val activity = context as? ComponentActivity

    // ===== 设置项状态订阅（字段名与 setter 均取自 AppSettingsController 源码）=====
    val mpvProfile by settings.mpvProfile.collectAsStateWithLifecycle()
    val hardwareDecode by settings.hardwareDecode.collectAsStateWithLifecycle()
    val playerCompatMode by settings.playerCompatMode.collectAsStateWithLifecycle()
    val customPlayerOutput by settings.customPlayerOutput.collectAsStateWithLifecycle()
    val allowBackgroundPlayback by settings.allowBackgroundPlayback.collectAsStateWithLifecycle()
    val autoFullScreen by settings.autoFullScreen.collectAsStateWithLifecycle()
    val playerGestureControlEnable by settings.playerGestureControlEnable.collectAsStateWithLifecycle()
    val danmuEnable by settings.danmuEnable.collectAsStateWithLifecycle()
    val danmuRenderEmoji by settings.danmuRenderEmoji.collectAsStateWithLifecycle()
    val danmuShieldEnable by settings.danmuShieldEnable.collectAsStateWithLifecycle()
    val danmuKeywordShieldEnable by settings.danmuKeywordShieldEnable.collectAsStateWithLifecycle()
    val danmuUserShieldEnable by settings.danmuUserShieldEnable.collectAsStateWithLifecycle()
    val shieldList by settings.shieldList.collectAsStateWithLifecycle()
    val qualityLevel by settings.qualityLevel.collectAsStateWithLifecycle()
    val logEnable by settings.logEnable.collectAsStateWithLifecycle()
    val contributionRankEnable by settings.contributionRankEnable.collectAsStateWithLifecycle()
    // 动态取色开关（外观分组；API < 31 无 Monet，禁用并提示）
    val isDynamic by settings.isDynamic.collectAsStateWithLifecycle()

    // 单选对话框显隐
    var showMpvProfileDialog by remember { mutableStateOf(false) }
    var showQualityLevelDialog by remember { mutableStateOf(false) }

    // 抓取状态非 StateFlow（进程级单例布尔），本页是唯一入口之一，
    // 用本地镜像 + 操作后递增 tick 触发重组，展示动态标题（对齐旧版 refreshSubtitleLabels 的效果）
    var logCapturingTick by remember { mutableIntStateOf(0) }
    val logCapturing = remember(logCapturingTick) { LogCaptureService.isCapturing }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        // ===== 外观（Material 3 动态取色）=====
        SettingSectionTitle("外观")

        SettingSwitchRow(
            title = "动态取色",
            subtitle = if (Build.VERSION.SDK_INT >= 31) {
                "跟随系统壁纸提取主题色；关闭使用 M3 默认色板"
            } else {
                "动态取色仅 Android 12+ 支持，当前系统使用 M3 默认色板"
            },
            checked = isDynamic,
            // Android 12 以下无 Monet API，开关禁用（主副标题灰显）
            enabled = Build.VERSION.SDK_INT >= 31,
            onChange = { settings.setIsDynamic(it) },
        )

        // ===== 播放 =====
        SettingSectionTitle("播放")

        SettingClickRow(title = "mpv 档位", subtitle = mpvProfile, onClick = {
            showMpvProfileDialog = true
        })

        SettingSwitchRow(
            title = "硬件解码",
            subtitle = "使用硬件解码加速播放",
            checked = hardwareDecode,
            onChange = { settings.setHardwareDecode(it) },
        )

        SettingSwitchRow(
            title = "兼容模式",
            subtitle = "mediacodec_embed 输出，兼容性优先",
            checked = playerCompatMode,
            onChange = { settings.setPlayerCompatMode(it) },
        )

        SettingSwitchRow(
            title = "自定义输出",
            subtitle = "自定义视频/音频输出驱动",
            checked = customPlayerOutput,
            onChange = { settings.setCustomPlayerOutput(it) },
        )

        SettingSwitchRow(
            title = "后台播放",
            subtitle = "退出直播间后继续播放",
            checked = allowBackgroundPlayback,
            onChange = { settings.setAllowBackgroundPlayback(it) },
        )

        SettingSwitchRow(
            title = "自动横屏",
            subtitle = "进入直播间自动横屏",
            checked = autoFullScreen,
            onChange = { settings.setAutoFullScreen(it) },
        )

        SettingSwitchRow(
            title = "手势控制",
            subtitle = "单击显示控制栏 / 双击播放暂停 / 左右滑动调音量亮度",
            checked = playerGestureControlEnable,
            onChange = { settings.setPlayerGestureControlEnable(it) },
        )

        // ===== 弹幕 =====
        SettingSectionTitle("弹幕")

        SettingSwitchRow(
            title = "启用弹幕",
            subtitle = "",
            checked = danmuEnable,
            onChange = { settings.setDanmuEnable(it) },
        )

        SettingSwitchRow(
            title = "渲染表情",
            subtitle = "弹幕中的表情图片",
            checked = danmuRenderEmoji,
            onChange = { settings.setDanmuRenderEmoji(it) },
        )

        SettingSwitchRow(
            title = "弹幕屏蔽",
            subtitle = "启用屏蔽过滤",
            checked = danmuShieldEnable,
            onChange = { settings.setDanmuShieldEnable(it) },
        )

        SettingSwitchRow(
            title = "关键词屏蔽",
            subtitle = "",
            checked = danmuKeywordShieldEnable,
            onChange = { settings.setDanmuKeywordShieldEnable(it) },
        )

        SettingSwitchRow(
            title = "用户屏蔽",
            subtitle = "",
            checked = danmuUserShieldEnable,
            onChange = { settings.setDanmuUserShieldEnable(it) },
        )

        SettingClickRow(
            title = "屏蔽词管理",
            subtitle = "共 ${shieldList.size} 个",
            onClick = { Router.toast("屏蔽词管理页将在后续版本接入") },
        )

        // ===== 画质 =====
        SettingSectionTitle("画质")

        SettingClickRow(
            title = "清晰度档位",
            subtitle = qualityLevelLabel(qualityLevel),
            onClick = { showQualityLevelDialog = true },
        )

        // ===== 其他 =====
        SettingSectionTitle("其他")

        SettingSwitchRow(
            title = "日志输出",
            subtitle = "",
            checked = logEnable,
            onChange = { settings.setLogEnable(it) },
        )

        SettingSwitchRow(
            title = "贡献榜",
            subtitle = "直播间显示贡献榜",
            checked = contributionRankEnable,
            onChange = { settings.setContributionRankEnable(it) },
        )

        SettingClickRow(
            title = if (logCapturing) "停止抓取日志" else "抓取日志",
            subtitle = if (logCapturing) "采集中，点击停止并导出" else "手动开关：开启后写入 logcat 文件，用于排查闪退",
            onClick = {
                if (LogCaptureService.isCapturing) {
                    LogCaptureService.stop()
                    val path = LogCaptureService.lastLogPath()
                    Router.toast("已停止，日志文件：${path ?: "未知路径"}")
                } else {
                    ensureWritePermissionIfNeeded(activity)
                    LogCaptureService.start(context)
                    val path = LogCaptureService.lastLogPath()
                    Router.toast("开始抓取：${path ?: "启动失败"}")
                }
                // 触发本页重组以刷新「抓取日志」行动态标题
                logCapturingTick++
            },
        )
    }

    if (showMpvProfileDialog) {
        SingleChoiceDialog(
            title = "mpv 档位",
            labels = listOf("流畅", "均衡", "画质"),
            values = listOf("performance", "balanced", "quality"),
            selectedValue = mpvProfile,
            onSelect = { value ->
                settings.setMpvProfile(value)
                showMpvProfileDialog = false
            },
            onDismiss = { showMpvProfileDialog = false },
        )
    }

    if (showQualityLevelDialog) {
        SingleChoiceDialog(
            title = "清晰度档位",
            labels = listOf("最低", "中间", "最高"),
            values = listOf("0", "1", "2"),
            selectedValue = qualityLevel.toString(),
            onSelect = { value ->
                settings.setQualityLevel(value.toInt())
                showQualityLevelDialog = false
            },
            onDismiss = { showQualityLevelDialog = false },
        )
    }
}

/** 清晰度档位副标题文案（对齐旧版 qualityLevelLabel：0 最低 / 2 最高 / 其余中间）。 */
private fun qualityLevelLabel(level: Int): String = when (level) {
    0 -> "最低"
    2 -> "最高"
    else -> "中间"
}

/**
 * 单选对话框 —— 对应旧版 AlertDialog.setSingleChoiceItems：
 * 选中即生效并关闭；点击遮罩/取消键关闭不改值。
 */
@Composable
private fun SingleChoiceDialog(
    title: String,
    labels: List<String>,
    values: List<String>,
    selectedValue: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column {
                labels.forEachIndexed { index, label ->
                    val value = values[index]
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        RadioButton(selected = value == selectedValue, onClick = { onSelect(value) })
                        Text(text = label, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {},
    )
}

/**
 * Android 9- 直接写共享存储需要 WRITE_EXTERNAL_STORAGE。
 * 对齐现版行为：仅发起运行时请求、不等待授权结果；activity 为空（预览等
 * 非 Activity 场景）时静默跳过。普通函数（非 @Composable），供点击回调调用——
 * Compose 禁止在回调里发起组合副作用。
 */
private fun ensureWritePermissionIfNeeded(activity: ComponentActivity?) {
    if (activity == null || Build.VERSION.SDK_INT >= 29) return
    if (ContextCompat.checkSelfPermission(
            activity, Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
            1001,
        )
    }
}
