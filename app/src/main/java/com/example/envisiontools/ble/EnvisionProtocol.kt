package com.example.envisiontools.ble

import java.util.UUID

data class TlvFrame(val cmd: Int, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TlvFrame) return false
        return cmd == other.cmd && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * cmd + payload.contentHashCode()
}

object EnvisionProtocol {

    // BLE UUIDs
    val UUID_TX: UUID = UUID.fromString("75568951-b1a7-47d5-af5c-4d7ed8188f74")
    val UUID_RX: UUID = UUID.fromString("1afcc197-0425-4b24-807d-0a3516c86e36")
    val UUID_BOOT: UUID = UUID.fromString("def01d15-4baa-465d-99a6-69a4054e9c91")

    // Client Characteristic Configuration Descriptor UUID
    val UUID_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val DEVICE_NAME_PREFIX = "ENVISION"

    // Frame sync bytes
    const val SYNC_0 = 0xAA.toByte()
    const val SYNC_1 = 0x55.toByte()

    // Message IDs
    const val MSG_CMD_DESC = 32
    const val MSG_CMD_COORD = 33
    const val MSG_CMD_POI = 34
    const val MSG_CMD_TIME = 35
    const val MSG_CMD_FLUSH = 36
    const val MSG_CMD_TARGET = 37
    const val MSG_GPS_POSITION = 53

    const val MSG_START_STAGE_ONE = 60
    const val MSG_STOP_STAGE_ONE = 61
    const val MSG_START_STAGE_THREE = 41
    const val MSG_STOP_STAGE_THREE = 42
    const val MSG_START_STAGE_FOUR = 80
    const val MSG_STOP_STAGE_FOUR = 81
    const val MSG_START_STAGE_FIVE = 110
    const val MSG_STOP_STAGE_FIVE = 111
    const val MSG_START_STAGE_SIX = 130
    const val MSG_STOP_STAGE_SIX = 131

    const val MSG_GET_BRIGHTNESS = 85
    const val MSG_GET_BRIGHTNESS_RESP = 86
    const val MSG_GET_CALIBRATION = 119
    const val MSG_GET_CALIBRATION_RESP = 120
    const val MSG_GET_USER_CONFIG = 121
    const val MSG_GET_USER_CONFIG_RESP = 122
    const val MSG_GET_WMM_FIELD = 123
    const val MSG_GET_WMM_FIELD_RESP = 124

    const val MSG_FORMAT_PARTITION = 133
    const val MSG_STAGE_SIX_ACK = 132

    const val MSG_FILE_LIST_REQ = 140
    const val MSG_FILE_LIST_RESP = 141
    const val MSG_FILE_READ_REQ = 142
    const val MSG_FILE_READ_RESP = 143
    const val MSG_FILE_WRITE_REQ = 144
    const val MSG_FILE_WRITE_RESP = 145
    const val MSG_FILE_DELETE_REQ = 146
    const val MSG_FILE_DELETE_RESP = 147
    const val MSG_FILE_INFO_REQ = 148
    const val MSG_FILE_INFO_RESP = 149

    /**
     * CRC8: negative sum of CMD_LOW + CMD_HIGH + LEN_LOW + LEN_HIGH + all payload bytes, & 0xFF
     */
    fun computeCrc(cmd: Int, length: Int, payload: ByteArray): Int {
        var sum = 0
        sum += cmd and 0xFF
        sum += (cmd shr 8) and 0xFF
        sum += length and 0xFF
        sum += (length shr 8) and 0xFF
        for (b in payload) {
            sum += b.toInt() and 0xFF
        }
        return (-sum) and 0xFF
    }

    /**
     * Build a TLV frame: [0xAA][0x55][CMD_LE 2B][LEN_LE 2B][PAYLOAD][CRC8]
     */
    fun buildTlvFrame(cmd: Int, payload: ByteArray): ByteArray {
        val length = payload.size
        val crc = computeCrc(cmd, length, payload)
        // 2 sync + 2 cmd + 2 len + payload + 1 crc
        val result = ByteArray(7 + length)
        result[0] = SYNC_0
        result[1] = SYNC_1
        result[2] = (cmd and 0xFF).toByte()
        result[3] = ((cmd shr 8) and 0xFF).toByte()
        result[4] = (length and 0xFF).toByte()
        result[5] = ((length shr 8) and 0xFF).toByte()
        payload.copyInto(result, 6)
        result[6 + length] = crc.toByte()
        return result
    }

    /**
     * Parse a TLV frame from a buffer. Returns null if the buffer is incomplete or CRC is invalid.
     * Searches for sync bytes [0xAA, 0x55] to find frame start.
     */
    fun parseTlvFrame(buffer: ByteArray): TlvFrame? {
        var start = -1
        for (i in 0 until buffer.size - 1) {
            if (buffer[i] == SYNC_0 && buffer[i + 1] == SYNC_1) {
                start = i
                break
            }
        }
        if (start < 0) return null

        // Need at least 7 bytes (2 sync + 2 cmd + 2 len + 1 crc, 0-byte payload)
        if (buffer.size - start < 7) return null

        val cmd = (buffer[start + 2].toInt() and 0xFF) or
                ((buffer[start + 3].toInt() and 0xFF) shl 8)
        val length = (buffer[start + 4].toInt() and 0xFF) or
                ((buffer[start + 5].toInt() and 0xFF) shl 8)

        // Full frame size: 7 + payload length
        val frameSize = 7 + length
        if (buffer.size - start < frameSize) return null

        val payload = buffer.copyOfRange(start + 6, start + 6 + length)
        val expectedCrc = computeCrc(cmd, length, payload)
        val actualCrc = buffer[start + 6 + length].toInt() and 0xFF

        if (expectedCrc != actualCrc) return null

        return TlvFrame(cmd, payload)
    }
}
