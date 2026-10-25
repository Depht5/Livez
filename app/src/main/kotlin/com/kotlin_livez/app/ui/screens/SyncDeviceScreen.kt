package com.kotlin_livez.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kotlin_livez.app.models.SyncClientInfoModel
import com.kotlin_livez.app.modules.sync.local_sync.SyncClientRequest
import com.kotlin_livez.app.modules.sync.local_sync.SyncDeviceController
import com.kotlin_livez.app.services.SyncClient
import com.kotlin_livez.app.services.SyncProgress
import com.kotlin_livez.app.services.SyncService
import com.kotlin_livez.app.ui.Router
import com.kotlin_livez.app.ui.components.SettingClickRow
import com.kotlin_livez.app.ui.components.SettingGroupCard
import com.kotlin_livez.app.ui.components.SyncDeviceRow
import com.kotlin_livez.app.ui.components.SyncProgressDialogUi
import com.kotlin_livez.app.ui.theme.CustomIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 同步设备页 —— 对应 Dart `sync_device_page.dart`（task 10-24-lan-sync-ui）。
 *
 * 由 [LocalSyncScreen] 连接成功后进入：设备信息卡 + 七类数据推送入口
 * （配置包 / 关注 / 历史 / 屏蔽词 / B站 / 抖音 / 斗鱼；快手已随平台移除，无快手足）。
 *
 * 与 Dart 的差异：路由只传 address/port，进页重取 `GET /info`（无状态、进程重建安全），
 * 代价是多一次 info 请求。数据类推送前弹「覆盖/不覆盖」确认（对齐 Dart showOverlayDialog，
 * 返回键关闭视同「不覆盖」）；推送中模态进度框不可点外/返回关闭。
 */
@Composable
fun SyncDeviceScreen(
    address: String,
    port: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 进页重取 /info（loading / 失败重试两态；retryKey 触发重拉）
    var info by remember { mutableStateOf<SyncClientInfoModel?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(address, port, retryKey) {
        loadError = null
        try {
            info = withContext(Dispatchers.IO) {
                SyncClientRequest().getClientInfo(SyncClient("route", "", address, port, "android"))
            }
        } catch (e: Exception) {
            loadError = e.message ?: "连接失败"
        }
    }

    // 控制器随 info 就绪创建（client 元数据以 /info 返回为准）
    val controller = remember(info) {
        info?.let { i ->
            SyncDeviceController(SyncClient("route", i.name, address, port, i.type), i)
        }
    }
    DisposableEffect(controller) {
        // 出栈即断开连接（task 10-25-lan-sync-polish 用户决策）：通知对端移除连接提示卡
        onDispose { controller?.disconnect() }
    }

    // 对端离线（其退出连接页/关闭应用的 UDP bye 帧）：正在连接的设备离线 → 提示并自动返回
    LaunchedEffect(address) {
        SyncService.peerBye.collect { byeAddress ->
            if (byeAddress == address) {
                Router.toast("连接已中断")
                onBack()
            }
        }
    }

    // 推送状态：进度框内容；null = 无进行中的推送
    var pushProgress by remember { mutableStateOf<SyncProgress?>(null) }
    // 数据类推送的「覆盖/不覆盖」待确认动作
    var overlayAction by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }

    val onToast: (String) -> Unit = { message ->
        Router.toast(message)
        // 所有推送路径（成功/失败）最终都以一次 toast 收尾——以之收起进度框
        pushProgress = null
    }
    val onProgress: (String, Int, Int, String) -> Unit = { stage, current, total, message ->
        pushProgress = SyncProgress(stage, current, total, message)
    }

    /** 数据类推送：先确认覆盖方式再执行。 */
    fun confirmThen(action: (Boolean) -> Unit) {
        overlayAction = action
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding(),
    ) {
        // 顶栏：返回 + 居中标题「同步」（与 LocalSyncScreen 同款结构）
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable { onBack() },
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
                text = "同步",
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp),
            )
            // 右侧占位使标题居中（与返回按钮对称）
            Box(modifier = Modifier.size(48.dp))
        }

        val currentInfo = info
        val currentController = controller
        when {
            currentInfo == null || currentController == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (loadError != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "获取设备信息失败：${loadError}",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                            )
                            TextButton(onClick = { retryKey++ }) { Text(text = "重试") }
                        }
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                }
            }

            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 12.dp),
                ) {
                    // 设备信息卡（与接收端连接提示卡共用 SyncDeviceRow 样式）
                    SettingGroupCard {
                        SyncDeviceRow(
                            name = currentInfo.name,
                            type = currentInfo.type,
                            address = currentInfo.address,
                        )
                    }

                    Spacer(modifier = Modifier.padding(12.dp))

                    // 七类数据推送入口（对齐 Dart 页面行序；账号类无覆盖确认）
                    SettingGroupCard {
                        SettingClickRow(
                            title = "同步完整配置包",
                            subtitle = "设置、关注、历史和屏蔽数据一起同步",
                            onClick = {
                                confirmThen { overlay ->
                                    pushProgress = SyncProgress(stage = "同步配置包")
                                    currentController.syncProfile(overlay, onToast, onProgress)
                                }
                            },
                        )
                        SettingClickRow(
                            title = "同步关注列表",
                            subtitle = "",
                            onClick = {
                                confirmThen { overlay ->
                                    pushProgress = SyncProgress(stage = "准备同步关注")
                                    currentController.syncFollowAndTag(overlay, onToast, onProgress)
                                }
                            },
                        )
                        SettingClickRow(
                            title = "同步观看记录",
                            subtitle = "",
                            onClick = {
                                confirmThen { overlay ->
                                    pushProgress = SyncProgress(stage = "准备同步历史")
                                    currentController.syncHistory(overlay, onToast, onProgress)
                                }
                            },
                        )
                        SettingClickRow(
                            title = "同步弹幕屏蔽词",
                            subtitle = "",
                            onClick = {
                                confirmThen { overlay ->
                                    pushProgress = SyncProgress(stage = "准备同步屏蔽词")
                                    currentController.syncBlockedWord(overlay, onToast, onProgress)
                                }
                            },
                        )
                        SettingClickRow(
                            title = "同步哔哩哔哩账号",
                            subtitle = "",
                            onClick = { currentController.syncBiliAccount(onToast, onProgress) },
                        )
                        SettingClickRow(
                            title = "同步抖音账号",
                            subtitle = "给 TV 等设备同步网页登录 Cookie",
                            onClick = { currentController.syncDouyinAccount(onToast, onProgress) },
                        )
                        SettingClickRow(
                            title = "同步斗鱼账号",
                            subtitle = "给 TV 等设备同步网页登录 Cookie 与自动续期凭据",
                            onClick = { currentController.syncDouyuAccount(onToast, onProgress) },
                        )
                    }
                }
            }
        }
    }

    // 覆盖确认（对齐 Dart showOverlayDialog；返回键/点外关闭视同「不覆盖」）
    overlayAction?.let { action ->
        AlertDialog(
            onDismissRequest = {
                overlayAction = null
                action(false)
            },
            title = { Text(text = "数据覆盖") },
            text = { Text(text = "是否覆盖对方设备上的同类数据？选择“不覆盖”会合并同步。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        overlayAction = null
                        action(true)
                    },
                ) { Text(text = "覆盖") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        overlayAction = null
                        action(false)
                    },
                ) { Text(text = "不覆盖") }
            },
        )
    }

    // 发送侧进度框（对齐 Dart SyncProgressDialog：不可点外/返回关闭）
    pushProgress?.let { progress ->
        Dialog(
            onDismissRequest = { },
            properties = DialogProperties(dismissOnClickOutside = false),
        ) {
            BackHandler { /* 推送进行中：吞掉返回键 */ }
            SyncProgressDialogUi(progress)
        }
    }
}
