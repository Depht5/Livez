package com.kotlin_livez.core

import com.kotlin_livez.core.interfaces.LiveSite
import com.kotlin_livez.core.site.BiliBiliSite
import com.kotlin_livez.core.site.DouyinSite
import com.kotlin_livez.core.site.DouyuSite
import com.kotlin_livez.core.site.HuyaSite

/**
 * 核心聚合入口。
 * 对应 Dart `lib/simple_live_core.dart` 的导出列表 + app 侧 `Sites.allSites` 的聚合职责：
 * 统一暴露公共类型与可用站点列表。新增公共站点时在此注册。
 */
object LiveSiteRegistry {

    /** 全部站点实例（对应 Dart app 侧 Sites.allSites）。 */
    val allSites: List<LiveSite> = listOf(
        BiliBiliSite(),
        DouyuSite(),
        HuyaSite(),
        DouyinSite(),
    )

    fun find(id: String): LiveSite? = allSites.firstOrNull { it.id == id }
}
