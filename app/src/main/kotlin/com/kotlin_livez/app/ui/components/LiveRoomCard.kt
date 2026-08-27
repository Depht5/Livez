package com.kotlin_livez.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kotlin_livez.app.app.Utils
import com.kotlin_livez.app.ui.theme.ImagePlaceholder
import com.kotlin_livez.core.model.LiveRoomItem

/**
 * 直播间卡片（Compose 版）—— 对应 Dart `LiveRoomCard` 与现版 View 体系
 * `ui/LiveRoomCard.kt`。结构镜像：上半部分 16:9 封面（顶部两角圆角），
 * 下半部分 64dp 文字区（8dp 内边距）：标题单行加粗省略、空标题回退用户名；
 * 底行左侧主播名 + 右侧 🔥 人气；卡片白底 8dp 圆角 + 阴影，整体可点击。
 *
 * 封面高度不再由外部传 itemWidth 计算（View 版做法），改用 Modifier.aspectRatio(16/9)
 * 按列宽自适应，视觉等价且避免列宽取整偏差。
 *
 * @param item 直播间数据（core 的 [LiveRoomItem]）
 * @param onClick 卡片点击回调（进直播间）
 */
@Composable
fun LiveRoomCard(
    item: LiveRoomItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // M3 surface 底、8dp 圆角；阴影 elevation 2dp（对齐 View 版）
            .shadow(elevation = 2.dp, shape = RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        // 封面：16:9，顶部两角圆角（底部直角，由卡片整体裁剪配合）
        NetImage(
            url = item.cover,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(ImagePlaceholder),
            shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
        )

        // 文字区：固定 64dp 高、8dp 内边距
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(8.dp),
        ) {
            // 标题：14sp 加粗、单行省略；空标题回退用户名
            Text(
                text = item.title.trim().ifEmpty { item.userName },
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.weight(1f))

            // 底行：左主播名（占满剩余宽度省略）+ 右 🔥 人气（对应 Dart spaceBetween 底部行）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = item.userName,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "🔥 ${Utils.onlineToString(item.online)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
        }
    }
}
