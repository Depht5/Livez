package com.kotlin_livez.app.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kotlin_livez.app.di.ServiceContainer
import com.kotlin_livez.app.services.MpvOptionsService
import com.kotlin_livez.app.modules.settings.other.OtherSettingsController
import com.kotlin_livez.app.ui.Router
import com.kotlin_livez.app.ui.components.SettingClickRow
import com.kotlin_livez.app.ui.components.SettingGroupCard
import com.kotlin_livez.app.ui.components.SettingSectionTitle
import com.kotlin_livez.app.ui.components.SettingSwitchRow
import com.kotlin_livez.app.ui.theme.CustomIcons

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
    // ===== 设置项状态订阅（字段名与 setter 均取自 AppSettingsController 源码）=====
    val mpvProfile by settings.mpvProfile.collectAsStateWithLifecycle()
    val hardwareDecode by settings.hardwareDecode.collectAsStateWithLifecycle()
    val playerCompatMode by settings.playerCompatMode.collectAsStateWithLifecycle()
    val videoOutputDriver by settings.videoOutputDriver.collectAsStateWithLifecycle()
    val videoHardwareDecoder by settings.videoHardwareDecoder.collectAsStateWithLifecycle()
    val audioOutputDriver by settings.audioOutputDriver.collectAsStateWithLifecycle()
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
    val contributionRankEnable by settings.contributionRankEnable.collectAsStateWithLifecycle()
    // 动态取色开关（外观分组；API < 31 无 Monet，禁用并提示）
    val isDynamic by settings.isDynamic.collectAsStateWithLifecycle()

    // 单选对话框显隐
    var showMpvProfileDialog by remember { mutableStateOf(false) }
    var showQualityLevelDialog by remember { mutableStateOf(false) }

    // 驱动编辑对话框：null = 关闭；"vo"/"hwdec"/"ao"
    var editingDriver by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding(),
    ) {
        // M3 顶栏：返回 + 标题（task m3-mine-settings）
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable { Router.back() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = CustomIcons.Back,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp),
                )
            }
            Text(
                text = "设置",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        // 滚动区（顶栏吸顶不随滚动）；底部导航栏避让 + 收尾边距，
        // 避免最后一张卡片贴到屏幕底缘被误读为"滑不到底"
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
        SettingSectionTitle("外观")
        SettingGroupCard {
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
        }

        // ===== 播放 =====
        SettingSectionTitle("播放")
        SettingGroupCard {
            SettingSwitchRow(
                title = "硬件解码",
                subtitle = "使用硬件解码加速播放",
                checked = hardwareDecode,
                onChange = { settings.setHardwareDecode(it) },
            )

            SettingClickRow(
                title = "mpv 档位",
                // 兼容模式 = mediacodec 直通，渲染管线不经过 mpv，档位无实际作用 → 灰显
                subtitle = if (playerCompatMode) {
                    "兼容模式下不可用"
                } else {
                    MpvOptionsService.profileLabels[mpvProfile] ?: mpvProfile
                },
                enabled = !playerCompatMode,
                onClick = { showMpvProfileDialog = true },
            )

            SettingClickRow(
                title = "视频输出驱动",
                subtitle = if (playerCompatMode) {
                    "兼容模式下不可用"
                } else {
                    videoOutputDriver.ifEmpty { "默认（跟随 mpv 档位：gpu）" }
                },
                enabled = !playerCompatMode,
                onClick = { editingDriver = "vo" },
            )

            SettingClickRow(
                title = "视频硬件解码器",
                subtitle = if (playerCompatMode) {
                    "兼容模式下不可用"
                } else {
                    videoHardwareDecoder.ifEmpty { "默认（auto-safe）" }
                },
                enabled = !playerCompatMode,
                onClick = { editingDriver = "hwdec" },
            )

            SettingClickRow(
                title = "音频输出驱动",
                subtitle = audioOutputDriver.ifEmpty { "默认（audiotrack,opensles）" },
                onClick = { editingDriver = "ao" },
            )

            SettingSwitchRow(
                title = "兼容模式",
                subtitle = "强制 mediacodec_embed 直通渲染（gpu 通路异常时的退路；开启后 mpv 档位渲染参数不生效）",
                checked = playerCompatMode,
                onChange = { settings.setPlayerCompatMode(it) },
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
                subtitle = "单击显示控制栏 / 双击全屏 / 左右滑动调音量亮度",
                checked = playerGestureControlEnable,
                onChange = { settings.setPlayerGestureControlEnable(it) },
            )
        }

        // ===== 弹幕 =====
        SettingSectionTitle("弹幕")
        SettingGroupCard {
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
        }

        // ===== 画质 =====
        SettingSectionTitle("画质")
        SettingGroupCard {
            SettingClickRow(
                title = "清晰度档位",
                subtitle = qualityLevelLabel(qualityLevel),
                onClick = { showQualityLevelDialog = true },
            )
        }

        // ===== 其他 =====
        SettingSectionTitle("其他")
        SettingGroupCard {
            SettingSwitchRow(
                title = "贡献榜",
                subtitle = "直播间显示贡献榜",
                checked = contributionRankEnable,
                onChange = { settings.setContributionRankEnable(it) },
            )
        }
        }
    }

    editingDriver?.let { key ->
        when (key) {
            "vo" -> SingleChoiceDialog(
                title = "视频输出驱动",
                labels = listOf("默认") +
                    OtherSettingsController.videoOutputDrivers
                        .filterKeys { it != "mediacodec_embed" }.values.toList(),
                values = listOf("") +
                    OtherSettingsController.videoOutputDrivers
                        .filterKeys { it != "mediacodec_embed" }.keys.toList(),
                selectedValue = videoOutputDriver,
                onSelect = { value ->
                    settings.setVideoOutputDriver(value)
                    editingDriver = null
                },
                onDismiss = { editingDriver = null },
            )

            "hwdec" -> SingleChoiceDialog(
                title = "视频硬件解码器",
                labels = listOf("默认") +
                    OtherSettingsController.hardwareDecoder
                        .filterKeys { it != "mediacodec" && it != "mediacodec-copy" }.values.toList(),
                values = listOf("") +
                    OtherSettingsController.hardwareDecoder
                        .filterKeys { it != "mediacodec" && it != "mediacodec-copy" }.keys.toList(),
                selectedValue = videoHardwareDecoder,
                onSelect = { value ->
                    settings.setVideoHardwareDecoder(value)
                    editingDriver = null
                },
                onDismiss = { editingDriver = null },
            )

            else -> SingleChoiceDialog(
                title = "音频输出驱动",
                labels = OtherSettingsController.audioOutputDrivers.values.toList(),
                values = OtherSettingsController.audioOutputDrivers.keys.toList(),
                selectedValue = audioOutputDriver,
                onSelect = { value ->
                    settings.setAudioOutputDriver(value)
                    editingDriver = null
                },
                onDismiss = { editingDriver = null },
            )
        }
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
            // 内容可滚动：驱动选项列表 20+ 项超出对话框高度，不滚动会裁切底部选项
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                labels.forEachIndexed { index, label ->
                    val value = values[index]
                    val selected = value == selectedValue
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            // 选中项：主题加深色圆条（药丸）+ 阴影覆盖，整行水平范围可点
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (selected) {
                                    // 半透明主题色：灰化 + 玻璃质感（task m3-mine-settings 迭代）
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                                } else {
                                    Color.Transparent
                                },
                            )
                            .clickable { onSelect(value) }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                    ) {
                        // onClick = null：点击统一由行处理（圆圈不再单独拦截手势）
                        RadioButton(selected = selected, onClick = null)
                        Text(
                            text = label,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {},
    )
}
