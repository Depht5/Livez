package com.kotlin_livez.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.kotlin_livez.app.BuildConfig
import com.kotlin_livez.app.R
import com.kotlin_livez.app.services.LogCaptureService
import com.kotlin_livez.app.ui.Router
import com.kotlin_livez.app.ui.components.SettingGroupCard
import com.kotlin_livez.app.ui.theme.LocalAppDarkTheme
import com.kotlin_livez.core.update.VersionName

/**
 * 我的页（Compose 版）—— 对应 Dart `mine_page.dart`（账号 + 功能入口列表），
 * 替换现版 View 体系 `ui/MineView.kt`，作为 MainShell 第三个 Tab 的内容。
 *
 * 结构镜像：白底纵向列表 + 行间分隔线；账号入口已合并为单行「账号管理」
 * （B 站 / 斗鱼 / 虎牙 / 抖音 的明细与状态移入账号管理页），其余为功能入口行（点击跳转或提示）。
 * 行样式由 [MineMenuRow] 统一渲染，副标题可留空。
 *
 * 底栏「重复点击我的 Tab」事件：Dart 端 mine 页无 kBottomNavigationBarClicked
 * 订阅方（仅 home/follow/category 控制器监听），故本页不消费该事件、无需订阅。
 */
@Composable
fun MineScreen(modifier: Modifier = Modifier) {
    // 宿主 Activity（权限请求用；非 Activity 场景传 null 由工具函数跳过）
    val activity = LocalContext.current as? ComponentActivity

    // 「抓取日志」确认对话框显隐
    var showLogDialog by remember { mutableStateOf(false) }

    val entries = buildMineEntries()

    // M3 单卡片列表（task m3-mine-settings 迭代：与设置页圆角卡片同风格，不分组/无顶栏）
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        // 顶部信息区（task 10-22-mine-header-dialog-spacing）：应用图标 + 应用名（包名）+ 版本名。
        // 随内容一起滚动（与布局预览 mine_screen_preview.html 一致），不吸顶。
        // 应用名显示**包名**：debug 变体带 `.debug` 后缀，能一眼分清装的是哪个包
        //（应用标签在 debug 下是「Livez D」，与正式版混在一起时反而更难分辨）。
        // 版本名与关于页同一口径（VersionName.display），bump 后自动跟随。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 图标阴影沿用直播间/分类卡片的策略：浅色画 2dp 阴影，深色/纯黑不画
            //（深色底上阴影几乎不可见，却仍要多录一层阴影绘制指令）。
            val iconShadow = if (LocalAppDarkTheme.current) {
                Modifier
            } else {
                Modifier.shadow(elevation = 2.dp, shape = RoundedCornerShape(16.dp))
            }
            Image(
                painter = painterResource(R.mipmap.ic_launcher),
                contentDescription = null,
                modifier = Modifier
                    .size(72.dp)
                    .then(iconShadow),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                // 应用名取资源 string（release「Livez」/ debug「Livez D」），不再显示包名
                text = stringResource(R.string.app_name),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = VersionName.display(BuildConfig.VERSION_NAME),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        SettingGroupCard {
            entries.forEach { entry ->
                MineMenuRow(
                    entry = entry,
                    onClick = { handleEntryClick(entry, activity) { showLogDialog = true } },
                )
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

/** 构建条目清单（对齐旧版 MineView.refreshAccounts 的固定清单；账号入口已合并为「账号管理」）。 */
private fun buildMineEntries(): List<MineEntry> = listOf(
    MineEntry("account", "账号管理"),
    MineEntry("history", "观看历史"),
    MineEntry("settings", "设置"),
    MineEntry("local_sync", "局域网同步"),
    MineEntry("webdav", "WebDAV 同步"),
    MineEntry("parse", "解析链接"),
    MineEntry("log", "抓取日志"),
    // 关于（task 10-21-settings-about-update-check）：按需求放在「抓取日志」下方，
    // 无副标题；版本信息与更新入口都在二级页内
    MineEntry("about", "关于"),
)

/** 条目点击分发（对齐旧版 clickItem 的 when 分支；未迁移页面保持 Router 兜底路径）。 */
private fun handleEntryClick(
    entry: MineEntry,
    activity: ComponentActivity?,
    onToggleLog: () -> Unit,
) {
    when (entry.key) {
        "account" -> Router.openFeature("account", "账号管理")
        "history" -> Router.openFeature("history", "观看历史")
        "settings" -> Router.openFeature("settings", "设置")
        "local_sync" -> Router.openFeature("local_sync", "局域网同步")
        "webdav" -> Router.openFeature("webdav", "WebDAV 同步")
        "parse" -> Router.openFeature("parse", "解析链接")
        "log" -> toggleLogCapture(activity, onToggleLog)
        "about" -> Router.navigate("about")
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
                    "livez_logs/），用于定位闪退。\n需要先进入直播间复现问题，再回来停止采集。",
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
    // M3 行样式（task m3-mine-settings）：标题 + 独立副标题（账号状态）两段式 + 右侧 chevron；
    // 保持平铺列表（用户决策：不做标题与分组）。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = entry.title, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
            if (entry.subtitle.isNotEmpty()) {
                Text(
                    text = entry.subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Text(text = "›", fontSize = 18.sp, color = MaterialTheme.colorScheme.outline)
    }
}
