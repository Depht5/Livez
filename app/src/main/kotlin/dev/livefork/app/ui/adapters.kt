package dev.livefork.app.ui

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.livefork.core.model.LiveRoomItem

/** 简单条目（标题 + 可选副标题）。 */
data class MenuItem(
    val title: String,
    val subtitle: String = "",
)

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

    class RoomViewHolder(val itemView: FrameLayout, val card: LiveRoomCard) : RecyclerView.ViewHolder(itemView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RoomViewHolder {
        // item 容器：卡片四周留统一 margin，阴影在 item 内绘制，避免与相邻卡片互遮导致左右不对称
        val itemView = FrameLayout(parent.context).apply { clipChildren = false }
        val card = LiveRoomCard(parent.context)
        val shadowMargin = dp(4)
        itemView.addView(
            card,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply {
                marginStart = shadowMargin
                marginEnd = shadowMargin
                topMargin = shadowMargin
                bottomMargin = shadowMargin
            },
        )
        val layout = gridLayout
        itemView.layoutParams = if (layout != null) {
            // item 高度按卡片实际宽（列宽 - 两侧 margin）的 16:9 封面 + 文字区 + 上下 margin 计算，
            // 与卡片内容精确贴合（避免 margin 后文字区溢出/裁切）
            val cardWidth = layout.itemWidth - dp(8)
            val itemHeight = (cardWidth / LiveRoomGridLayout.coverAspectRatio).toInt() +
                LiveRoomCard.detailsExtentPx + dp(8)
            RecyclerView.LayoutParams(layout.itemWidth, itemHeight)
        } else {
            RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return RoomViewHolder(itemView, card)
    }

    override fun onBindViewHolder(holder: RoomViewHolder, position: Int) {
        val item = items[position]
        // 卡片实际宽 = 列宽 - 两侧 margin（封面 16:9 高度按此计算）
        val cardWidth = (gridLayout?.itemWidth ?: 0) - dp(8)
        holder.card.bind(item, cardWidth)
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