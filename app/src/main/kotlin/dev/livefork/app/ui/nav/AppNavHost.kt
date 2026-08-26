package dev.livefork.app.ui.nav

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.livefork.app.app.EventBus
import dev.livefork.app.app.Sites
import dev.livefork.app.modules.category.CategoryController
import dev.livefork.app.modules.home.HomeController
import dev.livefork.app.ui.Router
import dev.livefork.app.ui.screens.AccountScreen
import dev.livefork.app.ui.screens.CategoryDetailScreen
import dev.livefork.app.ui.screens.CategoryScreen
import dev.livefork.app.ui.screens.FollowScreen
import dev.livefork.app.ui.screens.HomeScreen
import dev.livefork.app.ui.screens.HistoryScreen
import dev.livefork.app.ui.screens.LiveRoomScreen
import dev.livefork.app.ui.screens.MineScreen
import dev.livefork.app.ui.screens.QRLoginScreen
import dev.livefork.app.ui.screens.SearchScreen
import dev.livefork.app.ui.screens.SettingsScreen
import dev.livefork.app.ui.screens.WebLoginScreen

/**
 * 应用导航宿主 —— 对应 Dart `lib/routes/pages.dart` 的路由表。
 * 单 Activity 架构：所有页面为 NavHost 内的 composable 路由；
 * 已注册 main、category/{siteId}（分类详情）、settings（设置）、history（观看历史）、
 * search（搜索）、account（账号管理）、weblogin/{siteId}（网页登录）、qrlogin（B 站扫码）、
 * follow（关注用户）、livertoom/{siteId}/{roomId}（直播间，Batch 7a）。
 */
@Composable
fun AppNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    // 壳层状态提升到 NavHost 层：路由切换只销毁目的地内容，本组合始终存活——
    // 进入分类详情等路由再返回时，三 Tab 的控制器数据与滚动状态原样保留
    //（对齐原版 Activity 栈行为：MainActivity 常驻栈底不重建）。
    val shell = remember { ShellState() }
    DisposableEffect(Unit) {
        onDispose { shell.dispose() }
    }

    NavHost(
        navController = navController,
        startDestination = "main",
        // 必须显式填满：否则 NavHost 包内容测量，竖屏壳层高度塌缩为底栏高度
        //（实测症状：内容区高度 0、底栏被垂直居中）
        modifier = modifier.fillMaxSize(),
    ) {
        // 三 Tab 壳（首页/分类/我的为内部状态切换，非独立路由）
        composable("main") { MainShell(shell) }

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
            CategoryDetailScreen(
                siteId = entry.arguments?.getString("siteId").orEmpty(),
                categoryId = entry.arguments?.getString("categoryId").orEmpty(),
                categoryName = entry.arguments?.getString("categoryName").orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }

        // 设置（Batch 4）：条目绑定 AppSettingsController，无路由参数
        composable("settings") { SettingsScreen() }

        // 观看历史（Batch 4）：控制器在 Screen 内 remember 创建、出栈销毁
        composable("history") { HistoryScreen() }

        // 搜索（Batch 5）：控制器在 Screen 内 remember 创建、出栈销毁
        composable("search") { SearchScreen() }

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
        // 转场动画（用户反馈：进入/退出直播间改为右侧滑入/滑出，替代默认渐变）：
        // - enter：从屏幕右缘滑入（initialOffsetX = it，即 100% 宽度偏移起步）；
        // - popExit：向屏幕右缘滑出（targetOffsetX = it），与 enter 镜像；
        // - exit / popEnter：保持淡出/淡入——「main」壳层是底栏页面，若跟随左移会出现
        //   「整个首页横移」的观感，淡入淡出让下层页面原地过渡、只有直播间层在滑动；
        // - 时长统一 tween(280)（250–300 区间取中）：固定时长比默认 spring 更可控，
        //   且与 leaveRoom() 的「先 GONE 等两帧再出栈」兼容——Surface 图层先消失、
        //   滑出动画随后播放，视频不会残留在滑动画面上。
        composable(
            route = "livertoom/{siteId}/{roomId}",
            arguments = listOf(
                navArgument("siteId") { type = NavType.StringType },
                navArgument("roomId") { type = NavType.StringType },
            ),
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
        ) { entry ->
            LiveRoomScreen(
                siteId = entry.arguments?.getString("siteId").orEmpty(),
                roomId = entry.arguments?.getString("roomId").orEmpty(),
                onBack = { navController.popBackStack() },
            )
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
    /** 首页/分类控制器（等价现版 homeView/categoryView 字段复用语义）。 */
    val homeController = HomeController()
    val categoryController = CategoryController()

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
    }
}

/**
 * 主界面三 Tab 壳 —— 对应 Flutter `home_page.dart` 的底部导航结构。
 * 内容区 + 底栏；三个 Tab 均已迁移为 Compose Screen。
 *
 * @param shell 壳层状态（由 AppNavHost 持有，跨路由导航存活）
 */
@Composable
private fun MainShell(shell: ShellState) {
    // 当前选中 Tab（rememberSaveable：进程重建后恢复选中态；路由往返经
    // NavHost 的 SaveableStateHolder 同样恢复）
    var currentIndex by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
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
                1 -> CategoryScreen(
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
        MainBottomBar(currentIndex) { index ->
            if (index == currentIndex) {
                // 对齐 Dart indexed_controller.setIndex：仅重复点击当前 Tab 才发射事件，
                // 且发射值为「页面标识」而非底栏槽位——Dart 发射 items[i].index
                // （Constant.allHomePages：首页 0 / 关注 1 / 分类 2 / 我的 3），
                // 订阅方按页面标识过滤：HomeController/首页 Screen 收 index==0、
                // CategoryScreen 收 index==2、FollowUserController 收 index==1。
                // 本 fork 底栏为 首页(槽0)/分类(槽1)/我的(槽2)，需做槽位→页面标识映射；
                // 若直接发射槽位，重按「分类」发 1 会静默失效、重按「我的」发 2 会误触发分类刷新。
                val pageIndex = when (index) {
                    0 -> 0   // 首页
                    1 -> 2   // 分类
                    else -> 3 // 我的
                }
                EventBus.emit(EventBus.kBottomNavigationBarClicked, pageIndex)
            } else {
                currentIndex = index
            }
        }
    }
}

/**
 * 底部导航栏 —— Material 3 默认观感（task 08-26-material3-ui-restyle）：
 * NavigationBarItem + 药丸选中指示器 + ripple 状态层，颜色跟随 colorScheme
 * （选中 onSecondaryContainer / 未选中 onSurfaceVariant，M3 语义）。
 * 移除旧版「白底、纯文字、无 indicator、去涟漪」的自定义观感
 * （main_nav.xml 无 icon，故 icon 位放平台文字、label 置空）。
 * 高度：按用户反馈定为 60dp（原项目 56dp 基础上调 4dp 微调；tab 下移 4dp 保留）。
 * 底部导航条 insets 由 NavigationBar 默认 windowInsets 处理。
 */
@Composable
private fun MainBottomBar(currentIndex: Int, onSelect: (Int) -> Unit) {
    val titles = listOf("首页", "分类", "我的")

    NavigationBar(modifier = Modifier.height(60.dp)) {
        titles.forEachIndexed { index, title ->
            NavigationBarItem(
                selected = currentIndex == index,
                onClick = { onSelect(index) },
                modifier = Modifier.offset(y = 4.dp),
                icon = {
                    Text(text = title, fontSize = 13.sp)
                },
                label = null,
            )
        }
    }
}
