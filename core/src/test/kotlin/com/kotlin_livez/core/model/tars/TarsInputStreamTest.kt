package com.kotlin_livez.core.model.tars

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TarsInputStreamTest {

    @Test
    fun signedByte_roundTripsNegativeValue() {
        val encoded = TarsOutputStream().apply { writeLong(-1L, 0) }.toByteArray()

        assertEquals(-1L, TarsInputStream(encoded).readLong(0, true))
    }

    @Test
    fun negativeStringLength_failsWithoutSpinning() {
        // tag0 + STRING4 + length(-5). 旧实现 seek tag1 时游标 0 -> 5 -> 0，
        // 会在 OkHttp reader 线程永久忙循环，close/切房也无法中断。
        val malformed = byteArrayOf(0x07, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFB.toByte())
        var failure: Throwable? = null
        val decoder = Thread {
            failure = runCatching {
                TarsInputStream(malformed).readLong(1, false)
            }.exceptionOrNull()
        }.apply {
            name = "tars-negative-length-test"
            isDaemon = true
            start()
        }

        decoder.join(1_000)

        assertFalse(decoder.isAlive, "畸形 Tars 帧不得让解码线程永久自旋")
        assertTrue(
            failure is TarsDecodeException,
            "应该抛出 TarsDecodeException，实际为 $failure",
        )
    }

    @Test
    fun truncatedSimpleList_isRejectedInsteadOfSilentlyReturningEmptyBytes() {
        val malformed = byteArrayOf(
            0x0D, // tag0 SIMPLE_LIST
            0x00, // element type BYTE
            0x00, 0x05, // declared size=5
            0x01, 0x02, // only two payload bytes
        )

        assertFailsWith<TarsDecodeException> {
            TarsInputStream(malformed).readBytes(0, true)
        }
    }

    @Test
    fun truncatedInteger_isReportedAsDecodeError() {
        val malformed = byteArrayOf(0x03) // tag0 INT8，但缺少 8 字节数据

        assertFailsWith<TarsDecodeException> {
            TarsInputStream(malformed).readLong(0, true)
        }
    }

    @Test
    fun invalidStartPosition_isRejected() {
        assertFailsWith<TarsDecodeException> {
            TarsInputStream(byteArrayOf(0), startPos = 2)
        }
    }

    @Test
    fun deeplyNestedContainers_areRejectedBeforeStackOverflow() {
        var nested: Any = 1L
        repeat(70) { nested = listOf(nested) }
        @Suppress("UNCHECKED_CAST")
        val encoded = TarsOutputStream().apply {
            writeList(nested as List<*>, 0)
        }.toByteArray()

        assertFailsWith<TarsDecodeException> {
            TarsInputStream(encoded).readLong(1, false)
        }
    }

    @Test
    fun structEndIsNotMistakenForOptionalTagZero() {
        // tag0 STRUCT_END：这是空结构的结束标记，不是一个整数字段。
        assertEquals(0L, TarsInputStream(byteArrayOf(0x0B)).readLong(0, false))
    }

    @Test
    fun structListHonorsCallerSpecificBatchLimit() {
        val encoded = TarsOutputStream().apply {
            writeList(List(3) { HYMsgItem() }, 0)
        }.toByteArray()

        assertFailsWith<TarsDecodeException> {
            TarsInputStream(encoded).readStructList(0, true, maxSize = 2) { HYMsgItem() }
        }
    }
}
