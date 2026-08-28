package com.kotlin_livez.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.kotlin_livez.app.di.ServiceContainer
import com.kotlin_livez.app.ui.Router
import com.kotlin_livez.app.ui.nav.AppNavHost
import com.kotlin_livez.app.ui.theme.AppTheme

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
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        Router.install(applicationContext)

        setContent {
            // 动态取色开关（设置 → 外观）：状态驱动主题即时切换，无需重建 Activity。
            // API < 31 时 AppTheme 内部自动回退 M3 默认色板。
            val dynamicColor by ServiceContainer.appSettings.isDynamic
                .collectAsStateWithLifecycle()
            AppTheme(dynamicColor = dynamicColor) {
                val navController = rememberNavController()
                // 注入 Router 双模式导航；离开组合时清空引用避免泄漏
                DisposableEffect(navController) {
                    Router.navController = navController
                    onDispose { Router.navController = null }
                }
                // 根级填满窗口（NavHost 内部包内容测量，必须由根约束撑起）
                AppNavHost(
                    navController = navController,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
