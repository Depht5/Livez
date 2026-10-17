package com.kotlin_livez.app.ui.nav

import android.net.Uri
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.kotlin_livez.app.app.EventBus
import com.kotlin_livez.app.app.Sites
import com.kotlin_livez.app.di.ServiceContainer
import com.kotlin_livez.app.modules.category.CategoryController
import com.kotlin_livez.app.modules.follow_user.FollowUserController
import com.kotlin_livez.app.modules.home.HomeController
import com.kotlin_livez.app.ui.Router
import com.kotlin_livez.app.ui.useWideLayout
import com.kotlin_livez.app.ui.screens.AccountScreen
import com.kotlin_livez.app.ui.screens.CategoryDetailScreen
import com.kotlin_livez.app.ui.screens.CategoryScreen
import com.kotlin_livez.app.ui.screens.FollowScreen
import com.kotlin_livez.app.ui.screens.HomeScreen
import com.kotlin_livez.app.ui.screens.HistoryScreen
import com.kotlin_livez.app.ui.screens.LiveRoomScreen
import com.kotlin_livez.app.ui.screens.MineScreen
import com.kotlin_livez.app.ui.screens.QRLoginScreen
import com.kotlin_livez.app.ui.screens.SearchScreen
import com.kotlin_livez.app.ui.screens.SettingsScreen
import com.kotlin_livez.app.ui.theme.CustomIcons
import com.kotlin_livez.app.ui.screens.WebLoginScreen

/**
 * 应用导航宿主 —— 对应 Dart `lib/routes/pages.dart` 的路由表。
 * 单 Activity 架构：所有页面为 NavHost 内的 composable 路由；
 * 已注册 main、category/{siteId}（分类详情）、settings（设置）、history（观看历史）、
 * search（搜索）、account（账号管理）、weblogin/{siteId}（网页登录）、qrlogin（B 站扫码）、
 * follow（关注用户）、livertoom/{siteId}/{roomId}（直播间，Batch 7a）。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    // 壳层状态提升到 NavHost 层：路由切换只销毁目的地内容，本组合始终存活——
    // 进入分类详情等路由再返回时，三 Tab 的控制器数据与滚动状态原样保留
    //（对齐原版 Activity 栈行为：MainActivity 常驻栈底不重建）。
    val shell = remember { ShellState() }
    DisposableEffect(Unit) {
        onDispose { shell.dispose() }
    }

    // 一镜到底共享元素（task 09-12-shared-element-transition）：SharedTransitionLayout
    // 包裹 NavHost，卡片封面 ↔ 直播间视频容器经 LocalSharedTransitionScope 配对；
    // 各路由经 LocalNavAnimatedVisibilityScope 下发 AnimatedContentScope（见各 composable）。
    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
            NavHostContent(navController, shell, modifier)
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.NavHostContent(
    navController: NavHostController,
    shell: ShellState,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = "main",
        // 必须显式填满：否则 NavHost 包内容测量，竖屏壳层高度塌缩为底栏高度
        //（实测症状：内容区高度 0、底栏被垂直居中）
        modifier = modifier.fillMaxSize(),
        // 全局页面转场（task 08-29-nav-anim-danmu-panel，用户反馈：所有页面统一为
        // 进入直播间的右侧滑入动画）：
        // - enter：新页面从屏幕右缘滑入（initialOffsetX = it，100% 宽度偏移起步）；
        // - popExit：顶层页面向屏幕右缘滑出，与 enter 镜像；
        // - exit / popEnter：原地淡出/淡入——「main」壳层等下层页面保持不横移，
        //   只有新页面层在滑动；「main」作为被覆盖方仅参与 fade（出入场观感不变）；
        // - 时长统一 tween(280)（250–300 区间取中，沿用原直播间转场取值）：固定时长比
        //   默认 spring 更可控，且与 leaveRoom() 的「先 GONE 等两帧再出栈」兼容——
        //   Surface 图层先消失、滑出动画随后播放，视频不会残留在滑动画面上。
        enterTransition = {
            slideInHorizontally(animationSpec = tween(280)) { fullX -> fullX }
        },
        exitTransition = {
            fadeOut(animationSpec = tween(280))
        },
        popEnterTransition = {
            fadeIn(animationSpec = tween(280))
        },
        popExitTransition = {
            slideOutHorizontally(animationSpec = tween(280)) { fullX -> fullX }
        },
    ) {
        // 三 Tab 壳（首页/分类/我的为内部状态切换，非独立路由）
        // 参与一镜到底的路由将 AnimatedContentScope 下发给页面（卡片封面共享元素用）。
        composable("main") {
            CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                MainShell(shell)
            }
        }

        // 分类详情：siteId 走 path 参数；categoryId/categoryName 为可选 query 参数
        //（导航时经 Uri.encode 编码，NavController 解析后自动解码）
        composable(
            route = "category/{siteId}?categoryId={categoryId}&categoryName={categoryName}",
            arguments = listOf(
                navArgument("siteId") { type = NavType.StringType },
                navArgument("categoryId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("categoryName") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                CategoryDetailScreen(
                    siteId = entry.arguments?.getString("siteId").orEmpty(),
                    categoryId = entry.arguments?.getString("categoryId").orEmpty(),
                    categoryName = entry.arguments?.getString("categoryName").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
        }

        // 设置（Batch 4）：条目绑定 AppSettingsController，无路由参数
        composable("settings") { SettingsScreen() }

        // 观看历史（Batch 4）：控制器在 Screen 内 remember 创建、出栈销毁
        composable("history") { HistoryScreen() }

        // 搜索（Batch 5）：控制器在 Screen 内 remember 创建、出栈销毁
        composable("search") {
            CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                SearchScreen()
            }
        }

        // 账号管理（Batch 5）：对话框/登录入口在 Screen 内，无路由参数
        composable("account") { AccountScreen() }

        // 网页登录（Batch 5）：siteId 走 path 参数（导航时经 Uri.encode 编码，
        // NavController 解析后自动解码），按值选择 B 站/抖音登录控制器
        composable(
            route = "weblogin/{siteId}",
            arguments = listOf(
                navArgument("siteId") { type = NavType.StringType },
            ),
        ) { entry ->
            WebLoginScreen(
                siteId = entry.arguments?.getString("siteId").orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }

        // B 站扫码登录（Batch 5）：generate + 3s 轮询在控制器内，无路由参数
        composable("qrlogin") {
            QRLoginScreen(onBack = { navController.popBackStack() })
        }

        // 关注用户（Batch 6）：控制器在 Screen 内 remember 创建、出栈销毁；
        // 底栏重按关注 Tab 的刷新由 FollowUserController 内部订阅处理，无需路由层介入
        composable("follow") { FollowScreen() }

        // 直播间（Batch 7a）：siteId/roomId 走 path 参数（导航时经 Uri.encode 编码，
        // NavController 解析后自动解码）；控制器在 Screen 内 remember 创建 + DisposableEffect
        // 销毁（等价原 LiveRoomActivity onDestroy：controller.dispose() + videoView.destroy()）
        //
        // 转场动画：原直播间专用右滑转场已提升为 NavHost 全局默认（见上方 NavHost 参数，
        // task 08-29-nav-anim-danmu-panel）。一镜到底（task 09-12-shared-element-transition）：
        // 本路由 enter/popExit 覆盖为 fade——共享元素封面飞行为主视觉，右滑与其叠加会打架。
        composable(
            route = "livertoom/{siteId}/{roomId}",
            arguments = listOf(
                navArgument("siteId") { type = NavType.StringType },
                navArgument("roomId") { type = NavType.StringType },
            ),
            enterTransition = { fadeIn(animationSpec = tween(280)) },
            popExitTransition = { fadeOut(animationSpec = tween(280)) },
        ) { entry ->
            CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                LiveRoomScreen(
                    siteId = entry.arguments?.getString("siteId").orEmpty(),
                    roomId = entry.arguments?.getString("roomId").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

/**
 * 三 Tab 壳层状态持有者 —— 对齐现版 MainActivity 的字段生命周期：
 * homeView/categoryView 字段（控制器）随壳层存活，
 * 跨底栏 Tab 切换与路由导航（如进分类详情再返回）均不重建、不丢数据。
 * 进程重建后整体重建并重新首屏加载（与原版「Activity 重建即全量重建」一致）。
 */
private class ShellState {
    /** 首页/分类/关注控制器（等价现版 homeView/categoryView 字段复用语义）。 */
    val homeController = HomeController()
    val categoryController = CategoryController()

    /** 关注用户控制器（task follow-tab-and-room-follow：关注为底栏 Tab，随壳层存活）。 */
    val followController = FollowUserController(
        appSettings = ServiceContainer.appSettings,
        followService = ServiceContainer.followService,
        dbService = ServiceContainer.dbService,
        currentRoom = ServiceContainer.currentRoom,
    )

    /** 分类页各站点网格滚动状态（键 = 站点 id）：随壳层存活，跨 Tab 切换/路由导航不丢位置。 */
    val categoryGridStates: Map<String, LazyGridState> =
        Sites.supportSites.associate { it.id to LazyGridState() }

    /** 首页各站点网格滚动状态（键 = 站点 id）：语义同上，与分类页保持同一壳层持有范式。 */
    val homeGridStates: Map<String, LazyGridState> =
        Sites.supportSites.associate { it.id to LazyGridState() }

    /** 离开组合（Activity 销毁）时释放各页面控制器资源（对齐原 MainActivity.onDestroy 语义）。 */
    fun dispose() {
        homeController.dispose()
        categoryController.dispose()
        followController.dispose()
    }
}

/**
 * 主界面四 Tab 壳 —— 对应 Flutter `home_page.dart` 的底部导航结构
 * （task follow-tab-and-room-follow：加入「关注」Tab；Tab 顺序 首页/关注/分类/我的）。
 * 内容区 + 底栏；四个 Tab 均已迁移为 Compose Screen。
 *
 * @param shell 壳层状态（由 AppNavHost 持有，跨路由导航存活）
 */
@Composable
private fun MainShell(shell: ShellState) {
    // 当前选中 Tab（rememberSaveable：进程重建后恢复选中态；路由往返经
    // NavHost 的 SaveableStateHolder 同样恢复）
    var currentIndex by rememberSaveable { mutableIntStateOf(0) }

    // Tab 状态持有者提升到分支之外（task 10-07-tablet-landscape-layout）：宽/窄两分支
    // 在组合树中的位置不同，若把 holder 放在分支内，横竖屏切换会因分支切换而重建、
    // 各 Tab 的 rememberSaveable（选中站点/分页器位置）随之丢失。放在 MainShell 层
    // 则跨旋转存活，语义对齐 Dart 的 IndexedStack（pages 由控制器持有、旋转不丢）。
    val tabStateHolder = rememberSaveableStateHolder()

    // 选中/重按的唯一入口（宽/窄两分支共用，避免映射逻辑写两遍）：
    // 对齐 Dart indexed_controller.setIndex —— 仅重复点击当前 Tab 才发射事件，
    // 且发射值为「页面标识」而非底栏槽位（Constant.allHomePages：首页 0 / 关注 1 / 分类 2 / 我的 3），
    // 订阅方按页面标识过滤：HomeController/首页 Screen 收 index==0、
    // FollowUserController 收 index==1（回顶/刷新）、CategoryScreen 收 index==2。
    // 本 fork 底栏为 首页(槽0)/关注(槽1)/分类(槽2)/我的(槽3)——对调后槽位与 Dart 页面标识
    // 恰好一致，直接透传。
    val onSelectTab: (Int) -> Unit = { index ->
        if (index == currentIndex) {
            EventBus.emit(EventBus.kBottomNavigationBarClicked, index)
        } else {
            currentIndex = index
        }
    }

    if (useWideLayout()) {
        // 大屏（平板横屏，task 10-07-tablet-landscape-layout）：底栏整条不组合，改为左侧
        // NavigationRail 紧凑左上对齐（对齐 Dart indexed_page.dart 横屏分支的
        // NavigationRail + `Visibility(visible: portrait)` 底栏）。
        Row(modifier = Modifier.fillMaxSize()) {
            MainNavigationRail(currentIndex, onSelectTab)
            // 内容区左侧 1px 分隔线（对齐原项目 `Border.left` Colors.grey.withAlpha(50)）
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            Box(modifier = Modifier.weight(1f)) {
                MainTabContent(currentIndex, shell, tabStateHolder)
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                MainTabContent(currentIndex, shell, tabStateHolder)
            }
            MainBottomBar(currentIndex, onSelectTab)
        }
    }
}

/**
 * Tab 内容区 —— 宽/窄两分支**共用的唯一实现**（勿在两分支各写一份 `when`，否则必然漂移）。
 *
 * SaveableStateProvider（task 08-29-scroll-restore-follow-refresh）：底栏/侧栏 Tab 切换时
 * 保留各 Tab 的 rememberSaveable 状态。根因（真机实证：切换后首页/分类回落第一站点顶部）：
 * 此前 `when` 分支直接销毁重建、无状态保存——首页/分类的 selectedSiteIndex 与
 * rememberPagerState 均为 rememberSaveable，丢弃后切回即重置。各站点 LazyGridState 由壳层
 * 持有本就存活，恢复站点索引后位置随之恢复。路由导航由 NavHost 自带 SaveableStateProvider
 * 保存（进直播间返回不丢），与本层嵌套互不影响。
 *
 * **不得**在本 provider 内部套常驻 `graphicsLayer` 容器（Phase 2.3 真机回归修正）：
 * 底栏/侧栏切换的 120ms 微淡入曾写成 provider 内唯一的根
 * `Box(Modifier.fillMaxSize().graphicsLayer { alpha = tabAlpha.value })`，真机上点击底栏切换后
 * **上方画面冻结在上一页**（底栏选中态与触摸命中都已切到新页，直到进/出直播间二级路由才恢复）。
 * 根因：`SaveableStateProvider(currentIndex)` 底层由 `ReusableContent(currentIndex)` 驱动，切换 key 时
 * 会**原地复用直接根 `LayoutNode`**；被复用根节点上的硬件加速图层 DisplayList 离屏缓存失步冻结在
 * 旧页最后一帧，而 hit test 仍遍历语义树命中新分支。故恢复为 provider 直接包裹 `when (currentIndex)`
 * （与原版 Dart `IndexedStack` 底栏无过渡直切一致）；要再加重绘过渡，只能放在 provider **之外**。
 */
@Composable
private fun MainTabContent(
    currentIndex: Int,
    shell: ShellState,
    tabStateHolder: SaveableStateHolder,
) {
    tabStateHolder.SaveableStateProvider(currentIndex) {
        when (currentIndex) {
            0 -> HomeScreen(
                controller = shell.homeController,
                gridStates = shell.homeGridStates,
                onOpenLiveRoom = { site, roomId ->
                    // 直播间已迁路由（Batch 7）：Router.openLiveRoom 导航至
                    // livertoom/{siteId}/{roomId}（siteId/roomId 经 URL 编码）
                    Router.openLiveRoom(site, roomId)
                },
            )
            1 -> FollowScreen(controller = shell.followController)
            2 -> CategoryScreen(
                controller = shell.categoryController,
                gridStates = shell.categoryGridStates,
                onOpenCategory = { site, sub ->
                    // 分类详情已迁路由（本批）：path/query 字符串传参，
                    // 特殊字符经 URL 编码（NavController 解析后自动解码）
                    val route = "category/${Uri.encode(site.id)}" +
                        "?categoryId=${Uri.encode(sub.id)}" +
                        "&categoryName=${Uri.encode(sub.name)}"
                    Router.navigate(route)
                },
            )
            else -> MineScreen()
        }
    }
}

/**
 * 左侧导航栏（平板横屏）—— 对齐 Dart `indexed_page.dart` 的横屏 NavigationRail：
 *
 * | 原项目 | 此处 |
 * |---|---|
 * | `labelType: NavigationRailLabelType.none`（纯图标无文字） | `label = null` + `alwaysShowLabel = false` |
 * | `groupAlignment: -1`（**顶部对齐**） | M3 `NavigationRail` 内部为居中排列，故在末尾追加 `Spacer(weight(1f))` 把各项顶到左上角 |
 * | `padding: edgeInsetsV8` | 每项 `padding(vertical = 4.dp)` |
 * | 图标/选中态 | 复用底栏的 `CustomIcons` 四项与显式配色（选中 onSecondaryContainer / 未选中 onSurfaceVariant） |
 * | 无障碍 | label 置空但保留 `contentDescription`（底栏既有约定） |
 */
@Composable
private fun MainNavigationRail(currentIndex: Int, onSelect: (Int) -> Unit) {
    val titles = listOf("首页", "关注", "分类", "我的")
    val icons = listOf(
        CustomIcons.Home,
        CustomIcons.HeartOutline,
        CustomIcons.Category,
        CustomIcons.Mine,
    )

    NavigationRail(
        modifier = Modifier.fillMaxHeight(),
        // 容器色与底栏 NavigationBar 一致（surfaceContainer；纯黑模式下该槽位同样不覆盖）
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        titles.forEachIndexed { index, title ->
            val selected = currentIndex == index
            NavigationRailItem(
                selected = selected,
                onClick = { onSelect(index) },
                modifier = Modifier.padding(vertical = 4.dp),
                icon = {
                    Icon(
                        imageVector = icons[index],
                        contentDescription = title,
                        tint = if (selected) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(24.dp),
                    )
                },
                label = null,
                alwaysShowLabel = false,
            )
        }
        // 紧凑左上对齐：可伸缩占位吃掉剩余高度（M3 NavigationRail 内部按居中排列，
        // 无此占位时四项会垂直居中而非贴顶）
        Spacer(modifier = Modifier.weight(1f))
    }
}

/**
 * 底部导航栏 —— Material 3 默认观感（task 08-26-material3-ui-restyle）：
 * NavigationBarItem + 药丸选中指示器 + ripple 状态层，颜色跟随 colorScheme。
 * 图标化（task 08-28-bottom-tab-icons）：icon 槽由 13sp 文字占位改为 CustomIcons 图标
 * （Home / HeartOutline[关注与直播间关注按钮同源复用] / Category / Mine），**纯图标无文字**
 * （label 置空，用户决策）；选中 onSecondaryContainer / 未选中 onSurfaceVariant 显式着色
 * （不依赖 NavigationBarItem 的 LocalContentColor 内部实现）。
 * 高度：60 → 68dp（适配 24dp 图标 + label；tab 下移 4dp 保留）。
 * 底部导航条 insets 由 NavigationBar 默认 windowInsets 处理。
 */
@Composable
private fun MainBottomBar(currentIndex: Int, onSelect: (Int) -> Unit) {
    val titles = listOf("首页", "关注", "分类", "我的")
    val icons = listOf(
        CustomIcons.Home,
        CustomIcons.HeartOutline,
        CustomIcons.Category,
        CustomIcons.Mine,
    )

    NavigationBar(modifier = Modifier.height(68.dp)) {
        titles.forEachIndexed { index, title ->
            val selected = currentIndex == index
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(index) },
                modifier = Modifier.offset(y = 4.dp),
                icon = {
                    Icon(
                        imageVector = icons[index],
                        contentDescription = title,
                        tint = if (selected) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(24.dp),
                    )
                },
                label = null,
            )
        }
    }
}
