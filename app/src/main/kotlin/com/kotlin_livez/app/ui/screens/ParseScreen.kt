package com.kotlin_livez.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kotlin_livez.app.app.Site
import com.kotlin_livez.app.modules.mine.parse.ParseController
import com.kotlin_livez.app.ui.Router
import com.kotlin_livez.app.ui.theme.CustomIcons
import com.kotlin_livez.core.model.LivePlayQuality
import com.kotlin_livez.core.model.LiveRoomDetail
import kotlinx.coroutines.launch

/**
 * 链接解析页（对应 Dart `lib/modules/mine/parse/parse_page.dart`，按产品要求重设计）。
 *
 * 与原项目的差异：
 * - 原项目是「直播间跳转」「获取直链」两张卡片、两个输入框；这里合并成**一个输入框 + 两个按钮**，
 *   同一段链接可以二选一使用，少一次复制粘贴；
 * - 快手已整体移除，支持链接说明里不再列出快手。
 *
 * 布局：加深主题色（`surfaceContainerHigh`，与设置页分组卡片同规格）圆角矩形里放输入框与按钮，
 * 下方是支持链接说明；解析结果通过 Toast 提示，直链走「清晰度 → 线路 → 复制」两段弹窗。
 */
@Composable
fun ParseScreen(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val controller = remember { ParseController() }
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current

    var input by remember { mutableStateOf("") }
    // 进行中的动作：null 空闲；"jump" 跳转中；"direct" 直链中
    var busyAction by remember { mutableStateOf<String?>(null) }
    var qualityPicker by remember { mutableStateOf<QualityPicker?>(null) }
    var linePicker by remember { mutableStateOf<LinePicker?>(null) }

    fun copyToClipboard(url: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("直播直链", url))
    }

    fun jump() {
        if (busyAction != null) return
        keyboard?.hide()
        scope.launch {
            busyAction = "jump"
            when (val result = controller.parse(input)) {
                is ParseController.ParseResult.Success ->
                    Router.openLiveRoom(result.site, result.roomId)
                is ParseController.ParseResult.Failure -> Router.toast(result.message)
            }
            busyAction = null
        }
    }

    fun fetchDirectLink() {
        if (busyAction != null) return
        keyboard?.hide()
        scope.launch {
            busyAction = "direct"
            when (val result = controller.parse(input)) {
                is ParseController.ParseResult.Failure -> Router.toast(result.message)
                is ParseController.ParseResult.Success -> {
                    val loaded = controller.loadDetailAndQualities(result.roomId, result.site)
                    if (loaded == null || loaded.second.isEmpty()) {
                        Router.toast("读取直链失败,无法读取清晰度")
                    } else {
                        qualityPicker = QualityPicker(
                            site = result.site,
                            roomId = result.roomId,
                            detail = loaded.first,
                            qualities = loaded.second,
                        )
                    }
                }
            }
            busyAction = null
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding(),
    ) {
        // 顶栏：返回 + 标题（与设置/关于页同规格）
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
                text = "解析链接",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            // 输入区：加深主题色圆角矩形（与设置页分组卡片同规格）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            text = "输入支持的直播链接",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    minLines = 3,
                    maxLines = 3,
                    shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        cursorColor = MaterialTheme.colorScheme.primary,
                    ),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = { jump() },
                        modifier = Modifier.weight(1f).height(44.dp),
                        enabled = busyAction == null,
                    ) {
                        if (busyAction == "jump") {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text(text = "链接跳转", fontSize = 14.sp)
                        }
                    }
                    FilledTonalButton(
                        onClick = { fetchDirectLink() },
                        modifier = Modifier.weight(1f).height(44.dp),
                        enabled = busyAction == null,
                    ) {
                        if (busyAction == "direct") {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        } else {
                            Text(text = "获取直链", fontSize = 14.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // 支持链接说明（原项目文案删去快手段落；可长按选择复制）
            SelectionContainer {
                Text(
                    text = SUPPORT_LINKS,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    qualityPicker?.let { picker ->
        AlertDialog(
            onDismissRequest = { qualityPicker = null },
            title = { Text("选择清晰度") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    picker.qualities.forEach { quality ->
                        Text(
                            text = quality.quality,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    qualityPicker = null
                                    scope.launch {
                                        busyAction = "direct"
                                        val playUrl = controller.loadPlayUrls(
                                            roomId = picker.roomId,
                                            site = picker.site,
                                            detail = picker.detail,
                                            quality = quality,
                                        )
                                        busyAction = null
                                        if (playUrl == null || playUrl.urls.isEmpty()) {
                                            Router.toast("读取直链失败")
                                        } else {
                                            linePicker = LinePicker(playUrl.urls)
                                        }
                                    }
                                }
                                .padding(vertical = 12.dp),
                            textAlign = TextAlign.Center,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            },
            confirmButton = {},
        )
    }

    linePicker?.let { picker ->
        AlertDialog(
            onDismissRequest = { linePicker = null },
            title = { Text("选择线路") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    picker.urls.forEachIndexed { index, url ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    copyToClipboard(url)
                                    linePicker = null
                                    Router.toast("已复制直链")
                                }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(
                                text = "线路${index + 1}",
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = url,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
        )
    }
}

/** 清晰度弹窗状态（选中清晰度后要回查站点，故连同 site/roomId/detail 一起带走）。 */
private data class QualityPicker(
    val site: Site,
    val roomId: String,
    val detail: LiveRoomDetail,
    val qualities: List<LivePlayQuality>,
)

/** 线路弹窗状态。 */
private data class LinePicker(val urls: List<String>)

/** 支持链接说明：原项目 `parse_page.dart` 文案，删除快手平台部分。 */
private const val SUPPORT_LINKS = """支持以下类型的链接解析：
哔哩哔哩：
https://live.bilibili.com/xxxxx
https://b23.tv/xxxxx
虎牙直播：
https://www.huya.com/xxxxx
斗鱼直播：
https://www.douyu.com/xxxxx
https://www.douyu.com/topic/xxx?rid=xxx
抖音直播：
https://v.douyin.com/xxxxx
https://live.douyin.com/xxxxx
https://www.douyin.com/live/xxxxx
https://www.douyin.com/follow/live/xxxxx
https://webcast.amemv.com/webcast/reflow/xxxxx"""
