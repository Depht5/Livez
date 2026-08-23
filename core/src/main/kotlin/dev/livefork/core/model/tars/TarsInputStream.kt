package dev.livefork.core.model.tars

import dev.livefork.core.common.BinaryReader

/**
 * Tars 输入流（解码）。
 * 对应 Dart `TarsInputStream`（packages/tars_dart/lib/tars/codec/tars_input_stream.dart）。
 * 解码语义逐字段保持与 Dart 一致：读头（type/tag）、按 tag 跳读、类型分派解码。
 */
class TarsInputStream(bytes: ByteArray, startPos: Int = 0) {

    private val br = BinaryReader(bytes)

    init {
        if (startPos > 0) br.position = startPos
    }

    /** 跳过指定字节数（不解析）。 */
    fun skipBytes(len: Int) {
        br.position += len
    }

    /** 字段头部。 */
    private class HeadData {
        var type: Int = 0
        var tag: Int = 0
    }

    companion object {
        /** 字符串最大长度（对应 Dart TarsStruct.TARS_MAX_STRING_LENGTH）。 */
        private const val TARS_MAX_STRING_LENGTH = 100 * 1024 * 1024
    }

    /** 可读总长度。 */
    val length: Int
        get() = br.length

    /** 读取一个字段头，返回头消耗的字节数（1 或 2）。 */
    private fun readHead(hd: HeadData): Int {
        if (br.position >= br.length) {
            throw TarsDecodeException("read file to end")
        }
        val b = br.read()
        hd.type = b and 15
        hd.tag = (b and (15 shl 4)) shr 4
        if (hd.tag == 15) {
            hd.tag = br.read()
            return 2
        }
        return 1
    }

    /** 预览下一个字段头（不移动位置），返回头消耗字节数。 */
    private fun peakHead(hd: HeadData): Int {
        val curPos = br.position
        val len = readHead(hd)
        br.position = curPos
        return len
    }

    /**
     * 跳到指定 tag。
     * 返回是否找到 tag 对应的字段（对应 Dart skipToTag）。
     */
    fun skipToTag(tag: Int): Boolean {
        try {
            while (true) {
                val hd = HeadData()
                val len = peakHead(hd)
                if (tag <= hd.tag || hd.type == TarsStructType.STRUCT_END.index) {
                    return tag == hd.tag
                }
                br.position += len
                skipFieldWithType(hd.type)
            }
        } catch (e: Exception) {
            return false
        }
    }

    /** 跳到当前结构的结束标记（STRUCT_END）。 */
    fun skipToStructEnd() {
        val hd = HeadData()
        do {
            readHead(hd)
            skipFieldWithType(hd.type)
        } while (hd.type != TarsStructType.STRUCT_END.index)
    }

    /** 跳过一个字段（含头）。 */
    private fun skipField() {
        val hd = HeadData()
        readHead(hd)
        skipFieldWithType(hd.type)
    }

    /** 按类型跳过字段数据（对应 Dart skipFieldWithType）。 */
    private fun skipFieldWithType(type: Int) {
        when (TarsStructType.fromIndex(type)) {
            TarsStructType.BYTE -> br.position += 1
            TarsStructType.SHORT -> br.position += 2
            TarsStructType.INT -> br.position += 4
            TarsStructType.LONG -> br.position += 8
            TarsStructType.FLOAT -> br.position += 4
            TarsStructType.DOUBLE -> br.position += 8
            TarsStructType.STRING1 -> {
                val len = br.read()
                br.position += len
            }
            TarsStructType.STRING4 -> {
                br.position += br.readInt(4).toInt()
            }
            TarsStructType.MAP -> {
                val size = readLong(0, true).toInt()
                for (i in 0 until size * 2) {
                    skipField()
                }
            }
            TarsStructType.LIST -> {
                val size = readLong(0, true).toInt()
                for (i in 0 until size) {
                    skipField()
                }
            }
            TarsStructType.SIMPLE_LIST -> {
                val hd = HeadData()
                readHead(hd)
                if (hd.type != TarsStructType.BYTE.index) {
                    throw TarsDecodeException("skipField with invalid type, type value: $type,${hd.type}")
                }
                val size = readLong(0, true).toInt()
                br.position += size
            }
            TarsStructType.STRUCT_BEGIN -> skipToStructEnd()
            TarsStructType.STRUCT_END, TarsStructType.ZERO_TAG -> {
                // 无数据
            }
        }
    }

    /**
     * 读整数（对应 Tars int1/int2/int4/int8，与 Dart readInt 语义一致），
     * 返回 64 位 Long。字节序大端。
     */
    fun readLong(tag: Int, isRequire: Boolean): Long {
        var n = 0L
        if (skipToTag(tag)) {
            val hd = HeadData()
            readHead(hd)
            when (hd.type) {
                TarsStructType.ZERO_TAG.index -> n = 0
                TarsStructType.BYTE.index -> n = br.readInt(1)
                TarsStructType.SHORT.index -> n = br.readInt(2)
                TarsStructType.INT.index -> n = br.readInt(4)
                TarsStructType.LONG.index -> n = br.readInt(8)
                else -> throw TarsDecodeException("type mismatch.")
            }
        } else if (isRequire) {
            throw TarsDecodeException("require field not exist.")
        }
        return n
    }

    /** 读 32 位整数。 */
    fun readInt(tag: Int, isRequire: Boolean): Int = readLong(tag, isRequire).toInt()

    /** 读布尔（对应 Tars int1，非 0 即 true）。 */
    fun readBool(tag: Int, isRequire: Boolean): Boolean = readLong(tag, isRequire) != 0L

    /** 读字符串（对应 Tars string1/string4）。 */
    fun readString(tag: Int, isRequire: Boolean): String {
        var n = ""
        if (skipToTag(tag)) {
            val hd = HeadData()
            readHead(hd)
            when (hd.type) {
                TarsStructType.STRING1.index -> n = readString1()
                TarsStructType.STRING4.index -> n = readString4()
                else -> throw TarsDecodeException("type mismatch.")
            }
        } else if (isRequire) {
            throw TarsDecodeException("require field not exist.")
        }
        return n
    }

    private fun readString1(): String {
        val len = br.read()
        val ss = br.readBytes(len)
        return String(ss, Charsets.UTF_8)
    }

    private fun readString4(): String {
        val len = br.readInt(4).toInt()
        if (len > TARS_MAX_STRING_LENGTH || len < 0) {
            throw TarsDecodeException("string too long: $len")
        }
        val ss = br.readBytes(len)
        return String(ss, Charsets.UTF_8)
    }

    /** 读浮点数（对应 Tars float/double）。 */
    fun readFloat(tag: Int, isRequire: Boolean): Double {
        var n = 0.0
        if (skipToTag(tag)) {
            val hd = HeadData()
            readHead(hd)
            when (hd.type) {
                TarsStructType.ZERO_TAG.index -> n = 0.0
                TarsStructType.FLOAT.index -> n = br.readFloat(4)
                TarsStructType.DOUBLE.index -> n = br.readFloat(8)
                else -> throw TarsDecodeException("type mismatch.")
            }
        } else if (isRequire) {
            throw TarsDecodeException("require field not exist.")
        }
        return n
    }

    /** 读字节数组（对应 Tars SimpleList<byte>，兼容 List<int> 形式）。 */
    fun readBytes(tag: Int, isRequire: Boolean): ByteArray {
        var lr = ByteArray(0)
        if (skipToTag(tag)) {
            val hd = HeadData()
            readHead(hd)
            when (hd.type) {
                TarsStructType.SIMPLE_LIST.index -> {
                    val hh = HeadData()
                    readHead(hh)
                    if (hh.type != TarsStructType.BYTE.index) {
                        throw TarsDecodeException("type mismatch, tag: $tag,type:${hd.type},${hh.type}")
                    }
                    val size = readLong(0, true).toInt()
                    if (size < 0) {
                        throw TarsDecodeException("invalid size, tag: $tag, size:$size")
                    }
                    lr = try {
                        br.readBytes(size)
                    } catch (e: Exception) {
                        ByteArray(0)
                    }
                }
                TarsStructType.LIST.index -> {
                    val size = readLong(0, true).toInt()
                    if (size < 0) throw TarsDecodeException("size invalid: $size")
                    lr = ByteArray(size)
                    for (i in 0 until size) {
                        lr[i] = readLong(0, true).toByte()
                    }
                }
                else -> throw TarsDecodeException("type mismatch.")
            }
        } else if (isRequire) {
            throw TarsDecodeException("require field not exist.")
        }
        return lr
    }

    /** 读结构体列表（对应 Tars List<Struct>）。 */
    fun <T : TarsStruct> readStructList(tag: Int, isRequire: Boolean, factory: () -> T): List<T> {
        val ls = mutableListOf<T>()
        if (skipToTag(tag)) {
            val hd = HeadData()
            readHead(hd)
            if (hd.type == TarsStructType.LIST.index) {
                val size = readLong(0, true).toInt()
                if (size < 0) throw TarsDecodeException("size invalid: $size")
                for (i in 0 until size) {
                    @Suppress("UNCHECKED_CAST")
                    ls.add(readTarsStruct(factory(), 0, true) as T)
                }
            } else {
                throw TarsDecodeException("type mismatch.")
            }
        } else if (isRequire) {
            throw TarsDecodeException("require field not exist.")
        }
        return ls
    }

    /** 读 String->String 映射（对应 Tars Map<String,String>，用于 RequestPacket 的 context/status）。 */
    fun readStringMap(tag: Int, isRequire: Boolean): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        if (skipToTag(tag)) {
            val hd = HeadData()
            readHead(hd)
            if (hd.type == TarsStructType.MAP.index) {
                val size = readLong(0, true).toInt()
                if (size < 0) throw TarsDecodeException("size invalid:$size")
                for (i in 0 until size) {
                    val k = readString(0, true)
                    val v = readString(1, true)
                    map[k] = v
                }
            } else {
                throw TarsDecodeException("type mismatch.")
            }
        } else if (isRequire) {
            throw TarsDecodeException("require field not exist.")
        }
        return map
    }

    /** 读 String->bytes 映射（对应 Tars Map<String,SimpleList>，用于 TUP3 newData）。 */
    fun readByteArrayMap(tag: Int, isRequire: Boolean): Map<String, ByteArray> {
        val map = LinkedHashMap<String, ByteArray>()
        if (skipToTag(tag)) {
            val hd = HeadData()
            readHead(hd)
            if (hd.type == TarsStructType.MAP.index) {
                val size = readLong(0, true).toInt()
                if (size < 0) throw TarsDecodeException("size invalid:$size")
                for (i in 0 until size) {
                    val k = readString(0, true)
                    val v = readBytes(1, true)
                    map[k] = v
                }
            } else {
                throw TarsDecodeException("type mismatch.")
            }
        } else if (isRequire) {
            throw TarsDecodeException("require field not exist.")
        }
        return map
    }

    /**
     * 读结构体（对应 Tars Struct）。
     * 直接填充传入的模板实例（调用方每次传入新实例，等价于 Dart 的 deepCopy+readFrom）。
     */
    fun readTarsStruct(ts: TarsStruct, tag: Int, isRequire: Boolean): TarsStruct {
        if (skipToTag(tag)) {
            val hd = HeadData()
            readHead(hd)
            if (hd.type == TarsStructType.STRUCT_BEGIN.index) {
                ts.readFrom(this)
                skipToStructEnd()
                return ts
            } else {
                throw TarsDecodeException("type mismatch.")
            }
        } else if (isRequire) {
            throw TarsDecodeException("require field not exist.")
        }
        return ts
    }
}