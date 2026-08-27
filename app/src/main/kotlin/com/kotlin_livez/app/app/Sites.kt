package com.kotlin_livez.app.app

import com.kotlin_livez.app.R
import com.kotlin_livez.core.interfaces.LiveSite
import com.kotlin_livez.core.site.BiliBiliSite
import com.kotlin_livez.core.site.DouyinSite
import com.kotlin_livez.core.site.DouyuSite
import com.kotlin_livez.core.site.HuyaSite

/**
 * 站点 UI 包装 —— 对应 Dart `Site`（lib/app/sites.dart）。
 */
data class Site(
    val id: String,
    val name: String,
    /** 站点 logo 资源路径（Dart 为 Flutter asset；Android 侧占位，UI 层按 id 解析）。 */
    val logo: String,
    val liveSite: LiveSite,
) {
    /** 站点 logo drawable 资源（对应 Dart site.logo asset；UI 层图标经此映射）。 */
    val logoRes: Int
        get() = when (id) {
            Constant.kBiliBili -> R.drawable.bilibili_2
            Constant.kDouyu -> R.drawable.douyu
            Constant.kHuya -> R.drawable.huya
            Constant.kDouyin -> R.drawable.douyin
            else -> R.drawable.douyin
        }
}

/**
 * 站点注册表 —— 对应 Dart `Sites`（lib/app/sites.dart）。
 */
object Sites {

    /** 全部站点（顺序即默认顺序，镜像 Dart allSites）。 */
    val allSites: Map<String, Site> by lazy {
        linkedMapOf(
            Constant.kBiliBili to Site(
                id = Constant.kBiliBili,
                logo = "bilibili",
                name = "哔哩哔哩",
                liveSite = BiliBiliSite(),
            ),
            Constant.kDouyu to Site(
                id = Constant.kDouyu,
                logo = "douyu",
                name = "斗鱼直播",
                liveSite = DouyuSite(),
            ),
            Constant.kHuya to Site(
                id = Constant.kHuya,
                logo = "huya",
                name = "虎牙直播",
                liveSite = HuyaSite(),
            ),
            Constant.kDouyin to Site(
                id = Constant.kDouyin,
                logo = "douyin",
                name = "抖音直播",
                liveSite = DouyinSite(),
            ),
        )
    }

    /**
     * 支持的站点列表（对应 Dart supportSites）：按 AppSettingsController.siteSort 排序。
     */
    val supportSites: List<Site>
        get() {
            val sort = com.kotlin_livez.app.di.ServiceContainer.appSettings.siteSort.value
            return sort.mapNotNull { allSites[it] }
        }
}