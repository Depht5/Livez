package dev.livefork.app.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.livefork.app.app.Sites
import dev.livefork.app.di.ServiceContainer
import dev.livefork.app.models.FollowUser
import dev.livefork.app.modules.follow_user.FollowGroupMode
import dev.livefork.app.modules.follow_user.FollowUserController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 关注用户页 —— 对应 Flutter `follow_user_page.dart`。
 * 分组（直播状态/平台）+ 标签筛选 + 搜索 + 列表（点击进直播，长按取消关注）+ 刷新。
 */
class FollowUserActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, FollowUserActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: FollowUserController
    private lateinit var tagBar: LinearLayout
    private lateinit var modeButton: TextView
    private lateinit var searchInput: EditText
    private lateinit var refreshButton: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: FollowAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        controller = FollowUserController(
            appSettings = ServiceContainer.appSettings,
            followService = ServiceContainer.followService,
            dbService = ServiceContainer.dbService,
            currentRoom = ServiceContainer.currentRoom,
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(0xF5, 0xF5, 0xF5))
        }

        // 顶部：分组模式 + 搜索 + 刷新
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dpR(12), dpR(8), dpR(12), dpR(8))
        }
        modeButton = TextView(this).apply {
            text = "状态分组"
            textSize = 13f
            setTextColor(Color.parseColor("#FF3F4B"))
            gravity = Gravity.CENTER
            setPadding(dpR(8), dpR(6), dpR(8), dpR(6))
        }
        searchInput = EditText(this).apply {
            hint = "搜索关注的用户"
            textSize = 14f
            isSingleLine = true
        }
        refreshButton = TextView(this).apply {
            text = "刷新"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#FF3F4B"))
            setPadding(dpR(10), dpR(6), dpR(10), dpR(6))
        }
        topBar.addView(modeButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        topBar.addView(searchInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(refreshButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(topBar)

        // 标签栏（横向滚动）
        val tagScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        tagBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpR(12), dpR(6), dpR(12), dpR(6))
        }
        tagScroll.addView(tagBar, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(tagScroll)

        // 列表
        recycler = RecyclerView(this)
        adapter = FollowAdapter(
            onClick = { item -> openRoom(item) },
            onLongClick = { item ->
                AlertDialog.Builder(this)
                    .setTitle("取消关注")
                    .setMessage("确定取消关注「${item.userName}」吗？")
                    .setPositiveButton("取消关注") { _, _ -> controller.removeItem(item) }
                    .setNegativeButton("取消", null)
                    .show()
            },
            onOpenRoom = { item -> openRoom(item) },
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.addItemDecoration(DividerItemDecoration(this, LinearLayoutManager.VERTICAL))
        recycler.adapter = adapter
        root.addView(recycler, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

        setContentView(root)

        modeButton.setOnClickListener {
            val newMode = if (controller.groupMode.value == FollowGroupMode.liveStatus) {
                FollowGroupMode.platform
            } else {
                FollowGroupMode.liveStatus
            }
            controller.setGroupMode(newMode)
            modeButton.text = if (newMode == FollowGroupMode.liveStatus) "状态分组" else "平台分组"
            rebuildTagBar()
        }

        refreshButton.setOnClickListener {
            controller.refreshAllStatus()
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                controller.setSearchKeyword(s?.toString() ?: "")
            }
        })

        scope.launch {
            controller.list.collect { adapter.submit(it) }
        }
        scope.launch {
            controller.tagList.collect { rebuildTagBar() }
        }

        rebuildTagBar()
    }

    private fun openRoom(item: FollowUser) {
        val site = Sites.allSites[item.siteId]
        if (site != null) {
            Router.openLiveRoom(site, item.roomId)
        } else {
            Router.toast("无法打开直播间：站点不存在")
        }
    }

    private fun rebuildTagBar() {
        tagBar.removeAllViews()
        for (option in controller.groupOptions) {
            val chip = TextView(this).apply {
                text = option.title
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dpR(14), dpR(6), dpR(14), dpR(6))
                setTextColor(
                    if (option.id == controller.selectedGroupId.value) Color.WHITE
                    else Color.parseColor("#666666"),
                )
                setBackgroundColor(
                    if (option.id == controller.selectedGroupId.value) Color.parseColor("#FF3F4B")
                    else Color.parseColor("#F0F0F0"),
                )
            }
            chip.setOnClickListener {
                controller.setGroupOption(option)
                rebuildTagBar()
            }
            tagBar.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dpR(8) })
        }
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }
}

private class FollowAdapter(
    private val onClick: (FollowUser) -> Unit,
    private val onLongClick: (FollowUser) -> Unit,
    private val onOpenRoom: (FollowUser) -> Unit,
) : RecyclerView.Adapter<FollowAdapter.VH>() {

    private val items = mutableListOf<FollowUser>()

    fun submit(list: List<FollowUser>) {
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
        }
        root.addView(status, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        return VH(root, title, sub, status)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val site = Sites.allSites[item.siteId]
        holder.title.text = item.userName
        holder.sub.text = "${site?.name ?: item.siteId} · ${item.tag}"
        holder.status.text = when (item.liveStatus) {
            2 -> "直播中"
            0 -> "状态未知"
            else -> "未开播"
        }
        holder.status.setTextColor(
            if (item.liveStatus == 2) Color.parseColor("#FF3F4B") else Color.parseColor("#999999"),
        )
        holder.root.setOnClickListener { onClick(item) }
        holder.root.setOnLongClickListener { onLongClick(item); true }
    }

    override fun getItemCount(): Int = items.size
}