package com.cgjnkim.mobile_jai.jai

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A GVCP transaction the camera refused, or one that never came back. */
class GvcpException(message: String, val status: Int = 0) : Exception(message)

/**
 * GigE Vision Control Protocol framing: command packets, acks and status codes.
 *
 * Everything on the wire is big-endian. A command is an 8-byte header -- key 0x42,
 * flags, command, payload length, request id -- followed by the payload; an ack echoes
 * the request id with a status in place of the key.
 */
object Gvcp {

    const val PORT = 3956
    const val KEY = 0x42
    const val HEADER_SIZE = 8

    const val FLAG_ACK_REQUIRED = 0x01

    /** Discovery only: the device may answer by broadcast, which it must when it sits on another subnet. */
    const val FLAG_ALLOW_BROADCAST_ACK = 0x10

    const val DISCOVERY_CMD = 0x0002
    const val DISCOVERY_ACK = 0x0003
    const val FORCEIP_CMD = 0x0004
    const val FORCEIP_ACK = 0x0005
    const val PACKETRESEND_CMD = 0x0040
    const val READREG_CMD = 0x0080
    const val READREG_ACK = 0x0081
    const val WRITEREG_CMD = 0x0082
    const val WRITEREG_ACK = 0x0083
    const val READMEM_CMD = 0x0084
    const val READMEM_ACK = 0x0085
    const val WRITEMEM_CMD = 0x0086
    const val WRITEMEM_ACK = 0x0087
    const val PENDING_ACK = 0x0089

    /** READMEM carries at most 536 bytes and must be a multiple of four. */
    const val READMEM_MAX = 512

    const val STATUS_SUCCESS = 0x0000

    fun command(cmd: Int, requestId: Int, payload: ByteArray, flags: Int = FLAG_ACK_REQUIRED): ByteArray {
        val b = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.BIG_ENDIAN)
        b.put(KEY.toByte())
        b.put(flags.toByte())
        b.putShort(cmd.toShort())
        b.putShort(payload.size.toShort())
        b.putShort(requestId.toShort())
        b.put(payload)
        return b.array()
    }

    data class Ack(val status: Int, val command: Int, val length: Int, val requestId: Int, val payload: ByteArray)

    fun parseAck(data: ByteArray, size: Int): Ack? {
        if (size < HEADER_SIZE) return null
        val b = ByteBuffer.wrap(data, 0, size).order(ByteOrder.BIG_ENDIAN)
        val status = b.short.toInt() and 0xFFFF
        val command = b.short.toInt() and 0xFFFF
        val length = b.short.toInt() and 0xFFFF
        val requestId = b.short.toInt() and 0xFFFF
        val payload = data.copyOfRange(HEADER_SIZE, minOf(size, HEADER_SIZE + length))
        return Ack(status, command, length, requestId, payload)
    }

    fun statusName(status: Int): String = when (status) {
        0x0000 -> "SUCCESS"
        0x8001 -> "NOT_IMPLEMENTED"
        0x8002 -> "INVALID_PARAMETER"
        0x8003 -> "INVALID_ADDRESS"
        0x8004 -> "WRITE_PROTECT"
        0x8005 -> "BAD_ALIGNMENT"
        0x8006 -> "ACCESS_DENIED"
        0x8007 -> "BUSY"
        0x800B -> "PACKET_UNAVAILABLE"
        0x800C -> "DATA_OVERRUN"
        0x800D -> "INVALID_HEADER"
        0x800F -> "PACKET_NOT_YET_AVAILABLE"
        0x8010 -> "PACKET_AND_PREV_REMOVED_FROM_MEMORY"
        0x8011 -> "PACKET_REMOVED_FROM_MEMORY"
        0x8FFF -> "ERROR"
        else -> "0x%04X".format(status)
    }
}

/**
 * The GigE Vision bootstrap registers: the fixed part of the register map every
 * device implements, which is where control privilege, the heartbeat and the stream
 * channels live. Stream channel registers repeat every [SC_STRIDE] bytes.
 */
object Bootstrap {
    const val VERSION = 0x0000L
    const val DEVICE_MODE = 0x0004L
    const val FIRST_URL = 0x0200L
    const val URL_SIZE = 512
    const val MESSAGE_CHANNEL_COUNT = 0x0900L
    const val STREAM_CHANNEL_COUNT = 0x0904L
    const val GVCP_CAPABILITY = 0x0934L
    const val HEARTBEAT_TIMEOUT = 0x0938L
    const val TIMESTAMP_TICK_HIGH = 0x093CL
    const val TIMESTAMP_TICK_LOW = 0x0940L
    const val TIMESTAMP_CONTROL = 0x0944L
    const val TIMESTAMP_VALUE_HIGH = 0x0948L
    const val TIMESTAMP_VALUE_LOW = 0x094CL
    const val CCP = 0x0A00L

    const val SC_STRIDE = 0x40L
    const val SCP = 0x0D00L
    const val SCPS = 0x0D04L
    const val SCPD = 0x0D08L
    const val SCDA = 0x0D18L

    /** CCP values: control access lets others read; exclusive would lock out even discovery tools. */
    const val CCP_CONTROL = 0x2L
    const val CCP_RELEASE = 0x0L

    /** TIMESTAMP_CONTROL bits. Latch copies the free-running counter into VALUE_HIGH/LOW. */
    const val TIMESTAMP_LATCH = 0x2L

    fun scp(channel: Int) = SCP + SC_STRIDE * channel
    fun scps(channel: Int) = SCPS + SC_STRIDE * channel
    fun scpd(channel: Int) = SCPD + SC_STRIDE * channel
    fun scda(channel: Int) = SCDA + SC_STRIDE * channel
}
