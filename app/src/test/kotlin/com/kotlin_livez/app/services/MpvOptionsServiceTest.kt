package com.kotlin_livez.app.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `mpv 档位` 三档契约测试（task 10-20-live-playback-power-optimization R1）。
 *
 * 只校验纯数据表，不触碰 `AppSettingsController` / Android 依赖。
 */
class MpvOptionsServiceTest {

    @Test
    fun profileLabels_exposeExactlyThreeTiersInOrder() {
        assertEquals(listOf("performance", "balanced", "quality"), MpvOptionsService.profileLabels.keys.toList())
        assertEquals(listOf("低功耗", "均衡", "画质"), MpvOptionsService.profileLabels.values.toList())
    }

    @Test
    fun performanceProfile_isMediaCodecEmbedZeroCopy() {
        val options = MpvOptionsService.profileOptions("performance")
        assertEquals("mediacodec_embed", options["vo"])
        assertEquals("mediacodec", options["hwdec"])
        // 低功耗档不走 GPU 着色管线，不应携带缩放/去色带参数
        assertEquals(null, options["scale"])
        assertEquals(null, options["deband"])
    }

    @Test
    fun balancedProfile_isLightweightGpuWithSoftDecodeFallback() {
        val options = MpvOptionsService.profileOptions("balanced")
        assertEquals("fast", options["profile"])
        assertEquals("gpu", options["vo"])
        assertEquals("auto-safe", options["hwdec"])
        assertEquals("bilinear", options["scale"])
        assertEquals("bilinear", options["cscale"])
        assertEquals("bilinear", options["dscale"])
        assertEquals("no", options["correct-downscaling"])
        assertEquals("no", options["sigmoid-upscaling"])
        assertEquals("no", options["deband"])
    }

    @Test
    fun qualityProfile_isGpuHqWithHighOrderScaling() {
        val options = MpvOptionsService.profileOptions("quality")
        assertEquals("gpu-hq", options["profile"])
        assertEquals("gpu", options["vo"])
        assertEquals("auto-safe", options["hwdec"])
        assertEquals("spline36", options["scale"])
        assertEquals("spline36", options["cscale"])
        assertEquals("mitchell", options["dscale"])
        assertEquals("yes", options["correct-downscaling"])
        assertEquals("yes", options["sigmoid-upscaling"])
        assertEquals("yes", options["deband"])
    }

    @Test
    fun unknownProfile_fallsBackToBalanced() {
        assertSame(
            MpvOptionsService.profileOptions("balanced"),
            MpvOptionsService.profileOptions("no-such-profile"),
        )
    }

    @Test
    fun profileSubtitle_matchesTierCopy() {
        assertEquals("低功耗（mediacodec_embed + mediacodec）", MpvOptionsService.profileSubtitle("performance"))
        assertEquals("均衡（vo=gpu + fast）", MpvOptionsService.profileSubtitle("balanced"))
        assertEquals("画质（vo=gpu + gpu-hq）", MpvOptionsService.profileSubtitle("quality"))
        assertTrue(MpvOptionsService.profileSubtitle("unknown").isNotEmpty())
    }

    @Test
    fun profileDescriptions_coverEveryTier() {
        assertEquals(
            MpvOptionsService.profileLabels.keys,
            MpvOptionsService.profileDescriptions.keys,
        )
        assertTrue(MpvOptionsService.profileDescriptions.values.all { it.isNotBlank() })
    }
}
