package dev.livefork.app.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.livefork.app.di.ServiceContainer
import dev.livefork.app.modules.mine.account.AccountController
import dev.livefork.app.ui.Router
import dev.livefork.app.ui.components.SettingClickRow
import dev.livefork.app.ui.components.SettingSectionTitle
import kotlinx.coroutines.launch

/** 页面底色（对应现版 AccountActivity 根布局的 #F5F5F5，仅本页使用）。 */
private val AccountBackground = Color(0xFFF5F5F5)

/**
 * 账号管理页（Compose 版）—— 对应 Dart `account_page.dart`，
 * 替换现版 View 体系 `ui/AccountActivity.kt`，注册为 "account" 路由。
 *
 * 结构镜像（1:1 对齐现版条目与交互）：
 * - 哔哩哔哩分组：网页登录 / 扫码登录 / 粘贴 Cookie / 退出登录；
 * - 抖音分组：网页登录 / 粘贴 Cookie / 恢复默认 ttwid。
 *
 * 与现版的差异（本批任务清单要求）：「退出登录」增加确认对话框
 * （现版为直接登出，无确认）；其余对话框标题/文案/按钮逐字对齐现版。
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
        )
    }
    DisposableEffect(account) {
        onDispose { account.dispose() }
    }
    val coroutineScope = rememberCoroutineScope()

    // 对话框显隐状态：粘贴 Cookie（B 站/抖音）、退出登录确认、恢复默认 ttwid 确认
    var showBiliCookieDialog by remember { mutableStateOf(false) }
    var showDouyinCookieDialog by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var showTtwidConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AccountBackground)
            .verticalScroll(rememberScrollState()),
    ) {
        // ===== 哔哩哔哩分组 =====
        SettingSectionTitle(text = "哔哩哔哩")
        SettingClickRow(
            title = "网页登录",
            subtitle = "使用浏览器登录 B 站",
            onClick = {
                // WebView 登录路由（siteId 经 Uri.encode 编码，NavController 自动解码）
                Router.navigate("weblogin/${Uri.encode("bilibili")}")
            },
        )
        SettingClickRow(
            title = "扫码登录",
            subtitle = "使用手机扫码登录",
            onClick = { Router.navigate("qrlogin") },
        )
        SettingClickRow(
            title = "粘贴 Cookie",
            subtitle = "",
            onClick = { showBiliCookieDialog = true },
        )
        SettingClickRow(
            title = "退出登录",
            subtitle = "清除 B 站账号",
            onClick = { showLogoutConfirm = true },
        )

        // ===== 抖音分组 =====
        SettingSectionTitle(text = "抖音")
        SettingClickRow(
            title = "网页登录",
            subtitle = "在页面内完成抖音登录",
            onClick = { Router.navigate("weblogin/${Uri.encode("douyin")}") },
        )
        SettingClickRow(
            title = "粘贴 Cookie",
            subtitle = "",
            onClick = { showDouyinCookieDialog = true },
        )
        SettingClickRow(
            title = "恢复默认 ttwid",
            subtitle = "",
            onClick = { showTtwidConfirm = true },
        )
    }

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
                ) { Text(text = "退出") }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) { Text(text = "取消") }
            },
        )
    }

    if (showTtwidConfirm) {
        AlertDialog(
            onDismissRequest = { showTtwidConfirm = false },
            title = { Text(text = "恢复默认 ttwid") },
            text = { Text(text = "确定恢复默认 ttwid 并清除自定义 Cookie 吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showTtwidConfirm = false
                        account.applyDouyinCookieInput("")
                    },
                ) { Text(text = "恢复") }
            },
            dismissButton = {
                TextButton(onClick = { showTtwidConfirm = false }) { Text(text = "取消") }
            },
        )
    }
}

/**
 * 粘贴 Cookie 输入对话框 —— 对齐现版 showCookieDialog：
 * 标题 + 说明文案 + 多行输入框（hint「在此输入 Cookie」，3-6 行）+ 保存/取消按钮。
 */
@Composable
private fun CookieInputDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: (input: String) -> Unit,
) {
    var input by remember { mutableStateOf("") }

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
            TextButton(onClick = onDismiss) { Text(text = "取消") }
        },
    )
}
