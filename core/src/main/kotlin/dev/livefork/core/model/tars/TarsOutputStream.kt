package dev.livefork.core.model.tars

import dev.livefork.core.common.BinaryWriter

/**
 * Tars 输出流（编码）。
 * 对应 Dart `TarsOutputStream`（packages/tars_dart/lib/tars/codec/tars_output_stream.dart）。
 * 头部编码：1 字节，低 4 位为 type、高 4 位为 tag；tag>=15 时再跟 1 字节 tag。
 * 整数按值域自动选择 int1/int2/int4/int8 编码（0 写 ZERO_TAG 不跟数据），大端序。
 */
class TarsOutputStream {

    private val bw = BinaryWriter()

    /** 写字段头（type、tag）。 */
    fun writeHead(type: Int, tag: Int) {
        if (tag < 15) {
            bw.writeInt(((tag shl 4) or type).toLong(), 1)
        } else if (tag < 256) {
            bw.writeInt(((15 shl 4) or type).toLong(), 1)
            bw.writeInt(tag.toLong(), 1)
        } else {
            throw TarsEncodeException("tag is too large: $tag")
        }
    }

    /** 写 1 字节整数（对应 Tars int1）。0 时写 ZERO_TAG 头。 */
    fun writeByte(b: Int, tag: Int) {
        if (b == 0) {
            writeHead(TarsStructType.ZERO_TAG.index, tag)
        } else {
            writeHead(TarsStructType.BYTE.index, tag)
            bw.writeInt((b and 0xFF).toLong(), 1)
        }
    }

    /** 写布尔（对应 Tars int1）。 */
    fun writeBool(b: Boolean, tag: Int) = writeByte(if (b) 1 else 0, tag)

    /** 写 Int 整数（对应 Dart writeInt）。 */
    fun writeInt(n: Int, tag: Int) = writeLong(n.toLong(), tag)

    /**
     * 写 64 位整数（对应 Tars int1/int2/int4/int8，按值域自动选择）。
     */
    fun writeLong(n: Long, tag: Int) {
        when {
            n == 0L -> writeHead(TarsStructType.ZERO_TAG.index, tag)
            n in -128L..127L -> {
                writeHead(TarsStructType.BYTE.index, tag)
                bw.writeInt(n and 0xFF, 1)
            }
            n in -32768L..32767L -> {
                writeHead(TarsStructType.SHORT.index, tag)
                bw.writeInt(n, 2)
            }
            n in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() -> {
                writeHead(TarsStructType.INT.index, tag)
                bw.writeInt(n, 4)
            }
            else -> {
                writeHead(TarsStructType.LONG.index, tag)
                bw.writeInt(n, 8)
            }
        }
    }

    /** 写字符串（对应 Tars string1/string4）。 */
    fun writeString(s: String, tag: Int) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) {
            writeHead(TarsStructType.STRING1.index, tag)
            bw.writeInt(0, 1)
            return
        }
        if (bytes.size > 255) {
            writeHead(TarsStructType.STRING4.index, tag)
            bw.writeInt(bytes.size.toLong(), 4)
        } else {
            writeHead(TarsStructType.STRING1.index, tag)
            bw.writeInt(bytes.size.toLong(), 1)
        }
        bw.writeBytes(bytes)
    }

    /** 写字节数组（对应 Tars SimpleList<byte>）。 */
    fun writeBytes(ls: ByteArray, tag: Int) {
        writeHead(TarsStructType.SIMPLE_LIST.index, tag)
        writeHead(TarsStructType.BYTE.index, 0)
        writeLong(ls.size.toLong(), 0)
        bw.writeBytes(ls)
    }

    /** 写列表（对应 Tars List）。 */
    fun writeList(ls: List<*>, tag: Int) {
        writeHead(TarsStructType.LIST.index, tag)
        writeLong(ls.size.toLong(), 0)
        for (item in ls) {
            writeValue(item, 0)
        }
    }

    /** 写映射（对应 Tars Map）。 */
    fun writeMap(map: Map<*, *>, tag: Int) {
        writeHead(TarsStructType.MAP.index, tag)
        writeLong(map.size.toLong(), 0)
        for ((k, v) in map) {
            writeValue(k, 0)
            writeValue(v, 1)
        }
    }

    /** 写结构体（对应 Tars Struct，STRUCT_BEGIN...STRUCT_END 包裹）。 */
    fun writeTarsStruct(o: TarsStruct, tag: Int) {
        writeHead(TarsStructType.STRUCT_BEGIN.index, tag)
        o.writeTo(this)
        writeHead(TarsStructType.STRUCT_END.index, 0)
    }

    /** 按运行时类型分派写入（对应 Dart write(dynamic, tag)）。 */
    private fun writeValue(value: Any?, tag: Int) {
        when (value) {
            null -> throw TarsEncodeException("Tars 值不能为空: tag=$tag")
            is Boolean -> writeBool(value, tag)
            is Byte -> writeByte(value.toInt(), tag)
            is Short -> writeLong(value.toLong(), tag)
            is Int -> writeInt(value, tag)
            is Long -> writeLong(value, tag)
            is Float -> writeDouble(value.toDouble(), tag, float32 = true)
            is Double -> writeDouble(value, tag, float32 = false)
            is String -> writeString(value, tag)
            is ByteArray -> writeBytes(value, tag)
            is List<*> -> writeList(value, tag)
            is Map<*, *> -> writeMap(value, tag)
            is TarsStruct -> writeTarsStruct(value, tag)
            else -> throw TarsEncodeException("不支持的 Tars 类型: ${value::class}")
        }
    }

    /** 写浮点数（对应 Tars float/double）。 */
    fun writeDouble(value: Double, tag: Int, float32: Boolean = false) {
        if (float32) {
            writeHead(TarsStructType.FLOAT.index, tag)
        } else {
            writeHead(TarsStructType.DOUBLE.index, tag)
        }
        if (float32) {
            bw.writeInt(java.lang.Float.floatToIntBits(value.toFloat()).toLong(), 4)
        } else {
            bw.writeInt(java.lang.Double.doubleToLongBits(value), 8)
        }
    }

    /** 输出字节数组（对应 Dart toUint8List）。 */
    fun toByteArray(): ByteArray = bw.toByteArray()
}