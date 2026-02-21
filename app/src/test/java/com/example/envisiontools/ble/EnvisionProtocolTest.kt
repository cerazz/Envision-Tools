package com.example.envisiontools.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EnvisionProtocolTest {

    @Test
    fun testComputeCrcFlushStart() {
        // cmd=36 (flush), length=1, payload=[0x00]
        // sum = 36 + 0 + 1 + 0 + 0 = 37
        // crc = (-37) & 0xFF = 219
        val crc = EnvisionProtocol.computeCrc(36, 1, byteArrayOf(0x00))
        assertEquals((-37) and 0xFF, crc)
    }

    @Test
    fun testBuildAndParseTlvFrameRoundTrip() {
        val cmd = 35
        val epoch = 1_700_000_000
        val payload = byteArrayOf(
            (epoch and 0xFF).toByte(),
            ((epoch shr 8) and 0xFF).toByte(),
            ((epoch shr 16) and 0xFF).toByte(),
            ((epoch shr 24) and 0xFF).toByte()
        )

        val frame = EnvisionProtocol.buildTlvFrame(cmd, payload)

        // Check sync bytes
        assertEquals(0xAA.toByte(), frame[0])
        assertEquals(0x55.toByte(), frame[1])

        // Check cmd little-endian
        assertEquals((cmd and 0xFF).toByte(), frame[2])
        assertEquals(0.toByte(), frame[3])

        // Check length little-endian
        assertEquals(4.toByte(), frame[4])
        assertEquals(0.toByte(), frame[5])

        // Parse it back
        val parsed = EnvisionProtocol.parseTlvFrame(frame)
        assertNotNull(parsed)
        assertEquals(cmd, parsed!!.cmd)
        assertArrayEquals(payload, parsed.payload)
    }

    @Test
    fun testBuildTlvFrameSize() {
        val payload = ByteArray(10)
        val frame = EnvisionProtocol.buildTlvFrame(1, payload)
        // 2 sync + 2 cmd + 2 len + 10 payload + 1 crc = 17 bytes
        assertEquals(17, frame.size)
    }

    @Test
    fun testBuildTlvFrameNoPayload() {
        val frame = EnvisionProtocol.buildTlvFrame(85, ByteArray(0))
        // 2 sync + 2 cmd + 2 len + 0 payload + 1 crc = 7 bytes
        assertEquals(7, frame.size)
        val parsed = EnvisionProtocol.parseTlvFrame(frame)
        assertNotNull(parsed)
        assertEquals(85, parsed!!.cmd)
        assertEquals(0, parsed.payload.size)
    }

    @Test
    fun testParseTlvFrameInvalidCrc() {
        val frame = EnvisionProtocol.buildTlvFrame(35, ByteArray(4)).toMutableList()
        // Corrupt the CRC byte
        frame[frame.size - 1] = (frame[frame.size - 1].toInt() xor 0xFF).toByte()
        assertNull(EnvisionProtocol.parseTlvFrame(frame.toByteArray()))
    }

    @Test
    fun testParseTlvFrameIncomplete() {
        val frame = EnvisionProtocol.buildTlvFrame(35, ByteArray(4))
        // Provide only first 4 bytes (not enough for a valid frame)
        assertNull(EnvisionProtocol.parseTlvFrame(frame.copyOf(4)))
    }

    @Test
    fun testParseTlvFrameWithLeadingGarbage() {
        val payload = byteArrayOf(1, 2, 3)
        val frame = EnvisionProtocol.buildTlvFrame(33, payload)
        // Prepend some garbage bytes that don't form a sync pattern
        val withGarbage = byteArrayOf(0x01, 0x02, 0x03) + frame
        val parsed = EnvisionProtocol.parseTlvFrame(withGarbage)
        assertNotNull(parsed)
        assertEquals(33, parsed!!.cmd)
        assertArrayEquals(payload, parsed.payload)
    }

    @Test
    fun testMessageIdConstants() {
        assertEquals(32, EnvisionProtocol.MSG_CMD_DESC)
        assertEquals(33, EnvisionProtocol.MSG_CMD_COORD)
        assertEquals(34, EnvisionProtocol.MSG_CMD_POI)
        assertEquals(35, EnvisionProtocol.MSG_CMD_TIME)
        assertEquals(36, EnvisionProtocol.MSG_CMD_FLUSH)
        assertEquals(37, EnvisionProtocol.MSG_CMD_TARGET)
        assertEquals(53, EnvisionProtocol.MSG_GPS_POSITION)
        assertEquals(60, EnvisionProtocol.MSG_START_STAGE_ONE)
        assertEquals(61, EnvisionProtocol.MSG_STOP_STAGE_ONE)
        assertEquals(85, EnvisionProtocol.MSG_GET_BRIGHTNESS)
        assertEquals(86, EnvisionProtocol.MSG_GET_BRIGHTNESS_RESP)
        assertEquals(140, EnvisionProtocol.MSG_FILE_LIST_REQ)
        assertEquals(141, EnvisionProtocol.MSG_FILE_LIST_RESP)
    }

    @Test
    fun testBleUuidsNotNull() {
        assertNotNull(EnvisionProtocol.UUID_TX)
        assertNotNull(EnvisionProtocol.UUID_RX)
        assertNotNull(EnvisionProtocol.UUID_BOOT)
        assertNotNull(EnvisionProtocol.UUID_CCCD)
    }
}
