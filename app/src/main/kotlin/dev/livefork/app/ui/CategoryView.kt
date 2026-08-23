package dev.livefork.app.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import dev.livefork.app.app.Site
import dev.livefork.app.modules.category.AppLiveCategory
import dev.livefork.app.modules.category.CategoryController
import dev.livefork.app.modules.category.CategoryListController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 类目视图 —— 对应 Flutter `category_page.dart`。
 * 顶部站点 Tab，下方为当前站点分类列表（主分类标题 + 子分类文本）。
 */
class CategoryView(context: Context) : LinearLayout(context) {

    private val controller = CategoryController()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val tabLayout = TabLayout(context)
    private val contentFrame = FrameLayout(context)

    private val catViews = mutableMapOf<String, CatListView>()
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
            tabLayout.addTab(tabLayout.newTab().setText(site.name))
        }

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showSite(tab.position)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}

            override fun onTabReselected(tab: TabLayout.Tab) {
                showSite(tab.position)
            }
        })

        tabLayout.getTabAt(0)?.select()
    }

    private fun showSite(index: Int) {
        val site = controller.sites.getOrNull(index) ?: return
        val catView = catViews.getOrPut(site.id) { buildCatList(site) }
        currentSiteId = site.id
        contentFrame.removeAllViews()
        contentFrame.addView(catView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        catView.attach()
    }

    private fun buildCatList(site: Site): CatListView {
        val listController = controller.categoryListControllers.getValue(site.id)
        return CatListView(context, site, listController)
    }

    /** 站点分类列表。 */
    private inner class CatListView(
        context: Context,
        val site: Site,
        val listController: CategoryListController,
    ) : RecyclerView(context) {

        private val listAdapter = object : RecyclerView.Adapter<CatListViewHolder>() {
            private val items = mutableListOf<AppLiveCategory>()

            fun submit(list: List<AppLiveCategory>) {
                items.clear()
                items.addAll(list)
                notifyDataSetChanged()
            }

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CatListViewHolder {
                val root = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    setBackgroundColor(Color.WHITE)
                }
                val title = TextView(parent.context).apply {
                    textSize = 15f
                    setTextColor(Color.parseColor("#222222"))
                }
                val subs = TextView(parent.context).apply {
                    textSize = 12f
                    setTextColor(Color.parseColor("#888888"))
                }
                root.addView(title, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
                root.addView(subs, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
                return CatListViewHolder(root, title, subs)
            }

            override fun onBindViewHolder(holder: CatListViewHolder, position: Int) {
                val cat = items[position]
                holder.title.text = cat.name
                holder.subs.text = if (cat.children.isEmpty()) "暂无子分类"
                else cat.children.joinToString(" / ") { it.name }
                holder.root.setOnClickListener {
                    Router.toast("分类「${cat.name}」详情页将在后续版本接入")
                }
            }

            override fun getItemCount(): Int = items.size
        }

        init {
            layoutManager = LinearLayoutManager(context)
            addItemDecoration(androidx.recyclerview.widget.DividerItemDecoration(
                context, LinearLayoutManager.VERTICAL,
            ))
            this.adapter = listAdapter
        }

        fun attach() {
            listController.loadData()
            scope.launch {
                listController.list.collect { listAdapter.submit(it) }
            }
        }
    }

    private class CatListViewHolder(
        val root: LinearLayout,
        val title: TextView,
        val subs: TextView,
    ) : RecyclerView.ViewHolder(root)

    /** 页面销毁。 */
    fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        controller.dispose()
    }
}

private fun dp(value: Int): Int =
    (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()