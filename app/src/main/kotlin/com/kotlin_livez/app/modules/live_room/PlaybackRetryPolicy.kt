package com.kotlin_livez.app.modules.live_room

/**
 * 播放失败重试策略（纯逻辑，可单测）。
 *
 * 背景（task 10-27-bili-playback-open-failure-recovery）：B 站登录后进房时，
 * `getRoomPlayInfo` 返回的线路列表里常有一条 CDN 直接返回 HTTP 400/404
 * （真机日志：`cn-gotcha08` 稳定 400，同批的 `cn-gotcha04`/`cn-gotcha09` 可播）。
 * 此时 mpv 只发 `END_FILE`：既没有 `eof-reached`（不走 mediaEnd），失败文案也不在
 * 错误特征词里（不走 mediaError），控制器完全收不到事件 → 黑屏不播，
 * 只有手动刷新（刷新会换一批 URL）才能恢复。
 *
 * 这里把"该怎么重试"从控制器里抽出来，规则：
 * - **从未出画（开流失败）→ 优先换下一条线路**：那批 URL 是同一次响应拿到的，
 *   其它 CDN 通常可用，比重签同一条线路（对单条 CDN 坏掉完全无效）快得多；
 * - 单线路 / 已是末条 / 已切够次数 / 恢复风暴熔断 → 交回原有"重签 → 放弃"链路。
 */
internal object PlaybackRetryPolicy {

    /** 一次开流失败后的动作。 */
    internal enum class OpenFailureAction {
        /** 切到下一条线路（不重新取流，直接用本次响应里的下一条 URL） */
        SwitchLine,

        /** 没有可换的线路（单线路 / 末条 / 已切够次数 / 熔断），交回原重试链路 */
        NoLineAvailable,
    }

    /**
     * `END_FILE` 是否应判定为"开流失败"。
     *
     * 只有"本次 loadfile 还没出过画就结束"才算：用户 stop、loadfile 替换旧文件、
     * 正常 EOF 都不会命中，因此不会误判成失败（见 task 09-12-refresh-crash 的教训）。
     *
     * @param expectMedia [PlayerEngine.open] 后置 true，[PlayerEngine.stop] / 释放会话后置 false
     * @param mediaLoaded 本次 loadfile 是否已经 file-loaded
     */
    fun shouldReportOpenFailure(expectMedia: Boolean, mediaLoaded: Boolean): Boolean =
        expectMedia && !mediaLoaded

    /**
     * 开流失败后是否换线。
     *
     * @param lineCount 本次取流返回的线路数
     * @param currentIndex 当前线路下标（0 起）
     * @param switchesSoFar 本会话已因开流失败切过几次（防止在 N 条坏线路间无限横跳）
     * @param recoveryStorm 是否已触发恢复风暴熔断（5 分钟窗口内恢复次数达上限）
     */
    fun openFailureAction(
        lineCount: Int,
        currentIndex: Int,
        switchesSoFar: Int,
        recoveryStorm: Boolean,
    ): OpenFailureAction {
        if (recoveryStorm) return OpenFailureAction.NoLineAvailable
        if (lineCount <= 1) return OpenFailureAction.NoLineAvailable
        if (currentIndex < 0 || currentIndex >= lineCount - 1) return OpenFailureAction.NoLineAvailable
        if (switchesSoFar >= lineCount - 1) return OpenFailureAction.NoLineAvailable
        return OpenFailureAction.SwitchLine
    }

    /** 从播放地址里取 host（解析失败返回空串，调用方按"未知 host"处理）。 */
    fun hostOf(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
    }

    /**
     * 会话内失败线路降权（P2）：把本会话实测"开流失败过"的 CDN host 排到末尾，
     * 其余保持原有顺序（`sortedBy` 是稳定排序）。
     *
     * 刻意**不硬编码**具体 CDN 域名（如 `cn-gotcha08`）—— 平台节点名会变，
     * 只依据"本会话真的失败过"这一事实。
     */
    fun reorderByFailedHosts(urls: List<String>, failedHosts: Set<String>): List<String> {
        if (urls.isEmpty() || failedHosts.isEmpty()) return urls
        return urls.sortedBy { url -> if (failedHosts.contains(hostOf(url))) 1 else 0 }
    }
}
