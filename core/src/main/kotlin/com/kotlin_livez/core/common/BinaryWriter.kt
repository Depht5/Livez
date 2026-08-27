package com.kotlin_livez.core.common

/**
 * 字节写入工具。
 * 对应 Dart `BinaryWriter`（lib/src/common/binary_writer.dart）。
 * 大端序；`writeInt` 按长度写入 1/2/4/8 字节整数（1 字节取低 8 位无符号），
 * `writeDouble` 按长度写入 4 字节 float / 8 字节 double。
 */
class BinaryWriter {

    /** 字节缓冲（对应 Dart 的 List<int> buffer）。 */
    private val buffer = mutableListOf<Int>()

    /** 当前写入位置（对应 Dart position，始终等于 buffer.length）。 */
    var position: Int = 0
        private set

    /** 已写入字节数。 */
    val length: Int
        get() = buffer.size

    /** 追加字节列表。 */
    fun writeBytes(list: List<Int>) {
        for (b in list) buffer.add(b and 0xFF)
        position += list.size
    }

    /** 追加字节数组。 */
    fun writeBytes(list: ByteArray) {
        for (b in list) buffer.add(b.toInt() and 0xFF)
        position += list.size
    }

    /** 追加单字节。 */
    fun writeInt8(value: Int) {
        buffer.add(value and 0xFF)
        position += 1
    }

    /**
     * 写入大端序整数（len 取值 1/2/4/8）。
     * len==1 时取低 8 位（无符号，对应 Dart toUnsigned(8)）。
     */
    fun writeInt(value: Long, len: Int) {
        if (len == 1) {
            buffer.add((value and 0xFF).toInt())
        } else if (len == 2) {
            buffer.add(((value shr 8) and 0xFF).toInt())
            buffer.add((value and 0xFF).toInt())
        } else if (len == 4) {
            buffer.add(((value shr 24) and 0xFF).toInt())
            buffer.add(((value shr 16) and 0xFF).toInt())
            buffer.add(((value shr 8) and 0xFF).toInt())
            buffer.add((value and 0xFF).toInt())
        } else if (len == 8) {
            buffer.add(((value shr 56) and 0xFF).toInt())
            buffer.add(((value shr 48) and 0xFF).toInt())
            buffer.add(((value shr 40) and 0xFF).toInt())
            buffer.add(((value shr 32) and 0xFF).toInt())
            buffer.add(((value shr 24) and 0xFF).toInt())
            buffer.add(((value shr 16) and 0xFF).toInt())
            buffer.add(((value shr 8) and 0xFF).toInt())
            buffer.add((value and 0xFF).toInt())
        } else {
            throw IllegalArgumentException("BinaryWriter.writeInt 不支持的长度: $len")
        }
        position += len
    }

    /**
     * 写入大端序浮点数（len 取值 4=float / 8=double）。
     */
    fun writeDouble(value: Double, len: Int) {
        if (len == 4) {
            writeInt(java.lang.Float.floatToIntBits(value.toFloat()).toLong(), 4)
        } else if (len == 8) {
            writeInt(java.lang.Double.doubleToLongBits(value), 8)
        } else {
            throw IllegalArgumentException("BinaryWriter.writeDouble 不支持的长度: $len")
        }
    }

    /** 导出为字节数组。 */
    fun toByteArray(): ByteArray = buffer.map { it.toByte() }.toByteArray()
}

/**
 * 字节读取工具。
 * 对应 Dart `BinaryReader`（lib/src/common/binary_writer.dart）。
 * 大端序；`readInt(1)` 返回无符号字节（0-255），`readInt(2/4)` 为有符号扩展，
 * `readInt(8)` 为 64 位整数。
 */
class BinaryReader(val buffer: ByteArray) {

    /** 当前读取位置。 */
    var position: Int = 0

    /** 缓冲总长度。 */
    val length: Int
        get() = buffer.size

    /** 读取下一个字节（0-255），位置前进 1。 */
    fun read(): Int {
        val byte = buffer[position].toInt() and 0xFF
        position += 1
        return byte
    }

    /** 读取大端序整数（len 取值 1/2/4/8）。 */
    fun readInt(len: Int): Long {
        val result = if (len == 1) {
            (buffer[position].toInt() and 0xFF).toLong()
        } else if (len == 2) {
            val v = ((buffer[position].toInt() and 0xFF) shl 8) or
                (buffer[position + 1].toInt() and 0xFF)
            v.toShort().toLong()
        } else if (len == 4) {
            val v = ((buffer[position].toInt() and 0xFF) shl 24) or
                ((buffer[position + 1].toInt() and 0xFF) shl 16) or
                ((buffer[position + 2].toInt() and 0xFF) shl 8) or
                (buffer[position + 3].toInt() and 0xFF)
            v.toLong()
        } else if (len == 8) {
            var v = 0L
            for (i in 0 until 8) {
                v = (v shl 8) or (buffer[position + i].toLong() and 0xFF)
            }
            v
        } else {
            throw IllegalArgumentException("BinaryReader.readInt 不支持的长度: $len")
        }
        position += len
        return result
    }

    /** 读取 1 字节（无符号 0-255）。 */
    fun readByte(): Long = readInt(1)

    /** 读取 2 字节有符号整数。 */
    fun readShort(): Long = readInt(2)

    /** 读取 4 字节有符号整数。 */
    fun readInt32(): Long = readInt(4)

    /** 读取 8 字节有符号整数。 */
    fun readLong(): Long = readInt(8)

    /** 读取指定长度的字节，位置前进 len。 */
    fun readBytes(len: Int): ByteArray {
        val bytes = buffer.copyOfRange(position, position + len)
        position += len
        return bytes
    }

    /**
     * 读取大端序浮点数（len 取值 4=float / 8=double）。
     */
    fun readFloat(len: Int): Double {
        val result = if (len == 4) {
            val v = readInt(4).toInt()
            java.lang.Float.intBitsToFloat(v).toDouble()
        } else if (len == 8) {
            val v = readInt(8)
            java.lang.Double.longBitsToDouble(v)
        } else {
            throw IllegalArgumentException("BinaryReader.readFloat 不支持的长度: $len")
        }
        return result
    }
}