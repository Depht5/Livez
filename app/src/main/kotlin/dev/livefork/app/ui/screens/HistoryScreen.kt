package dev.livefork.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.livefork.app.app.Sites
import dev.livefork.app.di.ServiceContainer
import dev.livefork.app.models.History
import dev.livefork.app.modules.mine.history.HistoryController
import dev.livefork.app.ui.Router
import kotlinx.coroutines.launch

/**
 * 观看历史页（Compose 版）—— 对应 Dart `history_page.dart`，
 * 替换现版 View 体系 `ui/HistoryActivity.kt`，注册为 "history" 路由。
 *
 * 结构镜像：surface 底标题栏（「观看历史」18sp + error 底「清空」按钮）+ 记录列表
 * （标题 15sp onSurface 单行 + 副标题「站点 · 主播名」12sp onSurfaceVariant + 行间分隔线）。
 * 点击进直播间（Router.openLiveRoom 保持）；清空需确认对话框（取消无副作用）。
 *
 * 与现版的差异（implement.md 本批要求新增）：长按单条记录弹确认框删除
 * （HistoryController.removeItem 既有能力，现版 View 未暴露入口）。
 *
 * 空态与下拉刷新：现版两者皆无（空数据即空白列表），保持对齐不加 UI。
 */
@Composable
fun HistoryScreen(modifier: Modifier = Modifier) {
    // 控制器路由级生命周期：remember 创建 + DisposableEffect 销毁
    // （对齐原 Activity onDestroy 的 scope cancel；BasePageController.dispose 即此语义）
    val controller = remember { HistoryController(ServiceContainer.dbService) }
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }

    val list by controller.list.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    // 清空确认 / 单条删除确认对话框状态
    var showClearDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<History?>(null) }

    // 首次进入加载（对齐原 Activity onCreate 的 controller.loadData()）
    LaunchedEffect(controller) {
        controller.loadData()
    }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow).statusBarsPadding()) {
        HistoryTitleBar(onClear = { showClearDialog = true })

        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        ) {
            itemsIndexed(list) { index, item ->
                HistoryItemRow(
                    item = item,
                    onClick = {
                        val site = Sites.allSites[item.siteId]
                        if (site != null) {
                            Router.openLiveRoom(site, item.roomId)
                        } else {
                            Router.toast("无法打开历史记录：站点不存在")
                        }
                    },
                    onLongClick = { pendingDelete = item },
                )
                if (index < list.lastIndex) {
                    HorizontalDivider()
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(text = "清空观看历史") },
            text = { Text(text = "确定要清空所有观看历史吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        coroutineScope.launch { controller.clean() }
                    },
                ) { Text(text = "清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text(text = "取消") }
            },
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(text = "删除记录") },
            text = { Text(text = "确定要删除「${target.userName}」的观看记录吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        coroutineScope.launch { controller.removeItem(target) }
                    },
                ) { Text(text = "删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(text = "取消") }
            },
        )
    }
}

/** 标题栏：surface 底、左右 16dp 上下 12dp；标题 18sp onSurface 占满剩余宽度 + error 底「清空」按钮。 */
@Composable
private fun HistoryTitleBar(onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "观看历史",
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = onClear,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Text(text = "清空", fontSize = 13.sp)
        }
    }
}

/**
 * 单条记录行：标题 15sp #222 单行省略 + 副标题「站点 · 主播名」12sp #888；
 * 点击进直播间、长按删除。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryItemRow(
    item: History,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val site = Sites.allSites[item.siteId]
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
    ) {
        Text(
            text = item.userName,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(text = "${site?.name ?: item.siteId} · ${item.userName}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
