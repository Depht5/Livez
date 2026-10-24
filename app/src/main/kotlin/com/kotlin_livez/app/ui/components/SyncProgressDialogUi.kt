package com.kotlin_livez.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kotlin_livez.app.services.SyncProgress

/**
 * 同步进度框内容 —— 对应 Dart `_SyncProgressDialogBody`
 * （lib/widgets/sync_progress_dialog.dart）。
 *
 * 发送侧（SyncDeviceScreen 的页面级 Dialog）与接收侧（全局宿主 [SyncProgressHost]）共用，
 * 保证两端进度反馈视觉一致：标题行（阶段 + n/m）→ 线性进度条（无总量时不确定样式）→ 消息行。
 */
@Composable
fun SyncProgressDialogUi(progress: SyncProgress, modifier: Modifier = Modifier) {
    val progressText = if (progress.total > 0) "${progress.current}/${progress.total}" else "--/--"
    Surface(
        modifier = modifier.width(320.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = progress.stage,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = progressText,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            if (progress.isIndeterminate) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { progress.percent.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = progress.displayMessage,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
