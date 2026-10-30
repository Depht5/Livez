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

    /**
     * 重试耗尽后的动作。
     *
     * 语义对齐 Dart `_playbackRetriesExhausted`：还有剩余线路 → 换下一条；已是末线路、站点是
     * 虎牙/斗鱼、且未触发恢复风暴 → 重置线路与计数、重新签名再试一轮；否则判放弃。
     *
     * **为什么还要 `reSignCycles`（Kotlin 侧新增的兜底）**：Dart 的"重签再试一轮"靠恢复风暴
     * （5 分钟内 6 次"真恢复"）收敛，而 Kotlin 现在只在**真正出画**时记一次恢复 —— 全天候坏
     * 流（例如斗鱼 CDN 稳定 400）永远不产生"恢复"，风暴永远不会触发，重签会无限循环。
     * 因此给重签循环加一个显式上限：同一条坏流最多再签 [MAX_RE_SIGN_CYCLES] 轮，之后走
     * 「播放失败」兜底（task 10-30-douyu-playback-header-leak）。
     */
    internal enum class ExhaustedAction {
        /** 切到下一条线路 */
        SwitchLine,

        /** 末线路：重置线路与重试计数，重新签名再试一轮 */
        ReSign,

        /** 放弃播放（提示「播放失败」/ 判下播），交给自动换台 */
        GiveUp,
    }

    /**
     * 重试耗尽后的动作判定。
     *
     * @param lineCount 本次取流返回的线路数
     * @param currentIndex 当前线路下标（0 起）
     * @param canReSign 该站点是否支持"末线路重签续播"（虎牙/斗鱼）
     * @param recoveryStorm 是否已触发恢复风暴熔断
     * @param reSignCycles 本段连续失败里已经重签过几轮（真正出画/换房时归零）
     */
    fun exhaustedAction(
        lineCount: Int,
        currentIndex: Int,
        canReSign: Boolean,
        recoveryStorm: Boolean,
        reSignCycles: Int,
    ): ExhaustedAction {
        if (lineCount <= 0) return ExhaustedAction.GiveUp
        if (currentIndex < lineCount - 1) return ExhaustedAction.SwitchLine
        if (canReSign && !recoveryStorm && reSignCycles < MAX_RE_SIGN_CYCLES) {
            return ExhaustedAction.ReSign
        }
        return ExhaustedAction.GiveUp
    }

    /** 同一段连续失败里，末线路最多重签几轮（每轮之后仍会走 2 次常规重试）。 */
    internal const val MAX_RE_SIGN_CYCLES = 2
}
