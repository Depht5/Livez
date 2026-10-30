package com.kotlin_livez.app.modules.live_room

import com.kotlin_livez.app.modules.live_room.PlaybackRetryPolicy.ExhaustedAction
import com.kotlin_livez.app.modules.live_room.PlaybackRetryPolicy.OpenFailureAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放失败重试策略单测（task 10-27-bili-playback-open-failure-recovery）。
 *
 * 四个反例对应 task 09-12-refresh-crash 的历史坑：把 stop / loadfile 替换 / 正常 EOF
 * 误判成"开流失败"会导致无限切线或无限重试，必须钉住。
 */
class PlaybackRetryPolicyTest {

    /* ------------------------- shouldReportOpenFailure ------------------------- */

    @Test
    fun openFailure_onlyWhenExpectedAndNeverLoaded() {
        // 正例：open() 后还没出画就 END_FILE（HTTP 400/404 开流失败）
        assertTrue(PlaybackRetryPolicy.shouldReportOpenFailure(expectMedia = true, mediaLoaded = false))
    }

    @Test
    fun openFailure_ignoredWhenUserStopped() {
        // 反例：退出直播间 / 手动 stop（stop() 会把 expectMedia 置 false）
        assertFalse(PlaybackRetryPolicy.shouldReportOpenFailure(expectMedia = false, mediaLoaded = false))
    }

    @Test
    fun openFailure_ignoredWhenFileAlreadyLoaded() {
        // 反例：出过画之后的 END_FILE（正常 EOF / 断流 / loadfile 替换旧文件）
        assertFalse(PlaybackRetryPolicy.shouldReportOpenFailure(expectMedia = true, mediaLoaded = true))
    }

    @Test
    fun openFailure_ignoredAfterStopEvenIfLoaded() {
        assertFalse(PlaybackRetryPolicy.shouldReportOpenFailure(expectMedia = false, mediaLoaded = true))
    }

    /* --------------------------- openFailureAction ---------------------------- */

    @Test
    fun openFailureAction_switchesToNextLine() {
        assertEquals(
            OpenFailureAction.SwitchLine,
            PlaybackRetryPolicy.openFailureAction(lineCount = 4, currentIndex = 0, switchesSoFar = 0, recoveryStorm = false),
        )
        assertEquals(
            OpenFailureAction.SwitchLine,
            PlaybackRetryPolicy.openFailureAction(lineCount = 4, currentIndex = 2, switchesSoFar = 2, recoveryStorm = false),
        )
    }

    @Test
    fun openFailureAction_singleLineHasNothingToSwitch() {
        assertEquals(
            OpenFailureAction.NoLineAvailable,
            PlaybackRetryPolicy.openFailureAction(lineCount = 1, currentIndex = 0, switchesSoFar = 0, recoveryStorm = false),
        )
        assertEquals(
            OpenFailureAction.NoLineAvailable,
            PlaybackRetryPolicy.openFailureAction(lineCount = 0, currentIndex = -1, switchesSoFar = 0, recoveryStorm = false),
        )
    }

    @Test
    fun openFailureAction_lastLineFallsBackToRetryChain() {
        assertEquals(
            OpenFailureAction.NoLineAvailable,
            PlaybackRetryPolicy.openFailureAction(lineCount = 4, currentIndex = 3, switchesSoFar = 3, recoveryStorm = false),
        )
    }

    @Test
    fun openFailureAction_stopsAfterSwitchingAllLines() {
        // 4 条线路最多切 3 次；第 4 次必须交回原链路，避免在坏线路间无限横跳
        assertEquals(
            OpenFailureAction.NoLineAvailable,
            PlaybackRetryPolicy.openFailureAction(lineCount = 4, currentIndex = 1, switchesSoFar = 3, recoveryStorm = false),
        )
    }

    @Test
    fun openFailureAction_respectsRecoveryStorm() {
        assertEquals(
            OpenFailureAction.NoLineAvailable,
            PlaybackRetryPolicy.openFailureAction(lineCount = 4, currentIndex = 0, switchesSoFar = 0, recoveryStorm = true),
        )
    }

    /* -------------------------- reorderByFailedHosts -------------------------- */

    @Test
    fun reorder_movesFailedHostToEndKeepingOrder() {
        val urls = listOf(
            "https://d1--cn-gotcha08.bilivideo.com/live-bvc/1/a.flv?x=1",
            "https://d1--cn-gotcha04.bilivideo.com/live-bvc/2/a.flv?x=2",
            "https://d1--cn-gotcha09.bilivideo.com/live-bvc/3/a.flv?x=3",
        )
        val ordered = PlaybackRetryPolicy.reorderByFailedHosts(urls, setOf("d1--cn-gotcha08.bilivideo.com"))
        assertEquals(urls[1], ordered[0])
        assertEquals(urls[2], ordered[1])
        assertEquals(urls[0], ordered[2])
    }

    @Test
    fun reorder_keepsOrderWhenNoFailureRecorded() {
        val urls = listOf("https://a.example/x", "https://b.example/y")
        assertEquals(urls, PlaybackRetryPolicy.reorderByFailedHosts(urls, emptySet()))
        assertEquals(
            emptyList<String>(),
            PlaybackRetryPolicy.reorderByFailedHosts(emptyList(), setOf("a.example")),
        )
    }

    @Test
    fun hostOf_parsesAndToleratesGarbage() {
        assertEquals("d1--cn-gotcha08.bilivideo.com", PlaybackRetryPolicy.hostOf("https://d1--cn-gotcha08.bilivideo.com/x.flv?a=1"))
        assertEquals("", PlaybackRetryPolicy.hostOf(null))
        assertEquals("", PlaybackRetryPolicy.hostOf(""))
        assertEquals("", PlaybackRetryPolicy.hostOf("not a url"))
    }

    /* ------------------------- exhaustedAction ------------------------- */

    @Test
    fun exhausted_switchesToNextLineWhenNotLast() {
        // 还有下一条线路：先换线（不重签）
        assertEquals(
            ExhaustedAction.SwitchLine,
            PlaybackRetryPolicy.exhaustedAction(
                lineCount = 3, currentIndex = 0, canReSign = true, recoveryStorm = false, reSignCycles = 0,
            ),
        )
    }

    @Test
    fun exhausted_reSignsOnLastLineForHuyaOrDouyu() {
        // 末线路 + 虎牙/斗鱼 + 未熔断 + 还没用满重签轮数 → 重签再试
        assertEquals(
            ExhaustedAction.ReSign,
            PlaybackRetryPolicy.exhaustedAction(
                lineCount = 1, currentIndex = 0, canReSign = true, recoveryStorm = false, reSignCycles = 0,
            ),
        )
        assertEquals(
            ExhaustedAction.ReSign,
            PlaybackRetryPolicy.exhaustedAction(
                lineCount = 2, currentIndex = 1, canReSign = true, recoveryStorm = false,
                reSignCycles = PlaybackRetryPolicy.MAX_RE_SIGN_CYCLES - 1,
            ),
        )
    }

    @Test
    fun exhausted_givesUpAfterReSignCyclesExhausted() {
        // 永久坏流（如斗鱼 CDN 稳定 400）不会产生"真恢复"，风暴永远不触发 ——
        // 必须靠重签轮数上限收敛到「播放失败」，否则会无限重签
        assertEquals(
            ExhaustedAction.GiveUp,
            PlaybackRetryPolicy.exhaustedAction(
                lineCount = 1, currentIndex = 0, canReSign = true, recoveryStorm = false,
                reSignCycles = PlaybackRetryPolicy.MAX_RE_SIGN_CYCLES,
            ),
        )
    }

    @Test
    fun exhausted_givesUpOnRecoveryStorm() {
        assertEquals(
            ExhaustedAction.GiveUp,
            PlaybackRetryPolicy.exhaustedAction(
                lineCount = 1, currentIndex = 0, canReSign = true, recoveryStorm = true, reSignCycles = 0,
            ),
        )
    }

    @Test
    fun exhausted_givesUpWhenSiteCannotReSign() {
        // 非虎牙/斗鱼（如 B 站）：末线路耗尽直接放弃
        assertEquals(
            ExhaustedAction.GiveUp,
            PlaybackRetryPolicy.exhaustedAction(
                lineCount = 1, currentIndex = 0, canReSign = false, recoveryStorm = false, reSignCycles = 0,
            ),
        )
    }

    @Test
    fun exhausted_givesUpWithoutUrls() {
        assertEquals(
            ExhaustedAction.GiveUp,
            PlaybackRetryPolicy.exhaustedAction(
                lineCount = 0, currentIndex = -1, canReSign = true, recoveryStorm = false, reSignCycles = 0,
            ),
        )
    }
}
