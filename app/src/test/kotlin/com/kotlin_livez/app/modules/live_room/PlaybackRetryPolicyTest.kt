package com.kotlin_livez.app.modules.live_room

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
}
