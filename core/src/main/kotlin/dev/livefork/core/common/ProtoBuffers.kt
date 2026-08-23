package dev.livefork.core.common

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 手写 protobuf 解析失败异常。
 * 对应 Dart `FormatException` 语义（如 IM 响应为 JSON、varint 越界等），
 * 便于上层按解析失败单独捕获处理。
 */
class ProtoParseException(message: String) : RuntimeException(message)

/**
 * protobuf tag：字段编号 + wire type。
 * wire type：0=varint，1=64-bit（fixed64/double），2=length-delimited（bytes/string/嵌入消息），
 * 3=start group，4=end group，5=32-bit（fixed32/float）。
 */
data class ProtoTag(val fieldNumber: Int, val wireType: Int)

/**
 * 最小手写 protobuf 读取器（对应 protobuf 二进制格式，仅解析不校验）。
 * 与 Dart `protobuf` 包（douyin.pb.dart）的 fromBuffer 语义对齐：
 * uint64/int64 → Long（int64 负值以无符号 varint 解码），uint32/int32 → Int，
 * string → String（UTF-8），bytes → ByteArray（length-delimited），bool → Boolean。
 */
class ProtoReader(
    private val bytes: ByteArray,
    private var pos: Int = 0,
    private val limit: Int = bytes.size,
) {
    /** 是否已读完所有字节 */
    fun isAtEnd(): Boolean = pos >= limit

    /** 剩余可读字节数 */
    val remaining: Int get() = limit - pos

    /** 读取一个 tag（varint），解出字段编号与 wire type */
    fun readTag(): ProtoTag {
        val tag = readVarint()
        return ProtoTag((tag ushr 3).toInt(), (tag and 7).toInt())
    }

    /**
     * 读取无符号 varint（uint64/int64 共用）。int64 负值按无符号低 64 位解码，
     * 与 protobuf 序列化端（C++ 对负数 sign-extend 成 64 位 varint）一致。
     */
    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (pos >= limit) throw ProtoParseException("protobuf varint 读取越界")
            val b = bytes[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift >= 64) throw ProtoParseException("protobuf varint 过长")
        }
    }

    /** 读取 uint32/int32（低 32 位） */
    fun readInt32(): Int = readVarint().toInt()

    /** 读取 zigzag32（sint32） */
    fun readZigZag32(): Int {
        val raw = readVarint().toInt()
        return (raw ushr 1) xor -(raw and 1)
    }

    /** 读取 zigzag64（sint64） */
    fun readZigZag64(): Long {
        val raw = readVarint()
        return (raw ushr 1) xor -(raw and 1L)
    }

    /** 读取 bool（varint 0/1） */
    fun readBoolean(): Boolean = readVarint() != 0L

    /** 读取 string（length-delimited + UTF-8） */
    fun readString(): String = String(readBytes(), Charsets.UTF_8)

    /** 读取 bytes / 嵌入消息载荷（length-delimited），返回子数组副本 */
    fun readBytes(): ByteArray {
        val len = readVarint().toInt()
        if (len < 0 || pos + len > limit) throw ProtoParseException("protobuf length-delimited 读取越界")
        val result = bytes.copyOfRange(pos, pos + len)
        pos += len
        return result
    }

    /** 读取嵌入消息（length-delimited），交由 [parser] 解析 */
    fun <T> readMessage(parser: (ProtoReader) -> T): T {
        val len = readVarint().toInt()
        if (len < 0 || pos + len > limit) throw ProtoParseException("protobuf 嵌入消息读取越界")
        val sub = ProtoReader(bytes, pos, pos + len)
        val result = parser(sub)
        pos += len
        return result
    }

    /** 读取 float（32-bit wire type，小端） */
    fun readFloat(): Float {
        val len = readVarint().toInt()
        if (len != 4 || pos + 4 > limit) {
            pos += len
            throw ProtoParseException("protobuf float 长度非法")
        }
        val value = ByteBuffer.wrap(bytes, pos, 4).order(ByteOrder.LITTLE_ENDIAN).float
        pos += 4
        return value
    }

    /** 读取 64-bit wire type 的原始值（小端） */
    fun readFixed64(): Long {
        if (pos + 8 > limit) throw ProtoParseException("protobuf fixed64 读取越界")
        val value = ByteBuffer.wrap(bytes, pos, 8).order(ByteOrder.LITTLE_ENDIAN).long
        pos += 8
        return value
    }

    /** 读取 32-bit wire type 的原始值（小端） */
    fun readFixed32(): Int {
        if (pos + 4 > limit) throw ProtoParseException("protobuf fixed32 读取越界")
        val value = ByteBuffer.wrap(bytes, pos, 4).order(ByteOrder.LITTLE_ENDIAN).int
        pos += 4
        return value
    }

    /** 按 wire type 跳过未知字段（支持嵌套 group 递归跳过） */
    fun skipField(wireType: Int) {
        when (wireType) {
            0 -> readVarint()
            1 -> if (pos + 8 > limit) throw ProtoParseException("protobuf 8 字节字段越界") else pos += 8
            2 -> {
                val len = readVarint().toInt()
                if (len < 0 || pos + len > limit) throw ProtoParseException("protobuf length-delimited 越界")
                pos += len
            }
            5 -> if (pos + 4 > limit) throw ProtoParseException("protobuf 4 字节字段越界") else pos += 4
            3 -> {
                // start group：递归跳过直到匹配的 end group
                while (true) {
                    val tag = readTag()
                    if (tag.wireType == 4) break
                    skipField(tag.wireType)
                }
            }
            4 -> throw ProtoParseException("意外的 end group")
            else -> throw ProtoParseException("未知 wire type：$wireType")
        }
    }
}

/**
 * 最小手写 protobuf 写入器。
 * 用于构造 PushFrame（心跳 hb / ack / 进房），与 Dart `obj.writeToBuffer()` 对齐。
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    /** 写 tag（fieldNumber shl 3 | wireType） */
    fun writeTag(fieldNumber: Int, wireType: Int) {
        writeVarint(((fieldNumber shl 3) or wireType).toLong())
    }

    /** 写无符号 varint（uint64/int64） */
    fun writeVarint(value: Long) {
        var v = value
        while (true) {
            if (v and -0x80L == 0L) {
                out.write(v.toInt())
                return
            }
            out.write((v and 0x7F).toInt() or 0x80)
            v = v ushr 7
        }
    }

    /** 写 uint64 / int64 字段 */
    fun writeUInt64(fieldNumber: Int, value: Long) {
        writeTag(fieldNumber, 0)
        writeVarint(value)
    }

    /** 写 int32 / uint32 字段 */
    fun writeInt32(fieldNumber: Int, value: Int) {
        writeTag(fieldNumber, 0)
        writeVarint(value.toLong())
    }

    /** 写 bool 字段 */
    fun writeBool(fieldNumber: Int, value: Boolean) {
        writeTag(fieldNumber, 0)
        writeVarint(if (value) 1L else 0L)
    }

    /** 写 string 字段（length-delimited + UTF-8） */
    fun writeString(fieldNumber: Int, value: String) {
        writeBytes(fieldNumber, value.toByteArray(Charsets.UTF_8))
    }

    /** 写 bytes / 嵌入消息字段（length-delimited） */
    fun writeBytes(fieldNumber: Int, value: ByteArray) {
        writeTag(fieldNumber, 2)
        writeVarint(value.size.toLong())
        out.write(value)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}