package com.kotlin_livez.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 二维码图片（Compose 版）—— 对应 Dart `qr_flutter` 的 `QrImageView`。
 *
 * 用于 B 站 / 斗鱼扫码登录：接口下发的二维码内容是**给另一台设备的 App 扫的 URL**，
 * 不能用 WebView 打开（同机打开会被页面的 deeplink 跳转顶成「网页无法打开」）。
 *
 * 实现要点：
 * - 编码用 zxing `QRCodeWriter`（`width/height = 0` → 最小模块矩阵，容错级别 L 容积最大）；
 * - 直接按模块 `drawRect`，不建 Bitmap：任意显示尺寸都锐利，也不占位图内存；
 * - 静区（quiet zone）由 Compose 的 padding 提供（zxing `MARGIN = 0`），
 *   因此深色主题下也是一块纯白方片 —— 扫码对比度不受主题影响。
 *
 * @param content 二维码内容（B 站 / 斗鱼登录 URL）
 * @param size 正方形边长（含白边）
 * @param quietZonePadding 白边（>= 8dp 才能被多数扫码器识别）
 */
@Composable
fun QrCodeImage(
    content: String,
    modifier: Modifier = Modifier,
    size: Dp = 220.dp,
    quietZonePadding: Dp = 12.dp,
) {
    // 只有内容变化才重新编码（重组/主题切换不重复算）
    val matrix = remember(content) {
        if (content.isEmpty()) null else runCatching { encodeQrMatrix(content) }.getOrNull()
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        if (matrix == null) {
            Text(text = "二维码生成失败", fontSize = 13.sp, color = Color(0xFF888888))
            return@Box
        }
        Canvas(modifier = Modifier.fillMaxSize().padding(quietZonePadding)) {
            val moduleSize = minOf(this.size.width, this.size.height) / matrix.width
            val offsetX = (this.size.width - moduleSize * matrix.width) / 2f
            val offsetY = (this.size.height - moduleSize * matrix.height) / 2f
            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) {
                    if (!matrix.get(x, y)) continue
                    // 多画 0.35px：抵消非整数模块尺寸落点产生的发丝缝
                    drawRect(
                        color = ModuleColor,
                        topLeft = Offset(offsetX + x * moduleSize, offsetY + y * moduleSize),
                        size = Size(moduleSize + 0.35f, moduleSize + 0.35f),
                    )
                }
            }
        }
    }
}

/** 二维码模块色（固定深色，不跟随主题 —— 白底深块是扫码器的识别前提）。 */
private val ModuleColor = Color(0xFF111111)

private fun encodeQrMatrix(content: String): BitMatrix {
    val hints = mapOf(
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
        // 静区交给 Compose 的 padding，避免白边翻倍导致码面偏小
        EncodeHintType.MARGIN to 0,
    )
    return QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
}
