package dev.livefork.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.livefork.app.di.ServiceContainer
import dev.livefork.app.services.LogCaptureService
import dev.livefork.app.ui.Router

/**
 * 我的页（Compose 版）—— 对应 Dart `mine_page.dart`（账号 + 功能入口列表），
 * 替换现版 View 体系 `ui/MineView.kt`，作为 MainShell 第三个 Tab 的内容。
 *
 * 结构镜像：白底纵向列表 + 行间分隔线；前两行为账号状态行（B 站 / 抖音，
 * 副标题实时反映登录状态），其余为功能入口行（点击跳转或提示）。
 * 账号登录状态直接订阅各账号服务 StateFlow（声明式重组替代旧版手动刷新）。
 *
 * 底栏「重复点击我的 Tab」事件：Dart 端 mine 页无 kBottomNavigationBarClicked
 * 订阅方（仅 home/follow/category 控制器监听），故本页不消费该事件、无需订阅。
 */
@Composable
fun MineScreen(modifier: Modifier = Modifier) {
    // 账号登录状态订阅（对应旧版 MineView 的两个 collect 刷新）
    val biliLogined by ServiceContainer.biliBiliAccount.logined.collectAsStateWithLifecycle()
    val douyinHasCookie by ServiceContainer.douyinAccount.hasCookie.collectAsStateWithLifecycle()

    // 宿主 Activity（权限请求用；非 Activity 场景传 null 由工具函数跳过）
    val activity = LocalContext.current as? ComponentActivity

    // 「抓取日志」确认对话框显隐
    var showLogDialog by remember { mutableStateOf(false) }

    val entries = buildMineEntries(biliLogined, douyinHasCookie)

    LazyColumn(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding(),
    ) {
        itemsIndexed(entries) { index, entry ->
            MineMenuRow(
                entry = entry,
                onClick = { handleEntryClick(entry, activity) { showLogDialog = true } },
            )
            if (index < entries.lastIndex) {
                HorizontalDivider()
            }
        }
    }

    if (showLogDialog) {
        LogCaptureDialog(onDismiss = { showLogDialog = false })
    }
}

// ===== 条目模型与构建 =====

/** 我的页条目：key 用于分发点击，title 为展示标题，subtitle 仅账号行使用。 */
private data class MineEntry(
    val key: String,
    val title: String,
    val subtitle: String = "",
)

/** 构建条目清单（对齐旧版 MineView.refreshAccounts 的固定清单与副标题文案）。 */
private fun buildMineEntries(biliLogined: Boolean, douyinHasCookie: Boolean): List<MineEntry> = listOf(
    MineEntry("account", "B 站账号", if (biliLogined) "已登录" else "未登录"),
    MineEntry("account_douyin", "抖音账号", if (douyinHasCookie) "已配置 Cookie" else "未配置"),
    MineEntry("history", "观看历史"),
    MineEntry("favorite", "我的收藏"),
    MineEntry("search", "搜索"),
    MineEntry("settings", "设置"),
    MineEntry("local_sync", "局域网同步"),
    MineEntry("remote_sync", "远端房间同步"),
    MineEntry("webdav", "WebDAV 同步"),
    MineEntry("parse", "解析链接"),
    MineEntry("log", "抓取日志"),
)

/** 条目点击分发（对齐旧版 clickItem 的 when 分支；未迁移页面保持 Router 兜底路径）。 */
private fun handleEntryClick(
    entry: MineEntry,
    activity: ComponentActivity?,
    onToggleLog: () -> Unit,
) {
    when (entry.key) {
        "account", "account_douyin" -> Router.openFeature("account", "账号管理")
        "history" -> Router.openFeature("history", "观看历史")
        "favorite" -> Router.openFeature("favorite", "我的收藏")
        "search" -> Router.openFeature("search", "搜索")
        "settings" -> Router.openFeature("settings", "设置")
        "local_sync" -> Router.openFeature("local_sync", "局域网同步")
        "remote_sync" -> Router.openFeature("remote_sync", "远端房间同步")
        "webdav" -> Router.openFeature("webdav", "WebDAV 同步")
        "parse" -> Router.openFeature("parse", "解析链接")
        "log" -> toggleLogCapture(activity, onToggleLog)
        else -> Router.toast("功能开发中：${entry.title}")
    }
}

/**
 * 抓取日志开关（对齐旧版 toggleLogCapture）：
 * 采集中→停止并提示路径；未采集→先按需发起写存储权限请求（API<29），再弹确认框。
 */
private fun toggleLogCapture(activity: ComponentActivity?, onConfirmRequested: () -> Unit) {
    if (LogCaptureService.isCapturing) {
        LogCaptureService.stop()
        Router.toast("日志采集已停止：${LogCaptureService.lastLogPath() ?: "未知路径"}")
    } else {
        ensureWritePermissionIfNeeded(activity)
        onConfirmRequested()
    }
}

/**
 * Android 9- 直接写共享存储需要 WRITE_EXTERNAL_STORAGE。
 * 对齐现版行为：仅发起运行时请求、不等待授权结果（拒绝后写入失败由服务层兜底）；
 * activity 为空（预览等非 Activity 场景）时静默跳过。普通函数（非 @Composable），
 * 供点击回调调用——Compose 禁止在回调里发起组合副作用。
 */
private fun ensureWritePermissionIfNeeded(activity: ComponentActivity?) {
    if (activity == null || Build.VERSION.SDK_INT >= 29) return
    if (ContextCompat.checkSelfPermission(
            activity, Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
            1001,
        )
    }
}

/** 「抓取日志」开始确认框（文案与按钮对齐旧版 AlertDialog；取消即关闭无副作用）。 */
@Composable
private fun LogCaptureDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "抓取日志") },
        text = {
            Text(
                text = "开启后将实时写入 logcat 日志文件（/storage/emulated/0/Download/" +
                    "livefork_logs/），用于定位闪退。\n需要先进入直播间复现问题，再回来停止采集。",
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    LogCaptureService.start(context)
                    Router.toast("开始抓取：${LogCaptureService.lastLogPath() ?: "启动失败"}")
                },
            ) { Text(text = "开始") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "取消") }
        },
    )
}

/**
 * 单个入口行：surface 底、左右 16dp 上下 14dp 内边距、15sp onSurface 文字
 * （对齐旧版 RecyclerView item 样式；纯文字行本无涟漪，恢复 ripple 后不影响视觉）。
 * 账号行（带副标题）展示「标题　副标题」（全角空格分隔，对齐旧版拼接规则）。
 */
@Composable
private fun MineMenuRow(entry: MineEntry, onClick: () -> Unit) {
    val displayText =
        if (entry.subtitle.isNotEmpty()) "${entry.title}　${entry.subtitle}" else entry.title
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 14.dp),
    ) {
        Text(text = displayText, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}
