package com.kotlin_livez.core.model.tars

import com.kotlin_livez.core.common.CoreError
import com.kotlin_livez.core.common.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * TUP3 协议 HTTP 客户端。
 * 对应 Dart `BaseTarsHttp`（packages/tars_dart/lib/tars/net/base_tars_http.dart）。
 *
 * 封包格式与 Dart 完全一致（PACKET_TYPE_TUP3=3）：
 * 请求 = 4 字节大端总长度 + RequestPacket（Tars 结构，tag 1..10）
 *   - tag1 iVersion=3、tag2 cPacketType=0、tag3 iMessageType=0、tag4 iRequestId=0
 *   - tag5 sServantName、tag6 sFuncName
 *   - tag7 sBuffer = Map<String, SimpleList>{"tReq": 结构体 Tars 编码} 的 Tars 编码
 *   - tag8 iTimeout=0、tag9 context={}、tag10 status={}
 * 响应解包同构：跳过 4 字节长度后读 RequestPacket，再读 newData 映射；
 * `""` 为返回值 code（非 0 抛错），`"tRsp"` 为响应结构体 Tars 编码。
 */
class TarsHttpClient(
    /** 服务地址（Dart baseUrl） */
    private val baseUrl: String,
    /** 服务名（Dart servantName，如 liveui / wupui） */
    private val servantName: String,
    /** 请求头（Dart headers） */
    private val headers: Map<String, String>,
) {

    /**
     * Tars 请求封包 RequestPacket。
     * 对应 Dart `RequestPacket`（packages/tars_dart/lib/tars/tup/request_packet.dart），
     * 字段标签号一致：iVersion=1、cPacketType=2、iMessageType=3、iRequestId=4、
     * sServantName=5、sFuncName=6、sBuffer=7、iTimeout=8、context=9、status=10。
     */
    private class TupRequestPacket {
        var iVersion: Long = 0L
        var cPacketType: Long = 0L
        var iMessageType: Long = 0L
        var iRequestId: Long = 0L
        var sServantName: String = ""
        var sFuncName: String = ""
        var sBuffer: ByteArray = ByteArray(0)
        var iTimeout: Long = 0L
        var context: Map<String, String> = emptyMap()
        var status: Map<String, String> = emptyMap()

        fun writeTo(os: TarsOutputStream) {
            os.writeLong(iVersion, 1)
            os.writeLong(cPacketType, 2)
            os.writeLong(iMessageType, 3)
            os.writeLong(iRequestId, 4)
            os.writeString(sServantName, 5)
            os.writeString(sFuncName, 6)
            os.writeBytes(sBuffer, 7)
            os.writeLong(iTimeout, 8)
            os.writeMap(context, 9)
            os.writeMap(status, 10)
        }

        fun readFrom(ins: TarsInputStream) {
            iVersion = ins.readLong(1, false)
            cPacketType = ins.readLong(2, false)
            iMessageType = ins.readLong(3, false)
            iRequestId = ins.readLong(4, false)
            sServantName = ins.readString(5, false)
            sFuncName = ins.readString(6, false)
            sBuffer = ins.readBytes(7, false)
            iTimeout = ins.readLong(8, false)
            context = ins.readStringMap(9, false)
            status = ins.readStringMap(10, false)
        }
    }

    /**
     * 发送 TUP3 请求并解析响应结构体（对应 Dart tupRequest）。
     * 返回码非 0 时抛出异常（对应 Dart TupResultException）。
     */
    suspend fun <RSP : TarsStruct> tupRequest(methodName: String, tReq: TarsStruct, tRsp: RSP): RSP {
        val data = buildRequest(methodName, tReq)
        val bytes = postBytes(data)
        return tupResponseDecode(bytes, tRsp)
    }

    /** 封包（对应 Dart buildRequest）。 */
    private fun buildRequest(methodName: String, tReq: TarsStruct): ByteArray {
        // 1) tReq 以 tag0 Struct 形式编码（对应 UniAttribute.put -> write(t, 0)）
        val reqOs = TarsOutputStream()
        reqOs.writeTarsStruct(tReq, 0)
        val tReqBytes = reqOs.toByteArray()

        // 2) newData = {"tReq": bytes}，以 tag0 Map 形式编码（对应 UniPacket.encode）
        val newDataOs = TarsOutputStream()
        newDataOs.writeMap(mapOf("tReq" to tReqBytes), 0)
        val newDataBytes = newDataOs.toByteArray()

        // 3) RequestPacket 编码
        val packet = TupRequestPacket().apply {
            iVersion = 3L
            cPacketType = 0L
            iMessageType = 0L
            iRequestId = 0L
            sServantName = servantName
            sFuncName = methodName
            sBuffer = newDataBytes
            iTimeout = 0L
            context = emptyMap()
            status = emptyMap()
        }
        val bodyOs = TarsOutputStream()
        packet.writeTo(bodyOs)
        val body = bodyOs.toByteArray()

        // 4) 4 字节大端总长度前缀（长度含自身 4 字节，对应 kUniPacketHeadSize）
        val totalSize = body.size + 4
        val buffer = ByteArray(totalSize)
        buffer[0] = ((totalSize shr 24) and 0xFF).toByte()
        buffer[1] = ((totalSize shr 16) and 0xFF).toByte()
        buffer[2] = ((totalSize shr 8) and 0xFF).toByte()
        buffer[3] = (totalSize and 0xFF).toByte()
        System.arraycopy(body, 0, buffer, 4, body.size)
        return buffer
    }

    /** 解包响应（对应 Dart tupResponseDecode）。 */
    private fun <RSP : TarsStruct> tupResponseDecode(data: ByteArray, tRsp: RSP): RSP {
        val ins = TarsInputStream(data, 4) // 跳过 4 字节大端长度
        val packet = TupRequestPacket()
        packet.readFrom(ins)

        // newData 是 RequestPacket.sBuffer 内的 tag0 map（对应 Dart decode 内层
        // TarsInputStream(package.sBuffer).readMap(..., 0, false)）；不能在外层流上继续读。
        val newDataIns = TarsInputStream(packet.sBuffer)
        val newData = newDataIns.readByteArrayMap(0, false)

        // code = newData[""] 以 int 解析（对应 respPack.get("", 0)）
        val code: Long = try {
            val codeBytes = newData[""]
            if (codeBytes != null) {
                TarsInputStream(codeBytes).readLong(0, true)
            } else {
                0L
            }
        } catch (e: Exception) {
            0L
        }
        if (code != 0L) {
            throw CoreError("虎牙TUP请求失败，返回错误码:$code")
        }

        // tRsp = newData["tRsp"] 结构体解码
        val rspBytes = newData["tRsp"]
        if (rspBytes != null) {
            return TarsInputStream(rspBytes).readTarsStruct(tRsp, 0, true) as RSP
        }
        return tRsp
    }

    /** 发送二进制 POST 请求（对应 Dart dio.post，Content-Type: application/x-wup）。 */
    private suspend fun postBytes(body: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        try {
            val mediaType = "application/x-wup".toMediaType()
            val builder = Request.Builder()
                .url(baseUrl)
                .header("Content-Type", "application/x-wup")
                .header("Content-Length", body.size.toString())
                .post(body.toRequestBody(mediaType))
            headers.forEach { (k, v) -> builder.header(k, v) }
            val req = builder.build()
            HttpClient.client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    throw CoreError("Tars 请求失败: ${res.code} ${res.message}", statusCode = res.code)
                }
                res.body?.bytes() ?: ByteArray(0)
            }
        } catch (e: CoreError) {
            throw e
        } catch (e: Exception) {
            throw CoreError("发送Tars请求失败")
        }
    }
}