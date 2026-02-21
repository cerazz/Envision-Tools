package com.example.envisiontools.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

// ---------------------------------------------------------------------------
// Data classes
// ---------------------------------------------------------------------------

data class LandscapePoint(val azimuth: Float, val altitude: Float)

data class LandscapeLine(
    val lineIndex: Int,
    val azMin: Float,
    val azMax: Float,
    val points: List<LandscapePoint>
)

data class PoiEntry(
    val sectorIndex: Int,
    val azimut: Float,
    val altitude: Float,
    val importance: Float,
    val elevation: Float,
    val distance: Float,
    val name: String
)

data class CalibrationData(val raw: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is CalibrationData && raw.contentEquals(other.raw)

    override fun hashCode(): Int = raw.contentHashCode()
}

data class UserConfigData(
    val raw: ByteArray,
    val deviceName: String
) {
    override fun equals(other: Any?): Boolean =
        other is UserConfigData && raw.contentEquals(other.raw)

    override fun hashCode(): Int = raw.contentHashCode()
}

data class WmmFieldData(
    val success: Boolean,
    val north: Float,
    val east: Float,
    val down: Float,
    val magnitude: Float
)

data class FileEntry(
    val size: Long,
    val attr: Int,
    val name: String
)

data class FileInfo(
    val status: Int,
    val size: Long,
    val attr: Int
)

// ---------------------------------------------------------------------------
// Helper to build little-endian payloads
// ---------------------------------------------------------------------------

private fun leBuffer(capacity: Int): ByteBuffer =
    ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN)

// ---------------------------------------------------------------------------
// Suspend command functions
// ---------------------------------------------------------------------------

/** Send flush start (type=0) followed by flush end (type=1). */
suspend fun sendFlush(manager: EnvisionBleManager) {
    val startPayload = byteArrayOf(0x00)
    manager.sendFrame(EnvisionProtocol.buildTlvFrame(EnvisionProtocol.MSG_CMD_FLUSH, startPayload))
}

/** Send flush end (type=1). */
suspend fun sendFlushEnd(manager: EnvisionBleManager) {
    val endPayload = byteArrayOf(0x01)
    manager.sendFrame(EnvisionProtocol.buildTlvFrame(EnvisionProtocol.MSG_CMD_FLUSH, endPayload))
}

/** Sync current Unix time to the device. */
suspend fun sendTime(manager: EnvisionBleManager) {
    val epoch = (System.currentTimeMillis() / 1000L).toInt()
    val payload = leBuffer(4).putInt(epoch).array()
    manager.sendFrame(EnvisionProtocol.buildTlvFrame(EnvisionProtocol.MSG_CMD_TIME, payload))
}

/** Send GPS position (lat/lon as floats). */
suspend fun sendPosition(lat: Float, lon: Float, manager: EnvisionBleManager) {
    val payload = leBuffer(8).putFloat(lat).putFloat(lon).array()
    manager.sendFrame(EnvisionProtocol.buildTlvFrame(EnvisionProtocol.MSG_GPS_POSITION, payload))
}

/** Send target position. */
suspend fun sendTarget(x: Float, y: Float, manager: EnvisionBleManager) {
    val payload = leBuffer(9).put(0).putFloat(x).putFloat(y).array()
    manager.sendFrame(EnvisionProtocol.buildTlvFrame(EnvisionProtocol.MSG_CMD_TARGET, payload))
}

/**
 * Send landscape descriptor and coordinate chunks for all lines.
 * Reports progress as (linesSent, totalLines).
 */
suspend fun sendLandscape(
    lines: List<LandscapeLine>,
    manager: EnvisionBleManager,
    onProgress: (Int, Int) -> Unit
) {
    sendFlush(manager)
    for ((index, line) in lines.withIndex()) {
        val pointCount = line.points.size
        // Descriptor: struct.pack('<hhBffI', 0, lineIndex, 4, azMin, azMax, pointCount)
        val descPayload = leBuffer(17)
            .putShort(0)
            .putShort(line.lineIndex.toShort())
            .put(4)
            .putFloat(line.azMin)
            .putFloat(line.azMax)
            .putInt(pointCount)
            .array()
        manager.sendFrame(EnvisionProtocol.buildTlvFrame(EnvisionProtocol.MSG_CMD_DESC, descPayload))

        // Send coordinate chunks (max 480 bytes of points = 60 points per chunk)
        val maxPointsPerChunk = 60 // 480 / 8
        var chunkIndex = 0
        var offset = 0
        while (offset < pointCount) {
            val end = minOf(offset + maxPointsPerChunk, pointCount)
            val chunkPoints = line.points.subList(offset, end)
            val rawPoints = leBuffer(chunkPoints.size * 8).apply {
                for (pt in chunkPoints) {
                    putFloat(pt.azimuth)
                    putFloat(pt.altitude)
                }
            }.array()
            val coordPayload = leBuffer(4 + rawPoints.size)
                .putShort(line.lineIndex.toShort())
                .putShort(chunkIndex.toShort())
                .put(rawPoints)
                .array()
            manager.sendFrame(EnvisionProtocol.buildTlvFrame(EnvisionProtocol.MSG_CMD_COORD, coordPayload))
            chunkIndex++
            offset = end
        }
        onProgress(index + 1, lines.size)
    }
    sendFlushEnd(manager)
}

/**
 * Send all POI entries.
 * Reports progress as (poisSent, totalPois).
 */
suspend fun sendPoi(
    pois: List<PoiEntry>,
    manager: EnvisionBleManager,
    onProgress: (Int, Int) -> Unit
) {
    sendFlush(manager)
    for ((index, poi) in pois.withIndex()) {
        val nameBytes = poi.name.toByteArray(Charsets.UTF_8)
        // struct.pack('<Bfffff', sectorIndex, azimut, altitude, importance, elevation, distance)
        val header = leBuffer(21)
            .put(poi.sectorIndex.toByte())
            .putFloat(poi.azimut)
            .putFloat(poi.altitude)
            .putFloat(poi.importance)
            .putFloat(poi.elevation)
            .putFloat(poi.distance)
            .array()
        val payload = header + nameBytes + byteArrayOf(0x00)
        manager.sendFrame(EnvisionProtocol.buildTlvFrame(EnvisionProtocol.MSG_CMD_POI, payload))
        onProgress(index + 1, pois.size)
    }
    sendFlushEnd(manager)
}

/** Request device brightness. Returns the uint16 value or null on timeout. */
suspend fun getBrightness(manager: EnvisionBleManager): Int? {
    manager.sendFrame(EnvisionProtocol.buildTlvFrame(EnvisionProtocol.MSG_GET_BRIGHTNESS, ByteArray(0)))
    val payload = manager.receiveResponse(EnvisionProtocol.MSG_GET_BRIGHTNESS_RESP) ?: return null
    if (payload.size < 2) return null
    return (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
}

/** Request calibration data (98 bytes). Returns null on timeout or bad response. */
suspend fun getCalibration(manager: EnvisionBleManager): CalibrationData? {
    manager.sendFrame(EnvisionProtocol.buildTlvFrame(EnvisionProtocol.MSG_GET_CALIBRATION, ByteArray(0)))
    val payload = manager.receiveResponse(EnvisionProtocol.MSG_GET_CALIBRATION_RESP) ?: return null
    if (payload.size < 98) return null
    return CalibrationData(payload)
}

/** Request user configuration (68 bytes, includes device_name[32] at offset 36). */
suspend fun getUserConfig(manager: EnvisionBleManager): UserConfigData? {
    manager.sendFrame(EnvisionProtocol.buildTlvFrame(EnvisionProtocol.MSG_GET_USER_CONFIG, ByteArray(0)))
    val payload = manager.receiveResponse(EnvisionProtocol.MSG_GET_USER_CONFIG_RESP) ?: return null
    if (payload.size < 68) return null
    val nameBytes = payload.copyOfRange(36, 68)
    var nameEnd = 0
    while (nameEnd < nameBytes.size && nameBytes[nameEnd] != 0.toByte()) nameEnd++
    val deviceName = String(nameBytes, 0, nameEnd, Charsets.UTF_8)
    return UserConfigData(payload, deviceName)
}

/** Request WMM field data. Returns null on timeout or failed status. */
suspend fun getWmmField(manager: EnvisionBleManager): WmmFieldData? {
    manager.sendFrame(EnvisionProtocol.buildTlvFrame(EnvisionProtocol.MSG_GET_WMM_FIELD, ByteArray(0)))
    val payload = manager.receiveResponse(EnvisionProtocol.MSG_GET_WMM_FIELD_RESP) ?: return null
    if (payload.size < 17) return null
    val success = payload[0].toInt() != 0
    val buf = ByteBuffer.wrap(payload, 1, 16).order(ByteOrder.LITTLE_ENDIAN)
    return WmmFieldData(
        success = success,
        north = buf.float,
        east = buf.float,
        down = buf.float,
        magnitude = buf.float
    )
}

/** Trigger a partition format. This command is fire-and-forget; the device does not send a response. */
suspend fun formatPartition(manager: EnvisionBleManager): Boolean {
    manager.sendFrame(EnvisionProtocol.buildTlvFrame(EnvisionProtocol.MSG_FORMAT_PARTITION, ByteArray(0)))
    return true
}

/** List files/directories at [path]. Returns null on error. */
suspend fun fileList(path: String, manager: EnvisionBleManager): List<FileEntry>? {
    val payload = path.toByteArray(Charsets.UTF_8) + byteArrayOf(0x00)
    manager.sendFrame(EnvisionProtocol.buildTlvFrame(EnvisionProtocol.MSG_FILE_LIST_REQ, payload))
    val resp = manager.receiveResponse(EnvisionProtocol.MSG_FILE_LIST_RESP) ?: return null
    if (resp.isEmpty()) return null
    val status = resp[0].toInt() and 0xFF
    if (status != 0 || resp.size < 3) return null
    val count = (resp[1].toInt() and 0xFF) or ((resp[2].toInt() and 0xFF) shl 8)
    val entries = mutableListOf<FileEntry>()
    var offset = 3
    repeat(count) {
        if (offset + 6 > resp.size) return entries
        val size = ByteBuffer.wrap(resp, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        val attr = resp[offset + 4].toInt() and 0xFF
        val nameLen = resp[offset + 5].toInt() and 0xFF
        offset += 6
        if (offset + nameLen > resp.size) return entries
        val name = String(resp, offset, nameLen, Charsets.UTF_8)
        offset += nameLen
        entries.add(FileEntry(size, attr, name))
    }
    return entries
}

/** Get file information. Returns null on error. */
suspend fun fileInfo(path: String, manager: EnvisionBleManager): FileInfo? {
    val payload = path.toByteArray(Charsets.UTF_8) + byteArrayOf(0x00)
    manager.sendFrame(EnvisionProtocol.buildTlvFrame(EnvisionProtocol.MSG_FILE_INFO_REQ, payload))
    val resp = manager.receiveResponse(EnvisionProtocol.MSG_FILE_INFO_RESP) ?: return null
    if (resp.size < 1) return null
    val status = resp[0].toInt() and 0xFF
    if (resp.size < 6) return FileInfo(status, 0, 0)
    val size = ByteBuffer.wrap(resp, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
    val attr = resp[5].toInt() and 0xFF
    return FileInfo(status, size, attr)
}

/**
 * Download a file from the device in chunks.
 * Returns the complete file data or null on error.
 */
suspend fun fileDownload(
    remotePath: String,
    manager: EnvisionBleManager,
    onProgress: (Int, Int) -> Unit
): ByteArray? {
    val info = fileInfo(remotePath, manager) ?: return null
    if (info.status != 0) return null
    val totalSize = info.size.toInt()
    val result = ByteArray(totalSize)
    var offset = 0
    val readSize = 480
    while (offset < totalSize) {
        val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
        val reqPayload = leBuffer(6 + pathBytes.size + 1)
            .putInt(offset)
            .putShort(readSize.toShort())
            .put(pathBytes)
            .put(0)
            .array()
        manager.sendFrame(EnvisionProtocol.buildTlvFrame(EnvisionProtocol.MSG_FILE_READ_REQ, reqPayload))
        val resp = manager.receiveResponse(EnvisionProtocol.MSG_FILE_READ_RESP) ?: return null
        if (resp.size < 3) return null
        val status = resp[0].toInt() and 0xFF
        if (status != 0) return null
        val bytesRead = (resp[1].toInt() and 0xFF) or ((resp[2].toInt() and 0xFF) shl 8)
        if (bytesRead <= 0) break
        resp.copyInto(result, offset, 3, 3 + bytesRead)
        offset += bytesRead
        onProgress(offset, totalSize)
    }
    return result
}

/**
 * Upload [data] to [remotePath] on the device.
 * Returns true on success.
 */
suspend fun fileUpload(
    remotePath: String,
    data: ByteArray,
    manager: EnvisionBleManager,
    onProgress: (Int, Int) -> Unit
): Boolean {
    val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
    val chunkSize = 256
    var offset = 0
    while (offset < data.size) {
        val end = minOf(offset + chunkSize, data.size)
        val chunk = data.copyOfRange(offset, end)
        val payload = leBuffer(5 + pathBytes.size + chunk.size)
            .putInt(offset)
            .put(pathBytes.size.toByte())
            .put(pathBytes)
            .put(chunk)
            .array()
        manager.sendFrame(EnvisionProtocol.buildTlvFrame(EnvisionProtocol.MSG_FILE_WRITE_REQ, payload))
        val resp = manager.receiveResponse(EnvisionProtocol.MSG_FILE_WRITE_RESP) ?: return false
        if (resp.isEmpty() || (resp[0].toInt() and 0xFF) != 0) return false
        offset = end
        onProgress(offset, data.size)
    }
    return true
}

/** Delete a file on the device. Returns true on success. */
suspend fun fileDelete(path: String, manager: EnvisionBleManager): Boolean {
    val payload = path.toByteArray(Charsets.UTF_8) + byteArrayOf(0x00)
    manager.sendFrame(EnvisionProtocol.buildTlvFrame(EnvisionProtocol.MSG_FILE_DELETE_REQ, payload))
    val resp = manager.receiveResponse(EnvisionProtocol.MSG_FILE_DELETE_RESP) ?: return false
    if (resp.isEmpty()) return false
    return (resp[0].toInt() and 0xFF) == 0
}

/** Send a generic stage start/stop command with no payload. */
suspend fun sendStageCommand(cmdId: Int, manager: EnvisionBleManager) {
    manager.sendFrame(EnvisionProtocol.buildTlvFrame(cmdId, ByteArray(0)))
}
