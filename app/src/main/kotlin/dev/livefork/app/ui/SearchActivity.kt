package dev.livefork.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import dev.livefork.app.app.Sites
import dev.livefork.app.di.ServiceContainer
import dev.livefork.app.modules.search.AppSearchController
import dev.livefork.core.model.LiveAnchorItem
import dev.livefork.core.model.LiveRoomItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest

/**
 * 搜索页 —— 对应 Flutter `search_page.dart`。
 * 站点 Tab + 直播间/主播模式切换 + 搜索框；结果点击进入直播间。
 */
class SearchActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, SearchActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: AppSearchController
    private lateinit var keywordInput: EditText
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: SearchResultAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        controller = AppSearchController(ServiceContainer.appSettings)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(0xF5, 0xF5, 0xF5))
        }

        // 搜索输入 + 模式切换
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dpR(12), dpR(8), dpR(12), dpR(8))
        }
        keywordInput = EditText(this).apply {
            hint = "输入关键词搜索"
            textSize = 14f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        topBar.addView(keywordInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val modeButton = TextView(this).apply {
            text = "直播间"
            textSize = 13f
            setTextColor(Color.parseColor("#FF3F4B"))
            gravity = Gravity.CENTER
            setPadding(dpR(10), dpR(6), dpR(10), dpR(6))
        }
        topBar.addView(modeButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(topBar)

        modeButton.setOnClickListener {
            val newMode = if (controller.searchMode.value == 0) 1 else 0
            controller.setSearchMode(newMode)
            modeButton.text = if (newMode == 0) "直播间" else "主播"
        }

        // 站点 Tab
        val tabLayout = TabLayout(this).apply {
            setSelectedTabIndicatorColor(Color.parseColor("#FF3F4B"))
            setTabTextColors(Color.parseColor("#666666"), Color.parseColor("#FF3F4B"))
            tabGravity = TabLayout.GRAVITY_START
            isTabIndicatorFullWidth = false
        }
        for (site in controller.sites) {
            tabLayout.addTab(tabLayout.newTab().setText(site.name))
        }
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                controller.setTabIndex(tab.position)
                refreshList()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}

            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        root.addView(tabLayout)

        // 结果列表
        recycler = RecyclerView(this)
        adapter = SearchResultAdapter { item ->
            val site = controller.sites[controller.index]
            if (item is LiveRoomItem) {
                Router.openLiveRoom(site, item.roomId)
            } else if (item is LiveAnchorItem) {
                Router.openLiveRoom(site, item.roomId)
            }
        }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.addItemDecoration(DividerItemDecoration(this, LinearLayoutManager.VERTICAL))
        recycler.adapter = adapter
        root.addView(recycler, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

        setContentView(root)

        // 监听当前站点结果列表
        scope.launch {
            controller.siteControllers[controller.sites[controller.index].id]
                ?.list?.collect { list ->
                    adapter.submit(list)
                }
        }

        keywordInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                doSearch()
                true
            } else {
                false
            }
        }
        keywordInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {}
        })

        tabLayout.getTabAt(controller.index)?.select()
    }

    private fun doSearch() {
        val keyword = keywordInput.text?.toString()?.trim() ?: ""
        if (keyword.isEmpty()) {
            Router.toast("请输入搜索关键词")
            return
        }
        controller.doSearch(keyword)
        refreshList()
    }

    private fun refreshList() {
        val listController = controller.siteControllers[controller.sites[controller.index].id] ?: return
        adapter.submit(listController.list.value)
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        controller.dispose()
        super.onDestroy()
    }
}

private class SearchResultAdapter(
    private val onClick: (Any) -> Unit,
) : RecyclerView.Adapter<SearchResultAdapter.VH>() {

    private val items = mutableListOf<Any>()

    fun submit(list: List<Any>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val root: LinearLayout, val title: TextView, val sub: TextView, val status: TextView)
        : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val root = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpR(16), dpR(12), dpR(16), dpR(12))
            setBackgroundColor(Color.WHITE)
        }
        val texts = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val title = TextView(parent.context).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#222222"))
            maxLines = 1
        }
        val sub = TextView(parent.context).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#888888"))
        }
        texts.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        texts.addView(sub, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val status = TextView(parent.context).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#FF3F4B"))
        }
        root.addView(status, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        return VH(root, title, sub, status)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        when (item) {
            is LiveRoomItem -> {
                holder.title.text = item.title
                holder.sub.text = "${item.userName} · 人气 ${item.online}"
                holder.status.text = "直播中"
            }
            is LiveAnchorItem -> {
                holder.title.text = item.userName
                holder.sub.text = "主播"
                holder.status.text = if (item.liveStatus) "直播中" else "未开播"
            }
            else -> {
                holder.title.text = item.toString()
                holder.sub.text = ""
                holder.status.text = ""
            }
        }
        holder.root.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size
}