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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kotlin_livez.app.modules.mine.account.bilibili.BiliBiliQRLoginController
import com.kotlin_livez.app.modules.mine.account.bilibili.QRStatus
import com.kotlin_livez.app.ui.Router
import com.kotlin_livez.app.ui.components.QrCodeImage
import com.kotlin_livez.app.ui.theme.CustomIcons

/**
 * B 站扫码登录页（Compose 版）—— 对应 Dart `qr_login_page.dart`，
 * 注册为 "qrlogin" 路由。
 *
 * **历史缺陷修复**：本页早期用 WebView 打开 `qrcodeUrl` 兜底（当时没有二维码图片库），
 * 而该 URL 是 `account.bilibili.com/h5/...scan-web` 的 H5 扫码页 —— 它是给**另一台设备的
 * B 站 APP** 扫的，同机打开会被页面的 deeplink 跳转顶成「网页无法打开」。
 * 现改为与上游一致：把 URL 用 [QrCodeImage] 本地渲染成二维码。
 *
 * 结构：标准顶栏（48dp 返回 + 居中标题）+ 状态区（转圈 / 二维码 / 失效与失败重试）
 * + 底部说明。登录成功由控制器回调 `onExit` → 出栈（对齐 Dart `Get.back()`）。
 */
@Composable
fun QRLoginScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    // 控制器路由级生命周期：remember 创建；onDispose close() 停止轮询协程
    val controller = remember {
        BiliBiliQRLoginController().apply {
            onToast = { Router.toast(it) }
            onExit = { onBack() }
        }
    }

    val status by controller.qrStatus.collectAsStateWithLifecycle()
    val qrcodeUrl by controller.qrcodeUrl.collectAsStateWithLifecycle()

    LaunchedEffect(controller) { controller.loadQRCode() }
    DisposableEffect(controller) {
        onDispose { controller.close() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding(),
    ) {
        // 顶栏：48dp 返回按钮 + 居中标题（右侧同宽占位保持标题居中；与本 App 分类详情页同款）
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
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
                text = "B 站扫码登录",
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

        // 状态区：占满剩余高度并垂直居中
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentAlignment = Alignment.Center,
        ) {
            when (status) {
                QRStatus.loading -> CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary,
                )

                QRStatus.failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "二维码加载失败",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    TextButton(onClick = { controller.loadQRCode() }) {
                        Text(text = "重试", fontSize = 14.sp)
                    }
                }

                QRStatus.expired -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "二维码已失效",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    TextButton(onClick = { controller.loadQRCode() }) {
                        Text(text = "刷新二维码", fontSize = 14.sp)
                    }
                }

                QRStatus.unscanned, QRStatus.scanned -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    QrCodeImage(content = qrcodeUrl, size = 220.dp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (status == QRStatus.scanned) {
                            "已扫描，请在手机上确认登录"
                        } else {
                            "请使用手机 B 站扫描二维码"
                        },
                        fontSize = 14.sp,
                        color = if (status == QRStatus.scanned) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }

        // 底部说明（对齐上游 qr_login_page.dart）
        Text(
            text = "请使用哔哩哔哩手机客户端扫描二维码登录",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .navigationBarsPadding(),
        )
    }
}
