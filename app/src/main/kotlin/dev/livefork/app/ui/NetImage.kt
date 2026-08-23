package dev.livefork.app.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import coil.load
import coil.transform.RoundedCornersTransformation

/**
 * 网络图片加载 —— 对应 Dart `NetImage`（lib/widgets/net_image.dart）。
 * 语义对齐 Dart：
 * - 空 URL 显示占位灰块；
 * - `//` 前缀补全为 `https:`；
 * - 加载中 / 失败显示占位外观；
 * - 直播间封面使用顶部圆角（8dp，对应卡片上两角圆角）。
 */
object NetImage {

    private val placeholder = ColorDrawable(Color.parseColor("#F2F2F2"))
    private val errorDrawable = ColorDrawable(Color.parseColor("#E0E0E0"))

    /** 加载直播间封面（16:9 区域，顶部圆角）。 */
    fun loadCover(imageView: ImageView, url: String, topRadiusDp: Float = 8f) {
        val pic = normalize(url)
        if (pic.isEmpty()) {
            imageView.setImageDrawable(placeholder)
            return
        }
        val radiusPx = topRadiusDp * imageView.resources.displayMetrics.density
        imageView.load(pic) {
            placeholder(placeholder)
            error(errorDrawable)
            crossfade(true)
            transformations(
                RoundedCornersTransformation(
                    topLeft = radiusPx,
                    topRight = radiusPx,
                    bottomLeft = 0f,
                    bottomRight = 0f,
                ),
            )
        }
    }

    /** 对应 Dart NetImage：空串 → ""；"//" 前缀补 "https:"。 */
    fun normalize(url: String): String {
        if (url.isEmpty()) return ""
        return if (url.startsWith("//")) "https:$url" else url
    }
}