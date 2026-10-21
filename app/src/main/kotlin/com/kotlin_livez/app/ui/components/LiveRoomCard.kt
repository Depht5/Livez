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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kotlin_livez.app.app.Utils
import com.kotlin_livez.app.ui.nav.liveRoomContainerSharedElement
import com.kotlin_livez.app.ui.nav.liveRoomCoverSharedElement
import com.kotlin_livez.app.ui.theme.CustomIcons
import com.kotlin_livez.app.ui.theme.LiveOrange
import com.kotlin_livez.app.ui.theme.LocalAppDarkTheme
import com.kotlin_livez.app.ui.theme.cardContainerColor
import com.kotlin_livez.app.ui.theme.imagePlaceholderColor
import com.kotlin_livez.core.model.LiveRoomItem

/**
 * 直播间卡片（Compose 版）—— 对应 Dart `LiveRoomCard` 与现版 View 体系
 * `ui/LiveRoomCard.kt`。结构镜像：上半部分 16:9 封面（顶部两角圆角），
 * 下半部分 64dp 文字区（8dp 内边距）：标题单行加粗省略、空标题回退用户名；
 * 底行左侧主播名 + 右侧火苗图标人气；卡片 8dp 圆角 + 阴影，整体可点击。
 *
 * 封面高度不再由外部传 itemWidth 计算（View 版做法），改用 Modifier.aspectRatio(16/9)
 * 按列宽自适应，视觉等价且避免列宽取整偏差。
 *
 * @param item 直播间数据（core 的 [LiveRoomItem]）
 * @param onClick 卡片点击回调（进直播间）
 * @param sharedElementSiteId 一镜到底共享元素用站点 id（task 09-12-shared-element-transition）：
 *   非空时封面挂 `live_cover_{siteId}_{roomId}` 共享 key 与直播间视频容器配对；null 不参与
 */
@Composable
fun LiveRoomCard(
    item: LiveRoomItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sharedElementSiteId: String? = null,
) {
    // 封面共享元素 modifier（null 安全：不参与一镜到底的调用方保持原状）
    val coverSharedModifier = if (sharedElementSiteId != null) {
        Modifier.liveRoomCoverSharedElement(sharedElementSiteId, item.roomId)
    } else {
        Modifier
    }
    // 容器展开：卡片根容器 ↔ 直播间根容器 sharedBounds（封面在容器内同步飞入）
    val containerSharedModifier = if (sharedElementSiteId != null) {
        Modifier.liveRoomContainerSharedElement(sharedElementSiteId, item.roomId)
    } else {
        Modifier
    }
    // 深色/纯黑主题下跳过 2dp 阴影（task 10-17-ui-power-and-recomposition-governance）：
    // 深色底上阴影几乎不可见，却仍要按 elevation 录一层阴影绘制指令。
    // 浅色下保持 8dp 圆角 + 2dp 阴影的既有视觉；只动 shadow 这一条件。
    val cardShadow = if (LocalAppDarkTheme.current) {
        Modifier
    } else {
        Modifier.shadow(elevation = 2.dp, shape = RoundedCornerShape(8.dp))
    }
    Column(
        modifier = containerSharedModifier
            .then(modifier)
            .fillMaxWidth()
            // 卡片容器色（task 08-29-chat-pin-bubble-follow-grid）：深色 = #1D1B20
            //（介于页面背景与底栏之间的灰）；浅色 = surface。8dp 圆角不变
            .then(cardShadow)
            // 圆角涟漪裁剪（本任务）：`.background(color, shape).clickable(...)` 不会裁剪
            // clickable 的默认 ripple（溢出圆角呈直角矩形），故显式先 clip 再 background/clickable。
            // 顺序要点：clip 必须排在 `.shadow` **之后** —— shadow 画在自己的图层上，
            // 若 clip 在其之前，阴影会被同一圆角裁掉；放之后则只裁内容与涟漪，阴影照常绘制。
            .clip(RoundedCornerShape(8.dp))
            .background(cardContainerColor(), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        // 封面：16:9。圆角由外层 Column 的 `.clip(RoundedCornerShape(8.dp))` 统一裁切，
        // 内层不再重复 shape（task 10-21-tablet-home-pager-fluency R4：省一层冗余圆角裁剪）。
        NetImage(
            url = item.cover,
            modifier = coverSharedModifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                // 封面底色主题感知（深色不再亮白，本任务）
                .background(imagePlaceholderColor()),
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

            // 底行：左主播名（占满剩余宽度省略）+ 右火苗图标人气（对应 Dart spaceBetween 底部行）
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
                // 人气：矢量火苗图标（人气橙）+ 数值，替换原 🔥 Emoji
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = CustomIcons.Flame,
                        contentDescription = null,
                        tint = LiveOrange,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = Utils.onlineToString(item.online),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
