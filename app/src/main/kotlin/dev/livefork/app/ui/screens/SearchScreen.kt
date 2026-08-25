package dev.livefork.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.livefork.app.app.Site
import dev.livefork.app.di.ServiceContainer
import dev.livefork.app.modules.search.AppSearchController
import dev.livefork.app.ui.Router
import dev.livefork.app.ui.components.LiveRoomCard
import dev.livefork.app.ui.components.PagedRoomGridCore
import dev.livefork.app.ui.theme.CardTitle
import dev.livefork.app.ui.theme.ImageError
import dev.livefork.app.ui.theme.LiveRed
import dev.livefork.app.ui.theme.TextSecondary
import dev.livefork.app.ui.theme.TextTertiary
import dev.livefork.core.model.LiveAnchorItem
import dev.livefork.core.model.LiveRoomItem

/** 页面底色（对应现版 SearchActivity 根布局的 #F5F5F5，仅本页使用）。 */
private val SearchBackground = Color(0xFFF5F5F5)

/**
 * 搜索页（Compose 版）—— 对应 Dart `search_page.dart`，
 * 替换现版 View 体系 `ui/SearchActivity.kt`，注册为 "search" 路由。
 *
 * 结构镜像：顶部搜索栏（白底：输入框 hint「输入关键词搜索」14sp + 右侧模式切换按钮
 * 「直播间/主播」13sp 红系）+ 站点 Tab（纯文字，选中红系 #FF3F4B / 未选中 #666666，
 * 指示条红系）+ 结果区。
 *
 * 结果渲染：SearchListController 是 BasePageController<Any>（直播间/主播两种结果并存，
 * 对齐 Dart dynamic），沿用现版 SearchResultAdapter 的 is 类型检查区分：
 * - 直播间 → LiveRoomCard 卡片网格（复用公共分页网格组件，视觉升级为首页卡片范式）；
 * - 主播 → 整行列表行（标题/副标题「主播」/右侧直播状态徽标，对齐现版 adapter 规格）。
 *
 * 行为对齐现版：切换模式只更新控制器状态、不自动重搜（列表保持上次结果直到下次搜索）；
 * 切换站点 Tab 经 controller.setTabIndex（其内部在有关键词且结果为空时自动补拉）；
 * 点击任意类型结果都进直播间（Router.openLiveRoom，直播间 Batch 7 才迁路由）。
 *
 * 控制器生命周期选型：路由级 remember 创建 + DisposableEffect 销毁——与现版
 * SearchActivity「onCreate 创建 / onDestroy 销毁」语义一致（每次进入页面都是全新
 * 搜索会话，返回即丢弃，不存在跨路由保活的既有行为）。
 */
@Composable
fun SearchScreen(modifier: Modifier = Modifier) {
    // 控制器路由级生命周期：remember 创建 + DisposableEffect 销毁
    //（对齐原 Activity onCreate/onDestroy 的 controller 生命周期）
    val controller = remember { AppSearchController(ServiceContainer.appSettings) }
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }

    val sites = controller.sites

    // 搜索关键词（rememberSaveable：进程重建后保留输入）
    var keyword by rememberSaveable { mutableStateOf("") }
    // 当前站点 Tab（初值对齐现版 controller.index——AppSearchController 会按上次搜索站点初始化）
    var selectedSiteIndex by rememberSaveable { mutableIntStateOf(controller.index) }
    val siteIndex = selectedSiteIndex.coerceIn(0, sites.lastIndex.coerceAtLeast(0))

    // 搜索模式（0=直播间，1=主播）：控制器 StateFlow 驱动按钮文案
    val searchMode by controller.searchMode.collectAsStateWithLifecycle()

    // 当前站点的结果列表控制器（切 Tab 即换控制器，等价现版 refreshList 的重挂逻辑）
    val listController = controller.siteControllers[sites[siteIndex].id]
    // 结果列表订阅：控制器为空时给空列表兜底（分支内分别订阅，避免混用 Flow/State 类型）
    val resultList: List<Any> = if (listController != null) {
        listController.list.collectAsStateWithLifecycle().value
    } else {
        emptyList()
    }

    // 搜索提交（对齐现版 doSearch：空关键词 toast 提示；有值则全站点清空并刷新当前站点）
    val doSearch: () -> Unit = {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) {
            Router.toast("请输入搜索关键词")
        } else {
            controller.doSearch(trimmed)
        }
    }

    Column(modifier = modifier.fillMaxSize().background(SearchBackground)) {
        // ===== 顶部搜索栏 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = keyword,
                onValueChange = { keyword = it },
                placeholder = {
                    Text(text = "输入关键词搜索", fontSize = 14.sp, color = TextTertiary)
                },
                textStyle = TextStyle(fontSize = 14.sp, color = CardTitle),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
                    focusedIndicatorColor = LiveRed,
                    unfocusedIndicatorColor = ImageError,
                ),
                modifier = Modifier.weight(1f),
            )
            // 模式切换按钮（对齐现版 modeButton：「直播间」↔「主播」，13sp 红系，
            // padding 10/6 提供热区；点击只切控制器状态，不自动重搜——对齐现版行为）
            Text(
                text = if (searchMode == 0) "直播间" else "主播",
                fontSize = 13.sp,
                color = LiveRed,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { controller.setSearchMode(if (searchMode == 0) 1 else 0) }
                    .padding(start = 10.dp, top = 6.dp, end = 2.dp, bottom = 6.dp),
            )
        }

        // ===== 站点 Tab（纯文字，对齐现版 TabLayout 默认 tab）=====
        SiteTextTabRow(
            sites = sites,
            selectedIndex = siteIndex,
            onSelect = { index ->
                selectedSiteIndex = index
                controller.setTabIndex(index)
            },
        )

        // ===== 结果区 =====
        if (listController != null) {
            PagedRoomGridCore(
                listController = listController,
                gridState = remember(listController) { LazyGridState() },
                // 关闭首屏自动加载（对齐现版：进入搜索页不自动搜索——空关键词下
                // 自动 loadData 会误置 pageEmpty，刚进页面就显示空态覆盖层）
                autoFirstLoad = false,
                // 主播结果行跨整行展示（直播间卡片保持单格）
                fullSpan = { it !is LiveRoomItem },
                itemContent = { index, item, mainAxisExtentDp ->
                    when (item) {
                        is LiveRoomItem -> {
                            // 固定行高 = mainAxisExtent（封面 16:9 高 + 文字区 64dp）
                            Box(modifier = Modifier.fillMaxWidth().height(mainAxisExtentDp.dp)) {
                                LiveRoomCard(
                                    item = item,
                                    onClick = { openRoom(sites[siteIndex], item.roomId) },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        is LiveAnchorItem -> {
                            AnchorResultRow(
                                item = item,
                                onClick = { openRoom(sites[siteIndex], item.roomId) },
                                showDivider = index < resultList.lastIndex,
                            )
                        }
                        else -> {
                            // 兜底（对齐现版 adapter 的 else 分支：仅 toString 展示）
                            Text(text = item.toString(), fontSize = 15.sp, color = CardTitle)
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            )
        } else {
            Box(modifier = Modifier.weight(1f).fillMaxSize())
        }
    }
}

/** 打开直播间（两种结果类型统一入口，对齐现版点击回调）。 */
private fun openRoom(site: Site, roomId: String) {
    Router.openLiveRoom(site, roomId)
}

/**
 * 站点文字 Tab 行 —— 对齐现版 TabLayout（GRAVITY_START + 指示条不全宽 +
 * setTabTextColors #666/#FF3F4B）。
 */
@Composable
private fun SiteTextTabRow(
    sites: List<Site>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    ScrollableTabRow(
        selectedTabIndex = selectedIndex,
        containerColor = Color.White,
        edgePadding = 12.dp,
        indicator = { tabPositions ->
            SecondaryIndicator(
                modifier = Modifier.tabIndicatorOffset(tabPositions[selectedIndex]),
                color = LiveRed,
            )
        },
        divider = { },
    ) {
        sites.forEachIndexed { index, site ->
            val selected = index == selectedIndex
            Tab(
                selected = selected,
                onClick = { onSelect(index) },
                selectedContentColor = LiveRed,
                unselectedContentColor = TextSecondary,
                text = {
                    Text(
                        text = site.name,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (selected) LiveRed else TextSecondary,
                    )
                },
            )
        }
    }
}

/**
 * 主播结果行 —— 视觉对齐现版 SearchResultAdapter 的 LiveAnchorItem 分支：
 * 白底、左右 16dp 上下 12dp 内边距、标题 15sp #222 单行、副标题「主播」12sp #888、
 * 右侧直播状态 12sp 红系（直播中/未开播）；行间分隔线对齐 DividerItemDecoration。
 */
@Composable
private fun AnchorResultRow(
    item: LiveAnchorItem,
    onClick: () -> Unit,
    showDivider: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.userName,
                    fontSize = 15.sp,
                    color = CardTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(text = "主播", fontSize = 12.sp, color = TextTertiary)
            }
            Text(
                text = if (item.liveStatus) "直播中" else "未开播",
                fontSize = 12.sp,
                color = LiveRed,
            )
        }
        if (showDivider) {
            HorizontalDivider()
        }
    }
}
