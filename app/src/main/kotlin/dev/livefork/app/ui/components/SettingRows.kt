package dev.livefork.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.livefork.app.ui.theme.CardTitle
import dev.livefork.app.ui.theme.LiveRed
import dev.livefork.app.ui.theme.TextTertiary

/** 行内箭头色（对应旧版 SettingRows 的 #BBBBBB，仅组件内部使用）。 */
private val RowArrow = Color(0xFFBBBBBB)

/**
 * Compose 版通用设置行 —— 对应旧 View 体系 `ui/SettingRows.kt` 的视觉规格
 * （白底行、标题 15sp #222、副标题 12sp #888、分组标题 13sp 红系、箭头 ›）。
 * API 形态不必逐参复刻：按 Compose 惯例改为 @Composable 函数 + 命名参数。
 * 供设置页使用；直播间弹幕设置等弹层（Batch 7）可复用。
 *
 * 注意：旧版 View SettingRows 仍被 AccountActivity 引用（Batch 5 迁移时随其删除），
 * 本文件与其同名不同包，互不影响。
 */

/** 分组标题：13sp 红系 #FF3F4B，上 14dp 下 4dp 左右 16dp（对齐旧版 sectionTitle）。 */
@Composable
fun SettingSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = LiveRed,
        modifier = modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 4.dp),
    )
}

/** 可点击行：标题+副标题+右侧箭头 ›（对齐旧版 clickRow；去涟漪对齐整体简洁风格）。 */
@Composable
fun SettingClickRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.White)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingRowTexts(title = title, subtitle = subtitle, modifier = Modifier.weight(1f))
        Text(text = "›", fontSize = 18.sp, color = RowArrow)
    }
}

/** 开关行：标题+副标题+右侧 Switch（对齐旧版 switchRow）。 */
@Composable
fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingRowTexts(title = title, subtitle = subtitle, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            // 选中态用主色红系，视觉贴近旧版 Switch（默认青绿与全局配色不符）
            colors = SwitchDefaults.colors(checkedTrackColor = LiveRed),
        )
    }
}

/**
 * 数值滑杆行：标题+副标题+下方 Slider。
 * 当前设置页未使用（旧版无此行型）；供后续弹幕设置弹层（Batch 7）等数值调节场景复用。
 */
@Composable
fun SettingSliderRow(
    title: String,
    subtitle: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    Column(modifier = modifier.fillMaxWidth().background(Color.White)) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingRowTexts(title = title, subtitle = subtitle, modifier = Modifier.weight(1f))
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
        )
    }
}

/** 标题+副标题垂直文本组（对齐旧版 rowTexts）。 */
@Composable
private fun SettingRowTexts(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(text = title, fontSize = 15.sp, color = CardTitle)
        if (subtitle.isNotEmpty()) {
            Text(text = subtitle, fontSize = 12.sp, color = TextTertiary)
        }
    }
}
