package dev.livefork.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import dev.livefork.app.app.Site
import dev.livefork.app.app.Sites
import dev.livefork.app.modules.category.detail.CategoryDetailController
import dev.livefork.core.model.LiveRoomItem
import dev.livefork.core.model.LiveSubCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 类目详情页 —— 对应 Flutter `category_detail_page.dart`。
 * 顶部栏（返回 + 子分类名），主体为直播间卡片网格（复用首页 LiveRoomCard 全套）：
 * 下拉刷新 + 滚动到底自动加载下一页 + 空/错/首次加载三态。
 */
class CategoryDetailActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_SITE_ID = "site_id"
        private const val EXTRA_SUB_ID = "sub_id"
        private const val EXTRA_SUB_NAME = "sub_name"

        fun start(context: Context, site: Site, subCategory: LiveSubCategory) {
            val intent = Intent(context, CategoryDetailActivity::class.java)
                .putExtra(EXTRA_SITE_ID, site.id)
                .putExtra(EXTRA_SUB_ID, subCategory.id)
                .putExtra(EXTRA_SUB_NAME, subCategory.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var controller: CategoryDetailController
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: LiveRoomAdapter
    private lateinit var stateFrame: FrameLayout
    private lateinit var stateText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val siteId = intent.getStringExtra(EXTRA_SITE_ID) ?: run { finish(); return }
        val subId = intent.getStringExtra(EXTRA_SUB_ID) ?: run { finish(); return }
        val subName = intent.getStringExtra(EXTRA_SUB_NAME) ?: ""
        val site = Sites.allSites[siteId] ?: run {
            Router.toast("站点不存在：$siteId")
            finish()
            return
        }
        val subCategory = LiveSubCategory(
            name = subName,
            pic = null,
            id = subId,
            parentId = "",
        )
        controller = CategoryDetailController(site, subCategory)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        /* ---- 顶部栏：返回 + 标题 ---- */
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            setBackgroundColor(Color.WHITE)
        }
        val backButton = TextView(this).apply {
            text = "←"
            textSize = 22f
            setTextColor(Color.parseColor("#333333"))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
        }
        backButton.setOnClickListener { finish() }
        titleBar.addView(backButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        val title = TextView(this).apply {
            text = subName
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.parseColor("#333333"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }
        titleBar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // 右侧占位使标题居中（对应返回键对称位置）
        titleBar.addView(
            TextView(this).apply { setPadding(dp(8), dp(12), dp(8), dp(12)) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(titleBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        /* ---- 内容：下拉刷新 + 直播卡片网格 ---- */
        swipe = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(Color.parseColor("#3498DB"))
        }
        recycler = RecyclerView(this).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            clipChildren = false
            clipToPadding = false
        }
        adapter = LiveRoomAdapter { item ->
            Router.openLiveRoom(site, item.roomId)
        }
        recycler.adapter = adapter
        recycler.addItemDecoration(GridSpacingDecoration(dp(12)))
        // 宽度变化（旋转/首次布局）重算网格列数（复用 LiveRoomGridLayout）
        recycler.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = right - left
            if (width > 0) applyGrid(width)
        }
        // 滚动到底自动加载下一页
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (!recyclerView.canScrollVertically(1)) {
                    controller.loadData()
                }
            }
        })
        swipe.addView(recycler, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        swipe.setOnRefreshListener {
            controller.refreshData()
            // 冷却被拒等未真正进入加载时立即收起，避免进度圈残留
            swipe.postDelayed({
                if (!controller.pageLoading.value) swipe.isRefreshing = false
            }, 150)
        }
        root.addView(swipe, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

        /* ---- 三态覆盖：首次加载 / 空 / 错误 ---- */
        stateFrame = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.WHITE)
        }
        val progress = ProgressBar(this)
        stateFrame.addView(progress, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))
        stateText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
            gravity = Gravity.CENTER
        }
        stateFrame.addView(stateText, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))
        stateFrame.setOnClickListener {
            // 空/错状态点击重试
            controller.refreshData()
        }
        root.addView(stateFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

        setContentView(root)

        bindState()
        controller.loadData()
    }

    private var lastGridWidth = -1

    /** 依据可用宽度重算网格（复用 LiveRoomGridLayout：16:9 封面 + 64dp 文字区、2~8 列、12dp 间距）。 */
    private fun applyGrid(width: Int) {
        if (width == lastGridWidth) return
        lastGridWidth = width
        val layout = LiveRoomGridLayout.resolve(width, LiveRoomCard.detailsExtentPx)
        val lm = recycler.layoutManager
        if (lm is GridLayoutManager) {
            if (lm.spanCount != layout.crossAxisCount) {
                lm.spanCount = layout.crossAxisCount
            }
        } else {
            recycler.layoutManager = GridLayoutManager(this, layout.crossAxisCount)
        }
        adapter.gridLayout = layout
    }

    private fun bindState() {
        scope.launch {
            controller.list.collect { list ->
                adapter.submitList(list)
                stateFrame.visibility = View.GONE
            }
        }
        scope.launch {
            controller.pageLoading.collect { loading ->
                if (loading && controller.list.value.isEmpty()) {
                    stateFrame.visibility = View.VISIBLE
                    stateText.visibility = View.GONE
                } else {
                    stateFrame.visibility = View.GONE
                }
                if (!loading) swipe.isRefreshing = false
            }
        }
        scope.launch {
            controller.pageError.collect { error ->
                if (error) {
                    stateFrame.visibility = View.VISIBLE
                    stateText.visibility = View.VISIBLE
                    stateText.text = "加载失败：${controller.errorMsg.value}\n点击重试"
                }
            }
        }
        scope.launch {
            controller.pageEmpty.collect { empty ->
                if (empty) {
                    stateFrame.visibility = View.VISIBLE
                    stateText.visibility = View.VISIBLE
                    stateText.text = "暂无内容\n点击刷新"
                }
            }
        }
    }

    override fun onDestroy() {
        controller.dispose()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }
}

private fun dp(value: Int): Int =
    (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()