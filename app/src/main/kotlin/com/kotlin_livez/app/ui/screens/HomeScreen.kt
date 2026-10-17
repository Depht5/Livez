package com.kotlin_livez.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kotlin_livez.app.app.Site
import com.kotlin_livez.app.modules.home.HomeController
import com.kotlin_livez.app.ui.Router
import com.kotlin_livez.app.ui.components.PagedRoomGrid
import com.kotlin_livez.app.ui.components.SitePagerTabs
import com.kotlin_livez.app.ui.theme.CustomIcons

/**
 * 首页（Compose 版）—— 对应 Dart `modules/home/home_page.dart`（站点 TabBar）+
 * `home_list_view.dart`（推荐直播间网格），替换现版 View 体系 `ui/HomeView.kt`。
 *
 * 结构镜像：顶部站点 Tab（logo 24dp + 8dp 间距 + 文字 16sp，选中红系 #FF3F4B /
 * 未选中 #666666 手动切色，指示条 #FF3F4B；装得下时水平居中）+
 * 站点横向翻页器（每页一个推荐直播间网格：下拉刷新 + 触底分页 + 错误/空态覆盖层），
 * Tab 点击与滑动手势双向同步；行尾固定搜索入口（48dp）。
 *
 * 标签行与分页器（含「切换平台跳过中间页」两页过渡）由公共组件 [SitePagerTabs] 承载
 * ——与分类页共用同一实现（原两份逐行同构的 SiteTabRow 已收敛）。
 * 本页只声明「本页是什么」：底栏下标（2026-10 收敛后 选中站点索引 / Pager 状态 /
 * 「重按首页 Tab → 回顶或刷新」监听全部内聚在 [SitePagerTabs] 内，见其注释）。
 *
 * 各站点列表控制器由 [HomeController] 壳层持有（跨底栏 Tab 切换存活）；
 * 滚动状态按站点各持一个 [LazyGridState]，切回站点不丢位置。
 *
 * @param controller 首页控制器（由壳层 ShellState 持有）
 * @param gridStates 各站点网格滚动状态（键 = 站点 id，由壳层持有——路由往返不丢位置）
 * @param onOpenLiveRoom 点击卡片回调（进直播间路由 livertoom/{siteId}/{roomId}）
 */
@Composable
fun HomeScreen(
    controller: HomeController,
    gridStates: Map<String, LazyGridState>,
    onOpenLiveRoom: (site: Site, roomId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sites = controller.sites

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding(),
    ) {
        SitePagerTabs(
            sites = sites,
            gridStates = gridStates,
            // 首页指示条/选中色跟随 primary（红系 #FF3F4B 的 M3 映射）
            indicatorColor = MaterialTheme.colorScheme.primary,
            // 底栏「重按首页 Tab」事件载荷 = 底栏下标（首页 = 0）：
            // 有滚动偏移则回顶、否则刷新选中站点，逻辑在 SitePagerTabs 内
            bottomNavTabIndex = 0,
            onRefreshSite = { site -> controller.homeListControllers[site.id]?.refreshData() },
            pageContent = { pageSite, gridState ->
                val listController = controller.homeListControllers[pageSite.id]
                if (listController != null) {
                    // 推荐网格：三态覆盖由 PagedRoomGrid 公共组件承载（指示器色跟随 secondary）
                    PagedRoomGrid(
                        listController = listController,
                        gridState = gridState,
                        onOpenRoom = { roomId -> onOpenLiveRoom(pageSite, roomId) },
                        indicatorColor = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.fillMaxSize(),
                        siteId = pageSite.id,
                    )
                } else {
                    // 控制器尚未就绪的兜底空页（正常流程不会出现）
                    Box(modifier = Modifier.fillMaxSize())
                }
            },
            modifier = Modifier.fillMaxSize(),
            // 行尾固定搜索入口：48dp 触控区 + 23dp 图标（用户定标；task mine-page-cleanup）
            trailing = {
                // 无界圆形涟漪（本任务）：48dp 图标按钮统一 ripple(bounded = false, radius = 20.dp)。
                // 父级（SiteTabRow 的 Row 与本页 Column）均无 clip，涟漪不会被裁剪。
                val interactionSource = remember { MutableInteractionSource() }
                val indication = remember { ripple(bounded = false, radius = 20.dp) }
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .size(48.dp)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = indication,
                            onClick = { Router.openFeature("search", "搜索") },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = CustomIcons.Search,
                        contentDescription = "搜索",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(23.dp),
                    )
                }
            },
        )
    }
}
