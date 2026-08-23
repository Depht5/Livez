package dev.livefork.app.ui

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.tabs.TabLayout
import dev.livefork.app.app.Site
import dev.livefork.app.modules.category.AppLiveCategory
import dev.livefork.app.modules.category.CategoryController
import dev.livefork.app.modules.category.CategoryListController
import dev.livefork.core.model.LiveSubCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 类目视图 —— 对应 Flutter `category_page.dart`（AppBar 内站点 TabBar + TabBarView）。
 * 顶部站点 Tab（图标 + 名称，可横滑，选中蓝 #3498DB），下方为当前站点分类列表：
 * 吸顶主分类标题 + 子分类卡片网格（40dp 图/fallback + 12sp 名称）+「显示全部」；
 * 点击子分类进入 [CategoryDetailActivity]。
 */
class CategoryView(context: Context) : LinearLayout(context) {

    private val controller = CategoryController()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val tabLayout = TabLayout(context)
    private val contentFrame = FrameLayout(context)

    /** 各 Tab 的名称文字（按 tab 顺序），选中高亮切换。 */
    private val tabLabels = mutableListOf<TextView>()

    private val catViews = mutableMapOf<String, CatListView>()
    private var currentSiteId: String? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.WHITE)

        tabLayout.apply {
            setBackgroundColor(Color.WHITE)
            setSelectedTabIndicatorColor(Color.parseColor("#3498DB"))
            setTabTextColors(Color.parseColor("#666666"), Color.parseColor("#3498DB"))
            // 移除点击水波纹反馈
            tabRippleColor = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
            tabMode = TabLayout.MODE_SCROLLABLE
            tabGravity = TabLayout.GRAVITY_START
            isTabIndicatorFullWidth = false
        }
        addView(tabLayout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        contentFrame.clipChildren = false
        addView(contentFrame, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        for (site in controller.sites) {
            // 自定义 Tab：站点 logo 24dp + 8dp 间隔 + 站点名（对应 Dart Tab(child: Row[logo, hGap8, text])）
            val tab = tabLayout.newTab()
            val custom = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val icon = ImageView(context).apply { setImageResource(site.logoRes) }
            icon.layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            val label = TextView(context).apply {
                text = site.name
                textSize = 16f
            }
            label.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(8) }
            custom.addView(icon)
            custom.addView(label)
            tab.setCustomView(custom)
            tabLayout.addTab(tab)
            tabLabels += label
        }

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                updateTabLabel(tab.position, selected = true)
                showSite(tab.position)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {
                updateTabLabel(tab.position, selected = false)
            }

            override fun onTabReselected(tab: TabLayout.Tab) {
                showSite(tab.position)
            }
        })

        tabLayout.getTabAt(0)?.select()
    }

    /** 选中标识：Tab 名称高亮（customView 不受 setTabTextColors 控制，手动切换）。 */
    private fun updateTabLabel(position: Int, selected: Boolean) {
        tabLabels.getOrNull(position)?.setTextColor(
            Color.parseColor(if (selected) "#3498DB" else "#666666"),
        )
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

    /** 站点分类列表：下拉刷新 + 单 Grid 列表（连续吸顶标题 + 子分类网格 + 显示全部）。 */
    private inner class CatListView(
        context: Context,
        val site: Site,
        val listController: CategoryListController,
    ) : SwipeRefreshLayout(context) {

        private var categories: List<AppLiveCategory> = emptyList()

        private val recycler = RecyclerView(context)
        private val adapter = CategoryAdapter(
            onClickSub = { cat, sub ->
                CategoryDetailActivity.start(context, site, sub)
            },
            onClickMore = { cat ->
                // 先展开（showAll=true），再重建行显示全部分类
                cat.showAll.value = true
                recreateRows()
            },
        )

        init {
            setColorSchemeColors(Color.parseColor("#3498DB"))
            recycler.setPadding(dp(12), dp(12), dp(12), dp(12))
            recycler.clipChildren = false
            recycler.clipToPadding = false
            recycler.adapter = adapter
            // 吸顶：滚动时把已越过吸顶线的分组标题用 translationY 钉在顶部（原位标题实体卡住）
            recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    applySticky()
                }
            })
            addView(recycler, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            setOnRefreshListener {
                listController.refreshData()
                // 冷却被拒等未真正进入加载时立即收起，避免进度圈残留
                postDelayed({
                    if (!listController.pageLoading.value) isRefreshing = false
                }, 150)
            }
            // 下拉刷新完成自动收起
            scope.launch {
                listController.pageLoading.collect { loading ->
                    if (!loading) isRefreshing = false
                }
            }
            // 数据变化 → 重建行（含「显示全部」展开）
            scope.launch {
                listController.list.collect { cats ->
                    categories = cats
                    recreateRows()
                }
            }
            // 宽度变化 → 重算子分类网格列数
            recycler.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
                val width = right - left
                if (width > 0) setupGrid(width)
            }
        }

        private fun recreateRows() {
            adapter.submit(categories)
            // 数据/布局变化后重算吸顶（等布局完成）
            post { applySticky() }
        }

        /**
         * 吸顶卡位：对所有可见分组标题，把"已越过吸顶线（top<0）且最靠近吸顶线"的那个
         * 用 translationY 钉在 RecyclerView 顶部；其余标题保持原位。
         * 标题实体随内容被"卡住"，内容从其下方滚过 —— 真实吸顶，无浮层/跳变。
         */
        private fun applySticky() {
            val lm = recycler.layoutManager as? GridLayoutManager ?: return
            var target: View? = null
            var targetTop = Int.MIN_VALUE
            for (i in 0 until lm.childCount) {
                val child = lm.getChildAt(i) ?: continue
                val pos = recycler.getChildAdapterPosition(child)
                if (pos == RecyclerView.NO_POSITION || !adapter.isHeader(pos)) continue
                val top = child.top
                if (top < 0 && top > targetTop) {
                    targetTop = top
                    target = child
                }
            }
            val targetView = target
            for (i in 0 until lm.childCount) {
                val child = lm.getChildAt(i) ?: continue
                val pos = recycler.getChildAdapterPosition(child)
                if (pos == RecyclerView.NO_POSITION || !adapter.isHeader(pos)) continue
                val pinned = targetView != null && child === targetView
                child.translationY = if (pinned) (-targetTop).toFloat() else 0f
                // 被卡标题提升 z 序（绘制在滚动卡片之上避免被盖）；
                // 关键：outlineProvider=null（无轮廓）——部分设备（如荣耀渲染器）对高 z/translationZ
                // 会绘制阴影轮廓，移除轮廓后即使 z 高也不产生阴影
                child.outlineProvider = null
                child.elevation = 0f
                child.z = if (pinned) 10f else 0f
            }
        }

        private fun setupGrid(width: Int) {
            val spanCount = (width / dp(80).toFloat()).toInt().coerceIn(1, 12)
            val lm = recycler.layoutManager
            if (lm is GridLayoutManager) {
                if (lm.spanCount != spanCount) {
                    lm.spanCount = spanCount
                    lm.spanSizeLookup = spanLookup(spanCount)
                }
            } else {
                recycler.layoutManager = GridLayoutManager(context, spanCount).apply {
                    spanSizeLookup = spanLookup(spanCount)
                }
            }
            if (recycler.itemDecorationCount == 0) {
                recycler.addItemDecoration(CategorySpacingDecoration(dp(8), adapter))
            }
        }

        private fun spanLookup(spanCount: Int): GridLayoutManager.SpanSizeLookup =
            object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (adapter.isHeader(position)) spanCount else 1
            }

        fun attach() {
            listController.loadData()
        }
    }

    /** 页面销毁。 */
    fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        controller.dispose()
    }
}

/* ---------- 分类列表行模型 ---------- */

private sealed class CategoryRow {
    class Header(val cat: AppLiveCategory) : CategoryRow()
    class Sub(val cat: AppLiveCategory, val sub: LiveSubCategory) : CategoryRow()
    class ShowMore(val cat: AppLiveCategory) : CategoryRow()
}

/* ---------- 分类列表适配器（吸顶标题 / 子分类卡片 / 显示全部） ---------- */

/** 分类列表适配器：3 种 item type（主分类标题跨全宽、子分类卡片、显示全部卡片）。 */
private class CategoryAdapter(
    private val onClickSub: (AppLiveCategory, LiveSubCategory) -> Unit,
    private val onClickMore: (AppLiveCategory) -> Unit,
) : RecyclerView.Adapter<CategoryAdapter.VH>() {

    private var rows = emptyList<CategoryRow>()
    private val primaryColor = Color.parseColor("#3498DB")

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_SUB = 1
        const val TYPE_SHOW_MORE = 2
    }

    fun submit(categories: List<AppLiveCategory>) {
        rows = categories.flatMap { cat ->
            buildList {
                add(CategoryRow.Header(cat))
                val subs = if (cat.showAll.value) cat.children else cat.take15
                subs.forEach { add(CategoryRow.Sub(cat, it)) }
                if (!cat.showAll.value && cat.children.size > cat.take15.size) {
                    add(CategoryRow.ShowMore(cat))
                }
            }
        }
        notifyDataSetChanged()
    }

    /** 供吸顶装饰与 spanSizeLookup 判断主分类标题行。 */
    fun isHeader(position: Int): Boolean =
        position in rows.indices && rows[position] is CategoryRow.Header

    fun headerText(position: Int): String =
        (rows.getOrNull(position) as? CategoryRow.Header)?.cat?.name ?: ""

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is CategoryRow.Header -> TYPE_HEADER
        is CategoryRow.ShowMore -> TYPE_SHOW_MORE
        else -> TYPE_SUB
    }

    class VH(val root: View) : RecyclerView.ViewHolder(root) {
        var viewType: Int = RecyclerView.NO_POSITION
        var subImage: ImageView? = null
        var subName: TextView? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH = when (viewType) {
        TYPE_HEADER -> VH(buildHeader(parent.context))
        TYPE_SHOW_MORE -> VH(buildSubCard(parent.context).also {
            (it.getChildAt(0) as View).visibility = View.GONE
            (it.getChildAt(1) as TextView).text = "显示全部"
        }).also { bindVhRefs(it) }
        else -> VH(buildSubCard(parent.context)).also { bindVhRefs(it) }
    }.also { it.viewType = viewType }

    /** 从卡片根视图提取子引用（图片 / 名称）。 */
    private fun bindVhRefs(vh: VH) {
        val root = vh.root as LinearLayout
        vh.subImage = root.getChildAt(0) as ImageView
        vh.subName = root.getChildAt(1) as TextView
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        when (val row = rows[position]) {
            is CategoryRow.Header -> {
                (holder.root as TextView).text = row.cat.name
            }
            is CategoryRow.Sub -> {
                // 无图时 NetImage 内部显示灰色占位（无蓝色边框装饰）
                NetImage.loadRound(holder.subImage!!, row.sub.pic ?: "", 8f)
                holder.subName?.text = row.sub.name
                holder.root.setOnClickListener { onClickSub(row.cat, row.sub) }
            }
            is CategoryRow.ShowMore -> {
                holder.subName?.text = "显示全部"
                holder.root.setOnClickListener { onClickMore(row.cat) }
            }
        }
    }

    /* 卡片构建 */

    private fun buildHeader(context: Context): TextView = TextView(context).apply {
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.parseColor("#222222"))
        // 不透明背景：吸顶卡位时盖住下方滚过的内容
        setBackgroundColor(Color.WHITE)
        setPadding(dp(4), dp(8), dp(4), dp(8))
    }

    private fun buildSubCard(context: Context): LinearLayout {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.WHITE)
            }
            elevation = dp(2).toFloat()
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipChildren = false
        }
        // 图标图片 40dp（无图时 NetImage 显示灰色占位；不含边框装饰）
        val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(image, LinearLayout.LayoutParams(dp(40), dp(40)))
        val name = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#666666"))
            maxLines = 1
            gravity = Gravity.CENTER
        }
        root.addView(name, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4) })
        // 统一卡片尺寸（宽随格子均分一致，高固定），避免边界贴在一起/大小不一
        root.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(84),
        )
        return root
    }
}

/* ---------- 分类网格间距 ---------- */

/** 网格间距：标题行上下文 8dp、子分类列间均分 8dp、行间 bottom 8dp。 */
private class CategorySpacingDecoration(
    private val spacing: Int,
    private val adapter: CategoryAdapter,
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: android.graphics.Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return
        val lm = parent.layoutManager as? GridLayoutManager ?: return
        if (adapter.isHeader(position)) {
            outRect.top = spacing
            outRect.bottom = spacing
            return
        }
        // 统一对称间距：列间/行间各 spacing，边缘 spacing/2（不受跨全宽标题的 position 偏移影响）
        val half = spacing / 2
        outRect.left = half
        outRect.right = half
        outRect.top = half
        outRect.bottom = half
    }
}

private fun dp(value: Int): Int =
    (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()