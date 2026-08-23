package dev.livefork.app.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/** 通用设置行。 */
object SettingRows {

    /** 带标题+副标题+Switch 的行。 */
    fun switchRow(
        context: Context,
        title: String,
        subtitle: String = "",
        checked: Boolean,
        onChange: (Boolean) -> Unit,
        onLongClick: (() -> Unit)? = null,
    ): LinearLayout {
        val row = baseRow(context)
        val texts = rowTexts(context, title, subtitle)
        val switch = Switch(context).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(switch, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        if (onLongClick != null) {
            row.setOnLongClickListener { onLongClick(); true }
        }
        return row
    }

    /** 带标题+副标题的可点击行。 */
    fun clickRow(
        context: Context,
        title: String,
        subtitle: String = "",
        onClick: () -> Unit,
    ): LinearLayout {
        val row = baseRow(context)
        val texts = rowTexts(context, title, subtitle)
        val arrow = TextView(context).apply {
            text = "›"
            textSize = 18f
            setTextColor(Color.parseColor("#BBBBBB"))
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(arrow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        row.setOnClickListener { onClick() }
        return row
    }

    /** 分组标题。 */
    fun sectionTitle(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.parseColor("#FF3F4B"))
        setPadding(dpR(16), dpR(14), dpR(16), dpR(4))
    }

    private fun baseRow(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.WHITE)
        setPadding(dpR(16), dpR(12), dpR(16), dpR(12))
    }

    /** 标题+副标题垂直容器（整体作为一个子 View 加入行）。 */
    private fun rowTexts(context: Context, title: String, subtitle: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val titleView = TextView(context).apply {
                text = title
                textSize = 15f
                setTextColor(Color.parseColor("#222222"))
            }
            addView(titleView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            val subtitleView = TextView(context).apply {
                text = subtitle
                textSize = 12f
                setTextColor(Color.parseColor("#888888"))
            }
            addView(subtitleView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
}

internal fun dpR(value: Int): Int =
    (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()