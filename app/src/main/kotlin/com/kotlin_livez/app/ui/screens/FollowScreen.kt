package com.kotlin_livez.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kotlin_livez.app.app.Sites
import com.kotlin_livez.app.di.ServiceContainer
import com.kotlin_livez.app.models.FollowUser
import com.kotlin_livez.app.modules.follow_user.FollowGroupMode
import com.kotlin_livez.app.modules.follow_user.FollowGroupOption
import com.kotlin_livez.app.modules.follow_user.FollowUserController
import com.kotlin_livez.app.services.FollowService
import com.kotlin_livez.app.ui.Router
import com.kotlin_livez.app.ui.components.CompactSearchBar
import com.kotlin_livez.app.ui.components.EmptyHint
import com.kotlin_livez.app.ui.components.NetImage
import com.kotlin_livez.app.ui.nav.liveRoomContainerSharedElement
import com.kotlin_livez.app.ui.nav.liveRoomCoverSharedElement
import com.kotlin_livez.app.ui.components.resolveLiveRoomGrid
import com.kotlin_livez.app.ui.theme.LocalAppDarkTheme
import com.kotlin_livez.app.ui.theme.cardContainerColor
import com.kotlin_livez.app.ui.theme.imagePlaceholderColor
import com.kotlin_livez.app.ui.theme.LiveIndicatorRed
import com.kotlin_livez.app.ui.theme.LiveIndicatorIdleGray
import com.kotlin_livez.core.model.FollowRefreshProgress

/**
 * 关注用户页（Compose 版）—— 对应 Dart `follow_user_page.dart`，
 * 替换现版 View 体系 `ui/FollowUserActivity.kt`，注册为 "follow" 路由。
 *
 * 视觉与交互照搬现版：顶部「分组模式切换 + 搜索框 + 刷新全部」白条、
 * 横向滚动分组标签栏（选中红底白字 / 未选中灰底灰字）、关注列表
 * （圆形头像 + 标题/副标题 + 直播状态徽标）；点击进直播间、长按取消关注。
 *
 * 底栏「重复点击关注 Tab」事件无需本页处理：Dart follow_user_controller onInit
 * 订阅 kBottomNavigationBarClicked index==1（scrollToTopOrRefresh：列表已滚动则回顶、
 * 在顶部才触发刷新）；Kotlin 版 FollowUserController.init 镜像为直接 refreshData()
 * （无滚动状态可查的简化），本页不重复监听。
 * 注：本 fork 底栏仅 首页(0)/分类(2)/我的(3)——关注在 Dart 是底栏 Tab 而本 fork
 * 无关注底栏入口（产品差异），壳层槽位→页面标识映射不发 1，控制器内 index==1
 * 订阅当前为死代码；保留以对齐 Dart 契约，后续若底栏加入关注入口即自动生效。
 *
 * 控制器生命周期（task follow-tab-and-room-follow：关注改为底栏 Tab）：
 * - 独立路由（follow）场景：controller 为 null，本页 remember 创建 + DisposableEffect 销毁
 *   （对齐原 Activity onDestroy 的 scope cancel）；
 * - 底栏 Tab 场景：由壳层 ShellState 持有 controller 注入（随壳层存活，底栏切换不重建、
 *   不丢数据/滚动），本页不再自建。
 *
 * 取消关注后的刷新语义以控制器现状为准：removeItem 内部删库后调 refreshData()
 * 整体重载列表（非局部移除）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowScreen(
    modifier: Modifier = Modifier,
    controller: FollowUserController? = null,
) {
    // 路由场景自建（remember 缓存）；底栏场景用注入的壳层控制器
    val effectiveController = controller ?: remember {
        FollowUserController(
            appSettings = ServiceContainer.appSettings,
            followService = ServiceContainer.followService,
            dbService = ServiceContainer.dbService,
            currentRoom = ServiceContainer.currentRoom,
        )
    }
    // 自建控制器（路由场景）随本页销毁；壳层注入的控制器由壳层管理生命周期
    DisposableEffect(effectiveController, controller != null) {
        if (controller != null) {
            onDispose { }
        } else {
            onDispose { effectiveController.dispose() }
        }
    }

    val list by effectiveController.list.collectAsStateWithLifecycle()
    val groupMode by effectiveController.groupMode.collectAsStateWithLifecycle()
    val selectedGroupId by effectiveController.selectedGroupId.collectAsStateWithLifecycle()
    // followList 参与组合读取（groupOptions 依赖它推导平台分组项），需订阅触发重组
    val followList by ServiceContainer.followService.followList.collectAsStateWithLifecycle()
    // groupOptions 的第三个输入：Sites.supportSites 按 siteSort 排序（决定平台分组项的先后序）
    val siteSort by ServiceContainer.appSettings.siteSort.collectAsStateWithLifecycle()
    // 真实刷新态（启动/进页/定时/手动共用）：绑定下拉刷新指示器
    val updating by ServiceContainer.followService.updating.collectAsStateWithLifecycle()
    // 空态判据：「本页确实没有关注」。由 `FollowUserController.filterData()` 每次重建分页列表时
    // 同步置位（`pageEmpty.value = items.isEmpty()`），与 `list` 同源；写法与 HistoryScreen 一致。
    val pageEmpty by effectiveController.pageEmpty.collectAsStateWithLifecycle()
    // 注：refreshProgress 的订阅已下沉到 RefreshProgressSection 内部——刷新进度每秒多次 tick，
    // 在本页收集会让整页（顶栏/分组条/网格）跟着重组（task 10-17-ui-power-and-recomposition-governance）。

    // 搜索关键字本地输入态；防抖语义对齐现版 TextWatcher：每次变更直接 setSearchKeyword
    var keyword by rememberSaveable { mutableStateOf("") }
    // 取消关注确认框目标（长按行置位）
    var pendingUnfollow by remember { mutableStateOf<FollowUser?>(null) }

    // 分组选项为控制器计算属性（**不得**在 controller 侧缓存——那会把按 followList 推导出的
    // 平台分组项冻结成陈旧列表）；改为在 UI 侧按其全部输入记忆化：
    // groupMode（状态/平台分支）、followList（平台分组的站点集合）、siteSort（站点先后序，
    // 经 Sites.supportSites 间接读取）。三者任一变化才重算，其余重组沿用同一列表实例。
    val groupOptions = remember(effectiveController, groupMode, followList, siteSort) {
        effectiveController.groupOptions
    }

    // 根背景用 surface：与顶栏白条、列表区底一致——原 surfaceContainerLow 会在
    // 分组按钮行与状态栏区域露出灰底，与页面其他区域形成色差（用户反馈）。
    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
        FollowTopBar(
            modeLabel = if (groupMode == FollowGroupMode.liveStatus) "状态分组" else "平台分组",
            onToggleMode = {
                val newMode = if (groupMode == FollowGroupMode.liveStatus) {
                    FollowGroupMode.platform
                } else {
                    FollowGroupMode.liveStatus
                }
                effectiveController.setGroupMode(newMode)
            },
            keyword = keyword,
            onKeywordChange = { value ->
                keyword = value
                effectiveController.setSearchKeyword(value)
            },
            onRefreshAll = { effectiveController.refreshAllStatus() },
        )

        TagChipBar(
            options = groupOptions,
            selectedGroupId = selectedGroupId,
            onSelect = { option -> effectiveController.setGroupOption(option) },
        )

        // 刷新进度区（进度流在子组件内部收集，tick 只重组本区）
        RefreshProgressSection(followService = ServiceContainer.followService)

        // 下拉刷新（对齐主页 PagedRoomGrid 模式，task player-icons-follow-ux）：
        // isRefreshing 绑定 FollowService.updating（真实刷新态）；onRefresh 强制全量刷
        // 状态（refreshAllStatus 内部 force 绕过 30s 冷却）。仅列表区参与下拉，
        // 顶栏/分组条固定。
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = updating,
            onRefresh = { effectiveController.refreshAllStatus() },
            state = pullState,
            modifier = Modifier.fillMaxSize(),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    isRefreshing = updating,
                    state = pullState,
                    modifier = Modifier.align(Alignment.TopCenter),
                    color = LiveIndicatorRed,
                )
            },
        ) {
            // 网格规格与首页同源（resolveLiveRoomGrid：列数/行高按视口宽度解析；
            // task 08-29-chat-pin-bubble-follow-grid：关注页排版改为首页同款卡片网格）
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val spec = resolveLiveRoomGrid(maxWidth.value)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(spec.columns),
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                    contentPadding = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(
                        items = list,
                        key = { item ->
                            if (item.id.isNotEmpty()) item.id else "${item.siteId}_${item.roomId}"
                        },
                    ) { item ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(spec.mainAxisExtentDp.dp)
                                // 卡片重排/增删动画（本任务范围：**分页器页外**的列表才加）。
                                // 关注页不在 SitePagerTabs 页内，加 animateItem 不触碰
                                // ui-compose.md 条目 27 的 C2 负面结论（页内网格逐项 graphicsLayer
                                // 无稳健改善、已回退）。animateItem 依赖上方稳定 key 生效。
                                .animateItem(),
                        ) {
                            FollowItemCard(
                                item = item,
                                onClick = { openRoom(item) },
                                onLongClick = { pendingUnfollow = item },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }

                // 空态：列表为空且已确认无数据（`pageEmpty`）时居中提示（task 10-17；对齐 HistoryScreen 写法）。
                // 不读 `updating`：刷新开始时它先变 true，会让空态在刷新过程中闪断一次
                // （pageEmpty 在同一次 filterData 内与 list 同步，刷新前后都保持为真）；
                // 也不读进页首帧：pageEmpty 初值 false，首次加载完成前不会闪现空态。
                // **不得**改读 refreshProgress：该流的订阅已下沉到 RefreshProgressSection
                // 内部（见上方注释），在本页收集会让每秒多次的进度 tick 重新带动整页重组。
                if (list.isEmpty() && pageEmpty) {
                    EmptyHint(text = "暂无关注的用户")
                }
            }
        }
    }

    pendingUnfollow?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingUnfollow = null },
            title = { Text(text = "取消关注") },
            text = { Text(text = "确定取消关注「${target.userName}」吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingUnfollow = null
                        effectiveController.removeItem(target)
                    },
                ) { Text(text = "取消关注", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUnfollow = null }) { Text(text = "取消") }
            },
        )
    }
}

/** 点击进直播间：站点查表后走 Router.openLiveRoom（直播间 Batch 7 才迁路由）。 */
private fun openRoom(item: FollowUser) {
    val site = Sites.allSites[item.siteId]
    if (site != null) {
        Router.openLiveRoom(site, item.roomId)
    } else {
        Router.toast("无法打开直播间：站点不存在")
    }
}

/**
 * 顶栏：白底、水平居中排布——「状态分组/平台分组」切换文字按钮（13sp 红字）+
 * 共享胶囊搜索框 [CompactSearchBar]（占位「搜索关注的用户」，占满剩余宽度；
 * task 10-17 起与搜索页共用同一实现，替换原 56dp 高的 OutlinedTextField）+
 * 「刷新」红底白字按钮。
 * 各回调一律具名传参（trailing lambda 绑定最后参数的既有 gotcha）。
 */
@Composable
private fun FollowTopBar(
    modeLabel: String,
    onToggleMode: () -> Unit,
    keyword: String,
    onKeywordChange: (String) -> Unit,
    onRefreshAll: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = modeLabel,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                // 先 clip 再 clickable（本任务）：分组切换与右侧「刷新」胶囊同为 14dp 圆角，
                // 水波纹随圆角裁剪而非溢出为直角矩形
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onToggleMode)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        // 共享胶囊搜索框（task 10-17：与搜索页共用一份实现，替换原 56dp 高的 OutlinedTextField）
        CompactSearchBar(
            value = keyword,
            onValueChange = onKeywordChange,
            placeholder = "搜索关注的用户",
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "刷新",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                // 先 clip 再 background/clickable（本任务）：防胶囊水波纹溢出为直角矩形
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                .clickable(onClick = onRefreshAll)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/**
 * 分组标签栏：横向滚动 chip 行（M3 token：选中 primary 底 surface 字、
 * 未选 surfaceContainerHigh 底 onSurfaceVariant 字，内边距 14/6、间距 8dp）。
 * 「全部」选项始终在首位；状态分组含 直播中/未开播，平台分组含各站点。
 */
@Composable
private fun TagChipBar(
    options: List<FollowGroupOption>,
    selectedGroupId: String,
    onSelect: (FollowGroupOption) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 12.dp, top = 6.dp, end = 12.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (option in options) {
            val selected = option.id == selectedGroupId
            Text(
                text = option.title,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    // 先 clip 再 background/clickable（本任务）：防胶囊水波纹溢出为直角矩形
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                        RoundedCornerShape(14.dp),
                    )
                    .clickable { onSelect(option) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * 刷新进度区 —— 对应 Dart follow_user_page._buildRefreshProgress：
 * 进度激活时显示「阶段文案 + 已解析/总数」、可选明细行、线性进度条；
 * 非激活时不占布局（对齐 Dart SizedBox.shrink）。
 *
 * 订阅下沉点（task 10-17-ui-power-and-recomposition-governance）：refreshProgress 在本函数
 * 内部 collectAsStateWithLifecycle，使每秒多次的进度 tick 只重组本区，不再带动整页
 * （顶栏 / 分组条 / 关注网格）重组——这是原「在本页收集后当参数传下来」写法的代价所在。
 *
 * 进度卡的淡入淡出（task 10-17-ui-animation-fluency-polish）：整体高度展开/收缩 + 淡入淡出，
 * 取代原先「active 变 false 立即从组合树消失」的硬切换。
 * **函数名固定为 `RefreshProgressSection`**（任务1 新建，命名不得更改）。
 *
 * 与 `PullToRefreshBox.isRefreshing` 的关系（ui-compose.md 条目 19 核对）：本函数只读
 * `followService.refreshProgress`（**刷新进度**流），不参与 `isRefreshing`（下拉指示器）的
 * 取值——`FollowScreen` 的 `isRefreshing` 仍直接绑 `followService.updating`（真实刷新态），
 * 二者是同一刷新过程的两条独立流。因此本改动**没有**把下拉指示器的可见性与其状态脱钩，
 * 条目 19 的「true→false 必须跨帧可见」前提未被触碰（`updating` 本身的语义由
 * FollowService 的 400ms delay + generation 守卫保证）。
 */
@Composable
private fun RefreshProgressSection(followService: FollowService) {
    val progress by followService.refreshProgress.collectAsStateWithLifecycle()
    // 最后一次「激活态」快照：AnimatedVisibility 的退场动画（收缩 + 淡出）期间
    // progress.active 已为 false，若直接读当前值，卡片内容会在收缩过程中瞬间清空
    // （stage 变空串、计数归零）。快照在组合期写入（幂等），退场期始终有内容可渲染。
    var lastActive by remember { mutableStateOf<FollowRefreshProgress?>(null) }
    if (progress.active) {
        lastActive = progress
    }
    val display = if (progress.active) progress else lastActive

    AnimatedVisibility(
        visible = progress.active,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        if (display != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = display.stage,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(text = "${display.resolvedCount}/${display.total}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                }
                if (display.detail.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = display.detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (display.total > 0) {
                    LinearProgressIndicator(
                        progress = { display.percent.toFloat() },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * 单条关注卡片（task 08-29-chat-pin-bubble-follow-grid）—— 首页 LiveRoomCard 同款排版
 * （2dp 阴影 + 8dp 圆角 + 16:9 封面 + 64dp 文字区），差异由用户定标：
 * - 标题位 = 用户名（原卡片为房间标题）；
 * - 主播名位 = 关注页描述行文本（roomTitle 非空 →「平台 · 标题」，空 → 仅平台名）；
 * - 不显示热度；
 * - 封面右上角状态角标（task 10-17 升级）：半透明深色胶囊 + 状态圆点——直播中为红色圆点、
 *   未开播为灰色圆点，文案白色；状态未知（0）不显示。
 * 封面 = roomCover（空 URL 显示占位底）。点击进直播间、长按弹取消关注确认。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FollowItemCard(
    item: FollowUser,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val site = Sites.allSites[item.siteId]
    // 深色/纯黑主题下跳过 2dp 阴影（task 10-17-ui-power-and-recomposition-governance）：
    // 与 LiveRoomCard / SubCategoryCard 同策略——深色底上阴影不可见却仍要录绘制指令。
    val cardShadow = if (LocalAppDarkTheme.current) {
        Modifier
    } else {
        Modifier.shadow(elevation = 2.dp, shape = RoundedCornerShape(8.dp))
    }
    Column(
        modifier = Modifier
            .liveRoomContainerSharedElement(item.siteId, item.roomId)
            .then(modifier)
            .then(cardShadow)
            // 圆角涟漪裁剪（本任务）：先 clip 再 background/combinedClickable，防长按/点击
            // 水波纹溢出圆角。clip 必须排在 `.shadow` **之后**：阴影画在自己的图层上，
            // 若 clip 在前会被同一圆角连阴影一起裁掉。
            .clip(RoundedCornerShape(8.dp))
            .background(cardContainerColor(), RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            NetImage(
                url = item.roomCover,
                modifier = Modifier
                    .liveRoomCoverSharedElement(item.siteId, item.roomId)
                    .fillMaxSize()
                    // 封面底色主题感知（深色不再亮白，本任务）
                    .background(imagePlaceholderColor()),
            )
            if (item.liveStatus != 0) {
                val live = item.liveStatus == 2
                // 角标（task 10-17-ui-material3-details-and-redesign）：半透明深色胶囊 +
                // 红色状态圆点（直播中）/ 弱化灰点（未开播）。圆点色与直播间内关注 Tab 的
                // 状态胶囊同源（LiveIndicatorRed = 直播强调红），白字保证封面上的可读性。
                // 灰点取 AppTheme 的 LiveIndicatorIdleGray（固定色，不随主题明暗）——
                // 它画在半透明深色胶囊上，取 outline/onSurfaceVariant 会在深色下低对比。
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        // 先 clip 再 background：胶囊底色严格被圆角裁剪
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0x99000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (live) LiveIndicatorRed else LiveIndicatorIdleGray),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (live) "直播中" else "未开播",
                        fontSize = 9.sp,
                        color = Color.White,
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(8.dp),
        ) {
            // 标题位 = 用户名（用户定标；14sp 加粗，对齐 LiveRoomCard 标题样式）
            Text(
                text = item.userName,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.weight(1f))
            // 主播名位 = 关注页描述行文本（无热度显示）
            Text(
                text = if (item.roomTitle.isEmpty()) {
                    site?.name ?: item.siteId
                } else {
                    "${site?.name ?: item.siteId} · ${item.roomTitle}"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
