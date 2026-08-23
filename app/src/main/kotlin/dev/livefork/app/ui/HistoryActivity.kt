package dev.livefork.app.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.livefork.app.app.Sites
import dev.livefork.app.di.ServiceContainer
import dev.livefork.app.modules.mine.history.HistoryController
import dev.livefork.app.models.History
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 观看历史页 —— 对应 Flutter `history_page.dart`。
 * 列表展示历史记录，点击进入直播间，支持单条删除与清空。
 */
class HistoryActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, HistoryActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: HistoryController
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        controller = HistoryController(ServiceContainer.dbService)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(0xF5, 0xF5, 0xF5))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dpR(16), dpR(12), dpR(16), dpR(12))
        }
        val title = TextView(this).apply {
            text = "观看历史"
            textSize = 18f
            setTextColor(Color.parseColor("#222222"))
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val clearButton = Button(this).apply {
            text = "清空"
            textSize = 13f
            setBackgroundColor(Color.parseColor("#FF3F4B"))
            setTextColor(Color.WHITE)
            isAllCaps = false
        }
        header.addView(clearButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(header)

        recycler = RecyclerView(this)
        adapter = HistoryAdapter { item ->
            val site = Sites.allSites[item.siteId]
            if (site != null) {
                Router.openLiveRoom(site, item.roomId)
            } else {
                Router.toast("无法打开历史记录：站点不存在")
            }
        }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.addItemDecoration(DividerItemDecoration(this, LinearLayoutManager.VERTICAL))
        recycler.adapter = adapter
        root.addView(recycler, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

        setContentView(root)

        clearButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清空观看历史")
                .setMessage("确定要清空所有观看历史吗？")
                .setPositiveButton("清空") { _, _ ->
                    scope.launch {
                        controller.clean()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        scope.launch {
            controller.list.collect { list ->
                adapter.submit(list)
            }
        }
        controller.loadData()
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }
}

private class HistoryAdapter(
    private val onClick: (History) -> Unit,
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    private val items = mutableListOf<History>()

    fun submit(list: List<History>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val root: LinearLayout, val title: TextView, val sub: TextView) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val root = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpR(16), dpR(12), dpR(16), dpR(12))
            setBackgroundColor(Color.WHITE)
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
        root.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(sub, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        return VH(root, title, sub)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val site = Sites.allSites[item.siteId]
        holder.title.text = item.userName
        holder.sub.text = "${site?.name ?: item.siteId} · ${item.userName}"
        holder.root.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size
}