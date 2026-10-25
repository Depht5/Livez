package com.kotlin_livez.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kotlin_livez.app.models.SyncClientInfoModel
import com.kotlin_livez.app.modules.sync.local_sync.SyncClientRequest
import com.kotlin_livez.app.modules.sync.local_sync.SyncDataType
import com.kotlin_livez.app.modules.sync.local_sync.SyncDeviceController
import com.kotlin_livez.app.services.SyncClient
import com.kotlin_livez.app.services.SyncProgress
import com.kotlin_livez.app.services.SyncService
import com.kotlin_livez.app.ui.Router
import com.kotlin_livez.app.ui.components.SettingGroupCard
import com.kotlin_livez.app.ui.components.SyncDeviceRow
import com.kotlin_livez.app.ui.components.SyncProgressDialogUi
import com.kotlin_livez.app.ui.theme.CustomIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 同步设备页 —— 对应 Dart `sync_device_page.dart`，task 10-25-lan-sync-batch-ui 起重构为
 * **选择式双向同步**：设备信息卡 + 「发送配置 / 同步配置」双按钮 + 圆点多选项。
 * 先在下方勾选数据类别，再点按钮决定方向（发送到对端 / 拉取到本机，拉取为 Livez 私有
 * GET 扩展，Dart 对端不支持）。「全部数据」= 配置包 + 斗鱼续期凭据，选中后其余选项变灰。
 *
 * 与 Dart 的差异：路由只传 address/port，进页重取 `GET /info`（无状态、进程重建安全）；
 * 传输前弹「覆盖/不覆盖」确认一次应用于整批（返回键关闭视同「不覆盖」）；
 * 传输中模态进度框不可点外/返回关闭。
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

    // 多选项勾选状态；「全部数据」选中时独占（其余项置灰不可选）
    var selected by remember { mutableStateOf(setOf<SyncDataType>()) }

    // 传输状态：进度框内容；null = 无进行中的传输
    var pushProgress by remember { mutableStateOf<SyncProgress?>(null) }
    // 「覆盖/不覆盖」待确认动作与其方向（弹窗文案随方向调整）
    var overlayAction by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    var overlayIsPush by remember { mutableStateOf(true) }

    val onToast: (String) -> Unit = { message ->
        Router.toast(message)
        // 所有传输路径（成功/失败）最终都以一次 toast 收尾——以之收起进度框
        pushProgress = null
    }
    val onProgress: (String, Int, Int, String) -> Unit = { stage, current, total, message ->
        pushProgress = SyncProgress(stage, current, total, message)
    }

    /** 启动传输：空选择提示；否则弹覆盖确认（一次选择应用于整批）。 */
    fun startTransfer(isPush: Boolean) {
        if (selected.isEmpty()) {
            Router.toast("请先选择要同步的数据")
            return
        }
        overlayIsPush = isPush
        overlayAction = action@{ overlay ->
            val currentController = controller ?: return@action
            pushProgress = SyncProgress(stage = if (isPush) "发送配置" else "同步配置")
            if (isPush) {
                currentController.pushSelection(selected, overlay, onToast, onProgress)
            } else {
                currentController.pullSelection(selected, overlay, onToast, onProgress)
            }
        }
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

                    // 方向按钮：左发送（本机 → 对端）/ 右拉取（对端 → 本机）。
                    // 水平边距 12dp 对齐 SettingGroupCard 的卡片外边距（用户反馈：
                    // 按钮通栏过宽，左右边缘应与上下卡片的阴影对齐）
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = { startTransfer(isPush = true) },
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) { Text(text = "发送配置") }
                        Button(
                            onClick = { startTransfer(isPush = false) },
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) { Text(text = "同步配置") }
                    }

                    Spacer(modifier = Modifier.padding(12.dp))

                    // 同步数据多选项（圆点标识；「全部数据」选中时其余项变灰不可选）
                    SettingGroupCard {
                        SyncDataType.entries.forEach { type ->
                            val allSelected = SyncDataType.ALL in selected
                            val enabled = type == SyncDataType.ALL || !allSelected
                            SyncOptionRow(
                                title = type.label,
                                checked = type in selected,
                                enabled = enabled,
                                onToggle = {
                                    selected = if (type == SyncDataType.ALL) {
                                        // 全部数据独占：选中即替换，取消即清空
                                        if (type in selected) emptySet() else setOf(type)
                                    } else if (type in selected) {
                                        selected - type
                                    } else {
                                        selected + type
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // 覆盖确认（一次选择应用于整批；返回键/点外关闭视同「不覆盖」；文案随方向调整）
    overlayAction?.let { action ->
        AlertDialog(
            onDismissRequest = {
                overlayAction = null
                action(false)
            },
            title = { Text(text = "数据覆盖") },
            text = {
                Text(
                    text = if (overlayIsPush) {
                        "是否覆盖对方设备上的同类数据？选择“不覆盖”会合并同步。"
                    } else {
                        "是否覆盖本机上的同类数据？选择“不覆盖”会合并同步。"
                    },
                )
            },
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

    // 传输进度框（对齐 Dart SyncProgressDialog：不可点外/返回关闭）
    pushProgress?.let { progress ->
        Dialog(
            onDismissRequest = { },
            properties = DialogProperties(dismissOnClickOutside = false),
        ) {
            BackHandler { /* 传输进行中：吞掉返回键 */ }
            SyncProgressDialogUi(progress)
        }
    }
}

/**
 * 同步数据多选行：左侧圆点标识（选中 = 主题色实心圆、未选 = 空心描边圆）+ 标题。
 * [enabled] = false 时整行置灰不可点（「全部数据」选中时的其余选项）。
 */
@Composable
private fun SyncOptionRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .then(
                    if (checked) {
                        Modifier.background(contentColor, CircleShape)
                    } else {
                        Modifier.border(1.5.dp, contentColor, CircleShape)
                    },
                ),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            fontSize = 15.sp,
            color = contentColor,
        )
    }
}
