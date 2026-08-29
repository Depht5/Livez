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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.kotlin_livez.app.di.ServiceContainer
import com.kotlin_livez.app.ui.Router
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

        Router.install(applicationContext)

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
            }
        }
    }
}
