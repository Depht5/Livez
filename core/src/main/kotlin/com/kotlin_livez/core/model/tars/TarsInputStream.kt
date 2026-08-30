package com.kotlin_livez.core.model.tars

import com.kotlin_livez.core.common.BinaryReader

/**
 * Tars 输入流（解码）。
 * 对应 Dart `TarsInputStream`（packages/tars_dart/lib/tars/codec/tars_input_stream.dart）。
 * 解码语义逐字段保持与 Dart 一致：读头（type/tag）、按 tag 跳读、类型分派解码。
 */
class TarsInputStream(bytes: ByteArray, startPos: Int = 0) {

    private val br = BinaryReader(bytes)

    init {
        if (startPos < 0 || startPos > bytes.size) {
            throw TarsDecodeException("start position out of bounds: $startPos/${bytes.size}")
        }
        br.position = startPos
    }

    /** 跳过指定字节数（不解析）。 */
    fun skipBytes(len: Int) {
        moveForward(len, "skip bytes")
    }

    /** 字段头部。 */
    private class HeadData {
        var type: Int = 0
        var tag: Int = 0
    }

    companion object {
        /** 字符串最大长度（对应 Dart TarsStruct.TARS_MAX_STRING_LENGTH）。 */
        private const val TARS_MAX_STRING_LENGTH = 100 * 1024 * 1024

        /** 防止畸形容器声明巨量元素后长期占用网络解码线程。 */
        private const val TARS_MAX_COLLECTION_SIZE = 1_000_000

        /** 防止恶意嵌套结构触发栈溢出。 */
        private const val TARS_MAX_NESTING_DEPTH = 64
    }

    /** 可读总长度。 */
    val length: Int
        get() = br.length

    private val remaining: Int
        get() = br.length - br.position

    private fun requireAvailable(len: Int, field: String) {
        if (len < 0 || len > remaining) {
            throw TarsDecodeException(
                "$field length out of bounds: len=$len, remaining=$remaining, position=${br.position}",
            )
        }
    }

    private fun moveForward(len: Int, field: String) {
        requireAvailable(len, field)
        br.position += len
    }

    private fun readCollectionSize(
        field: String,
        maxSize: Int = TARS_MAX_COLLECTION_SIZE,
    ): Int {
        val raw = readLong(0, true)
        if (raw < 0L || raw > maxSize.toLong()) {
            throw TarsDecodeException("$field size invalid: $raw")
        }
        return raw.toInt()
    }

    private fun readIntegerBytes(len: Int, field: String): Long {
        requireAvailable(len, field)
        return br.readInt(len)
    }

    private fun readFloatBytes(len: Int, field: String): Double {
        requireAvailable(len, field)
        return br.readFloat(len)
    }

    /** 读取一个字段头，返回头消耗的字节数（1 或 2）。 */
    private fun readHead(hd: HeadData): Int {
        if (remaining <= 0) {
            throw TarsDecodeException("read file to end")
        }
        val b = br.read()
        hd.type = b and 15
        hd.tag = (b and (15 shl 4)) shr 4
        if (hd.tag == 15) {
            requireAvailable(1, "extended tag")
            hd.tag = br.read()
            return 2
        }
        return 1
    }

    /** 预览下一个字段头（不移动位置），返回头消耗字节数。 */
    private fun peakHead(hd: HeadData): Int {
        val curPos = br.position
        return try {
            readHead(hd)
        } finally {
            br.position = curPos
        }
    }

    /**
     * 跳到指定 tag。
     * 返回是否找到 tag 对应的字段（对应 Dart skipToTag）。
     */
    fun skipToTag(tag: Int): Boolean {
        while (remaining > 0) {
            val start = br.position
            val hd = HeadData()
            val len = peakHead(hd)
            // STRUCT_END 不是 tag0 的普通字段。空嵌套结构中读 optional tag0
            // 必须返回“不存在”，否则紧接着会把 STRUCT_END 当整数解码。
            if (hd.type == TarsStructType.STRUCT_END.index) return false
            if (tag <= hd.tag) return tag == hd.tag
            moveForward(len, "field head")
            skipFieldWithType(hd.type, 0)
            if (br.position <= start) {
                throw TarsDecodeException(
                    "decoder made no progress while seeking tag=$tag at position=$start",
                )
            }
        }
        return false
    }

    /** 跳到当前结构的结束标记（STRUCT_END）。 */
    fun skipToStructEnd() {
        skipToStructEnd(0)
    }

    private fun skipToStructEnd(depth: Int) {
        if (depth > TARS_MAX_NESTING_DEPTH) {
            throw TarsDecodeException("Tars struct nesting is too deep: $depth")
        }
        while (remaining > 0) {
            val start = br.position
            val hd = HeadData()
            readHead(hd)
            if (hd.type == TarsStructType.STRUCT_END.index) return
            skipFieldWithType(hd.type, depth)
            if (br.position <= start) {
                throw TarsDecodeException(
                    "decoder made no progress while seeking struct end at position=$start",
                )
            }
        }
        throw TarsDecodeException("struct end not found")
    }

    /** 跳过一个字段（含头）。 */
    private fun skipField(depth: Int) {
        val hd = HeadData()
        readHead(hd)
        skipFieldWithType(hd.type, depth)
    }

    /** 按类型跳过字段数据（对应 Dart skipFieldWithType）。 */
    private fun skipFieldWithType(type: Int, depth: Int) {
        if (depth > TARS_MAX_NESTING_DEPTH) {
            throw TarsDecodeException("Tars container nesting is too deep: $depth")
        }
        when (TarsStructType.fromIndex(type)) {
            TarsStructType.BYTE -> moveForward(1, "byte")
            TarsStructType.SHORT -> moveForward(2, "short")
            TarsStructType.INT -> moveForward(4, "int")
            TarsStructType.LONG -> moveForward(8, "long")
            TarsStructType.FLOAT -> moveForward(4, "float")
            TarsStructType.DOUBLE -> moveForward(8, "double")
            TarsStructType.STRING1 -> {
                requireAvailable(1, "string1 size")
                val len = br.read()
                moveForward(len, "string1")
            }
            TarsStructType.STRING4 -> {
                requireAvailable(4, "string4 size")
                val len = br.readInt(4)
                if (len < 0L || len > TARS_MAX_STRING_LENGTH.toLong()) {
                    throw TarsDecodeException("string4 size invalid: $len")
                }
                moveForward(len.toInt(), "string4")
            }
            TarsStructType.MAP -> {
                val size = readCollectionSize("map")
                if (size > remaining / 2) {
                    throw TarsDecodeException("map size exceeds remaining payload: $size")
                }
                for (i in 0 until size * 2) {
                    skipField(depth + 1)
                }
            }
            TarsStructType.LIST -> {
                val size = readCollectionSize("list")
                if (size > remaining) {
                    throw TarsDecodeException("list size exceeds remaining payload: $size")
                }
                for (i in 0 until size) {
                    skipField(depth + 1)
                }
            }
            TarsStructType.SIMPLE_LIST -> {
                val hd = HeadData()
                readHead(hd)
                if (hd.type != TarsStructType.BYTE.index) {
                    throw TarsDecodeException("skipField with invalid type, type value: $type,${hd.type}")
                }
                val size = readCollectionSize("simple list")
                moveForward(size, "simple list")
            }
            TarsStructType.STRUCT_BEGIN -> skipToStructEnd(depth + 1)
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
                // Tars int1 是有符号字节；头部仍通过 BinaryReader.read() 按无符号读取。
                TarsStructType.BYTE.index -> n = readIntegerBytes(1, "int1").toByte().toLong()
                TarsStructType.SHORT.index -> n = readIntegerBytes(2, "int2")
                TarsStructType.INT.index -> n = readIntegerBytes(4, "int4")
                TarsStructType.LONG.index -> n = readIntegerBytes(8, "int8")
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
        requireAvailable(1, "string1 size")
        val len = br.read()
        requireAvailable(len, "string1")
        val ss = br.readBytes(len)
        return String(ss, Charsets.UTF_8)
    }

    private fun readString4(): String {
        requireAvailable(4, "string4 size")
        val rawLen = br.readInt(4)
        if (rawLen > TARS_MAX_STRING_LENGTH || rawLen < 0) {
            throw TarsDecodeException("string too long: $rawLen")
        }
        val len = rawLen.toInt()
        requireAvailable(len, "string4")
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
                TarsStructType.FLOAT.index -> n = readFloatBytes(4, "float")
                TarsStructType.DOUBLE.index -> n = readFloatBytes(8, "double")
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
                    val size = readCollectionSize("byte array")
                    requireAvailable(size, "byte array")
                    lr = br.readBytes(size)
                }
                TarsStructType.LIST.index -> {
                    val size = readCollectionSize("byte list")
                    if (size > remaining) {
                        throw TarsDecodeException("byte list size exceeds remaining payload: $size")
                    }
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
    fun <T : TarsStruct> readStructList(
        tag: Int,
        isRequire: Boolean,
        maxSize: Int = TARS_MAX_COLLECTION_SIZE,
        factory: () -> T,
    ): List<T> {
        val ls = mutableListOf<T>()
        if (skipToTag(tag)) {
            val hd = HeadData()
            readHead(hd)
            if (hd.type == TarsStructType.LIST.index) {
                val size = readCollectionSize("struct list", maxSize)
                if (size > remaining) {
                    throw TarsDecodeException("struct list size exceeds remaining payload: $size")
                }
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
                val size = readCollectionSize("string map")
                if (size > remaining / 2) {
                    throw TarsDecodeException("string map size exceeds remaining payload: $size")
                }
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
                val size = readCollectionSize("byte array map")
                if (size > remaining / 2) {
                    throw TarsDecodeException("byte array map size exceeds remaining payload: $size")
                }
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
