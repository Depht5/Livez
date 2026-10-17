package com.kotlin_livez.app.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kotlin_livez.app.di.ServiceContainer
import com.kotlin_livez.app.modules.mine.account.AccountController
import com.kotlin_livez.app.ui.Router
import com.kotlin_livez.app.ui.components.SettingClickRow
import com.kotlin_livez.app.ui.components.SettingGroupCard
import com.kotlin_livez.app.ui.theme.CustomIcons
import kotlinx.coroutines.launch

/**
 * 账号管理页（Compose 版）—— 对应 Dart `account_page.dart`，
 * 注册为 "account" 路由，入口为「我的 → 账号管理」。
 *
 * 版式与「我的」页一致：**圆角分组卡片 + 一平台一行**（副标题显示该平台登录态）。
 * 点击行为按"该平台配置项数量"区分：
 * - B站：弹底部小窗（ModalBottomSheet）承载多个登录方式（对应 Dart `Utils.showBottomSheet`）；
 * - 斗鱼 / 抖音：**直接弹 Cookie 输入框**——只有一个有效动作，且输入框自带「清除」按钮，
 *   再套一层小窗是多余层级（用户决策；抖音的「网页登录」「恢复默认 ttwid」两项已按此移除）。
 * 平台顺序 B站 → 斗鱼 → 虎牙 → 抖音（快手已按需求移除）。
 *
 * 对话框（粘贴 Cookie / 退出确认）仍是 AlertDialog，为 View 层职责；
 * 小窗内选中后先关闭小窗再开对话框（对齐 Dart `Get.back()` + `Get.dialog(...)`）。
 *
 * 控制器生命周期选型：路由级 remember 创建 + DisposableEffect 销毁——与现版
 * AccountActivity 字段创建 / onDestroy dispose 的语义一致。
 */
@Composable
fun AccountScreen(modifier: Modifier = Modifier) {
    // 控制器路由级生命周期：remember 创建 + DisposableEffect 销毁
    //（对齐原 Activity onDestroy 调 account.dispose()）
    val account = remember {
        AccountController(
            bilibiliAccount = ServiceContainer.biliBiliAccount,
            douyinAccount = ServiceContainer.douyinAccount,
            douyuAccount = ServiceContainer.douyuAccount,
        )
    }
    DisposableEffect(account) {
        onDispose { account.dispose() }
    }
    val coroutineScope = rememberCoroutineScope()

    // 各平台登录态订阅：作为组合依赖触发副标题重建
    //（对齐 Dart account_page 的 Obx 依赖；斗鱼/抖音以 hasCookie 为依赖键）
    val biliName by ServiceContainer.biliBiliAccount.name.collectAsStateWithLifecycle()
    val biliLogined by ServiceContainer.biliBiliAccount.logined.collectAsStateWithLifecycle()
    val douyuHasCookie by ServiceContainer.douyuAccount.hasCookie.collectAsStateWithLifecycle()
    val douyinHasCookie by ServiceContainer.douyinAccount.hasCookie.collectAsStateWithLifecycle()

    val douyuSummary = remember(douyuHasCookie) { account.getDouyuCookieSummaryText() }
    val douyinSummary = remember(douyinHasCookie) { account.getDouyinCookieSummaryText() }

    // 哔哩哔哩登录方式小窗开关（斗鱼/抖音直接弹对话框，不经小窗）
    var showBiliSheet by remember { mutableStateOf(false) }

    // 对话框显隐状态：粘贴 Cookie（B 站/斗鱼/抖音）、退出登录确认
    var showBiliCookieDialog by remember { mutableStateOf(false) }
    var showDouyuCookieDialog by remember { mutableStateOf(false) }
    var showDouyinCookieDialog by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding(),
    ) {
        // M3 顶栏：返回 + 标题（同设置页结构）
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
                text = "账号管理",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        // 与「我的」页同款圆角分组卡片；一平台一行
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            SettingGroupCard {
                SettingClickRow(
                    title = "哔哩哔哩",
                    subtitle = biliName,
                    onClick = { showBiliSheet = true },
                )
                SettingClickRow(
                    title = "斗鱼直播",
                    subtitle = douyuSummary,
                    // 斗鱼只有「配置 Cookie」一个动作：直接弹输入框（自带清除），不套小窗
                    onClick = { showDouyuCookieDialog = true },
                )
                // 虎牙无需登录（禁用行，对齐 Dart account_page 的 enabled: false 占位）
                SettingClickRow(
                    title = "虎牙直播",
                    subtitle = "无需登录",
                    onClick = {},
                    enabled = false,
                )
                SettingClickRow(
                    title = "抖音直播",
                    subtitle = douyinSummary,
                    // 同斗鱼：只有「配置 Cookie」一个动作，直接弹输入框（自带清除）
                    onClick = { showDouyinCookieDialog = true },
                )
            }
        }
    }

    // ===== 底部小窗：各平台的配置项 =====

    if (showBiliSheet) {
        AccountSheet(title = "登录哔哩哔哩", onDismiss = { showBiliSheet = false }) {
            SettingClickRow(
                title = "网页登录",
                subtitle = "填写用户名密码登录",
                onClick = {
                    showBiliSheet = false
                    Router.navigate("weblogin/${Uri.encode("bilibili")}")
                },
            )
            SettingClickRow(
                title = "扫码登录",
                subtitle = "使用哔哩哔哩 APP 扫描二维码登录",
                onClick = {
                    showBiliSheet = false
                    Router.navigate("qrlogin")
                },
            )
            SettingClickRow(
                title = "Cookie 登录",
                subtitle = "手动输入 Cookie 登录",
                onClick = {
                    showBiliSheet = false
                    showBiliCookieDialog = true
                },
            )
            if (biliLogined) {
                SettingClickRow(
                    title = "退出登录",
                    subtitle = "清除 B 站账号",
                    onClick = {
                        showBiliSheet = false
                        showLogoutConfirm = true
                    },
                )
            }
        }
    }

    // ===== 对话框 =====

    if (showBiliCookieDialog) {
        CookieInputDialog(
            title = "B 站 Cookie",
            message = "请输入 Cookie（形如 SESSDATA=...; bili_jct=...）",
            onDismiss = { showBiliCookieDialog = false },
            onConfirm = { input ->
                showBiliCookieDialog = false
                // B 站 Cookie 登录是挂起调用（setCookie + loadUserInfo），协程桥接
                coroutineScope.launch {
                    account.doBiliBiliCookieLogin(input.trim())
                    Router.toast("B 站账号登录成功")
                }
            },
        )
    }

    if (showDouyuCookieDialog) {
        CookieInputDialog(
            title = "配置斗鱼 Cookie",
            message = "匿名用户的斗鱼高画质可能约 10 分钟后断流；粘贴登录后的 " +
                "www.douyu.com 完整 Cookie 可恢复高画质长期播放。Cookie 只保存在本机。",
            initialText = account.currentDouyuCookie(),
            onDismiss = { showDouyuCookieDialog = false },
            onConfirm = { input ->
                showDouyuCookieDialog = false
                Router.toast(account.applyDouyuCookieInput(input))
            },
            onClear = if (douyuHasCookie) {
                {
                    showDouyuCookieDialog = false
                    Router.toast(account.applyDouyuCookieInput(""))
                }
            } else {
                null
            },
        )
    }

    if (showDouyinCookieDialog) {
        CookieInputDialog(
            title = "抖音 Cookie",
            message = "请输入抖音登录 Cookie (sid_guard=...)",
            onDismiss = { showDouyinCookieDialog = false },
            onConfirm = { input ->
                showDouyinCookieDialog = false
                account.applyDouyinCookieInput(input)
            },
        )
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text(text = "退出登录") },
            text = { Text(text = "确定清除 B 站账号吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutConfirm = false
                        account.logoutBilibili()
                    },
                ) {
                    // 破坏性动作统一 error 色（task 10-17；清除账号语义同「取消关注」）
                    Text(text = "退出", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) { Text(text = "取消") }
            },
        )
    }
}

/**
 * 平台配置小窗 —— 标题 + 若干配置项（复用 [SettingClickRow] 保持与列表同一行型）。
 * 对应 Dart `Utils.showBottomSheet(title: ..., child: Column(ListTile...))`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
            )
            content()
        }
    }
}

/**
 * 粘贴 Cookie 输入对话框 —— 对齐现版 showCookieDialog：
 * 标题 + 说明文案 + 多行输入框（3-6 行）+ 可选「清除」+ 保存/取消按钮。
 *
 * 多个函数参数（onDismiss/onConfirm/onClear）——**调用处一律具名传参**，
 * 不用 trailing lambda（会绑到最后一个参数，见 ui-compose.md gotcha 2）。
 */
@Composable
private fun CookieInputDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: (input: String) -> Unit,
    initialText: String = "",
    onClear: (() -> Unit)? = null,
) {
    var input by remember { mutableStateOf(initialText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column {
                Text(text = message, fontSize = 14.sp)
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = {
                        Text(text = "在此输入 Cookie", fontSize = 14.sp, maxLines = 1)
                    },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(input) }) { Text(text = "保存") }
        },
        dismissButton = {
            Row {
                if (onClear != null) {
                    TextButton(onClick = onClear) { Text(text = "清除") }
                }
                TextButton(onClick = onDismiss) { Text(text = "取消") }
            }
        },
    )
}
