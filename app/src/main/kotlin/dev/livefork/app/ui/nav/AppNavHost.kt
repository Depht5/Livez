package dev.livefork.app.ui.nav

import android.net.Uri
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.livefork.app.app.EventBus
import dev.livefork.app.app.Sites
import dev.livefork.app.modules.category.CategoryController
import dev.livefork.app.modules.home.HomeController
import dev.livefork.app.ui.MineView
import dev.livefork.app.ui.Router
import dev.livefork.app.ui.screens.CategoryDetailScreen
import dev.livefork.app.ui.screens.CategoryScreen
import dev.livefork.app.ui.screens.HomeScreen
import dev.livefork.app.ui.theme.TextPrimary
import dev.livefork.app.ui.theme.TextSecondary

/**
 * 应用导航宿主 —— 对应 Dart `lib/routes/pages.dart` 的路由表。
 * 单 Activity 架构：所有页面为 NavHost 内的 composable 路由；
 * 已注册 main 与 category/{siteId}（分类详情），其余路由按批次逐步迁入：
 * settings / history / search / account / follow /
 * weblogin/{siteId} / qrlogin / livertoom/{siteId}/{roomId}。
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
        modifier = modifier,
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

        // ===== 后续批次扩展的路由清单 =====
        // composable("settings") { ... }             // 设置（Batch 4）
        // composable("history") { ... }              // 历史（Batch 4）
        // composable("search") { ... }               // 搜索（Batch 5）
        // composable("account") { ... }              // 账号管理（Batch 5）
        // composable("follow") { ... }               // 关注（Batch 6）
        // composable("weblogin/{siteId}") { ... }    // WebView 登录（Batch 5）
        // composable("qrlogin") { ... }              // B 站扫码登录（Batch 5）
        // composable("livertoom/{siteId}/{roomId}") { ... } // 直播间（Batch 7）
    }
}

/**
 * 三 Tab 壳层状态持有者 —— 对齐现版 MainActivity 的字段生命周期：
 * homeView/categoryView 字段（控制器）与 mineView 实例缓存随壳层存活，
 * 跨底栏 Tab 切换与路由导航（如进分类详情再返回）均不重建、不丢数据。
 * 进程重建后整体重建并重新首屏加载（与原版「Activity 重建即全量重建」一致）。
 */
private class ShellState {
    /** 我的 Tab 过渡期传统 View 实例缓存（首次切到才创建，之后复用）。 */
    var mineView: MineView? = null

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
        mineView?.onDestroy()
    }
}

/**
 * 主界面三 Tab 壳 —— 对应 Flutter `home_page.dart` 的底部导航结构。
 * 内容区 + 底栏；首页/分类已迁移为 Compose Screen，我的过渡期仍为传统 View
 * （AndroidView 互操作包裹），后续批次替换为 Compose Screen。
 *
 * @param shell 壳层状态（由 AppNavHost 持有，跨路由导航存活）
 */
@Composable
private fun MainShell(shell: ShellState) {
    val context = LocalContext.current

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
                        // 直播间仍为旧 Activity（Batch 7 才迁路由），入口保持 Router.openLiveRoom
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
                else -> InteropTab(shell.mineView, { shell.mineView = it }) { MineView(context) }
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
 * 过渡期互操作容器：把旧 View 包进 AndroidView。
 * factory 在每次重新入组合时都会调用，经 cache/register 复用既有实例，
 * 等价原版 FrameLayout 切换时复用同一 View（不丢滚动位置与加载状态）。
 * 各 Tab 替换为 Compose Screen 后本组件随之删除。
 */
@Composable
private fun <T : View> InteropTab(
    existing: T?,
    register: (T) -> Unit,
    create: () -> T,
) {
    AndroidView(factory = { existing ?: create().also(register) })
}

/**
 * 底部导航栏：白底、无阴影（tonalElevation = 0）、去涟漪。
 *
 * 取舍说明：M3 NavigationBar 自带 indicator 药丸与固定图标槽位，而现版
 * BottomNavigationView 的菜单项只有文字（res/menu/main_nav.xml 无 icon），
 * 故 item 区域改放自定义朴素布局：纯文字、选中黑字（#333）/未选灰字（#666）、
 * 点击无涟漪，视觉对齐现版的简洁样式而非 M3 默认观感。
 */
@Composable
private fun MainBottomBar(currentIndex: Int, onSelect: (Int) -> Unit) {
    val titles = listOf("首页", "分类", "我的")

    NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
        titles.forEachIndexed { index, title ->
            // NavigationBar 的 content 即 RowScope，item 直接以扩展函数放置
            MainBottomBarItem(
                title = title,
                selected = currentIndex == index,
                onClick = { onSelect(index) },
            )
        }
    }
}

/** 单个底栏 item：等分宽度、居中文字、去涟漪点击。 */
@Composable
private fun RowScope.MainBottomBarItem(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null, // 移除点击涟漪（对齐现版 itemRippleColor = TRANSPARENT）
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            fontSize = 14.sp,
            color = if (selected) TextPrimary else TextSecondary,
        )
    }
}
