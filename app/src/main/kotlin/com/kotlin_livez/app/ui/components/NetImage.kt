package com.kotlin_livez.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.kotlin_livez.app.ui.theme.ImageError
import com.kotlin_livez.app.ui.theme.ImagePlaceholder

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

    // 加载中/失败用纯色 painter（对齐 View 版色值：加载中 #F2F2F2、失败 #E0E0E0）。
    //
    // 为何用 AsyncImage 而不是 SubcomposeAsyncImage（task 10-07-home-jank-jump-clip-fullscreen-video）：
    // SubcomposeAsyncImage 会为每张图做一次**子组合**（subcomposition）+ 额外测量，Coil 官方明确
    // 不建议在列表/网格中使用；首页一屏 8~12 张封面意味着一次切页/滚动要付出 8~12 次子组合，
    // 真机 release 的 gfxinfo 显示 GPU 空闲（p99 6ms）而 UI 线程/绘制指令录制超时（Slow UI thread /
    // Slow issue draw commands 各 ~270/360 帧），即成本在组合侧。AsyncImage + ColorPainter 占位
    // 只有单次组合，视觉与语义（占位色、失败色、crossfade、圆角裁剪、空 URL 分支）完全不变。
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(pic)
            .crossfade(true)
            .build(),
        contentDescription = null,
        contentScale = contentScale,
        placeholder = ColorPainter(placeholderColor),
        error = ColorPainter(errorColor),
        modifier = clipModifier,
    )
}

/** URL 归一化：空串原样返回；`//` 前缀补 `https:`（镜像 View 版 NetImage.normalize）。 */
fun normalizeUrl(url: String): String {
    if (url.isEmpty()) return ""
    return if (url.startsWith("//")) "https:$url" else url
}
