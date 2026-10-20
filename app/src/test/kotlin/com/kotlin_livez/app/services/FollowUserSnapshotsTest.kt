package com.kotlin_livez.app.services

import com.kotlin_livez.app.models.FollowUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * `FollowUserSnapshots.replaceById` 契约测试（task 10-20-follow-startup-status-refresh AC5）：
 * 旧快照不被修改、新旧列表可观察差异、按 id 替换保序、id 不存在返回原实例、id 迁移。
 */
class FollowUserSnapshotsTest {

    private fun follow(id: String, liveStatus: Int = 0): FollowUser = FollowUser(
        id = id,
        roomId = id.substringAfter('_'),
        siteId = id.substringBefore('_'),
        userName = id,
        liveStatus = liveStatus,
    )

    @Test
    fun replaceById_doesNotMutateOldSnapshot() {
        val old = follow("bilibili_1", liveStatus = 0)
        val items = listOf(old)
        val updated = old.copy(liveStatus = 2)

        val result = FollowUserSnapshots.replaceById(items, updated)

        // 旧对象（已发布快照）保持旧值 ⇒ StateFlow 能观察到差异
        assertEquals(0, old.liveStatus)
        assertEquals(2, result.single().liveStatus)
        assertNotSame(old, result.single())
        assertSame(old, items.single())
    }

    @Test
    fun replaceById_resultIsNotEqualToOldList() {
        val old = follow("bilibili_1", liveStatus = 0)
        val items = listOf(old)

        val result = FollowUserSnapshots.replaceById(items, old.copy(liveStatus = 2))

        // 列表相等按元素 data class equals 比较：0 → 2 必定不相等 ⇒ MutableStateFlow 发射
        assertNotEquals(items, result)
    }

    @Test
    fun replaceById_replacesMatchingIdAndKeepsOrder() {
        val first = follow("bilibili_1")
        val second = follow("bilibili_2")
        val third = follow("douyu_3")
        val items = listOf(first, second, third)

        val result = FollowUserSnapshots.replaceById(items, second.copy(liveStatus = 2))

        assertEquals(
            listOf("bilibili_1", "bilibili_2", "douyu_3"),
            result.map { it.id },
        )
        assertSame(first, result[0])
        assertSame(third, result[2])
        assertEquals(2, result[1].liveStatus)
        // 被替换的原对象不被修改
        assertEquals(0, second.liveStatus)
    }

    @Test
    fun replaceById_returnsSameListInstanceWhenIdMissing() {
        val items = listOf(follow("bilibili_1"))

        val result = FollowUserSnapshots.replaceById(items, follow("bilibili_404", liveStatus = 2))

        // 返回同一实例 = 调用方可据此判断项已被移出列表
        assertSame(items, result)
    }

    @Test
    fun replaceById_supportsIdMigrationWithOldId() {
        val migratedSource = follow("douyin_1")
        val items = listOf(follow("bilibili_0"), migratedSource, follow("bilibili_2"))
        val migrated = migratedSource.copy(
            id = "douyin_99",
            roomId = "99",
            liveStatus = 2,
        )

        val result = FollowUserSnapshots.replaceById(items, migrated, oldId = migratedSource.id)

        assertEquals(
            listOf("bilibili_0", "douyin_99", "bilibili_2"),
            result.map { it.id },
        )
        assertEquals("douyin_1", migratedSource.id)
    }

    @Test
    fun replaceById_replacesOnlyFirstDuplicateId() {
        val duplicate = follow("bilibili_1")
        val items = listOf(duplicate, follow("bilibili_1", liveStatus = 1))

        val result = FollowUserSnapshots.replaceById(items, duplicate.copy(liveStatus = 2))

        assertEquals(2, result[0].liveStatus)
        assertEquals(1, result[1].liveStatus)
    }
}
