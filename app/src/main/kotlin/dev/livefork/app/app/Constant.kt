package dev.livefork.app.app

/**
 * 全局常量 —— 对应 Dart `Constant`（lib/app/constant.dart）。
 * 站点 key、事件名冻结保留；UI 用常量（首页 tab / 直播页 tab / 快捷访问项）
 * 为数据描述，UI 层（Compose/View）按需读取。
 */
object Constant {

    const val kUpdateFollow = "UpdateFollow"
    const val kUpdateHistory = "UpdateHistory"

    /** 站点 key（冻结标识，勿改） */
    const val kBiliBili = "bilibili"
    const val kDouyu = "douyu"
    const val kHuya = "huya"
    const val kDouyin = "douyin"
}

/** 首页底部导航项（对应 Dart HomePageItem）。 */
data class HomePageItem(
    val iconData: String,
    val title: String,
    val index: Int,
)

/** 直播页 tab 项（对应 Dart LiveRoomTabItem）。 */
data class LiveRoomTabItem(
    val iconData: String,
    val title: String,
    val subtitle: String? = null,
)

/** 直播页快捷访问项（对应 Dart LiveRoomQuickAccessItem）。 */
data class LiveRoomQuickAccessItem(
    val iconData: String,
    val title: String,
    val subtitle: String,
)

/** 首页全部 tab（对应 Dart Constant.allHomePages，顺序冻结）。 */
val ALL_HOME_PAGES: Map<String, HomePageItem> = linkedMapOf(
    "recommend" to HomePageItem("home", "首页", 0),
    "follow" to HomePageItem("heart", "关注", 1),
    "category" to HomePageItem("apps", "分类", 2),
    "user" to HomePageItem("user", "我的", 3),
)

/** 直播页全部 tab（对应 Dart Constant.allLiveRoomTabs，顺序冻结）。 */
val ALL_LIVE_ROOM_TABS: Map<String, LiveRoomTabItem> = linkedMapOf(
    "chat" to LiveRoomTabItem("message", "聊天"),
    "super_chat" to LiveRoomTabItem("spark", "SC/头条"),
    "follow" to LiveRoomTabItem("heart", "关注"),
    "contribution_rank" to LiveRoomTabItem(
        "chart",
        "贡献榜/亲密榜",
        "虎牙暂无贡献榜，直播间内会自动跳过",
    ),
    "event_flow" to LiveRoomTabItem(
        "pulse",
        "动态",
        "展示短时间内重复较多的弹幕内容",
    ),
    "settings" to LiveRoomTabItem("settings", "设置"),
)

/** 直播页全部快捷访问项（对应 Dart Constant.allLiveRoomQuickAccess，顺序冻结）。 */
val ALL_LIVE_ROOM_QUICK_ACCESS: Map<String, LiveRoomQuickAccessItem> = linkedMapOf(
    "follow" to LiveRoomQuickAccessItem("list", "关注列表", "快速切到已关注的直播间"),
    "history" to LiveRoomQuickAccessItem("history", "观看历史", "打开已经看过的直播间记录"),
    "recommendation" to LiveRoomQuickAccessItem(
        "apps",
        "同类推荐",
        "按当前分区查找相似直播间",
    ),
    "contribution_rank" to LiveRoomQuickAccessItem(
        "chart",
        "贡献榜/亲密榜",
        "虎牙暂无贡献榜，直播间内会自动跳过",
    ),
)