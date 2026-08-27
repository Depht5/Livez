package com.kotlin_livez.core.interfaces

import com.kotlin_livez.core.danmaku.LiveDanmaku
import com.kotlin_livez.core.model.LiveAnchorItem
import com.kotlin_livez.core.model.LiveCategory
import com.kotlin_livez.core.model.LiveCategoryResult
import com.kotlin_livez.core.model.LiveContributionRankItem
import com.kotlin_livez.core.model.LivePlayQuality
import com.kotlin_livez.core.model.LivePlayUrl
import com.kotlin_livez.core.model.LiveRoomDetail
import com.kotlin_livez.core.model.LiveRoomItem
import com.kotlin_livez.core.model.LiveSearchAnchorResult
import com.kotlin_livez.core.model.LiveSearchRoomResult
import com.kotlin_livez.core.model.LiveSubCategory
import com.kotlin_livez.core.model.LiveSuperChatMessage

/**
 * 直播站点服务契约。
 *
 * 对应 Dart 的 `LiveSite`（`lib/src/interface/live_site.dart`），方法职责一一对应。
 * 方法名按 Kotlin 惯例使用驼峰（Dart 中的拼写错误如 getCategores/getPlayQualites 在此修正为
 * getCategories/getPlayQualities，但职责一致；冻结的业务标识如站点 key 保持不变）。
 *
 * 注：Dart 用 `interface/` 目录；Kotlin 中 `interface` 是保留字，故目录/包改为 `interfaces/`
 * （本规范记录的可接受镜像偏差）。
 */
open class LiveSite {

    /** 站点唯一ID */
    open var id: String = ""

    /** 站点名称 */
    open var name: String = ""

    /** 获取该站点的弹幕连接 */
    open fun danmaku(): LiveDanmaku = LiveDanmaku()

    /** 读取网站的分类 */
    open suspend fun categories(): List<LiveCategory> = emptyList()

    /** 搜索直播间 */
    open suspend fun searchRooms(keyword: String, page: Int = 1): LiveSearchRoomResult =
        LiveSearchRoomResult(hasMore = false, items = emptyList())

    /** 搜索主播 */
    open suspend fun searchAnchors(keyword: String, page: Int = 1): LiveSearchAnchorResult =
        LiveSearchAnchorResult(hasMore = false, items = emptyList())

    /** 读取类目下房间 */
    open suspend fun categoryRooms(category: LiveSubCategory, page: Int = 1): LiveCategoryResult =
        LiveCategoryResult(hasMore = false, items = emptyList())

    /** 读取推荐的房间 */
    open suspend fun recommendRooms(page: Int = 1): LiveCategoryResult =
        LiveCategoryResult(hasMore = false, items = emptyList())

    /** 读取房间详情 */
    open suspend fun roomDetail(roomId: String): LiveRoomDetail =
        LiveRoomDetail(
            roomId = "", title = "", cover = "", userName = "", userAvatar = "",
            online = 0, status = false, url = "",
        )

    /** 读取房间清晰度 */
    open suspend fun playQualities(detail: LiveRoomDetail): List<LivePlayQuality> = emptyList()

    /** 读取播放链接 */
    open suspend fun playUrls(detail: LiveRoomDetail, quality: LivePlayQuality): LivePlayUrl =
        LivePlayUrl(urls = emptyList())

    /** 查询直播状态 */
    open suspend fun liveStatus(roomId: String): Boolean = false

    /** 读取指定房间的SC */
    open suspend fun superChatMessage(roomId: String, detail: LiveRoomDetail? = null):
        List<LiveSuperChatMessage> = emptyList()

    /** 读取指定房间的贡献榜 */
    open suspend fun contributionRank(roomId: String, detail: LiveRoomDetail? = null):
        List<LiveContributionRankItem> = emptyList()
}
