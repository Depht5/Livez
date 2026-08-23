package dev.livefork.app.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.tabs.TabLayout
import dev.livefork.app.app.Site
import dev.livefork.app.app.controller.BasePageController
import dev.livefork.app.modules.home.HomeController
import dev.livefork.app.modules.home.HomeListController
import dev.livefork.core.model.LiveRoomItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 首页视图 —— 对应 Flutter `home_page.dart`（TabLayout + 站点推荐列表）。
 * 顶部站点 Tab，下方为当前站点推荐直播间列表（RecyclerView）。
 */
class HomeView(context: Context) : LinearLayout(context) {

    private val controller = HomeController()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val tabLayout = TabLayout(context)
    private val contentFrame = FrameLayout(context)

    private val siteViews = mutableMapOf<String, SiteListView>()
    private var currentSiteId: String? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.WHITE)

        tabLayout.setBackgroundColor(Color.WHITE)
        tabLayout.setSelectedTabIndicatorColor(Color.parseColor("#FF3F4B"))
        tabLayout.setTabTextColors(Color.parseColor("#666666"), Color.parseColor("#FF3F4B"))
        tabLayout.tabGravity = TabLayout.GRAVITY_START
        tabLayout.isTabIndicatorFullWidth = false

        addView(tabLayout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(contentFrame, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        for (site in controller.sites) {
            // 自定义 Tab 内容：平台图标 + 平台名（对应 Dart Tab 的 text + site.logo）
            val tab = tabLayout.newTab()
            val custom = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val icon = ImageView(context).apply { setImageResource(site.logoRes) }
            icon.layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
            val label = TextView(context).apply { text = site.name }
            label.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(4) }
            custom.addView(icon)
            custom.addView(label)
            tab.setCustomView(custom)
            tabLayout.addTab(tab)
        }

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showSite(tab.position)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}

            override fun onTabReselected(tab: TabLayout.Tab) {
                // 回到顶部（简化：重新刷新）
                showSite(tab.position)
            }
        })

        // 默认显示第一个站点
        tabLayout.getTabAt(0)?.select()
    }

    private fun showSite(index: Int) {
        val site = controller.sites.getOrNull(index) ?: return
        val listView = siteViews.getOrPut(site.id) { buildSiteList(site) }
        currentSiteId = site.id
        contentFrame.removeAllViews()
        contentFrame.addView(listView.root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        listView.attach()
    }

    private fun buildSiteList(site: Site): SiteListView {
        val listController = controller.homeListControllers.getValue(site.id)
        return SiteListView(context, site, listController)
    }

    /** 站点列表（SwipeRefreshLayout + RecyclerView），卡片网格布局（镜像 Dart home_list_view.dart）。 */
    private inner class SiteListView(
        context: Context,
        val site: Site,
        val listController: BasePageController<LiveRoomItem>,
    ) {
        val root: SwipeRefreshLayout = SwipeRefreshLayout(context).apply {
            setColorSchemeColors(Color.parseColor("#3498DB"))
        }
        private val recycler: RecyclerView = RecyclerView(context)
        private val adapter = LiveRoomAdapter { item ->
            Router.openLiveRoom(site, item.roomId)
        }
        private var collectJob: Job? = null

        init {
            recycler.setPadding(dp(12), dp(12), dp(12), dp(12))
            recycler.addItemDecoration(GridSpacingDecoration(dp(12)))
            recycler.adapter = adapter
            // 宽度变化（旋转 / 分屏 / 首次布局）时重算列数与卡片尺寸
            recycler.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
                val width = right - left
                if (width > 0) applyGrid(width)
            }
            root.addView(recycler, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            // 下拉刷新（对应 Dart RefreshIndicator）：刷完自动收起
            root.setOnRefreshListener { listController.refreshData() }
            scope.launch {
                listController.pageLoading.collect { loading ->
                    if (!loading) root.isRefreshing = false
                }
            }
        }

        /** 依据可用宽度计算网格（对应 Dart LiveRoomGridLayout.resolve）。 */
        private var lastGridWidth = -1

        private fun applyGrid(width: Int) {
            // 宽度未变则跳过：避免每次布局变化都重建条目（重建窗口会吞掉点击）
            if (width == lastGridWidth) return
            lastGridWidth = width
            val layout = LiveRoomGridLayout.resolve(width, LiveRoomCard.detailsExtentPx)
            val layoutManager = recycler.layoutManager
            if (layoutManager is GridLayoutManager) {
                if (layoutManager.spanCount != layout.crossAxisCount) {
                    layoutManager.spanCount = layout.crossAxisCount
                }
            } else {
                recycler.layoutManager = GridLayoutManager(context, layout.crossAxisCount)
            }
            adapter.gridLayout = layout
        }

        /** 进入可见：数据收集 + 首次加载（后续可用 refreshData 刷新）。 */
        fun attach() {
            collectJob?.cancel()
            collectJob = scope.launch {
                listController.list.collect { adapter.submitList(it) }
            }
            listController.loadData()
        }
    }

    /** 页面销毁（MainActivity 销毁时调用）。 */
    fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        controller.dispose()
    }
}

private fun dp(value: Int): Int =
    (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()