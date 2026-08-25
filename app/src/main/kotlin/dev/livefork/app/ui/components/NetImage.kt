package dev.livefork.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import dev.livefork.app.ui.theme.ImageError
import dev.livefork.app.ui.theme.ImagePlaceholder

/**
 * 网络图片（Compose 版）—— 对应 Dart `NetImage` 与现版 View 体系 `ui/NetImage.kt`。
 * 语义对齐：
 * - URL 归一化：`//` 前缀补全为 `https:`（[normalizeUrl]，与 View 版逻辑一致）；
 * - 空 URL 直接显示占位灰块，不发起加载；
 * - 加载中显示占位灰块（#F2F2F2）、失败显示失败灰块（#E0E0E0）；
 * - 形状：[cornerRadiusDp] 单值 = 四角同半径圆角；[isRound] = true 时为圆形头像
 *   （等价 View 版 loadRound 传直径/2 的能力）；封面顶部单角圆角场景由调用方用
 *   [shape] 传自定义 Shape（如仅上两角圆角），本组件不逐 API 复刻 View 版。
 *
 * @param url 图片地址（原始格式，内部做归一化）
 * @param modifier 尺寸与布局约束由调用方给定（如 Modifier.fillMaxWidth()）
 */
@Composable
fun NetImage(
    url: String,
    modifier: Modifier = Modifier,
    cornerRadiusDp: Float? = null,
    isRound: Boolean = false,
    shape: Shape? = null,
    contentScale: ContentScale = ContentScale.Crop,
    placeholderColor: Color = ImagePlaceholder,
    errorColor: Color = ImageError,
) {
    val pic = normalizeUrl(url)
    val clipModifier: Modifier = when {
        shape != null -> modifier.clip(shape)
        isRound -> modifier.clip(CircleShape)
        cornerRadiusDp != null -> modifier.clip(RoundedCornerShape(cornerRadiusDp.dp))
        else -> modifier
    }

    if (pic.isEmpty()) {
        // 空 URL：直接显示占位灰块，不发起加载（对齐 View 版行为）
        Box(modifier = clipModifier.background(placeholderColor))
        return
    }

    // 加载中/失败用纯色 Box 槽位（SubcomposeAsyncImage 稳定 API；Compose 层无 Drawable 概念，
    // 色值对齐 View 版：加载中 #F2F2F2、失败 #E0E0E0）
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(pic)
            .crossfade(true)
            .build(),
        contentDescription = null,
        contentScale = contentScale,
        loading = {
            Box(modifier = Modifier.fillMaxSize().background(placeholderColor))
        },
        error = {
            Box(modifier = Modifier.fillMaxSize().background(errorColor))
        },
        modifier = clipModifier,
    )
}

/** URL 归一化：空串原样返回；`//` 前缀补 `https:`（镜像 View 版 NetImage.normalize）。 */
fun normalizeUrl(url: String): String {
    if (url.isEmpty()) return ""
    return if (url.startsWith("//")) "https:$url" else url
}
