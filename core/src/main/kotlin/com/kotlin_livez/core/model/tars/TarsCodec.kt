package com.kotlin_livez.core.model.tars

/**
 * Tars 字段类型枚举。
 * 对应 Dart `TarsStructType`（packages/tars_dart/lib/tars/codec/tars_struct.dart）。
 * 索引值即编码头中低 4 位的 type 值，必须与 Dart 声明顺序一致。
 */
enum class TarsStructType(val index: Int) {
    /** int1 * */
    BYTE(0),
    /** int2 */
    SHORT(1),
    /** int4 */
    INT(2),
    /** int8 */
    LONG(3),
    /** float */
    FLOAT(4),
    /** double */
    DOUBLE(5),
    /** string1：1 字节长度 */
    STRING1(6),
    /** string4：4 字节长度 */
    STRING4(7),
    /** map */
    MAP(8),
    /** list */
    LIST(9),
    /** struct 开始标记 */
    STRUCT_BEGIN(10),
    /** struct 结束标记 */
    STRUCT_END(11),
    /** 零值标记（值为 0 的整数/浮点用，不写数据） */
    ZERO_TAG(12),
    /** 简单列表（字节数组） */
    SIMPLE_LIST(13);

    companion object {
        fun fromIndex(value: Int): TarsStructType = entries.firstOrNull { it.index == value }
            ?: throw TarsDecodeException("未知的 Tars 字段类型: $value")
    }
}

/** Tars 编解码异常。对应 Dart `TarsDecodeException`。 */
class TarsDecodeException(message: String) : RuntimeException(message)

/** Tars 编码异常。对应 Dart `TarsEncodeException`。 */
class TarsEncodeException(message: String) : RuntimeException(message)

/**
 * Tars 结构体基类。
 * 对应 Dart `TarsStruct`（tars/codec/tars_struct.dart）。
 * 子类实现字段的编解码（writeTo/readFrom），字段标签号必须与 Dart 侧一致。
 */
abstract class TarsStruct {
    /** 序列化到输出流。 */
    abstract fun writeTo(os: TarsOutputStream)

    /** 从输入流反序列化。 */
    abstract fun readFrom(ins: TarsInputStream)

    /** 编码为字节数组（对应 Dart toByteArray）。 */
    fun toByteArray(): ByteArray = TarsOutputStream().apply { writeTo(this) }.toByteArray()
}