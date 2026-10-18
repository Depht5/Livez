package com.kotlin_livez.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kotlin_livez.app.ui.theme.CustomIcons

/**
 * M3 胶囊搜索框（共享组件）—— task 10-17-ui-material3-details-and-redesign。
 *
 * 由搜索页与关注页**共用一份实现**（不要在两处各写一份）：
 * 标准 `TextField`/`OutlinedTextField` 的 56dp 最低高度会撑高顶栏并把文字上下裁切，
 * 这里改为 `BasicTextField` + 自绘胶囊容器——最小 38dp 高、`RoundedCornerShape(19.dp)`、
 * `surfaceContainerHigh` 填充、无外边框；左侧 [CustomIcons.Search] 18dp 图标 +
 * 中间单行输入与占位符 + 输入非空时右侧的 [CustomIcons.Close] 一键清空按钮（36dp 触控热区）。
 *
 * 高度用 `heightIn(min = 38.dp)` 而非定高：默认字号下内容最高 36dp（清空按钮）< 38dp，
 * 胶囊仍是精确 38dp、不撑高顶栏；系统大字号下 14sp 的单行行高（含 fontPadding ≈ 1.3~1.45×字号）
 * 会先接近、再超过 38dp（推算交叉点约在 `fontScale` ≳ 2.0，未真机实测），此后胶囊自适应上浮，
 * 宁可变高也不把文字上下裁掉。代价：高度超过 38dp 时 `RoundedCornerShape(19.dp)` 不再是严格胶囊形。
 *
 * 多参数组件——**调用处一律具名传参**，不用 trailing lambda（ui-compose.md gotcha 2）。
 *
 * @param value 当前输入文本（由调用方持有）
 * @param onValueChange 文本变更回调；右侧清空按钮同样经它回传空串（调用方无需额外状态）
 * @param placeholder 占位文案（仅 value 为空时显示）
 * @param keyboardActions IME 动作回调（如搜索页传 `KeyboardActions(onSearch = { ... })`）；
 *        默认无动作——关注页是即时过滤，无需提交
 */
@Composable
fun CompactSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    Row(
        modifier = modifier
            .heightIn(min = 38.dp)
            // 先 clip 再 background/clickable 的同一口径：胶囊底色被圆角裁剪
            .clip(RoundedCornerShape(19.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            // 右内边距只留 4dp：清空按钮自带 36dp 触控区，视觉边距由它贡献（见下方按钮），
            // 无按钮时输入框自身的 8dp 内边距仍让文字距右缘 12dp。
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = CustomIcons.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            singleLine = true,
            // 本地默认样式 + 覆盖字号/颜色：直接传裸 TextStyle 会丢掉主题下发的
            // fontFamily/letterSpacing（同 ui-compose.md 条目 29 第 3 点的合成语义）
            textStyle = LocalTextStyle.current.merge(
                TextStyle(fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface),
            ),
            // 光标色：系统默认黑在深色主题下不可见
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = keyboardActions,
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (value.isNotEmpty()) {
            // 清空按钮：36dp 圆形触控区（图标仍 16dp）；圆形裁剪让水波纹不外溢。
            // 36dp 而非贴合图标的 24dp —— 触控热区吃满胶囊高度，默认字号下 36dp < 最小 38dp
            // 不撑高胶囊，大字号下也仍是可点尺寸。
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable { onValueChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = CustomIcons.Close,
                    contentDescription = "清空",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
