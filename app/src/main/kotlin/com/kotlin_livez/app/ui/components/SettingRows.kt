package com.kotlin_livez.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compose 版通用设置行 —— 对应旧 View 体系 `ui/SettingRows.kt` 的视觉规格。
 * Material 3 风格化（task 08-26-material3-ui-restyle）：颜色统一取
 * `MaterialTheme.colorScheme`（表面 surface / 正文 onSurface / 次级 onSurfaceVariant /
 * 占位 outline / 选中 primary），点击恢复默认 ripple（不再去涟漪）。
 * 供设置页使用；直播间弹幕设置等弹层（Batch 7）可复用。
 */

/** 分组标题：13sp primary 色（M3 下跟随主题/动态取色），上 14dp 下 4dp 左右 16dp。 */
@Composable
fun SettingSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 28.dp, top = 14.dp, end = 16.dp, bottom = 4.dp),
    )
}

/** 可点击行：标题+副标题+右侧箭头 ›（对齐旧版 clickRow；默认 ripple）。 */
@Composable
fun SettingClickRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingRowTexts(
            title = title,
            subtitle = subtitle,
            modifier = Modifier.weight(1f),
            enabled = enabled,
        )
        Text(text = "›", fontSize = 18.sp, color = MaterialTheme.colorScheme.outline)
    }
}

/**
 * 开关行：标题+副标题+右侧 Switch（对齐旧版 switchRow；选中态用 primary）。
 *
 * 整行 `toggleable`（本任务）：点击行任意位置（含右侧留白）都能切换，提升大屏/单手可达性；
 * `Role.Switch` 让无障碍读到「开关」语义。**回调只在行上挂一次** —— Switch 自身的
 * `onCheckedChange` 传 null（避免「点开关」时行 toggleable 与开关回调双触发；
 * Switch 仍按 `enabled` 正常显隐灰显）。
 */
@Composable
fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onChange,
            )
            .padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingRowTexts(
            title = title,
            subtitle = subtitle,
            modifier = Modifier.weight(1f),
            enabled = enabled,
        )
        Switch(
            checked = checked,
            // 整行已由上方 toggleable 处理点击与语义，此处传 null 防双重回调
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
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
    Column(modifier = modifier.fillMaxWidth()) {
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

/** 标题+副标题垂直文本组（对齐旧版 rowTexts；禁用态用 outline 弱化）。 */
@Composable
private fun SettingRowTexts(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            fontSize = 15.sp,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.outline
            },
        )
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.outline
                },
            )
        }
    }
}


/**
 * 分组卡片容器（Material 3 设置页风格，task m3-mine-settings）：圆角 12dp +
 * surfaceContainerLow 底，水平外边距 12dp。组内行不再自带背景，由本容器托底；
 * 与页面根底色（surface）形成 M3 分组层次。
 */
@Composable
fun SettingGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        content = content,
    )
}

/**
 * 带自定义尾部控件的行：标题 + 副标题 + 右侧任意控件（task 10-21-settings-about-update-check）。
 *
 * 与 [SettingClickRow] 的区别：尾部不是箭头、整行也不可点击 —— 「当前版本」行右侧是
 * 「检查更新」按钮，点击目标必须只有按钮本身（整行可点会让「看版本号」也触发网络请求）。
 */
@Composable
fun SettingActionRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingRowTexts(
            title = title,
            subtitle = subtitle,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}
