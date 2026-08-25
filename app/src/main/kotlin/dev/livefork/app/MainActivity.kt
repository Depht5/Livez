package dev.livefork.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.navigation.compose.rememberNavController
import dev.livefork.app.ui.Router
import dev.livefork.app.ui.nav.AppNavHost
import dev.livefork.app.ui.theme.AppTheme

/**
 * 主界面 —— 对应 Flutter 端 `lib/main.dart` 根结构 + `home_page.dart` 的底部导航。
 * 单 Activity + Compose Navigation 宿主：页面路由集中在 ui/nav/AppNavHost.kt，
 * 三 Tab 壳（首页/分类/我的）由 main 路由内的 MainShell 承载。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Router.install(applicationContext)

        setContent {
            AppTheme {
                val navController = rememberNavController()
                // 注入 Router 双模式导航；离开组合时清空引用避免泄漏
                DisposableEffect(navController) {
                    Router.navController = navController
                    onDispose { Router.navController = null }
                }
                AppNavHost(navController)
            }
        }
    }
}
