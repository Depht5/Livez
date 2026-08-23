package dev.livefork.app.ui

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.setPadding
import androidx.recyclerview.widget.RecyclerView
import dev.livefork.core.model.LiveRoomItem

/** 简单条目（标题 + 可选副标题）。 */
data class MenuItem(
    val title: String,
    val subtitle: String = "",
)

/** 通用列表适配器：渲染 [MenuItem] 文本条目，点击回调。 */
class MenuAdapter(
    private val items: List<MenuItem>,
    private val onClick: (MenuItem) -> Unit,
) : RecyclerView.Adapter<MenuAdapter.MenuViewHolder>() {

    class MenuViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MenuViewHolder {
        val text = TextView(parent.context).apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            textSize = 15f
            setTextColor(Color.parseColor("#222222"))
            gravity = Gravity.CENTER_VERTICAL
        }
        return MenuViewHolder(text)
    }

    override fun onBindViewHolder(holder: MenuViewHolder, position: Int) {
        val item = items[position]
        holder.textView.text = if (item.subtitle.isEmpty()) item.title else "${item.title}　${item.subtitle}"
        holder.textView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size
}

/** 直播间卡片适配器：渲染 [LiveRoomItem]，点击回调。 */
class LiveRoomAdapter(
    private val onClick: (LiveRoomItem) -> Unit,
) : RecyclerView.Adapter<LiveRoomAdapter.RoomViewHolder>() {

    private val items = mutableListOf<LiveRoomItem>()

    fun submitList(list: List<LiveRoomItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class RoomViewHolder(val root: LinearLayout) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RoomViewHolder {
        val root = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.WHITE)
        }
        val title = TextView(parent.context).apply {
            setPadding(0, 0, 0, dp(4))
            textSize = 15f
            setTextColor(Color.parseColor("#222222"))
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
        return RoomViewHolder(root)
    }

    override fun onBindViewHolder(holder: RoomViewHolder, position: Int) {
        val item = items[position]
        val titleView = holder.root.getChildAt(0) as TextView
        val subView = holder.root.getChildAt(1) as TextView
        titleView.text = item.title
        subView.text = "${item.userName} · 人气 ${item.online}"
        holder.root.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size
}

private fun dp(value: Int): Int =
    (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()