package dev.livefork.app.ui

import android.graphics.Color
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.setPadding
import androidx.recyclerview.widget.GridLayoutManager
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

/** 直播间卡片网格适配器：按 [LiveRoomGridLayout] 渲染 [LiveRoomCard]，点击回调。 */
class LiveRoomAdapter(
    private val onClick: (LiveRoomItem) -> Unit,
) : RecyclerView.Adapter<LiveRoomAdapter.RoomViewHolder>() {

    private var items = emptyList<LiveRoomItem>()

    /** 当前网格布局；发生变化时重建条目（尺寸依赖列宽）。 */
    var gridLayout: LiveRoomGridLayout? = null
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    fun submitList(list: List<LiveRoomItem>) {
        items = list
        notifyDataSetChanged()
    }

    class RoomViewHolder(val card: LiveRoomCard) : RecyclerView.ViewHolder(card)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RoomViewHolder {
        val card = LiveRoomCard(parent.context)
        // 显式可点击，确保 item 始终接收触摸并触发 OnClickListener
        card.isClickable = true
        card.isFocusable = false
        card.isFocusableInTouchMode = false
        val layout = gridLayout
        card.layoutParams = if (layout != null) {
            RecyclerView.LayoutParams(layout.itemWidth, layout.mainAxisExtent)
        } else {
            RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return RoomViewHolder(card)
    }

    override fun onBindViewHolder(holder: RoomViewHolder, position: Int) {
        val item = items[position]
        holder.card.bind(item, gridLayout?.itemWidth ?: 0)
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size
}

/** 网格间距装饰：列间均匀分配 [spacing]，行间下边距 [spacing]（镜像 Dart 12dp 间距，边缘由列表 padding 承担）。 */
class GridSpacingDecoration(private val spacing: Int) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return
        val layoutManager = parent.layoutManager as? GridLayoutManager ?: return
        val column = position % layoutManager.spanCount
        outRect.left = spacing * column / layoutManager.spanCount
        outRect.right = spacing - (column + 1) * spacing / layoutManager.spanCount
        outRect.bottom = spacing
    }
}

private fun dp(value: Int): Int =
    (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()