package com.kotlin_livez.app.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kotlin_livez.app.di.ServiceContainer
import com.kotlin_livez.app.services.UpdateService
import com.kotlin_livez.app.ui.Router
import java.util.Locale

/**
 * 更新相关的全局宿主（task 10-21-settings-about-update-check），挂载在 `MainActivity` 的
 * `AppTheme { }` 内、`AppNavHost(...)` 之后 —— 因此无论当前在哪一页（首页 / 直播间 / 关于页）
 * 都能弹出更新弹窗。
 *
 * 职责：
 * 1. 冷启动静默检查（开关 + 每进程一次由 [UpdateService.checkOnStartup] 自己保证）；
 * 2. 唯一的「检测到新版本」弹窗（手动检查与静默检查共用）；
 * 3. 下载进度/结果的兜底 UI：通知权限被拒时用应用内进度对话框，安装引导失败时给可操作提示；
 * 4. 首次下载前请求通知权限（Android 13+），被拒也继续下载。
 */
@Composable
fun UpdateHost() {
    val context = LocalContext.current
    val updateService = ServiceContainer.updateService
    val pending by updateService.pendingRelease.collectAsStateWithLifecycle()
    val download by updateService.download.collectAsStateWithLifecycle()
    val installHint by updateService.installHint.collectAsStateWithLifecycle()

    // 冷启动检查：服务内部保证「开关关闭不检查」「每进程只查一次」
    LaunchedEffect(Unit) { updateService.checkOnStartup() }

    // 通知是否可用（关闭通知或未授予 POST_NOTIFICATIONS 时，进度只能落在应用内对话框）
    var notificationsEnabled by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }

    var waitingPermissionRelease by remember { mutableStateOf<UpdateService.Release?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // 授权结果落定后刷新可用性：否则会同时出现通知进度与应用内进度
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val release = waitingPermissionRelease
        waitingPermissionRelease = null
        if (release != null) {
            if (!granted) Router.toast("通知权限未开启，下载进度将显示在应用内")
            updateService.downloadAndInstall(release)
        }
    }

    val startDownload: (UpdateService.Release) -> Unit = { release ->
        val needsPermission = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            waitingPermissionRelease = release
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            updateService.downloadAndInstall(release)
        }
    }

    // 1) 发现新版本：手动检查与静默检查共用的唯一弹窗
    pending?.let { release ->
        UpdateAvailableDialog(
            release = release,
            onDownload = {
                updateService.dismissPending()
                startDownload(release)
            },
            // 「忽略」只关闭弹窗：不落库、不提示、下次检查仍会提示
            onIgnore = { updateService.dismissPending() },
        )
    }

    // 2) 下载过程中的兜底 UI 与结果反馈
    when (val current = download) {
        is UpdateService.Download.Downloading -> {
            if (!notificationsEnabled) {
                DownloadProgressDialog(state = current, onCancel = updateService::cancelDownload)
            }
        }

        is UpdateService.Download.Ready -> InstallReadyDialog(
            release = current.release,
            hint = installHint,
            onInstall = updateService::installReady,
            onDismiss = updateService::dismissDownload,
        )

        is UpdateService.Download.Failed -> DownloadFailedDialog(
            state = current,
            onRetry = { current.release?.let(startDownload) },
            onOpenPage = { current.release?.let { updateService.openReleasePage(it.pageUrl) } },
            onDismiss = updateService::dismissDownload,
        )

        else -> Unit
    }
}

/**
 * 「检测到新版本」弹窗 —— 按钮顺序按需求固定为**左「下载并安装」、右「忽略」**，
 * 因此用自绘 [Dialog] 而不是 `AlertDialog`（后者的 confirm/dismiss 槽位顺序相反）。
 */
@Composable
private fun UpdateAvailableDialog(
    release: UpdateService.Release,
    onDownload: () -> Unit,
    onIgnore: () -> Unit,
) {
    Dialog(onDismissRequest = onIgnore) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "检测到新版本：${release.displayVersion}",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = release.notes.ifBlank { "该版本未提供更新说明" },
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onDownload,
                        modifier = Modifier.weight(1f),
                    ) { Text("下载并安装") }
                    TextButton(
                        onClick = onIgnore,
                        modifier = Modifier.weight(1f),
                    ) { Text("忽略") }
                }
            }
        }
    }
}

/** 通知不可用时的应用内下载进度（与通知栏进度同源，都是 DownloadState）。 */
@Composable
private fun DownloadProgressDialog(
    state: UpdateService.Download.Downloading,
    onCancel: () -> Unit,
) {
    Dialog(onDismissRequest = { }) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "正在下载 ${state.release.displayVersion}",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(16.dp))
                if (state.percent >= 0) {
                    LinearProgressIndicator(
                        progress = { state.percent / 100f },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (state.total > 0) {
                        "${state.percent}%  ${formatBytes(state.received)} / ${formatBytes(state.total)}"
                    } else {
                        formatBytes(state.received)
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.align(Alignment.End),
                ) { Text("取消") }
            }
        }
    }
}

/** 下载完成：正常情况下服务已自动调起安装器；这里同时是「未知来源」未授权时的重试入口。 */
@Composable
private fun InstallReadyDialog(
    release: UpdateService.Release,
    hint: String?,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${release.displayVersion} 已下载") },
        text = { Text(hint ?: "安装包校验通过，点击「安装」调起系统安装器") },
        confirmButton = { TextButton(onClick = onInstall) { Text("安装") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** 下载失败：给出原因与两个可操作出口（重试 / 打开下载页）。 */
@Composable
private fun DownloadFailedDialog(
    state: UpdateService.Download.Failed,
    onRetry: () -> Unit,
    onOpenPage: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("下载失败") },
        text = { Text(state.message) },
        confirmButton = {
            Row {
                if (state.release != null) {
                    TextButton(onClick = onOpenPage) { Text("打开下载页") }
                    TextButton(onClick = onRetry) { Text("重试") }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
