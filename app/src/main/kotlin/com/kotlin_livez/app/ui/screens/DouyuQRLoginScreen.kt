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
import com.kotlin_livez.app.modules.mine.account.douyu.DouyuQRLoginController
import com.kotlin_livez.app.modules.mine.account.douyu.DouyuQRStatus
import com.kotlin_livez.app.ui.Router
import com.kotlin_livez.app.ui.components.QrCodeImage
import com.kotlin_livez.app.ui.theme.CustomIcons

/**
 * 斗鱼扫码登录页 —— 对应 Dart `DouyuQRLoginPage`
 * （lib/modules/mine/account/douyu/qr_login_page.dart），注册为 "douyuqrlogin" 路由。
 *
 * 结构：标准顶栏（48dp 返回 + 居中标题）+ 状态区（转圈 / 二维码 / 失效与失败重试）
 * + 底部说明。二维码由 [QrCodeImage] 本地渲染（内容为斗鱼下发的登录 URL），
 * **不使用 WebView** —— 该 URL 是给斗鱼 APP 扫的，同机打开只会被 deeplink 顶成「网页无法打开」。
 *
 * 生命周期：控制器随页面 remember 创建，`onDispose` 停轮询并清会话 Cookie；
 * 登录成功后控制器回调 `onExit` → 出栈（对齐 Dart `Get.back()`）。
 */
@Composable
fun DouyuQRLoginScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val controller = remember {
        DouyuQRLoginController().apply {
            onToast = { Router.toast(it) }
            onExit = { onBack() }
        }
    }

    val status by controller.status.collectAsStateWithLifecycle()
    val qrContent by controller.qrContent.collectAsStateWithLifecycle()

    LaunchedEffect(controller) { controller.loadQrCode() }
    DisposableEffect(controller) {
        onDispose { controller.close() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding(),
    ) {
        // 顶栏：48dp 返回 + 居中标题（左右对称占位，与 B 站扫码页同款）
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
                text = "斗鱼账号登录",
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp),
            )
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
                DouyuQRStatus.Loading -> CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary,
                )

                DouyuQRStatus.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "二维码加载失败",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    TextButton(onClick = { controller.loadQrCode() }) {
                        Text(text = "重试", fontSize = 14.sp)
                    }
                }

                DouyuQRStatus.Expired -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "二维码已失效",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    TextButton(onClick = { controller.loadQrCode() }) {
                        Text(text = "刷新二维码", fontSize = 14.sp)
                    }
                }

                DouyuQRStatus.Unscanned, DouyuQRStatus.Scanned -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    QrCodeImage(content = qrContent, size = 220.dp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (status == DouyuQRStatus.Scanned) {
                            "已扫描，请在斗鱼 APP 内确认登录"
                        } else {
                            "请使用斗鱼 APP 扫描二维码"
                        },
                        fontSize = 14.sp,
                        color = if (status == DouyuQRStatus.Scanned) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }

        // 底部说明（文案对齐上游 qr_login_page.dart）
        Text(
            text = "请使用斗鱼 APP 扫描二维码，并在 APP 内确认登录\n" +
                "登录后 Cookie 自动续期，无需定期重新配置\n" +
                "扫码确认在斗鱼 APP 内完成，登录凭证由斗鱼下发\n" +
                "介意设备/位置信息记录的话，可改用手动粘贴 Cookie",
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .navigationBarsPadding(),
        )
    }
}
