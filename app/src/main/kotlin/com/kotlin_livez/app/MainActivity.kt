package com.kotlin_livez.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.kotlin_livez.app.di.ServiceContainer
import com.kotlin_livez.app.ui.Router
import com.kotlin_livez.app.ui.components.UpdateHost
import com.kotlin_livez.app.ui.deviceOrientationPolicy
import com.kotlin_livez.app.ui.nav.AppNavHost
import com.kotlin_livez.app.ui.theme.AppTheme
import com.kotlin_livez.app.ui.theme.appThemeResolvedDark

/**
 * 主界面 —— 对应 Flutter 端 `lib/main.dart` 根结构 + `home_page.dart` 的底部导航。
 * 单 Activity + Compose Navigation 宿主：页面路由集中在 ui/nav/AppNavHost.kt，
 * 三 Tab 壳（首页/分类/我的）由 main 路由内的 MainShell 承载。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge 迁移（targetSdk 35 / androidx.activity 1.8+）：替代原 LiveApp 中手动
        // setDecorFitsSystemWindows(false) + 透明系统栏 + light bars 三件套（API 35 已弃用
        // statusBarColor/navigationBarColor setter）。默认 SystemBarStyle.auto：API 29+ 系统
        // 栏纯透明、浅色 app → 深色图标（与旧行为一致）；API 26-28 导航栏自动加融合 scrim。
        // （深色生效时的图标明暗切换见下方 DisposableEffect——auto 只跟随系统，不感知应用内手动切换）
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 大屏布局（task 10-07-tablet-landscape-layout）：Manifest 不再硬锁竖屏——平板需自由
        // 旋转才能进入横屏大屏布局（底栏左置 / 直播间左右分栏）。方向策略改为按设备档位在
        // 运行时决定：平板释放全部方向交给系统，紧凑手机锁竖屏（保持既有行为与手感）。
        // 对齐 Dart 原项目 resetPreferredOrientation："Tablets keep the OS orientation policy
        // so their layouts are not forced through portrait."（见 ui/WideLayout.kt）
        requestedOrientation = deviceOrientationPolicy()

        Router.install(applicationContext)

        // 前后台切换转发（task fullscreen-status-follow-style-background-play）：镜像 Dart
        // didChangeAppLifecycleState——ON_STOP/ON_START 转发给当前直播间控制器（后台播放
        // 快照 + 回前台停滞恢复）；无活跃直播间时为 no-op。
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    ServiceContainer.activeLiveRoom?.onAppForeground()
                }

                override fun onStop(owner: LifecycleOwner) {
                    ServiceContainer.activeLiveRoom?.onAppBackground()
                }
            },
        )

        // 预测性返回应用内开关的运行时拦截已移至 setContent 内 AppNavHost 之后的
        // Compose BackHandler（见下方注释）：OnBackPressedDispatcher 按"最后注册的
        // 启用回调优先"，onCreate 期注册的回调早于 NavHost 内部预测性回调、永远
        // 抢不到事件（真机实证：开关关闭后预览仍播放）。

        setContent {
            // ===== 主题状态（task 08-29-dark-mode）=====
            // 动态取色/深色模式/纯黑主题均为状态驱动即时切换，无需重建 Activity。
            // 深色解析唯一出口：2 手动深色 / 1 手动浅色 / 0 跟随系统（AppTheme 与系统栏共用）。
            val dynamicColor by ServiceContainer.appSettings.isDynamic
                .collectAsStateWithLifecycle()
            val themeMode by ServiceContainer.appSettings.themeMode
                .collectAsStateWithLifecycle()
            val amoledBlack by ServiceContainer.appSettings.amoledBlack
                .collectAsStateWithLifecycle()
            val dark = when (themeMode) {
                2 -> true
                1 -> false
                else -> isSystemInDarkTheme()
            }
            // 组合外镜像（ui.theme.appThemeResolvedDark）：effect/回调内读取用
            //（CompositionLocal.current 不可在非 Composable 上下文读）
            SideEffect { appThemeResolvedDark = dark }

            // 系统栏图标明暗跟随生效主题：深色 → 浅色图标（透明 scrim，API 26-28 导航栏
            // 无法深色图标化、保持浅色）；浅色 → enableEdgeToEdge() 默认 auto（现状行为）。
            DisposableEffect(dark) {
                if (dark) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                        navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                    )
                } else {
                    enableEdgeToEdge()
                }
                onDispose { }
            }

            AppTheme(dark = dark, amoledBlack = amoledBlack, dynamicColor = dynamicColor) {
                val navController = rememberNavController()
                // 注入 Router 双模式导航；离开组合时清空引用避免泄漏
                DisposableEffect(navController) {
                    Router.navController = navController
                    onDispose { Router.navController = null }
                }
                // 根级填满窗口（NavHost 内部包内容测量，必须由根约束撑起）。
                // 背景铺 colorScheme.background（task 08-29-dark-mode 真机反馈：深色下页面
                // 转场发白）——NavHost 自身不画背景，转场淡出/淡入的透明区域透出的是窗口
                // 背景（Activity DayNight 主题、只跟系统不跟应用内切换）；铺主题底色后
                // 转场揭示区域与页面底色一致，纯黑模式下同为纯黑。
                AppNavHost(
                    navController = navController,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                )

                // 更新宿主（task 10-21-settings-about-update-check）：冷启动静默检查 +
                // 全局「检测到新版本」弹窗 + 下载进度/结果兜底。放在 NavHost 之后、根组合内，
                // 保证任何页面之上都能弹（Dialog 自成窗口，不参与页面布局）。
                UpdateHost()

                // 预测性返回已整体移除（task 09-12-predictive-back 系列真机结论：seek 负载
                // 结构性过高 + 系统默认提交动画叠加掉帧；Manifest 未设
                // enableOnBackInvokedCallback，NavHost 内部处理器自然退化为无预览的直接
                // pop，手势返回开销与按钮返回一致）。页面级 BackHandler（全屏退全屏、关
                // 弹层）保留。
            }
        }
    }
}
