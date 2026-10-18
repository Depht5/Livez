package com.kotlin_livez.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/**
 * 居中空态提示（共享组件）——13sp 弱化色、水平居中，多行文案亦居中。
 *
 * 合并来源：直播间（原 `EmptyTabHint`）、关注页、历史页三处近同构私有实现
 *（三者逐字比对仅「关注页」那份缺 `textAlign`，其文案为单行，补齐后无视觉变化）。
 * 新页面请复用本组件，不要再各写一份（文案措辞可不同，规格必须一致）。
 *
 * 刻意**不带任何 clickable / pointer 修饰**：空态常叠在网格或列表之上
 *（如关注页下拉刷新），一旦吃掉指针事件，其下区域的手势就失效了。
 *
 * @param text 空态文案（如「暂无观看记录」）
 */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
