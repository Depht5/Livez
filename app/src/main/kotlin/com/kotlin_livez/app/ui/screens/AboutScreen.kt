package com.kotlin_livez.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kotlin_livez.app.di.ServiceContainer
import com.kotlin_livez.app.services.UpdateService
import com.kotlin_livez.app.ui.Router
import com.kotlin_livez.app.ui.components.SettingActionRow
import com.kotlin_livez.app.ui.components.SettingClickRow
import com.kotlin_livez.app.ui.components.SettingGroupCard
import com.kotlin_livez.app.ui.components.SettingSectionTitle
import com.kotlin_livez.app.ui.components.SettingSwitchRow
import com.kotlin_livez.app.ui.theme.CustomIcons
import com.kotlin_livez.core.update.VersionName

/**
 * 关于页（task 10-21-settings-about-update-check）。
 *
 * 三块内容：
 * 1. 当前版本：`v<版本名>，更新日期：<仓库发布日期>` + 右侧「检查更新」；
 * 2. 自动检查更新开关（默认开，控制冷启动静默检查）；
 * 3. 更新渠道（Gitee 默认 / GitHub，底部弹层单选）。
 *
 * 反馈分工：检查到新版本 → 由根组合的 [com.kotlin_livez.app.ui.components.UpdateHost] 弹全局弹窗
 * （手动与静默检查共用同一形态）；本页只处理「已是最新」Toast 与「检查失败」弹窗。
 */
/** 开源主页地址（GitHub 仓库）：关于页「开源主页」行用系统浏览器打开。 */
private const val OPEN_SOURCE_URL = "https://github.com/Depht5/Livez"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit = { Router.back() }) {
    val settings = ServiceContainer.appSettings
    val updateService = ServiceContainer.updateService
    val channelId by settings.updateChannel.collectAsStateWithLifecycle()
    val autoCheck by settings.autoCheckUpdate.collectAsStateWithLifecycle()
    val checkState by updateService.state.collectAsStateWithLifecycle()
    val releaseDate by updateService.releaseDate.collectAsStateWithLifecycle()

    var showChannelSheet by remember { mutableStateOf(false) }
    var failureMessage by remember { mutableStateOf<String?>(null) }
    // 只有「用户点按钮」触发的检查才回吐结果；冷启动静默检查失败不打扰本页
    var awaitingManualResult by remember { mutableStateOf(false) }
    val checking = checkState is UpdateService.State.Checking

    // 进页静默补取发布日期（缓存优先，24h 节流，失败静默）
    LaunchedEffect(Unit) { updateService.prefetchReleaseDateIfNeeded() }

    LaunchedEffect(checkState) {
        if (!awaitingManualResult) return@LaunchedEffect
        when (val current = checkState) {
            is UpdateService.State.UpToDate -> {
                awaitingManualResult = false
                Router.toast("已是最新版本 ${VersionName.display(current.currentVersion)}")
            }

            is UpdateService.State.Failed -> {
                awaitingManualResult = false
                failureMessage = current.message
            }

            // 有新版本时由全局弹窗反馈，这里只清掉等待标记
            is UpdateService.State.Available -> awaitingManualResult = false
            else -> Unit
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding(),
    ) {
        // 顶栏：与设置页同一范式（48dp 返回图标 + 20sp 标题）
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable(onClick = onBack),
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
                text = "关于",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            // 按需求去掉「当前版本」分组小标题（task 10-22-mine-header-dialog-spacing）：
            // 版本卡片直接跟在顶栏下面，补 8dp 间距代替原小标题自带的 14dp 上边距。
            Spacer(Modifier.height(8.dp))
            SettingGroupCard {
                SettingActionRow(
                    title = "当前版本",
                    subtitle = versionSubtitle(releaseDate),
                    trailing = {
                        if (checking) {
                            // 检查中：按钮禁用 + 文案变「检查中」，宽度与常态按钮一致，避免行高跳动
                            TextButton(enabled = false, onClick = {}) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("检查中")
                                }
                            }
                        } else {
                            TextButton(
                                onClick = {
                                    awaitingManualResult = true
                                    updateService.checkManually()
                                },
                            ) {
                                Text("检查更新")
                            }
                        }
                    },
                )
                // 开源主页（用户要求）：点击用系统浏览器打开 GitHub 仓库
                SettingClickRow(
                    title = "开源主页",
                    subtitle = "github.com/Depht5/Livez",
                    onClick = { Router.openUrl(OPEN_SOURCE_URL) },
                )
            }

            SettingSectionTitle("更新")
            SettingGroupCard {
                SettingSwitchRow(
                    title = "自动检查更新",
                    subtitle = "启动应用时自动检查一次",
                    checked = autoCheck,
                    onChange = { settings.setAutoCheckUpdate(it) },
                )
                SettingClickRow(
                    title = "更新渠道",
                    subtitle = UpdateService.Channel.fromId(channelId).label,
                    onClick = { showChannelSheet = true },
                )
            }
        }
    }

    if (showChannelSheet) {
        ModalBottomSheet(onDismissRequest = { showChannelSheet = false }) {
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp),
            ) {
                Text(
                    text = "更新渠道",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp),
                )
                ChannelOption(
                    label = UpdateService.Channel.GITEE.label,
                    description = "国内访问较快，推荐",
                    selected = channelId == UpdateService.Channel.GITEE.id,
                    onClick = {
                        settings.setUpdateChannel(UpdateService.Channel.GITEE.id)
                        showChannelSheet = false
                    },
                )
                ChannelOption(
                    label = UpdateService.Channel.GITHUB.label,
                    description = "直连 GitHub，海外网络更稳",
                    selected = channelId == UpdateService.Channel.GITHUB.id,
                    onClick = {
                        settings.setUpdateChannel(UpdateService.Channel.GITHUB.id)
                        showChannelSheet = false
                    },
                )
            }
        }
    }

    failureMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { failureMessage = null },
            title = { Text("检查更新失败") },
            text = { Text(message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        failureMessage = null
                        awaitingManualResult = true
                        updateService.checkManually()
                    },
                ) { Text("重试") }
            },
            dismissButton = {
                TextButton(onClick = { failureMessage = null }) { Text("关闭") }
            },
        )
    }
}

/** 当前版本行副标题：`v0.2.0，更新日期：2026-10-21`（日期未知时为 `—`）。 */private fun versionSubtitle(releaseDate: String?): String =
    "${VersionName.display(com.kotlin_livez.app.BuildConfig.VERSION_NAME)}，" +
        "更新日期：${releaseDate ?: "—"}"

/** 渠道选项行（底部弹层内）：单选圈 + 名称 + 说明。 */
@Composable
private fun ChannelOption(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = label,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
