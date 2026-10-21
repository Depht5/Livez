package com.kotlin_livez.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [VersionName] 的口径测试 —— 版本比较是更新检查的唯一判据，回归即等于「用户永远收不到更新」
 * 或「反复提示同一个版本」，因此每条规则都要有用例锁定。
 */
class VersionNameTest {

    @Test
    fun normalize_stripsVPrefixAndSuffix() {
        assertEquals("0.2.0", VersionName.normalize("v0.2.0"))
        assertEquals("0.2.0", VersionName.normalize("V0.2.0"))
        assertEquals("0.2.0", VersionName.normalize("0.2.0-beta.1"))
        assertEquals("0.2.0", VersionName.normalize("0.2.0+build.7"))
        assertEquals("0.2.0", VersionName.normalize("  v0.2.0  "))
    }

    @Test
    fun compare_treatsMissingSegmentsAsZero() {
        assertEquals(0, VersionName.compare("0.2", "0.2.0"))
        assertEquals(0, VersionName.compare("v0.2.1", "0.2.1"))
        assertEquals(0, VersionName.compare("0.2.0.0", "0.2"))
    }

    @Test
    fun compare_comparesSegmentsNumerically() {
        // 字符串比较会把 "0.2.10" 判成小于 "0.2.9"
        assertTrue(VersionName.compare("0.2.10", "0.2.9") > 0)
        assertTrue(VersionName.compare("0.10.0", "0.9.0") > 0)
        assertTrue(VersionName.compare("1.0.0", "0.99.99") > 0)
    }

    @Test
    fun compare_supportsDatedPatchVersions() {
        // 修复版比同基线新
        assertTrue(VersionName.compare("0.2.0.261021", "0.2.0") > 0)
        // 功能版本高于同基线修复版
        assertTrue(VersionName.compare("0.2.1", "0.2.0.261021") > 0)
        // 同基线的两个修复版按日期先后
        assertTrue(VersionName.compare("0.2.0.261105", "0.2.0.261021") > 0)
        // 功能版本之后的修复版
        assertTrue(VersionName.compare("0.2.1.261022", "0.2.1") > 0)
        assertFalse(VersionName.isNewer("0.2.0", "0.2.0.261021"))
    }

    @Test
    fun compare_isSymmetric() {
        assertEquals(-1, VersionName.compare("0.2.0", "0.2.1"))
        assertEquals(1, VersionName.compare("0.2.1", "0.2.0"))
        assertEquals(0, VersionName.compare("0.2.1", "0.2.1"))
    }

    @Test
    fun malformedInputDoesNotThrow() {
        assertEquals(emptyList(), VersionName.segments(""))
        assertEquals(emptyList(), VersionName.segments("   "))
        assertEquals(listOf(0), VersionName.segments("beta"))
        assertEquals(listOf(0, 2, 0), VersionName.segments("x.2.0"))
        assertEquals("", VersionName.display(""))
        // 全 0 与空串等价（都视为「未知/最旧」）
        assertEquals(0, VersionName.compare("", "0.0.0"))
        assertEquals(0, VersionName.compare("beta", "0"))
    }

    @Test
    fun display_alwaysHasVPrefix() {
        assertEquals("v0.2.0", VersionName.display("0.2.0"))
        assertEquals("v0.2.0", VersionName.display("v0.2.0"))
        assertEquals("v0.2.0.261021", VersionName.display("0.2.0.261021"))
    }
}
