package com.kotlin_livez.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kotlin_livez.app.app.Site
import com.kotlin_livez.app.di.ServiceContainer
import com.kotlin_livez.app.ui.Router
import com.kotlin_livez.app.ui.components.CompactSearchBar
import com.kotlin_livez.app.ui.components.LiveRoomCard
import com.kotlin_livez.app.ui.components.PagedRoomGridCore
import com.kotlin_livez.app.ui.components.SitePagerTabs
import com.kotlin_livez.app.ui.theme.CustomIcons
import com.kotlin_livez.app.ui.theme.LiveIndicatorRed
import com.kotlin_livez.core.model.LiveAnchorItem
import com.kotlin_livez.core.model.LiveRoomItem

/**
 * 搜索页（Compose 版）—— 对应 Dart `search_page.dart`，
 * 替换现版 View 体系 `ui/SearchActivity.kt`，注册为 "search" 路由。
 *
 * 结构镜像：顶部搜索栏（白底：输入框 hint「输入关键词搜索」14sp + 右侧模式切换按钮
 * 「直播间/主播」13sp 红系）+ 站点 Tab（纯文字，选中红系 #FF3F4B / 未选中 #666666，
 * 指示条红系）+ 结果区。
 *
 * 站点 Tab 与结果区分页器由公共组件 [SitePagerTabs] 承载（与首页 / 分类页同一实现，
 * 原页面私有的纯文字站点 Tab 行已删）：Tab 保持纯文字观感
 * （`showLogo = false` / `siteNameFontSize = 14.sp`），结果区支持**左右滑动切平台**
 * （Tab 选中态与指示条逐帧跟随滑动）；选中站点经 `onSiteChanged` 回写
 * `controller.setTabIndex`（沿用其「有关键词且该站点结果为空则自动补拉」语义）。
 * **有意偏离 Dart**：Dart 搜索页的 `TabBarView` 是 `NeverScrollableScrollPhysics`
 * （`search_page.dart:91`，上游刻意禁用滑动）；本页按需求方要求**不禁用滑动** ——
 * 日后勿当「对齐 Dart」把它改回去。
 *
 * 结果渲染：SearchListController 是 BasePageController<Any>（直播间/主播两种结果并存，
 * 对齐 Dart dynamic），沿用现版 SearchResultAdapter 的 is 类型检查区分：
 * - 直播间 → LiveRoomCard 卡片网格（复用公共分页网格组件，视觉升级为首页卡片范式）；
 * - 主播 → 整行列表行（标题/副标题「主播」/右侧直播状态徽标，对齐现版 adapter 规格）。
 *
 * 行为对齐现版：切换模式只更新控制器状态、不自动重搜（列表保持上次结果直到下次搜索）；
 * 切换站点（点 Tab 或左右滑动）都经 controller.setTabIndex（其内部在有关键词且结果为空时
 * 自动补拉）；点击任意类型结果都进直播间（Router.openLiveRoom，直播间 Batch 7 才迁路由）。
 *
 * 控制器生命周期选型：**进程级会话**（`ServiceContainer.appSearchController`），跨路由保活——
 * 进直播间是压栈路由、搜索页会话不销毁（对齐 Dart `app_pages.dart:105` 的
 * `BindingsBuilder.put`，而非被替换的 View 版 SearchActivity）；返回后关键词/结果/模式/站点都在。
 */
@Composable
fun SearchScreen(modifier: Modifier = Modifier) {
    // 进程级搜索会话（对齐 Dart：进直播间是压栈不销毁，返回必须是同一会话）
    val controller = ServiceContainer.appSearchController

    val sites = controller.sites

    // 搜索关键词（rememberSaveable：进程重建后保留输入）
    var keyword by rememberSaveable { mutableStateOf("") }

    // 各站点网格滚动状态（键 = 站点 id）：页面级 remember —— 与既有已知限制一致
    // （滚动位置不跨路由保留，见 ui-compose.md 条目 32 第 4 点；本任务只是把它从
    // 「单页 remember」变为「每站点一个页面级 remember」，不改善也不恶化）。
    // `controller.sites` = `AppSearchController` 构造时读一次 `Sites.supportSites` 后**缓存住**的
    // val（`supportSites` 自身虽是 getter，结果已被控制器存下）⇒ 实例本身即稳定；且 `remember`
    // 的 key 比较是 `equals`（ui-compose.md 条目 28），即便将来改成每次新建 List，内容不变仍命中缓存
    // ⇒ gridStates 实例稳定 —— SitePagerTabs 的 movableContentOf 槽位正以 `sites`/`gridStates`
    // 作 remember key，键不稳会让节点搬移的配对失败。
    val gridStates = remember(sites) { sites.associate { it.id to LazyGridState() } }

    // 站点 Tab 选中态改由 [SitePagerTabs] 持有（rememberSaveable + pagerState），
    // 变化时经 onSiteChanged 回写进程级控制器 controller.setTabIndex；首次组合即发射一次
    // （组件实际初始页 → 控制器），故两者不会长期失配——ui-compose.md 条目 32 第 2 点
    // 「站点 Tab 下标要以存活控制器为准」的约束，现由组件内的初始同步承担。
    // 搜索模式（0=直播间，1=主播）：控制器 StateFlow 驱动按钮文案
    val searchMode by controller.searchMode.collectAsStateWithLifecycle()

    // 搜索提交（对齐现版 doSearch：空关键词 toast 提示；有值则全站点清空并刷新当前站点）
    val doSearch: () -> Unit = {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) {
            Router.toast("请输入搜索关键词")
        } else {
            controller.doSearch(trimmed)
        }
    }

    // 根背景用 surface（task 10-17-ui-material3-details-and-redesign）：
    // 原 surfaceContainerLow 会在状态栏延伸区露出灰色断层（与顶栏白条不一致）。
    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
        // ===== 顶部：返回按钮 + 胶囊搜索框 + 模式切换 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(top = 4.dp, end = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 标准 48dp 返回按钮（与本 App 设置页/账号页同款，task 10-17 顶栏补齐）
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
            // 共享胶囊搜索框（task 10-17：与关注页共用一份实现，不再用 56dp 高的 TextField）
            CompactSearchBar(
                value = keyword,
                onValueChange = { keyword = it },
                placeholder = "输入关键词搜索",
                modifier = Modifier.weight(1f),
                keyboardActions = KeyboardActions(onSearch = { doSearch() }),
            )
            // 模式切换按钮（对齐现版 modeButton：「直播间」↔「主播」，13sp 红系，
            // padding 10/6 提供热区；点击只切控制器状态，不自动重搜——对齐现版行为）
            Text(
                text = if (searchMode == 0) "直播间" else "主播",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { controller.setSearchMode(if (searchMode == 0) 1 else 0) }
                    .padding(start = 10.dp, top = 6.dp, end = 2.dp, bottom = 6.dp),
            )
        }

        // ===== 站点 Tab + 结果区（由公共组件承载：纯文字 Tab、左右滑动切平台）=====
        SitePagerTabs(
            sites = sites,
            gridStates = gridStates,
            indicatorColor = MaterialTheme.colorScheme.primary,
            // 搜索是路由、不属底栏：传 null = 不注册底栏「重按当前 Tab」监听
            bottomNavTabIndex = null,
            // 选中站点变化（点 Tab / 滑动落位 / 首次组合同步）→ 回写进程级控制器；
            // setTabIndex 内部：有关键词且该站点结果为空则自动补拉（既有语义，正是需求）
            onSiteChanged = { index -> controller.setTabIndex(index) },
            // 初始站点以存活控制器为准（本页不再自持下标）：否则重新进入搜索路由会把平台拉回第 0 个
            //（与 ui-compose.md 条目 32 第 2 点的契约冲突）；自直播间返回仍按 saveable 恢复值
            initialSiteIndex = controller.index,
            // Tab 只加滑动、不换观感：纯文字 + 14sp（公共组件默认是 logo + 16sp）
            showLogo = false,
            siteNameFontSize = 14.sp,
            onRefreshSite = { site -> controller.siteControllers[site.id]?.refreshData() },
            pageContent = { site, gridState ->
                val listController = controller.siteControllers[site.id]
                if (listController != null) {
                    // 本页结果列表：仅 AnchorResultRow 的分隔线判据需要「本页」列表长度。
                    // 订阅放在 pageContent 内（各页订各页的控制器），页面层不再订阅「当前站点」。
                    val pageList = listController.list.collectAsStateWithLifecycle().value
                    PagedRoomGridCore(
                        listController = listController,
                        // 网格滚动状态由组件按站点持有（不再是 remember(listController){…}）
                        gridState = gridState,
                        // 关闭首屏自动加载（对齐现版：进入搜索页不自动搜索——空关键词下
                        // 自动 loadData 会误置 pageEmpty，刚进页面就显示空态覆盖层）
                        autoFirstLoad = false,
                        // 未发起搜索 / 会话未开始（如进程重建后）时居中显示占位——
                        // 否则三态一个分支都不命中，结果区表现为一片空白
                        emptyPlaceholderText = "输入关键词搜索",
                        // 主播结果行跨整行展示（直播间卡片保持单格）
                        fullSpan = { it !is LiveRoomItem },
                        itemContent = { index, item, mainAxisExtentDp ->
                            when (item) {
                                is LiveRoomItem -> {
                                    // 固定行高 = mainAxisExtent（封面 16:9 高 + 文字区 64dp）
                                    Box(modifier = Modifier.fillMaxWidth().height(mainAxisExtentDp.dp)) {
                                        LiveRoomCard(
                                            item = item,
                                            onClick = { openRoom(site, item.roomId) },
                                            modifier = Modifier.fillMaxSize(),
                                            // 站点取自本页参数（不再是页面层按「当前站点」下标取）
                                            sharedElementSiteId = site.id,
                                        )
                                    }
                                }
                                is LiveAnchorItem -> {
                                    AnchorResultRow(
                                        item = item,
                                        onClick = { openRoom(site, item.roomId) },
                                        showDivider = index < pageList.lastIndex,
                                    )
                                }
                                else -> {
                                    // 兜底（对齐现版 adapter 的 else 分支：仅 toString 展示）
                                    Text(text = item.toString(), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        },
                    )
                } else {
                    // 控制器尚未就绪的兜底空页（正常流程不会出现）
                    Box(modifier = Modifier.fillMaxSize())
                }
            },
            // 底部手势导航条安全区 + 占满剩余高度（结果网格是本页滚动容器；
            // 与设置页/账号页同款做法）
            modifier = Modifier.weight(1f).navigationBarsPadding(),
        )
    }
}

/** 打开直播间（两种结果类型统一入口，对齐现版点击回调）。 */
private fun openRoom(site: Site, roomId: String) {
    Router.openLiveRoom(site, roomId)
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
                .background(MaterialTheme.colorScheme.surface)
                // 点击跳转（Bug 修复）：原链漏挂 clickable，主播搜索结果点了没反应。
                // 位置：在 background 之后、padding 之前 —— 内边距计入点击热区，水波纹铺满整行。
                .clickable(onClick = onClick)
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.userName,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(text = "主播", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = if (item.liveStatus) "直播中" else "未开播",
                fontSize = 12.sp,
                color = LiveIndicatorRed,
            )
        }
        if (showDivider) {
            HorizontalDivider()
        }
    }
}
