package com.kotlin_livez.app.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新检查的「发布选择」纯逻辑测试（task 10-21-settings-about-update-check）。
 *
 * 这两处是最容易被两个渠道的差异坑到的分支：
 * - 资产命名（Gitee `app-release.apk` vs GitHub `Livez-0.2.0-arm64-v8a.apk`）；
 * - 发布日期时区（GitHub `published_at` 为 UTC、Gitee `created_at` 带 `+08:00`）。
 */
class UpdateReleaseSelectionTest {

    private fun asset(name: String, url: String = "https://example.com/$name", size: Long = 1L) =
        UpdateService.AssetDto(name = name, downloadUrl = url, size = size)

    private fun release(
        tag: String,
        publishedAt: String? = null,
        createdAt: String? = null,
        prerelease: Boolean = false,
        draft: Boolean = false,
    ) = UpdateService.ReleaseDto(
        tagName = tag,
        body = "",
        prerelease = prerelease,
        draft = draft,
        createdAt = createdAt,
        publishedAt = publishedAt,
        assets = emptyList(),
    )

    @Test
    fun selectApk_handlesGiteeAssetNaming() {
        // Gitee release 同时挂出源码包与 APK，资产名不规范（app-release.apk）
        val assets = listOf(
            asset("v0.2.0.zip"),
            asset("app-release.apk"),
            asset("v0.2.0.tar.gz"),
        )
        assertEquals("app-release.apk", selectApkAsset(assets)?.name)
    }

    @Test
    fun selectApk_prefersArm64WhenMultipleApks() {
        val assets = listOf(
            asset("Livez-0.2.0-armeabi-v7a.apk"),
            asset("Livez-0.2.0-arm64-v8a.apk"),
        )
        assertEquals("Livez-0.2.0-arm64-v8a.apk", selectApkAsset(assets)?.name)
    }

    @Test
    fun selectApk_returnsNullWhenNoApk() {
        assertNull(selectApkAsset(listOf(asset("source.zip"), asset("source.tar.gz"))))
        assertNull(selectApkAsset(emptyList()))
    }

    @Test
    fun selectApk_ignoresAssetWithoutDownloadUrl() {
        val assets = listOf(
            asset("Livez-0.2.0-arm64-v8a.apk", url = ""),
            asset("app-release.apk"),
        )
        assertEquals("app-release.apk", selectApkAsset(assets)?.name)
    }

    @Test
    fun pickLatest_filtersPrereleaseAndDraft() {
        val releases = listOf(
            release("v0.3.0", publishedAt = "2026-10-21T00:00:00Z", prerelease = true),
            release("v0.2.2", publishedAt = "2026-10-20T00:00:00Z", draft = true),
            release("v0.2.1", publishedAt = "2026-10-19T00:00:00Z"),
        )
        assertEquals("v0.2.1", pickLatestRelease(releases)?.tagName)
    }

    @Test
    fun pickLatest_comparesUtcAndOffsetTimestamps() {
        // GitHub 给 UTC、Gitee 给 +08:00，同一时刻的不同写法必须能正确排序
        val releases = listOf(
            release("v0.2.0", createdAt = "2026-10-21T00:32:46+08:00"),
            release("v0.2.1", publishedAt = "2026-10-20T16:41:05Z"),
        )
        assertEquals("v0.2.1", pickLatestRelease(releases)?.tagName)
        assertTrue(publishedMillis(releases[0]) > 0L)
    }

    @Test
    fun publishedMillis_toleratesMissingAndInvalidValues() {
        assertEquals(0L, publishedMillis(release("v0.1.0")))
        assertEquals(0L, publishedMillis(release("v0.1.0", createdAt = "not-a-date")))
    }
}
