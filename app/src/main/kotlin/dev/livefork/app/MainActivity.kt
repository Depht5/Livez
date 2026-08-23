package dev.livefork.app

import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import dev.livefork.app.app.EventBus
import dev.livefork.app.ui.CategoryView
import dev.livefork.app.ui.HomeView
import dev.livefork.app.ui.MineView
import dev.livefork.app.ui.Router

/**
 * 主界面 —— 对应 Flutter 端 `lib/main.dart` 根结构 + `home_page.dart` 的底部导航。
 * 三个主 Tab：首页 / 分类 / 我的，页面用 FrameLayout 切换（View 体系，无 Fragment）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var contentFrame: FrameLayout
    private var homeView: HomeView? = null
    private var categoryView: CategoryView? = null
    private var mineView: MineView? = null
    private var currentIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Router.install(applicationContext)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        contentFrame = FrameLayout(this)
        root.addView(contentFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

        val bottomNav = BottomNavigationView(this).apply {
            inflateMenu(R.menu.main_nav)
            setOnItemSelectedListener { item ->
                val index = when (item.itemId) {
                    R.id.nav_home -> 0
                    R.id.nav_category -> 1
                    R.id.nav_mine -> 2
                    else -> -1
                }
                if (index >= 0) {
                    switchPage(index)
                    EventBus.emit(EventBus.kBottomNavigationBarClicked, index)
                }
                true
            }
        }
        root.addView(bottomNav, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        setContentView(root)
        switchPage(0)
    }

    private fun switchPage(index: Int) {
        if (index == currentIndex) return
        currentIndex = index
        contentFrame.removeAllViews()
        when (index) {
            0 -> {
                val view = homeView ?: HomeView(this).also { homeView = it }
                contentFrame.addView(view, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
                ))
            }
            1 -> {
                val view = categoryView ?: CategoryView(this).also { categoryView = it }
                contentFrame.addView(view, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
                ))
            }
            2 -> {
                val view = mineView ?: MineView(this).also { mineView = it }
                contentFrame.addView(view, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
                ))
            }
        }
    }

    override fun onDestroy() {
        homeView?.onDestroy()
        categoryView?.onDestroy()
        mineView?.onDestroy()
        super.onDestroy()
    }
}