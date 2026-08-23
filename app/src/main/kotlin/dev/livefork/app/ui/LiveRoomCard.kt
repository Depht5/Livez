package dev.livefork.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.setPadding
import dev.livefork.app.app.Utils
import dev.livefork.core.model.LiveRoomItem

/**
 * 直播间卡片 —— 对应 Dart `LiveRoomCard`（lib/widgets/live_room_card.dart）。
 * 上半部分为 16:9 封面（顶部圆角），下半部分 64dp 文字区：
 * 标题（单行省略、加粗）+ 底行（左主播名，右人气），白底圆角整卡。
 *
 * 基类用 LinearLayout（VERTICAL）承载（与项目其他可点击列表项一致）：
 * 封面在上、文字区在下顺序排列，卡片整体可点击。
 */
class LiveRoomCard(context: Context) : LinearLayout(context) {

    companion object {
        /** 文字区高度（对应 Dart LiveRoomCard.detailsExtent = 64） */
        const val detailsExtentDp = 64

        /** 文字区像素高度 */
        val detailsExtentPx: Int
            get() = (detailsExtentDp * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
    }

    private val coverView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    private val titleView = TextView(context)
    private val userNameView = TextView(context)
    private val onlineView = TextView(context)

    init {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(Color.WHITE)
        }

        // 封面（16:9，高度由 bind 时的 itemWidth 决定）
        addView(coverView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))

        // 文字区（64dp）
        val details = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        titleView.apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#222222"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        userNameView.apply {
            textSize = 12f
            setTextColor(Color.parseColor("#888888"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        onlineView.apply {
            textSize = 12f
            setTextColor(Color.parseColor("#888888"))
        }

        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bottomRow.addView(
            userNameView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        bottomRow.addView(
            onlineView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        details.addView(
            titleView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        // 底行占满剩余高度并靠底部（对应 Dart spaceBetween 的底部行）
        details.addView(
            bottomRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                gravity = Gravity.BOTTOM
            },
        )

        addView(details, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, detailsExtentPx))
    }

    /** 绑定数据；[itemWidth] 为像素宽，决定封面高度（16:9）。 */
    fun bind(item: LiveRoomItem, itemWidth: Int) {
        val coverHeight = (itemWidth / LiveRoomGridLayout.coverAspectRatio).toInt()
        coverView.layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, coverHeight)
        NetImage.loadCover(coverView, item.cover)
        titleView.text = item.title.trim().ifEmpty { item.userName }
        userNameView.text = item.userName
        onlineView.text = "🔥 ${Utils.onlineToString(item.online)}"
    }

    private fun dp(value: Int): Int =
        (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
}