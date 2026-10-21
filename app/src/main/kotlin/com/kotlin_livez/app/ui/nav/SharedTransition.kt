@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.kotlin_livez.app.ui.nav

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect

/**
 * 一镜到底共享元素基础设施（task 09-12-shared-element-transition）。
 *
 * 方案（PDF 调研结论）：Compose 1.7 `SharedTransitionLayout` 包裹 NavHost，
 * 列表卡片封面 ↔ 直播间视频容器以相同 key 共享。mpv SurfaceView 内容不参与
 * View 层变换（PDF 明确警告），故共享锚点为封面图/容器 bounds，视频首帧就绪后
 * 淡入接棒。
 */

/** 由 [AppNavHost] 的 SharedTransitionLayout 提供；未包裹时为 null（modifier 安全退化）。 */
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** 由各路由 composable 的 AnimatedContentScope 提供（NavHost 转场作用域）。 */
val LocalNavAnimatedVisibilityScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/** 卡片封面 ↔ 直播间视频容器 的共享元素 key（站点+房间号保证全局唯一）。 */
fun liveRoomCoverKey(siteId: String, roomId: String): String = "live_cover_${siteId}_$roomId"

/** 卡片根容器 ↔ 直播间根容器 的共享 bounds key（容器展开：MaterialContainerTransform 效果）。 */
fun liveRoomContainerKey(siteId: String, roomId: String): String =
    "live_container_${siteId}_$roomId"

/**
 * 房间配对键（`siteId_roomId`）—— 比共享元素 key 更轻的「当前是否是这个房间的转场」判据。
 */
fun liveRoomPairKey(siteId: String, roomId: String): String = "${siteId}_$roomId"

/**
 * 当前**唯一**参与一镜到底的房间键（null = 不在转场中）。
 *
 * 为什么需要它（task 10-21-tablet-home-pager-fluency R1）：
 * `Modifier.sharedBounds` / `sharedElement` 每个调用点都会常驻一个 `SharedBoundsNode` +
 * 图形层，并在每次 Placement 把新的屏幕坐标写进 `sharedElement.currentBounds`（MutableState）。
 * 大屏首页同屏 20~30 张卡片、双页过渡 40~60 张，水平位移会让这几十个快照状态每帧失效，
 * 迫使全部卡片在滑动期间逐帧重录绘制指令 —— 而一镜到底真正用到的只有被点击的那 1 张。
 * 因此在列表侧**按需挂载**：只有房间键命中时才有共享节点。
 *
 * 写入方：[activateSharedRoom]（Router 导航前 / 直播间进入时）、
 * [clearSharedRoomIfMatch]（直播间退场卸载时）。
 */
var activeSharedRoomKey by mutableStateOf<String?>(null)
    private set

/** 标记某房间为当前转场房间（空值忽略，避免空 key 把整屏卡片的守卫误开）。 */
fun activateSharedRoom(siteId: String, roomId: String) {
    if (siteId.isEmpty() || roomId.isEmpty()) return
    activeSharedRoomKey = liveRoomPairKey(siteId, roomId)
}

/** 退场时清除标记，但只在「当前标记仍是这个房间」时清（换房场景不会误清新房间）。 */
fun clearSharedRoomIfMatch(siteId: String, roomId: String) {
    if (activeSharedRoomKey == liveRoomPairKey(siteId, roomId)) {
        activeSharedRoomKey = null
    }
}

/**
 * 共享元素/共享容器的 bounds 动画时长与曲线 —— 与 [AppNavHost] 的全局路由转场
 * （`slideInHorizontally(tween(280))` / `fadeIn(tween(280))`）保持一致。
 *
 * 为什么必须显式指定：Compose 的默认 bounds 曲线是
 * `DefaultBoundsTransform = BoundsTransform { _, _ -> DefaultSpring }`，而
 * `DefaultSpring = spring(stiffness = StiffnessMediumLow, visibilityThreshold = ...)`
 * （animation 1.8.2 `SharedTransitionScope.kt:1148/1169`）—— 物理弹簧的稳定耗时约 380–450ms，
 * 比页面滑动过渡长一截，观感上是「页面已经停住、共享容器还在飞」。
 */
private const val SharedTransitionDurationMs = 280

/**
 * 共享元素/共享容器的 bounds 变换（矩形从源 bounds 插值到目标 bounds）。
 * `BoundsTransform` 是 `fun interface`（`transform(initialBounds: Rect, targetBounds: Rect):
 * FiniteAnimationSpec<Rect>`，同文件 `:155`），故可直接用 lambda 构造；此处显式给出
 * `Rect` 类型实参，避免依赖 SAM 上下文推断。
 */
private val SharedBoundsTransform = BoundsTransform { _, _ ->
    tween<Rect>(durationMillis = SharedTransitionDurationMs, easing = FastOutSlowInEasing)
}

/**
 * 封面共享元素 modifier：卡片封面与直播间视频容器两端调用，key 一致即配对。
 * scope 缺失（未在 SharedTransitionLayout/路由转场作用域内）时原样返回——
 * 无匹配 key 的入口（如历史页）自然退化为普通转场。
 *
 * @param alwaysEnable 房间侧（直播间）传 true：进场/退场都必须挂载；
 *   列表卡片保持默认 false，仅当本次转场正是这个房间时才创建共享节点（见 [activeSharedRoomKey]）。
 */
@Composable
fun Modifier.liveRoomCoverSharedElement(
    siteId: String,
    roomId: String,
    alwaysEnable: Boolean = false,
): Modifier {
    if (!alwaysEnable && activeSharedRoomKey != liveRoomPairKey(siteId, roomId)) return this
    val sharedTransitionScope = LocalSharedTransitionScope.current ?: return this
    val animatedVisibilityScope = LocalNavAnimatedVisibilityScope.current ?: return this
    return with(sharedTransitionScope) {
        this@liveRoomCoverSharedElement.sharedElement(
            rememberSharedContentState(key = liveRoomCoverKey(siteId, roomId)),
            animatedVisibilityScope = animatedVisibilityScope,
            // 显式曲线：默认是 spring(StiffnessMediumLow)，与路由转场 tween(280) 不同步
            boundsTransform = SharedBoundsTransform,
        )
    }
}

/**
 * 容器展开共享 bounds（task 09-12-shared-element-transition 第二轮）：卡片根容器 ↔
 * 直播间根容器配对——进入时卡片矩形连续扩张为全屏（上下/左右同时展开），卡片内容与
 * 直播间内容交叉淡化，封面 [liveRoomCoverSharedElement] 在容器内同步飞入；返回反向。
 * 即 MaterialContainerTransform 的 Compose 原生等价物。
 */
@Composable
fun Modifier.liveRoomContainerSharedElement(
    siteId: String,
    roomId: String,
    alwaysEnable: Boolean = false,
): Modifier {
    if (!alwaysEnable && activeSharedRoomKey != liveRoomPairKey(siteId, roomId)) return this
    val sharedTransitionScope = LocalSharedTransitionScope.current ?: return this
    val animatedVisibilityScope = LocalNavAnimatedVisibilityScope.current ?: return this
    return with(sharedTransitionScope) {
        this@liveRoomContainerSharedElement.sharedBounds(
            rememberSharedContentState(key = liveRoomContainerKey(siteId, roomId)),
            animatedVisibilityScope = animatedVisibilityScope,
            // 进出场交叉淡化与 bounds 变换统一为 tween(280) + FastOutSlowInEasing
            // （fadeIn()/fadeOut() 的默认 spec 是 spring(StiffnessMediumLow) —— 见
            // animation 1.8.2 EnterExitTransition.kt:295，稳定耗时同样比路由转场长）
            enter = fadeIn(tween(SharedTransitionDurationMs, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(SharedTransitionDurationMs, easing = FastOutSlowInEasing)),
            boundsTransform = SharedBoundsTransform,
        )
    }
}

/**
 * 共享元素 key 唯一性守卫：返回**不允许挂共享元素**的列表下标。
 *
 * 判定为禁止的情形：
 * 1. `roomId` 为空 —— 所有空 id 会共享同一个 `live_cover_{site}_` key；
 * 2. 同一 `roomId` 在同屏列表中**不是首次出现**。
 *
 * 根因（Compose animation 1.8.2）：`SharedElement.kt:59-76`
 * `foundMatch = states.size > 1 && hasVisibleContent` —— 框架把「同 key 出现 ≥2 个
 * 调用点」直接当作「两条路由匹配成立」。折叠后：非 target 的那份 `shouldRenderInPlace=false`
 * 原位不再绘制（卡片空白）；`sharedBounds` 的所有同 key 节点被画进 root overlay
 *（`SharedTransitionScope.kt:141-144`，可盖住顶部栏）；转场期所有同 key state 被一起
 * 反复 retarget（`SharedElement.kt:87-99`）⇒ 动画拖长。
 *
 * 结论：共享元素 key 必须在**同屏同时组合**的范围内唯一。重复项退化为普通转场。
 * @param roomIds 与网格 items 一一对应；`null` = 该条目没有共享元素（主播行等），不参与判定
 */
internal fun sharedElementBlockedIndexes(roomIds: List<String?>): Set<Int> {
    val firstIndex = HashMap<String, Int>(roomIds.size)
    val blocked = HashSet<Int>()
    roomIds.forEachIndexed { index, roomId ->
        if (roomId == null) return@forEachIndexed
        if (roomId.isEmpty()) {
            blocked += index
        } else if (firstIndex.putIfAbsent(roomId, index) != null) {
            blocked += index
        }
    }
    return blocked
}
