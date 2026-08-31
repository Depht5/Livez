package com.kotlin_livez.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kotlin_livez.app.di.ServiceContainer
import com.kotlin_livez.app.modules.live_room.LiveRoomController
import com.kotlin_livez.app.ui.Router
import com.kotlin_livez.app.ui.theme.CustomIcons
import kotlin.math.roundToInt

/** ⋮ 菜单动作（对齐旧 LiveRoomSheets.showMenuSheet 的 items 顺序与文案）。 */
enum class RoomMenuAction(val label: String) {
    Quality("清晰度"),
    Line("播放线路"),
    DanmuSettings("弹幕设置"),
    LiveSettings("直播设置"),
    DanmuShield("弹幕屏蔽"),
    CopyLink("复制链接"),
    CopyPlayUrl("复制播放直链"),
    Share("分享"),
    Refresh("刷新直播间"),
}

/**
 * 全屏 ⋮ 菜单（task 08-31-live-room-menu-brightness-fix：去掉清晰度/播放线路/弹幕设置——
 * 全屏控制层底栏已有这三个入口，菜单不再重复）。
 */
val ROOM_MENU_ITEMS_FULLSCREEN = listOf(
    RoomMenuAction.LiveSettings,
    RoomMenuAction.DanmuShield,
    RoomMenuAction.CopyLink,
    RoomMenuAction.CopyPlayUrl,
    RoomMenuAction.Share,
    RoomMenuAction.Refresh,
)

/**
 * 非全屏 ⋮ 菜单（同一任务：去掉刷新直播间/清晰度/弹幕设置——竖屏底栏刷新按钮与
 * 控制层清晰度/弹幕设置入口已覆盖，菜单不再重复；播放线路竖屏无入口，保留）。
 */
val ROOM_MENU_ITEMS_PORTRAIT = listOf(
    RoomMenuAction.Line,
    RoomMenuAction.LiveSettings,
    RoomMenuAction.DanmuShield,
    RoomMenuAction.CopyLink,
    RoomMenuAction.CopyPlayUrl,
    RoomMenuAction.Share,
)

/**
 * 直播间弹层集合（Compose 版）—— 替换旧 View 体系 `ui/LiveRoomSheets.kt`，
 * 对应 Flutter `showQualitySheet`/`showPlayUrlsSheet`/`showDanmuSettingsSheet`/
 * `showLiveSettingsSheet`/`showDanmuShield` 的 Android 简化版。
 *
 * **文件位置说明**：五种弹层 + ⋮ 菜单约 300 行，独立成文件避免 LiveRoomScreen.kt
 * 膨胀；沿用「弹层只发动作与持久化，不做控制器逻辑」惯例（dialogs stay in the View layer）。
 * 字段/setter/文案逐项对照旧 LiveRoomSheets 1:1 搬运。
 */

/** 弹层种类（LiveRoomScreen 用单个状态驱动显示哪个弹层）。 */
enum class RoomSheet { None, Menu, Quality, Line, DanmuSettings, LiveSettings, DanmuShield }

/* ---------- ⋮ 菜单 ---------- */

/**
 * `⋮` 菜单：条目点击经 [onAction] 上抛（action 对应列表项），动作由宿主分发。
 * 菜单项列表由宿主按全屏/非全屏传入（[ROOM_MENU_ITEMS_FULLSCREEN] / [ROOM_MENU_ITEMS_PORTRAIT]）。
 */
@Composable
fun RoomMenuDialog(
    items: List<RoomMenuAction>,
    onAction: (RoomMenuAction) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "直播间菜单", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface) },
        confirmButton = {},
        text = {
            Column {
                items.forEach { action ->
                    Text(
                        text = action.label,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAction(action) }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        },
    )
}

/* ---------- 清晰度 / 线路 ---------- */

/**
 * 清晰度选择：单选高亮当前档（等价旧 setSingleChoiceItems）；
 * 空列表由宿主拦截（toast「暂无清晰度信息」后不打开）。
 */
@Composable
fun QualityDialog(
    names: List<String>,
    checkedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "清晰度", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface) },
        confirmButton = {},
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                names.forEachIndexed { index, name ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(index) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = index == checkedIndex, onClick = { onSelect(index) })
                        Text(text = name, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        },
    )
}

/**
 * 播放线路选择：标签「线路1..N」（等价旧 showPlayUrlsSheet）；
 * 空列表由宿主拦截（toast「暂无播放线路」后不打开）。
 */
@Composable
fun LineDialog(
    count: Int,
    checkedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "播放线路", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface) },
        confirmButton = {},
        text = {
            Column {
                repeat(count) { index ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(index) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = index == checkedIndex, onClick = { onSelect(index) })
                        Text(text = "线路${index + 1}", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        },
    )
}

/* ---------- 弹幕设置 ---------- */

/** 字体粗细 1–9 档标签（对齐 Dart danmu_settings_page 的 ["极细".."极粗"]）。 */
private val FONT_WEIGHT_LABELS = listOf("极细", "很细", "细", "正常", "小粗", "偏粗", "粗", "很粗", "极粗")

/**
 * 弹幕设置（竖屏底部弹窗）：壳层 + 标题 + 共用行内容，即时持久化。
 * 全屏时由 LiveRoomScreen 改挂 [DanmuSettingsSidePanel]（右侧小窗，对齐原项目
 * showRightDialog），两者共用 [DanmuSettingsRows]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DanmuSettingsSheet(
    onDismiss: () -> Unit,
    viewportHeight: Float = 0f,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        ) {
            SheetTitle(text = "弹幕设置")
            DanmuSettingsRows(viewportHeight)
        }
    }
}

/**
 * 弹幕设置行内容：「弹幕显示」8 项（默认开启/显示表情/显示区域/显示几行/不透明度/字号/粗细/速度），
 * 即时持久化。对齐 Dart `danmu_settings_page.dart` 弹幕显示区块；竖屏底部弹窗与
 * 全屏右侧小窗共用（task 08-29-nav-anim-danmu-panel）。
 * 数值行内联实现而非复用 SettingSliderRow：旧行型需要整数步进 + 单位后缀 +
 * valueLabel（如粗细的 极细..极粗），SettingSliderRow 的 Float API 不适用。
 */
@Composable
private fun DanmuSettingsRows(viewportHeight: Float) {
    val settings = ServiceContainer.appSettings
    val danmuEnable by settings.danmuEnable.collectAsStateWithLifecycle()
    val danmuRenderEmoji by settings.danmuRenderEmoji.collectAsStateWithLifecycle()
    val danmuArea by settings.danmuArea.collectAsStateWithLifecycle()
    val danmuLineCount by settings.danmuLineCount.collectAsStateWithLifecycle()
    val danmuOpacity by settings.danmuOpacity.collectAsStateWithLifecycle()
    val danmuSize by settings.danmuSize.collectAsStateWithLifecycle()
    val danmuFontWeight by settings.danmuFontWeight.collectAsStateWithLifecycle()
    val danmuSpeed by settings.danmuSpeed.collectAsStateWithLifecycle()

    // 弹幕层视口高度（px）：优先取直播间实际弹幕层高度；否则按原项目回退
    // `min(屏宽,屏高)×9/16` clamp(180dp..屏高)（_resolvePreviewViewportHeight）。
    // 字体/行数评估统一 px 口径（与渲染一致）。
    val density = LocalDensity.current.density
    val context = LocalContext.current
    val effectiveViewportH: Float = remember(viewportHeight, density) {
        if (viewportHeight > 0f) {
            viewportHeight
        } else {
            val w = context.resources.displayMetrics.widthPixels.toFloat()
            val h = context.resources.displayMetrics.heightPixels.toFloat()
            val shortest = minOf(w, h)
            (shortest * 9f / 16f).coerceIn(180f * density, h)
        }
    }
    // 当前字体下的可视行数上限（随弹幕层高度/显示区域/字号动态，对齐 estimateDanmuMaxVisibleLineCount）
    val maxVisibleLines by remember(effectiveViewportH, danmuArea, danmuSize, density) {
        derivedStateOf {
            settings.estimateDanmuMaxVisibleLineCount(
                viewportHeight = effectiveViewportH,
                area = danmuArea,
                fontSize = danmuSize * density,
            ).coerceAtLeast(1)
        }
    }
    // 显示区域单选弹层显隐（已改为 4 档滑条，不再需要弹层）
    // 应用显示区域档位并保持「行数自动收紧」（对齐原项目 onChanged 逻辑）
    fun applyDanmuArea(nextArea: Float) {
        val prevArea = danmuArea
        settings.setDanmuArea(nextArea)
        if (kotlin.math.abs(nextArea - prevArea) < 0.01f) return
        val nextMax = settings.estimateDanmuMaxVisibleLineCount(
            viewportHeight = effectiveViewportH,
            area = nextArea,
            fontSize = danmuSize * density,
        )
        if (settings.danmuLineCount.value > nextMax && nextMax >= 1) {
            settings.setDanmuLineCount(nextMax)
        }
    }

    // 默认开启
    SheetSwitchRow(label = "默认开启", checked = danmuEnable) {
        settings.setDanmuEnable(it)
    }
    // 显示弹幕表情
    SheetSwitchRow(label = "显示弹幕表情", checked = danmuRenderEmoji) {
        settings.setDanmuRenderEmoji(it)
    }
    // 显示区域（4 档滑条：全屏/3/4屏/半屏/1/4屏，task player-ui-polish；
    // 对齐原项目「显示区域」滑条语义，仅 4 个刻度；改档后保持「行数自动收紧」联动）
    val areaIndex = (DANMU_AREA_OPTIONS.indexOfFirst {
        kotlin.math.abs(it.first - danmuArea) < 0.01f
    } + 1).coerceIn(1, 4)
    SheetSliderRow(
        label = "显示区域",
        value = areaIndex,
        min = 1,
        max = 4,
        steps = 2, // 共 4 档（全屏/3/4屏/半屏/1/4屏）
        valueLabel = { idx -> DANMU_AREA_OPTIONS[(idx - 1).coerceIn(0, 3)].second },
        onChange = { idx ->
            applyDanmuArea(DANMU_AREA_OPTIONS[(idx - 1).coerceIn(0, 3)].first)
        },
    )
    // 显示几行（0=不显示，对齐 Dart lineCount=0 语义）；
    // 上限随弹幕层高度×显示区域×字号动态（对齐原项目 _buildDanmuLineValueMap），
    // 右侧数值显示「当前/上限」便于观察动态范围。
    SheetSliderRow(
        label = "显示几行",
        value = danmuLineCount.coerceIn(0, maxVisibleLines),
        min = 0,
        max = maxVisibleLines,
        valueLabel = { v -> "$v/$maxVisibleLines" },
        onChange = { settings.setDanmuLineCount(it) },
    )
    // 不透明度（%）
    SheetSliderRow(
        label = "不透明度",
        value = (danmuOpacity * 100).roundToInt().coerceIn(10, 100),
        min = 10,
        max = 100,
        unit = "%",
        showTickDots = false,
        onChange = { settings.setDanmuOpacity(it / 100f) },
    )
    // 字体大小：改字号后若当前行数超新上限则自动收紧（对齐原项目）
    SheetSliderRow(
        label = "字体大小",
        value = danmuSize.roundToInt(),
        min = 8,
        max = 72,
        showTickDots = false,
        onChange = { v ->
            val nextSize = v.toFloat()
            val nextMax = settings.estimateDanmuMaxVisibleLineCount(
                viewportHeight = effectiveViewportH,
                fontSize = nextSize * density,
            )
            if (settings.danmuLineCount.value > nextMax && nextMax >= 1) {
                settings.setDanmuLineCount(nextMax)
            }
            settings.setDanmuSize(nextSize)
        },
    )
    // 字体粗细（1–9，极细..极粗）
    SheetSliderRow(
        label = "字体粗细",
        value = danmuFontWeight,
        min = 1,
        max = 9,
        valueLabel = { v -> FONT_WEIGHT_LABELS.getOrElse(v - 1) { "$v" } },
        onChange = { settings.setDanmuFontWeight(it) },
    )
    // 滚动速度（秒数，越小越快；对齐 Dart danmuSpeed 语义）
    SheetSliderRow(
        label = "滚动速度",
        value = danmuSpeed.roundToInt(),
        min = 4,
        max = 20,
        unit = "秒",
        onChange = { settings.setDanmuSpeed(it.toFloat()) },
    )
}

/** 弹幕显示区域 4 档（对齐用户需求：全屏/3/4屏/半屏/1/4屏 → 1.0/0.75/0.5/0.25）。 */
private val DANMU_AREA_OPTIONS = listOf(
    1.00f to "全屏",
    0.75f to "3/4屏",
    0.50f to "半屏",
    0.25f to "1/4屏",
)

/* ---------- 弹幕设置（全屏右侧小窗） ---------- */

/**
 * 全屏弹幕设置右侧小窗 —— 对齐原项目 Utils.showRightDialog(弹幕设置, width=400)：
 * topRight 全高面板、透明遮罩点外部关闭、头部 back+标题+分隔线；行内容与竖屏
 * 底部弹窗共用 [DanmuSettingsRows]。滑入/滑出动画由宿主 AnimatedVisibility 承担
 * （200ms，右缘进/出），本组件只负责静态布局。
 */
@Composable
fun DanmuSettingsSidePanel(
    viewportHeight: Float,
    onDismiss: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // 透明遮罩：点外部关闭 + 挡住下层手势（对齐原项目 maskColor transparent）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        )
        // 面板：全高、宽 400dp（对齐原项目 width=400）、左侧圆角 4dp、surface 底色
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(400.dp)
                .background(
                    MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp),
                ),
        ) {
            // 头部：返回箭头 + 标题（对齐原 ListTile leading arrow_back + title）
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = CustomIcons.Back,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Text(
                    text = "弹幕设置",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                DanmuSettingsRows(viewportHeight)
            }
        }
    }
}

/* ---------- 直播设置 ---------- */

/**
 * 直播设置行内容（自动退出开关 + 退出分钟数滑条）—— 竖屏底部弹窗与全屏右侧小窗共用
 * （对齐弹幕设置的双形态：DanmuSettingsSheet / DanmuSettingsSidePanel 共用 DanmuSettingsRows）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveSettingsRows(controller: LiveRoomController) {
    val settings = ServiceContainer.appSettings
    val autoExitEnable by controller.autoExitEnable.collectAsStateWithLifecycle()
    val minutes by settings.roomAutoExitDuration.collectAsStateWithLifecycle()

    // 自动退出开关
    SheetSwitchRow(label = "自动退出直播间", checked = autoExitEnable) {
        controller.setAutoExitEnabled(it)
    }
    // 退出分钟数（5–180；同时写全局两项 + 房间级时长，对齐旧实现三连调用）
    Text(
        text = "退出时长：${minutes.coerceIn(5, 180)} 分钟",
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp),
    )
    // 连续量滑条（task 08-31-live-room-menu-brightness-fix：去掉 steps 刻度小点；
    // 对齐项目连续量滑条惯例：steps=0 + 自定义 track 去端点圆点/刻度/thumb 缺口，
    // 整数取值由 roundToInt 保证——见 ui-compose.md gotcha #16）
    Slider(
        value = minutes.coerceIn(5, 180).toFloat(),
        onValueChange = { v ->
            val m = v.roundToInt()
            settings.setRoomAutoExitDuration(m)
            settings.setAutoExitDuration(m)
            controller.setAutoExitMinutes(m)
        },
        valueRange = 5f..180f,
        steps = 0,
        modifier = Modifier.fillMaxWidth(),
        track = { state ->
            SliderDefaults.Track(
                sliderState = state,
                drawStopIndicator = null,
                drawTick = { _, _ -> },
                thumbTrackGapSize = 0.dp,
            )
        },
    )
}

/** 直播设置（竖屏底部弹窗）：壳层 + 标题 + 共用行内容。全屏时由 LiveRoomScreen 挂 [LiveSettingsSidePanel]。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveSettingsSheet(
    controller: LiveRoomController,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            SheetTitle(text = "直播设置")
            LiveSettingsRows(controller)
        }
    }
}

/**
 * 全屏直播设置右侧小窗 —— 对齐原项目 Utils.showRightDialog(直播设置, width=400) 与
 * [DanmuSettingsSidePanel] 同款壳层：topRight 全高面板、透明遮罩点外部关闭、头部
 * back+标题+分隔线；行内容与竖屏底部弹窗共用 [LiveSettingsRows]。
 * 滑入/滑出动画由宿主 AnimatedVisibility 承担（200ms，右缘进/出），本组件只负责静态布局。
 */
@Composable
fun LiveSettingsSidePanel(
    controller: LiveRoomController,
    onDismiss: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // 透明遮罩：点外部关闭 + 挡住下层手势（对齐原项目 maskColor transparent）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        )
        // 面板：全高、宽 400dp（对齐原项目 width=400）、左侧圆角 4dp、surface 底色
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(400.dp)
                .background(
                    MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp),
                ),
        ) {
            // 头部：返回箭头 + 标题（对齐原 ListTile leading arrow_back + title）
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = CustomIcons.Back,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Text(
                    text = "直播设置",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                LiveSettingsRows(controller)
            }
        }
    }
}

/* ---------- 弹幕屏蔽 ---------- */

/** 弹幕屏蔽：三个屏蔽开关 + 关键词增删（点 ✕ 词删除；输入框 + 行按钮添加）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DanmuShieldSheet(onDismiss: () -> Unit) {
    val settings = ServiceContainer.appSettings
    val shieldEnable by settings.danmuShieldEnable.collectAsStateWithLifecycle()
    val keywordEnable by settings.danmuKeywordShieldEnable.collectAsStateWithLifecycle()
    val userEnable by settings.danmuUserShieldEnable.collectAsStateWithLifecycle()
    // shieldList 为 StateFlow：增删后列表自动重组刷新（无需旧版手动 refreshKeywords）
    val shieldList by settings.shieldList.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        ) {
            SheetTitle(text = "弹幕屏蔽")
            SheetSwitchRow(label = "启用弹幕屏蔽", checked = shieldEnable) {
                settings.setDanmuShieldEnable(it)
            }
            SheetSwitchRow(label = "关键词屏蔽", checked = keywordEnable) {
                settings.setDanmuKeywordShieldEnable(it)
            }
            SheetSwitchRow(label = "用户屏蔽", checked = userEnable) {
                settings.setDanmuUserShieldEnable(it)
            }

            Text(
                text = "已屏蔽关键词 ${shieldList.size} 个：",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            for (word in shieldList) {
                Text(
                    text = "✕ $word",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clickable { settings.removeShieldList(word) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            TextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(text = "输入要屏蔽的关键词", fontSize = 14.sp) },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            SheetClickRow(
                label = "添加屏蔽词",
                onClick = {
                    val word = input.trim()
                    if (word.isEmpty()) {
                        Router.toast("请输入关键词")
                    } else {
                        settings.addShieldList(word)
                        input = ""
                    }
                },
            )
        }
    }
}

/* ---------- 小部件 ---------- */

/** 弹层标题：16sp 深色（对齐旧 sheetTitle）。 */
@Composable
private fun SheetTitle(text: String) {
    Text(
        text = text,
        fontSize = 16.sp,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

/** 开关行：label 左 + Switch 右（对齐旧 switchRow）。注意 onChange 具名传参防 trailing 绑定错位。 */
@Composable
private fun SheetSwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            // 选中态用主色红系，与全局配色一致（对齐 SettingRows 的处理）
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
        )
    }
}

/** 可点击行：文字行（对齐旧 clickRow）；可选右侧值（如「全屏」「3/4屏」）。 */
@Composable
private fun SheetClickRow(
    label: String,
    onClick: () -> Unit,
    value: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(
                text = value,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 整数滑杆行：label + 右侧数值文本（valueLabel 优先，否则 "$value$unit"）+ 下方 Slider。
 * 对齐旧 row() 帮助函数：整数步进（steps 锁定刻度）、即时回调持久化。
 * （自定义 track 走 experimental API，需 OptIn。）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetSliderRow(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    unit: String = "",
    valueLabel: ((Int) -> String)? = null,
    steps: Int? = null,
    showTickDots: Boolean = true,
    onChange: (Int) -> Unit,
) {
    val display = valueLabel?.invoke(value) ?: "$value$unit"
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(text = display, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Slider(
        value = value.toFloat().coerceIn(min.toFloat(), max.toFloat()),
        onValueChange = { v -> onChange(v.roundToInt().coerceIn(min, max)) },
        valueRange = min.toFloat()..max.toFloat(),
        // 整数步进：显式 steps 优先（如显示区域 4 档传 2 → 1..4 四刻度）；
        // 未传时默认「区间长度 - 1」全整数刻度（其余行沿用原行为）。
        // showTickDots=false（连续量滑条，task 08-29-nav-anim-danmu-panel）：刻度点整段
        // 去掉——密集整数档（如不透明度 89 档）的轨道会呈点阵观感。steps=0 后轨道本无
        // 刻度，再经自定义 track 去端点圆点（drawStopIndicator=null）与 thumb 缺口
        //（thumbTrackGapSize=0），轨道呈连续光条；整数取值仍由 roundToInt 保证
        //（拖动连续、松手吸附整数位）。
        steps = if (showTickDots) (steps ?: (max - min - 1)).coerceAtLeast(0) else 0,
        modifier = Modifier.fillMaxWidth(),
        track = { state ->
            if (showTickDots) {
                SliderDefaults.Track(sliderState = state)
            } else {
                SliderDefaults.Track(
                    sliderState = state,
                    drawStopIndicator = null,
                    drawTick = { _, _ -> },
                    thumbTrackGapSize = 0.dp,
                )
            }
        },
    )
}
