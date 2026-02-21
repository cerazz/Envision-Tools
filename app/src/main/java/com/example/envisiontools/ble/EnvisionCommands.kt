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

/**
 * Fully parsed calibration response (mirrors Python parse_calibration_response).
 * Payload layout (98 bytes):
 * [0]     uint8  success
 * [1..4]  uint32 calibration_date (unix epoch, LE)
 * [5]     uint8  calib_flags  (bits: gyro=0, accel=1, mag1=2, mag2=3, mag3=4, tempComp=5)
 * [6..17]  float[3] gyro_offset
 * [18..29] float[3] accel_offset
 * [30..41] float[3] accel_scale
 * [42..53] float[3] mag1_hard_iron
 * [54..65] float[3] mag2_hard_iron
 * [66..77] float[3] mag3_hard_iron
 * [78..81] float imu_to_optical_pitch_deg
 * [82..85] float imu_to_optical_yaw_deg
 * [86..87] int16 display_x_offset
 * [88..89] int16 display_y_offset
 * [90..93] float display_zoom
 * [94..97] float display_tilt
 */
data class CalibrationData(
    val success: Boolean,
    val calibrationDate: Long,         // unix timestamp; 0 = not set
    val gyroCalibrated: Boolean,
    val accelCalibrated: Boolean,
    val mag1Calibrated: Boolean,
    val mag2Calibrated: Boolean,
    val mag3Calibrated: Boolean,
    val tempCompEnabled: Boolean,
    val gyroOffset: FloatArray,        // [x, y, z]
    val accelOffset: FloatArray,
    val accelScale: FloatArray,
    val mag1HardIron: FloatArray,
    val mag2HardIron: FloatArray,
    val mag3HardIron: FloatArray,
    val imuToOpticalPitchDeg: Float,
    val imuToOpticalYawDeg: Float,
    val displayXOffset: Short,
    val displayYOffset: Short,
    val displayZoom: Float,
    val displayTilt: Float
) {
    companion object {
        fun parse(payload: ByteArray): CalibrationData? {
            if (payload.size < 98) return null
            val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val success   = buf.get().toInt() and 0xFF
            val calDate   = buf.int.toLong() and 0xFFFFFFFFL
            val flags     = buf.get().toInt() and 0xFF
            val gyroOff   = FloatArray(3) { buf.float }
            val accelOff  = FloatArray(3) { buf.float }
            val accelSca  = FloatArray(3) { buf.float }
            val mag1      = FloatArray(3) { buf.float }
            val mag2      = FloatArray(3) { buf.float }
            val mag3      = FloatArray(3) { buf.float }
            val pitch     = buf.float
            val yaw       = buf.float
            val dxOff     = buf.short
            val dyOff     = buf.short
            val zoom      = buf.float
            val tilt      = buf.float
            return CalibrationData(
                success            = success != 0,
                calibrationDate    = calDate,
                gyroCalibrated     = (flags and 0x01) != 0,
                accelCalibrated    = (flags and 0x02) != 0,
                mag1Calibrated     = (flags and 0x04) != 0,
                mag2Calibrated     = (flags and 0x08) != 0,
                mag3Calibrated     = (flags and 0x10) != 0,
                tempCompEnabled    = (flags and 0x20) != 0,
                gyroOffset         = gyroOff,
                accelOffset        = accelOff,
                accelScale         = accelSca,
                mag1HardIron       = mag1,
                mag2HardIron       = mag2,
                mag3HardIron       = mag3,
                imuToOpticalPitchDeg = pitch,
                imuToOpticalYawDeg   = yaw,
                displayXOffset     = dxOff,
                displayYOffset     = dyOff,
                displayZoom        = zoom,
                displayTilt        = tilt
            )
        }
    }
    override fun equals(other: Any?): Boolean = other is CalibrationData &&
        success == other.success && calibrationDate == other.calibrationDate
    override fun hashCode(): Int = calibrationDate.hashCode()
}

/**
 * Fully parsed user configuration (mirrors Python parse_user_config_response).
 * Payload layout (68 bytes):
 * [0]      uint8  success
 * [1]      uint8  brightness
 * [2]      uint8  auto_brightness
 * [3]      uint8  screen_timeout
 * [4]      uint8  orientation
 * [5..12]  double home_latitude  (LE)
 * [13..20] double home_longitude (LE)
 * [21..24] float  home_altitude
 * [25..26] int16  timezone_offset (minutes from UTC)
 * [27]     uint8  language
 * [28]     uint8  date_format
 * [29]     uint8  coordinate_format
 * [30]     uint8  temperature_unit  (0=°C, 1=°F)
 * [31]     uint8  show_constellation_lines
 * [32]     uint8  show_constellation_names
 * [33]     uint8  show_deep_sky_objects
 * [34]     uint8  show_planets
 * [35]     uint8  magnitude_limit
 * [36..67] char[32] device_name  (null-terminated UTF-8)
 */
data class UserConfigData(
    val success: Boolean,
    val deviceName: String,
    val brightness: Int,
    val autoBrightness: Boolean,
    val screenTimeout: Int,          // seconds
    val orientation: Int,
    val homeLatitude: Double,
    val homeLongitude: Double,
    val homeAltitude: Float,
    val timezoneOffsetMinutes: Short, // offset in minutes from UTC
    val language: Int,
    val dateFormat: Int,
    val coordinateFormat: Int,
    val temperatureUnit: Int,         // 0 = °C, 1 = °F
    val showConstellationLines: Boolean,
    val showConstellationNames: Boolean,
    val showDeepSkyObjects: Boolean,
    val showPlanets: Boolean,
    val magnitudeLimit: Int
) {
    val timezoneHours: Int get() = timezoneOffsetMinutes / 60
    val timezoneMinutes: Int get() = Math.abs(timezoneOffsetMinutes.toInt()) % 60
    val timezoneLabel: String get() =
        "UTC${if (timezoneHours >= 0) "+" else ""}$timezoneHours:${"%02d".format(timezoneMinutes)}"

    companion object {
        fun parse(payload: ByteArray): UserConfigData? {
            if (payload.size < 68) return null
            val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val success    = (buf.get().toInt() and 0xFF) != 0
            val brightness = buf.get().toInt() and 0xFF
            val autoBr     = (buf.get().toInt() and 0xFF) != 0
            val timeout    = buf.get().toInt() and 0xFF
            val orient     = buf.get().toInt() and 0xFF
            val lat        = buf.double
            val lon        = buf.double
            val alt        = buf.float
            val tzOffset   = buf.short
            val lang       = buf.get().toInt() and 0xFF
            val dateFmt    = buf.get().toInt() and 0xFF
            val coordFmt   = buf.get().toInt() and 0xFF
            val tempUnit   = buf.get().toInt() and 0xFF
            val constLines = (buf.get().toInt() and 0xFF) != 0
            val constNames = (buf.get().toInt() and 0xFF) != 0
            val deepSky    = (buf.get().toInt() and 0xFF) != 0
            val planets    = (buf.get().toInt() and 0xFF) != 0
            val magLim     = buf.get().toInt() and 0xFF
            val nameBytes  = ByteArray(32).also { buf.get(it) }
            val nameEnd    = nameBytes.indexOfFirst { it == 0.toByte() }.takeIf { it >= 0 } ?: 32
            val deviceName = String(nameBytes, 0, nameEnd, Charsets.UTF_8)
            return UserConfigData(
                success = success,
                deviceName = deviceName,
                brightness = brightness,
                autoBrightness = autoBr,
                screenTimeout = timeout,
                orientation = orient,
                homeLatitude = lat,
                homeLongitude = lon,
                homeAltitude = alt,
                timezoneOffsetMinutes = tzOffset,
                language = lang,
                dateFormat = dateFmt,
                coordinateFormat = coordFmt,
                temperatureUnit = tempUnit,
                showConstellationLines = constLines,
                showConstellationNames = constNames,
                showDeepSkyObjects = deepSky,
                showPlanets = planets,
                magnitudeLimit = magLim
            )
        }
    }
    override fun equals(other: Any?): Boolean = other is UserConfigData && deviceName == other.deviceName
    override fun hashCode(): Int = deviceName.hashCode()
}

data class WmmFieldData(
    val success: Boolean,
    val north: Float,
    val east: Float,
    val down: Float,
    val magnitude: Float
) {
    val horizontalIntensity: Float get() =
        Math.sqrt((north * north + east * east).toDouble()).toFloat()
    val inclinationDeg: Float get() =
        Math.toDegrees(-Math.atan2(down.toDouble(), horizontalIntensity.toDouble())).toFloat()
    val declinationDeg: Float get() =
        Math.toDegrees(Math.atan2(east.toDouble(), north.toDouble())).toFloat()
}

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
                .putShort(chunkPoints.size.toShort())
                .put(rawPoints)
                .array()
            manager.sendFrame(EnvisionProtocol.buildTlvFrame(EnvisionProtocol.MSG_CMD_COORD, coordPayload))
            chunkIndex++
            offset = end
        }
        onProgress(index + 1, lines.size)
    }
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
}

/** Request device brightness. Returns the uint16 value or null on timeout. */
suspend fun getBrightness(manager: EnvisionBleManager): Int? {
    manager.sendFrame(EnvisionProtocol.buildTlvFrame(EnvisionProtocol.MSG_GET_BRIGHTNESS, ByteArray(0)))
    val payload = manager.receiveResponse(EnvisionProtocol.MSG_GET_BRIGHTNESS_RESP) ?: return null
    if (payload.size < 2) return null
    return (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
}

/** Request calibration data (98 bytes). Returns fully parsed CalibrationData or null on timeout/error. */
suspend fun getCalibration(manager: EnvisionBleManager): CalibrationData? {
    manager.sendFrame(EnvisionProtocol.buildTlvFrame(EnvisionProtocol.MSG_GET_CALIBRATION, ByteArray(0)))
    val payload = manager.receiveResponse(EnvisionProtocol.MSG_GET_CALIBRATION_RESP) ?: return null
    return CalibrationData.parse(payload)
}

/** Request user configuration (68 bytes, includes device_name[32] at offset 36). */
/** Request user configuration (68 bytes). Returns fully parsed UserConfigData or null on timeout/error. */
suspend fun getUserConfig(manager: EnvisionBleManager): UserConfigData? {
    manager.sendFrame(EnvisionProtocol.buildTlvFrame(EnvisionProtocol.MSG_GET_USER_CONFIG, ByteArray(0)))
    val payload = manager.receiveResponse(EnvisionProtocol.MSG_GET_USER_CONFIG_RESP) ?: return null
    return UserConfigData.parse(payload)
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
