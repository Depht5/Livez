package dev.livefork.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.livefork.app.app.Sites
import dev.livefork.app.modules.category.detail.CategoryDetailController
import dev.livefork.app.ui.Router
import dev.livefork.app.ui.components.PagedRoomGrid
import dev.livefork.app.ui.theme.CategoryBlue
import dev.livefork.app.ui.theme.TextPrimary
import dev.livefork.core.model.LiveSubCategory

/**
 * 分类详情页（Compose 版）—— 对应 Dart `modules/category/detail/category_detail_page.dart`，
 * 替换现版 View 体系 `ui/CategoryDetailActivity.kt`。
 *
 * 结构镜像：顶部栏（返回「←」+ 居中标题 16sp medium + 右侧对称占位），
 * 主体为直播间卡片网格（复用 LiveRoomCard 全套）：下拉刷新 + 触底分页 +
 * 首屏加载/错误重试/空态三态——网格机制由公共组件 PagedRoomGrid 承载
 * （原 DetailRoomGrid 实现已逐行收敛进该组件，行为不变；仅错误/空态文案保留本页原文）。
 *
 * 参数经 route path/query 字符串传入（siteId / categoryId / categoryName）；
 * 控制器按参数 remember 创建，离开组合即销毁（等价原 Activity onDestroy）。
 *
 * @param siteId 站点 id（route path 参数）
 * @param categoryId 子分类 id（query 参数，可空）
 * @param categoryName 子分类名（仅用于标题展示；详情接口只用 id）
 * @param onBack 返回回调（对应原 Activity finish → popBackStack）
 */
@Composable
fun CategoryDetailScreen(
    siteId: String,
    categoryId: String,
    categoryName: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val site = Sites.allSites[siteId]

    // 站点不存在：提示后退出（对应原 onCreate 里 toast + finish）
    LaunchedEffect(siteId) {
        if (site == null) {
            Router.toast("站点不存在：$siteId")
            onBack()
        }
    }
    if (site == null) {
        // 空白帧等待返回动画（下一帧即 popBackStack）
        Box(modifier = modifier.fillMaxSize().background(Color.White))
        return
    }

    // 控制器路由级生命周期：remember 创建 + DisposableEffect 销毁
    // （对齐原 Activity onDestroy 调 controller.dispose()）
    val controller = remember(siteId, categoryId) {
        CategoryDetailController(
            site = site,
            subCategory = LiveSubCategory(name = categoryName, pic = null, id = categoryId, parentId = ""),
        )
    }
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }

    Column(modifier = modifier.fillMaxSize().background(Color.White)) {
        DetailTitleBar(title = categoryName.ifEmpty { "分类详情" }, onBack = onBack)
        PagedRoomGrid(
            listController = controller,
            gridState = remember { LazyGridState() },
            onOpenRoom = { roomId ->
                // 直播间仍为旧 Activity（Batch 7 才迁路由），入口保持 Router.openLiveRoom
                Router.openLiveRoom(site, roomId)
            },
            indicatorColor = CategoryBlue,
            // 错误/空态文案对齐现版 CategoryDetailActivity（区别于 HomeScreen 默认文案）
            errorTextBuilder = { msg -> "加载失败：" + msg.trim().ifEmpty { "未知错误" } + "\n点击刷新" },
            emptyText = "暂无内容\n点击刷新",
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 顶部栏 —— 对齐现版布局：返回键（22sp #333）+ 居中标题（16sp medium 单行省略）+
 * 右侧占位使标题居中。
 */
@Composable
private fun DetailTitleBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .background(Color.White)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "←",
            fontSize = 22.sp,
            color = TextPrimary,
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onBack,
                )
                .padding(vertical = 12.dp, horizontal = 8.dp),
        )
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 12.dp),
        )
        // 右侧占位使标题居中（对应返回键对称位置）
        Text(
            text = "",
            fontSize = 22.sp,
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
        )
    }
}
