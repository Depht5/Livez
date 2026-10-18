package com.kotlin_livez.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import com.kotlin_livez.app.app.Sites
import com.kotlin_livez.app.app.Utils
import com.kotlin_livez.app.di.ServiceContainer
import com.kotlin_livez.app.models.History
import com.kotlin_livez.app.modules.mine.history.HistoryController
import com.kotlin_livez.app.ui.Router
import com.kotlin_livez.app.ui.components.EmptyHint
import com.kotlin_livez.app.ui.components.NetImage
import com.kotlin_livez.app.ui.theme.CustomIcons
import kotlinx.coroutines.launch

/**
 * 观看历史页（Compose 版）—— 对应 Dart `history_page.dart`，
 * 替换现版 View 体系 `ui/HistoryActivity.kt`，注册为 "history" 路由。
 *
 * 结构镜像：surface 底标题栏（48dp 返回按钮 + 「观看历史」18sp + error 底「清空」按钮）+
 * 记录列表（44dp 圆形头像 + 主播名 15sp onSurface 单行 + 副标题「站点 · yyyy-MM-dd HH:mm」
 * 12sp onSurfaceVariant + 行间分隔线）。
 * 点击进直播间（Router.openLiveRoom 保持）；清空需确认对话框（取消无副作用）。
 *
 * 与现版的差异（implement.md 本批要求新增）：长按单条记录弹确认框删除
 * （HistoryController.removeItem 既有能力，现版 View 未暴露入口）。
 *
 * 空态（task 10-17-ui-material3-details-and-redesign 新增）：列表为空且已加载完成
 * （`pageEmpty`，即非加载态）时居中提示「暂无观看记录」（原实现为空白列表）。
 * 下拉刷新仍无（现版即无，保持对齐）。
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
    // 空态判据（与 PagedRoomGrid 的空态判据同源）：BaseController 只在「第 1 页加载完成
    // 且结果为空」时置 pageEmpty，故进页首帧（此时尚未完成加载）不会闪现空态提示。
    val pageEmpty by controller.pageEmpty.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    // 清空确认 / 单条删除确认对话框状态
    var showClearDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<History?>(null) }

    // 首次进入加载（对齐原 Activity onCreate 的 controller.loadData()）
    LaunchedEffect(controller) {
        controller.loadData()
    }

    // 根背景用 surface（task 10-17-ui-material3-details-and-redesign）：
    // 原 surfaceContainerLow 会在状态栏延伸区露出灰色断层（与顶栏白条不一致）。
    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
        HistoryTitleBar(onClear = { showClearDialog = true })

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    // 底部手势导航条安全区（与设置页/账号页同款做法）
                    .navigationBarsPadding(),
            ) {
                itemsIndexed(
                    items = list,
                    // 稳定唯一 key：History.id 即 Room 表主键（`HistoryEntity` 的 @PrimaryKey），
                    // 语义为 "siteId_roomId"（见 `DbModels.History` / `DBEntities.HistoryEntity`），
                    // 写入方 LiveRoomController.addHistory 与导入方 BulkDataImportService 都保证
                    // roomId 非空后再落库 → 该 List 内 id 必不重复。
                    key = { _, item -> item.id },
                ) { index, item ->
                    // item 根包一层 Column 以把重排动画作用在**整个条目**（行 + 其下分隔线）上：
                    // 直接给行挂 animateItem 而让分隔线留在槽内，长按删除某条后行与线会分离动画。
                    // 本页在 **SitePagerTabs 页外**，故允许加 animateItem（页内网格不加，见
                    // ui-compose.md 条目 27 的 C2 结论）。
                    Column(modifier = Modifier.fillMaxWidth().animateItem()) {
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

            // 空态：列表为空且已确认无数据（pageEmpty，即「非加载态」）时居中提示
            // （task 10-17；对齐仓库既有空态风格）
            if (list.isEmpty() && pageEmpty) {
                EmptyHint(text = "暂无观看记录")
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
                    // 破坏性动作统一 error 色（task 10-17，与「取消关注」同口径）
                ) { Text(text = "清空", color = MaterialTheme.colorScheme.error) }
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
                    // 破坏性动作统一 error 色（task 10-17）
                ) { Text(text = "删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(text = "取消") }
            },
        )
    }
}

/**
 * 标题栏：surface 底、上下 4dp、右 16dp；48dp 返回按钮 + 标题 18sp onSurface
 * 占满剩余宽度 + error 底「清空」按钮（task 10-17：顶栏补齐标准返回按钮）。
 */
@Composable
private fun HistoryTitleBar(onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(top = 4.dp, end = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 标准 48dp 返回按钮（与本 App 设置页/账号页同款写法）
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
 * 单条记录行（task 10-17-ui-material3-details-and-redesign 重做信息层）：
 * 左侧主播圆形头像 [History.face]（44dp）+ 中间两行——第一行主播名 15sp onSurface，
 * 第二行「站点名 · 格式化后的更新时间」12sp onSurfaceVariant；点击进直播间、长按删除。
 *
 * 修复点：原实现把 `userName` 显示了两次（正文一次、副标题里又拼了一次）；
 * 副标题改为「站点 · yyyy-MM-dd HH:mm」（[Utils.formatDateTime]，全仓共享格式化出口）。
 * 站点名取值照搬本文件既有写法：`Sites.allSites[item.siteId]?.name ?: item.siteId`。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryItemRow(
    item: History,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val site = Sites.allSites[item.siteId]
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NetImage(
            url = item.face,
            modifier = Modifier.size(44.dp),
            isRound = true,
        )
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = item.userName,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${site?.name ?: item.siteId} · ${Utils.formatDateTime(item.updateTime)}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
