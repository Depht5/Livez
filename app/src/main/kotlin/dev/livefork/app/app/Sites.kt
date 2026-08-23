package dev.livefork.app.app

import dev.livefork.core.interfaces.LiveSite
import dev.livefork.core.site.BiliBiliSite
import dev.livefork.core.site.DouyinSite
import dev.livefork.core.site.DouyuSite
import dev.livefork.core.site.HuyaSite
import dev.livefork.core.site.KuaishouSite

/**
 * 站点 UI 包装 —— 对应 Dart `Site`（lib/app/sites.dart）。
 */
data class Site(
    val id: String,
    val name: String,
    /** 站点 logo 资源路径（Dart 为 Flutter asset；Android 侧占位，UI 层按 id 解析）。 */
    val logo: String,
    val liveSite: LiveSite,
)

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
            Constant.kKuaishou to Site(
                id = Constant.kKuaishou,
                logo = "kuaishou",
                name = "快手直播",
                liveSite = KuaishouSite(),
            ),
        )
    }

    /**
     * 支持的站点列表（对应 Dart supportSites）：按 AppSettingsController.siteSort 排序。
     */
    val supportSites: List<Site>
        get() {
            val sort = dev.livefork.app.di.ServiceContainer.appSettings.siteSort.value
            return sort.mapNotNull { allSites[it] }
        }
}