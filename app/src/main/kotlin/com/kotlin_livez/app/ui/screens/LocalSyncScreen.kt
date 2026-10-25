package com.kotlin_livez.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.kotlin_livez.app.modules.sync.local_sync.LocalSyncController
import com.kotlin_livez.app.services.ConnectedPeer
import com.kotlin_livez.app.services.SyncService
import com.kotlin_livez.app.ui.PortraitCaptureActivity
import com.kotlin_livez.app.ui.Router
import com.kotlin_livez.app.ui.components.QrCodeImage
import com.kotlin_livez.app.ui.components.SettingClickRow
import com.kotlin_livez.app.ui.components.SettingGroupCard
import com.kotlin_livez.app.ui.components.SyncDeviceRow
import com.kotlin_livez.app.ui.theme.CustomIcons

/**
 * 局域网同步页 —— 对应 Dart `local_sync_page.dart`（task 10-24-lan-sync-ui）。
 *
 * 结构：顶栏（返回 + 居中标题 + 右侧「信息」）→ 地址输入卡（输入框 + 扫一扫 + 连接）
 * → 已发现设备卡（UDP 广播发现，可刷新）→ 底部灰字提示。
 * 「信息」弹层展示本机地址二维码（[QrCodeImage] 本地渲染）与服务状态，供对端扫码；
 * 扫码结果含多个地址（`;` 分隔）时弹选择弹层，单地址回填输入框（不自动连接，对齐 Dart）。
 *
 * 远程同步房间已按用户决策永久移除（2026-10-24）：扫码结果不再特判 6 位房间号，
 * 非地址内容统一走地址解析失败提示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalSyncScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    // 服务会话启动（task 10-25-lan-sync-polish）：先启动再建控制器，保证控制器 init 的首包
    // hello/info 不会早于 UDP 就绪（install 幂等；退出由 AppNavHost 栈监视统一 stop）
    val appContext = LocalContext.current
    val controller = remember {
        SyncService.install(appContext)
        LocalSyncController()
    }
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }
    val addressText by controller.addressText.collectAsStateWithLifecycle()
    val scanClients by controller.scanClients.collectAsStateWithLifecycle()
    // 连接提示卡数据（接收端视角）：对端 GET /info 时由 SyncService 登记
    val connectedPeers by SyncService.connectedPeers.collectAsStateWithLifecycle()

    // 退场内容冻结（第一轮反馈：对端断开时列表先随状态瞬间清空、空壳再收缩，观感是
    // 闪烁卡顿而非淡出）：AnimatedVisibility 退场期间内容仍随状态重组，须以「最后一帧
    // 非空列表」渲染，动画结束才 dispose；重进场恒用新列表
    var lastPeers by remember { mutableStateOf<List<ConnectedPeer>>(emptyList()) }
    val displayPeers = connectedPeers.ifEmpty { lastPeers }
    SideEffect {
        if (connectedPeers.isNotEmpty()) lastPeers = connectedPeers
    }

    var connecting by remember { mutableStateOf(false) }
    var connectError by remember { mutableStateOf<String?>(null) }
    var showInfo by remember { mutableStateOf(false) }
    var pickerAddresses by remember { mutableStateOf<List<String>?>(null) }

    // 对端连接时自动关闭二维码弹层（用户决策 task 10-25-lan-sync-polish）：监听已连接地址集合的
    // 新增；仅新增时关闭（TTL 过期移除不触发）
    var lastPeerAddresses by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(connectedPeers) {
        val addresses = connectedPeers.map { it.address }.toSet()
        if (showInfo && (addresses - lastPeerAddresses).isNotEmpty()) {
            showInfo = false
        }
        lastPeerAddresses = addresses
    }

    // 周期广播（3s，页面存活期间）：Wi-Fi 广播有丢包，单发发现「有概率」失败；
    // 同时发 hello（触发 Dart 对端回 info）与自身 info（直接进对端列表，双向填充）
    LaunchedEffect(Unit) {
        while (isActive) {
            SyncService.sendHello()
            SyncService.sendInfo()
            delay(3_000)
        }
    }

    // 相机权限：拒绝时 toast 引导（zxing 库不会自行申请，须调用方先拿到授权）
    val context = LocalContext.current
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        // 结果分流（对齐 Dart toScanQr；6 位房间号分支已随远程同步移除）：
        // 多地址（; 分隔）→ 选择弹层；单地址 → 回填输入框（不自动连接）
        val content = result.contents
        if (!content.isNullOrBlank()) {
            val list = LocalSyncController.splitScanAddresses(content)
            if (list.size >= 2) {
                pickerAddresses = list
            } else {
                list.firstOrNull()?.let { controller.addressText.value = it }
            }
        }
    }
    val scanOptions = remember {
        ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("对准对方设备展示的二维码")
            setBeepEnabled(false)
            setOrientationLocked(true)
            setCaptureActivity(PortraitCaptureActivity::class.java)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            scanLauncher.launch(scanOptions)
        } else {
            Router.toast("需要相机权限才能扫码，可手动输入地址连接")
        }
    }
    fun launchScan() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            scanLauncher.launch(scanOptions)
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /** 连接成功 → 进设备页；失败 → 长 toast 文案弹窗（对齐 Dart connectClient）。 */
    fun connectTo(address: String? = null) {
        connecting = true
        controller.connect(
            onSuccess = { client, _ ->
                connecting = false
                Router.navigate("syncdevice/${android.net.Uri.encode(client.address)}/${client.port}")
            },
            onError = {
                connecting = false
                connectError = it
            },
            manualAddress = address,
        )
    }
    fun connectDiscovered(client: com.kotlin_livez.app.services.SyncClient) {
        connecting = true
        controller.connectClient(
            client,
            onSuccess = { c, _ ->
                connecting = false
                Router.navigate("syncdevice/${android.net.Uri.encode(c.address)}/${c.port}")
            },
            onError = {
                connecting = false
                connectError = it
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding(),
    ) {
        // 顶栏：返回 + 居中标题 + 右侧「信息」（与 QRLoginScreen 同款 48dp 触控区结构）
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
                text = "局域网数据同步",
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp),
            )
            TextButton(onClick = { showInfo = true }) { Text(text = "信息") }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp),
        ) {
            // 地址输入卡：输入框（尾部扫一扫）+ 连接按钮
            SettingGroupCard {
                Column(modifier = Modifier.padding(12.dp)) {
                    OutlinedTextField(
                        value = addressText,
                        onValueChange = { controller.addressText.value = it },
                        singleLine = true,
                        label = { Text("客户端地址") },
                        placeholder = { Text("请输入地址或扫码自动填写") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { connectTo() }),
                        trailingIcon = {
                            TextButton(onClick = { launchScan() }) { Text("扫一扫") }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { connectTo() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(text = "连接") }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 连接提示卡（task 10-25-lan-sync-polish）：对端连接到本机时显示，
            // 样式与发送端设备卡一致（SyncDeviceRow 共用）。进出走展开/收起过渡
            // （task 10-25-lan-sync-batch-ui）：建立 = 自上向下展开淡入（下方已发现设备栏
            // 平滑下移）、断开 = 向上收起淡出（平滑上移）。内容用冻结的 [displayPeers]
            // （见其声明处说明）。
            // ⚠ 必须全限定调用：ColumnScope 内的同名扩展是禁用级（spec ui-compose 条目 15）
            androidx.compose.animation.AnimatedVisibility(
                visible = connectedPeers.isNotEmpty(),
                enter = expandVertically(
                    animationSpec = tween(300),
                    expandFrom = Alignment.Top,
                ) + fadeIn(tween(300)),
                // 淡出快于收缩（180 < 300ms）：收缩的裁剪边缘还没来得及显形、内容已先
                // 隐没，观感才是「淡出」而非「被裁走」（第一轮反馈优化）
                exit = shrinkVertically(
                    animationSpec = tween(300),
                    shrinkTowards = Alignment.Top,
                ) + fadeOut(tween(180)),
            ) {
                Column {
                    Text(
                        text = "已连接设备",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 28.dp, end = 16.dp, top = 2.dp, bottom = 4.dp),
                    )
                    SettingGroupCard {
                        displayPeers.forEach { peer ->
                            SyncDeviceRow(
                                name = peer.name,
                                type = peer.type,
                                address = peer.address,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            // 已发现设备标题行 + 刷新
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 28.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "已发现设备(${scanClients.size})",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                // 胶囊文字「刷新」（task 10-25-lan-sync-batch-ui）：样式复刻关注页右上角
                // 「刷新」（primary 底 surface 字、13sp），圆角改 CircleShape 胶囊；
                        // 纵向内边距 3dp（用户反馈两轮：6→4→3dp，观感过高）
                Text(
                    text = "刷新",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        // 先 clip 再 background/clickable（同 FollowTopBar）：防水波纹溢出为直角矩形
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .clickable { SyncService.refreshClients() }
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }

            // 已发现设备列表（UDP 广播回包；空列表显示引导文案）
            SettingGroupCard {
                if (scanClients.isEmpty()) {
                    Text(
                        text = "暂未发现设备，请确认对端已打开本应用并处于同一局域网",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                } else {
                    scanClients.forEach { client ->
                        SettingClickRow(
                            title = client.name.ifEmpty { "未知设备" },
                            subtitle = client.address,
                            onClick = { connectDiscovered(client) },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "局域网同步需要两台设备在同一 Wi-Fi/局域网；跨网络请使用 WebDAV。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .navigationBarsPadding(),
            )
        }
    }

    // 连接中：模态转圈（对齐 Dart SmartDialog.showLoading(msg: "连接中...")）
    if (connecting) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { }) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(36.dp))
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = "连接中...", fontSize = 14.sp)
            }
        }
    }

    // 连接失败：长文案弹窗（对齐 Dart Utils.showMessageDialog，selectable 不适用于 AlertDialog）
    connectError?.let { message ->
        AlertDialog(
            onDismissRequest = { connectError = null },
            title = { Text(text = "连接失败") },
            text = { Text(text = message) },
            confirmButton = {
                TextButton(onClick = { connectError = null }) { Text(text = "确定") }
            },
        )
    }

    // 本机信息弹层：二维码 + 服务状态（对齐 Dart showInfo）
    if (showInfo) {
        LocalSyncInfoSheet(onDismiss = { showInfo = false })
    }

    // 扫码结果含多个地址：选择弹层（对齐 Dart showPickerAddress）
    pickerAddresses?.let { addresses ->
        ModalBottomSheet(onDismissRequest = { pickerAddresses = null }) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Text(
                    text = "请选择地址",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
                addresses.forEach { address ->
                    SettingClickRow(
                        title = address,
                        subtitle = "",
                        onClick = {
                            pickerAddresses = null
                            controller.addressText.value = address
                        },
                    )
                }
            }
        }
    }
}

/**
 * 本机信息弹层 —— 对应 Dart `LocalSyncController.showInfo`：
 * 服务运行中展示地址二维码（供对端扫）+ 三段状态文案；点二维码关闭。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalSyncInfoSheet(onDismiss: () -> Unit) {
    val httpRunning by SyncService.httpRunning.collectAsStateWithLifecycle()
    val httpErrorMsg by SyncService.httpErrorMsg.collectAsStateWithLifecycle()
    val udpErrorMsg by SyncService.udpErrorMsg.collectAsStateWithLifecycle()
    val ipAddress by SyncService.ipAddress.collectAsStateWithLifecycle()

    val addresses = ipAddress.split(";")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { "$it:${SyncService.httpPort}" }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            if (httpRunning && addresses.isNotEmpty()) {
                // 对齐 Dart：点二维码即关闭弹层
                QrCodeImage(
                    content = addresses.joinToString(";"),
                    size = 200.dp,
                    modifier = Modifier.clickable { onDismiss() },
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
            if (httpRunning) {
                Text(
                    text = if (addresses.isEmpty()) {
                        "服务已启动，但未获取到局域网 IP，请确认已连接同一局域网"
                    } else {
                        "服务已启动：${addresses.joinToString("；")}"
                    },
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                Text(
                    text = if (httpErrorMsg.isBlank()) "HTTP服务未启动，请尝试重启应用" else httpErrorMsg,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (httpRunning && udpErrorMsg.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = udpErrorMsg,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (httpRunning && addresses.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "请使用其他客户端扫描上方二维码\n建立连接后可选择需要同步的数据",
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
