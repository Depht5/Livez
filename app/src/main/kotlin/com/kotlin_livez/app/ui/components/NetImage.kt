package com.kotlin_livez.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import coil.size.Precision
import com.kotlin_livez.app.ui.theme.imageErrorColor
import com.kotlin_livez.app.ui.theme.imagePlaceholderColor

/**
 * 网络图片（Compose 版）—— 对应 Dart `NetImage` 与现版 View 体系 `ui/NetImage.kt`。
 * 语义对齐：
 * - URL 归一化：`//` 前缀补全为 `https:`（[normalizeUrl]，与 View 版逻辑一致）；
 * - 空 URL 直接显示占位灰块，不发起加载；
 * - 加载中显示占位灰块、失败显示失败灰块，色值**主题感知**（浅色 #F2F2F2 / #E0E0E0；
 *   深色取 `surfaceContainerHighest` / `surfaceContainerHigh`）；
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
    // 主题感知默认值（本任务）：@Composable 出现在默认参数位置合法（同 MaterialTheme.colorScheme.primary
    // 的用法），签名不变。深色/纯黑下取色槽灰，不再出现亮白占位块。
    placeholderColor: Color = imagePlaceholderColor(),
    errorColor: Color = imageErrorColor(),
) {
    val context = LocalContext.current
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

    // 记忆化（task 10-17-ui-power-and-recomposition-governance）：本组件在网格里一屏十几张，
    // 且每次重组都会重新执行函数体——原先 ImageRequest.Builder 与两个 ColorPainter 都是
    // 每次重组新建的对象，现按各自输入缓存：只在 URL（或 Context）变化时重建请求，
    // 只在颜色参数变化时重建 painter。
    val request = remember(context, pic) {
        ImageRequest.Builder(context)
            .data(pic)
            .crossfade(true)
            // 尺寸精度放宽：解码尺寸不必与目标严格一致（目标可自行缩放），Coil 可复用更大尺寸的
            // 内存缓存条目并避免多余放大解码；
            // 允许 RGB_565：图片确定为不透明时用 16 位色位图，内存减半（代价是轻微色阶损失）。
            .precision(Precision.INEXACT)
            .allowRgb565(true)
            .build()
    }
    val placeholderPainter = remember(placeholderColor) { ColorPainter(placeholderColor) }
    val errorPainter = remember(errorColor) { ColorPainter(errorColor) }

    // 加载中/失败用纯色 painter（色值主题感知：浅色对齐 View 版 #F2F2F2 / #E0E0E0）。
    //
    // 为何用 AsyncImage 而不是 SubcomposeAsyncImage（task 10-07-home-jank-jump-clip-fullscreen-video）：
    // SubcomposeAsyncImage 会为每张图做一次**子组合**（subcomposition）+ 额外测量，Coil 官方明确
    // 不建议在列表/网格中使用；首页一屏 8~12 张封面意味着一次切页/滚动要付出 8~12 次子组合，
    // 真机 release 的 gfxinfo 显示 GPU 空闲（p99 6ms）而 UI 线程/绘制指令录制超时（Slow UI thread /
    // Slow issue draw commands 各 ~270/360 帧），即成本在组合侧。AsyncImage + ColorPainter 占位
    // 只有单次组合，视觉与语义（占位色、失败色、crossfade、圆角裁剪、空 URL 分支）完全不变。
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = contentScale,
        placeholder = placeholderPainter,
        error = errorPainter,
        modifier = clipModifier,
    )
}

/** URL 归一化：空串原样返回；`//` 前缀补 `https:`（镜像 View 版 NetImage.normalize）。 */
fun normalizeUrl(url: String): String {
    if (url.isEmpty()) return ""
    return if (url.startsWith("//")) "https:$url" else url
}
