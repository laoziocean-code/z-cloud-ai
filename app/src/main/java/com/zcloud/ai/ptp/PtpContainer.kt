package com.zcloud.ai.ptp

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PTP 容器（12 字节头 + 载荷，全部小端序 Little-Endian）。 */
class PtpContainer(
    val length: Int,
    val type: Int,
    val code: Int,
    val transactionId: Long,
    val payload: ByteArray,      // 数据容器 = payload；命令/响应容器 = 参数序列化后的原始字节
) {
    /** 命令 / 响应容器的参数（u32 数组）。 */
    fun params(): IntArray {
        val n = payload.size / 4
        val out = IntArray(n)
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) out[i] = bb.int
        return out
    }

    val isResponse get() = type == Ptp.TYPE_RESPONSE
    val isData get() = type == Ptp.TYPE_DATA
    val isCommand get() = type == Ptp.TYPE_COMMAND
    val isEvent get() = type == Ptp.TYPE_EVENT

    override fun toString(): String =
        "PtpContainer(len=$length type=$type code=0x%04X tx=%d payload=%dB)".format(type, code, transactionId, payload.size)

    companion object {
        const val HEADER_SIZE = 12

        fun command(code: Int, transactionId: Long, params: IntArray): ByteArray {
            val body = ByteArray(params.size * 4)
            val bb = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
            params.forEach { bb.putInt(it) }
            return build(Ptp.TYPE_COMMAND, code, transactionId, body)
        }

        fun data(code: Int, transactionId: Long, payload: ByteArray): ByteArray =
            build(Ptp.TYPE_DATA, code, transactionId, payload)

        fun build(type: Int, code: Int, transactionId: Long, body: ByteArray): ByteArray {
            val buf = ByteBuffer.allocate(HEADER_SIZE + body.size).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(HEADER_SIZE + body.size)     // Length (UInt32)
            buf.putShort(type.toShort())            // Type   (UInt16)
            buf.putShort(code.toShort())            // Code   (UInt16)
            buf.putInt(transactionId.toInt())       // TransactionID (UInt32)
            buf.put(body)
            return buf.array()
        }

        /** 解析 12 字节头。返回 null 表示长度非法。 */
        fun parseHeader(header: ByteArray): PtpContainer? {
            if (header.size < HEADER_SIZE) return null
            val bb = ByteBuffer.wrap(header, 0, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val len = bb.int
            val type = bb.short.toInt() and 0xFFFF
            val code = bb.short.toInt() and 0xFFFF
            val tx = bb.int.toLong() and 0xFFFFFFFFL
            if (len < HEADER_SIZE || len > 32 * 1024 * 1024) return null
            return PtpContainer(len, type, code, tx, ByteArray(0))
        }
    }
}

/** 一次事务的完整结果。 */
data class PtpResult(
    val responseCode: Int,
    val responseParams: IntArray,
    val data: ByteArray?,
    val rawCommandHex: String,
    val rawDataHex: String,
    val rawResponseHex: String,
) {
    val ok get() = responseCode == Ptp.RC_OK

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = responseCode
}
