package com.example.envisiontools.viewmodel

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.envisiontools.ble.CalibrationData
import com.example.envisiontools.ble.EnvisionBleManager
import com.example.envisiontools.ble.EnvisionProtocol
import com.example.envisiontools.ble.FileEntry
import com.example.envisiontools.ble.LandscapeLine
import com.example.envisiontools.ble.LandscapePoint
import com.example.envisiontools.ble.PoiEntry
import com.example.envisiontools.ble.UserConfigData
import com.example.envisiontools.ble.WmmFieldData
import com.example.envisiontools.ble.fileDelete
import com.example.envisiontools.ble.fileList
import com.example.envisiontools.ble.formatPartition
import com.example.envisiontools.ble.getBrightness
import com.example.envisiontools.ble.getCalibration
import com.example.envisiontools.ble.getUserConfig
import com.example.envisiontools.ble.getWmmField
import com.example.envisiontools.ble.sendFlush
import com.example.envisiontools.ble.sendFlushEnd
import com.example.envisiontools.ble.sendLandscape
import com.example.envisiontools.ble.sendPoi
import com.example.envisiontools.ble.sendPosition
import com.example.envisiontools.ble.sendStageCommand
import com.example.envisiontools.ble.sendTarget
import com.example.envisiontools.ble.sendTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

data class EnvisionUiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val isScanning: Boolean = false,
    val statusMessage: String = "",
    val brightness: Int? = null,
    val wmmField: WmmFieldData? = null,
    val userConfig: UserConfigData? = null,
    val calibration: CalibrationData? = null,
    val fileList: List<FileEntry>? = null,
    val isLoading: Boolean = false,
    val currentFilePath: String = "/",
    val landscapeProgress: Pair<Int, Int>? = null,
    val poiProgress: Pair<Int, Int>? = null,
    val fullFlowRunning: Boolean = false,
)

@SuppressLint("MissingPermission")
class EnvisionViewModel : ViewModel() {

    private val bleManager = EnvisionBleManager(viewModelScope)

    private val _uiState = MutableStateFlow(EnvisionUiState())
    val uiState: StateFlow<EnvisionUiState> = _uiState.asStateFlow()

    private val _scanResults = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val scanResults: StateFlow<List<BluetoothDevice>> = _scanResults.asStateFlow()

    private val foundDevices = mutableSetOf<String>()
    private var leScanner: android.bluetooth.le.BluetoothLeScanner? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = result.scanRecord?.deviceName ?: device.name ?: return
            if (!name.startsWith(EnvisionProtocol.DEVICE_NAME_PREFIX)) return
            val address = device.address
            if (foundDevices.add(address)) {
                _scanResults.value = _scanResults.value + device
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _uiState.value = _uiState.value.copy(
                isScanning = false,
                statusMessage = "Scan failed (code $errorCode)"
            )
        }
    }

    fun startScan(context: Context) {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter ?: return
        if (!adapter.isEnabled) {
            _uiState.value = _uiState.value.copy(statusMessage = "Bluetooth is disabled")
            return
        }
        foundDevices.clear()
        _scanResults.value = emptyList()
        leScanner = adapter.bluetoothLeScanner
        leScanner?.startScan(scanCallback)
        _uiState.value = _uiState.value.copy(isScanning = true, statusMessage = "Scanning…")
    }

    fun stopScan() {
        leScanner?.stopScan(scanCallback)
        leScanner = null
        _uiState.value = _uiState.value.copy(isScanning = false, statusMessage = "Scan stopped")
    }

    fun connect(device: BluetoothDevice, context: Context) {
        stopScan()
        _uiState.value = _uiState.value.copy(
            connectionState = ConnectionState.CONNECTING,
            statusMessage = "Connecting…"
        )
        val deferred = bleManager.connect(device, context)
        viewModelScope.launch {
            try {
                deferred.await()
                _uiState.value = _uiState.value.copy(
                    connectionState = ConnectionState.CONNECTED,
                    statusMessage = "Connected"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    connectionState = ConnectionState.DISCONNECTED,
                    statusMessage = "Connection failed"
                )
            }
        }
    }

    fun disconnect() {
        bleManager.disconnect()
        _uiState.value = _uiState.value.copy(
            connectionState = ConnectionState.DISCONNECTED,
            statusMessage = "Disconnected"
        )
    }

    fun syncTime() {
        viewModelScope.launch {
            setLoading(true)
            sendTime(bleManager)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                statusMessage = "Time synced"
            )
        }
    }

    fun sendGpsPosition(lat: Float, lon: Float) {
        viewModelScope.launch {
            setLoading(true)
            sendPosition(lat, lon, bleManager)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                statusMessage = "Position sent"
            )
        }
    }

    fun sendTargetPosition(x: Float, y: Float) {
        viewModelScope.launch {
            setLoading(true)
            sendTarget(x, y, bleManager)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                statusMessage = "Target sent"
            )
        }
    }

    fun fetchBrightness() {
        viewModelScope.launch {
            setLoading(true)
            val brightness = getBrightness(bleManager)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                brightness = brightness,
                statusMessage = if (brightness != null) "Brightness: $brightness" else "Brightness read failed"
            )
        }
    }

    fun fetchCalibration() {
        viewModelScope.launch {
            setLoading(true)
            val cal = getCalibration(bleManager)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                calibration = cal,
                statusMessage = if (cal != null) "Calibration received" else "Calibration read failed"
            )
        }
    }

    fun fetchUserConfig() {
        viewModelScope.launch {
            setLoading(true)
            val config = getUserConfig(bleManager)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                userConfig = config,
                statusMessage = if (config != null) "Config: ${config.deviceName}" else "Config read failed"
            )
        }
    }

    fun fetchWmmField() {
        viewModelScope.launch {
            setLoading(true)
            val wmm = getWmmField(bleManager)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                wmmField = wmm,
                statusMessage = if (wmm != null) "WMM N=${wmm.north} E=${wmm.east}" else "WMM read failed"
            )
        }
    }

    fun doFormatPartition() {
        viewModelScope.launch {
            setLoading(true)
            formatPartition(bleManager)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                statusMessage = "Format partition sent"
            )
        }
    }

    fun stageCommand(cmdId: Int, label: String) {
        viewModelScope.launch {
            setLoading(true)
            sendStageCommand(cmdId, bleManager)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                statusMessage = "$label sent"
            )
        }
    }

    fun listFiles(path: String = "/") {
        viewModelScope.launch {
            setLoading(true)
            val entries = fileList(path, bleManager)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                fileList = entries,
                currentFilePath = path,
                statusMessage = if (entries != null) "${entries.size} entries in $path" else "File list failed"
            )
        }
    }

    fun deleteFile(path: String) {
        viewModelScope.launch {
            setLoading(true)
            val ok = fileDelete(path, bleManager)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                statusMessage = if (ok) "Deleted $path" else "Delete failed"
            )
            if (ok) listFiles(_uiState.value.currentFilePath)
        }
    }

    /** Send flush start (0x00) + flush end (0x01) to erase device data. */
    fun sendFlushCommand() {
        viewModelScope.launch {
            setLoading(true)
            sendFlush(bleManager)
            sendFlushEnd(bleManager)
            _uiState.value = _uiState.value.copy(isLoading = false, statusMessage = "Flush sent")
        }
    }

    /** Parse landscape JSON from a Uri and send all lines to the device. */
    fun loadAndSendLandscape(context: Context, uri: Uri) {
        viewModelScope.launch {
            setLoading(true)
            try {
                val text = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.readText()
                val lines = text?.let { parseLandscapeText(it) }
                if (lines == null) {
                    _uiState.value = _uiState.value.copy(isLoading = false, statusMessage = "Invalid landscape file")
                    return@launch
                }
                sendLandscape(lines, bleManager) { sent, total ->
                    _uiState.value = _uiState.value.copy(
                        landscapeProgress = Pair(sent, total),
                        statusMessage = "Landscape: $sent/$total lines"
                    )
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    landscapeProgress = null,
                    statusMessage = "Landscape sent (${lines.size} lines)"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    landscapeProgress = null,
                    statusMessage = "Landscape error: ${e.message}"
                )
            }
        }
    }

    /** Load landscape from a bundled asset file and send it to the device. */
    fun loadAndSendLandscapeFromAsset(context: Context, assetFileName: String) {
        viewModelScope.launch {
            setLoading(true)
            try {
                val text = context.assets.open(assetFileName).bufferedReader().readText()
                val lines = parseLandscapeText(text)
                if (lines == null) {
                    _uiState.value = _uiState.value.copy(isLoading = false, statusMessage = "Invalid asset: $assetFileName")
                    return@launch
                }
                sendLandscape(lines, bleManager) { sent, total ->
                    _uiState.value = _uiState.value.copy(
                        landscapeProgress = Pair(sent, total),
                        statusMessage = "Landscape: $sent/$total lines"
                    )
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    landscapeProgress = null,
                    statusMessage = "Landscape sent (${lines.size} lines) from $assetFileName"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    landscapeProgress = null,
                    statusMessage = "Asset error: ${e.message}"
                )
            }
        }
    }

    /** Parse POI JSON from a Uri and send all entries to the device. */
    fun loadAndSendPoi(context: Context, uri: Uri) {
        viewModelScope.launch {
            setLoading(true)
            try {
                val text = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.readText()
                val pois = text?.let { parsePoiText(it) }
                if (pois == null) {
                    _uiState.value = _uiState.value.copy(isLoading = false, statusMessage = "Invalid POI file")
                    return@launch
                }
                sendPoi(pois, bleManager) { sent, total ->
                    _uiState.value = _uiState.value.copy(
                        poiProgress = Pair(sent, total),
                        statusMessage = "POI: $sent/$total entries"
                    )
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    poiProgress = null,
                    statusMessage = "POI sent (${pois.size} entries)"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    poiProgress = null,
                    statusMessage = "POI error: ${e.message}"
                )
            }
        }
    }

    /** Load POI from a bundled asset file and send it to the device. */
    fun loadAndSendPoiFromAsset(context: Context, assetFileName: String) {
        viewModelScope.launch {
            setLoading(true)
            try {
                val text = context.assets.open(assetFileName).bufferedReader().readText()
                val pois = parsePoiText(text)
                if (pois == null) {
                    _uiState.value = _uiState.value.copy(isLoading = false, statusMessage = "Invalid asset: $assetFileName")
                    return@launch
                }
                sendPoi(pois, bleManager) { sent, total ->
                    _uiState.value = _uiState.value.copy(
                        poiProgress = Pair(sent, total),
                        statusMessage = "POI: $sent/$total entries"
                    )
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    poiProgress = null,
                    statusMessage = "POI sent (${pois.size} entries) from $assetFileName"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    poiProgress = null,
                    statusMessage = "POI error: ${e.message}"
                )
            }
        }
    }

    /**
     * Run the standard initialisation flow:
     *   flush → sync time → send GPS position → send landscape → send POI
     * Landscape and POI steps are skipped when the respective Uri is null.
     */
    fun runFullFlow(lat: Float, lon: Float, landscapeUri: Uri?, poiUri: Uri?, context: Context) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(fullFlowRunning = true, statusMessage = "Full flow: Flushing…")
            sendFlush(bleManager)
            sendFlushEnd(bleManager)

            _uiState.value = _uiState.value.copy(statusMessage = "Full flow: Syncing time…")
            sendTime(bleManager)

            _uiState.value = _uiState.value.copy(statusMessage = "Full flow: Sending position…")
            sendPosition(lat, lon, bleManager)

            if (landscapeUri != null) {
                _uiState.value = _uiState.value.copy(statusMessage = "Full flow: Sending landscape…")
                val lsText = context.contentResolver.openInputStream(landscapeUri)?.bufferedReader()?.readText()
                val lines = lsText?.let { parseLandscapeText(it) }
                if (lines != null) {
                    sendLandscape(lines, bleManager) { sent, total ->
                        _uiState.value = _uiState.value.copy(
                            landscapeProgress = Pair(sent, total),
                            statusMessage = "Full flow: Landscape $sent/$total"
                        )
                    }
                    _uiState.value = _uiState.value.copy(landscapeProgress = null)
                }
            }

            if (poiUri != null) {
                _uiState.value = _uiState.value.copy(statusMessage = "Full flow: Sending POI…")
                val poiText = context.contentResolver.openInputStream(poiUri)?.bufferedReader()?.readText()
                val pois = poiText?.let { parsePoiText(it) }
                if (pois != null) {
                    sendPoi(pois, bleManager) { sent, total ->
                        _uiState.value = _uiState.value.copy(
                            poiProgress = Pair(sent, total),
                            statusMessage = "Full flow: POI $sent/$total"
                        )
                    }
                    _uiState.value = _uiState.value.copy(poiProgress = null)
                }
            }

            _uiState.value = _uiState.value.copy(
                fullFlowRunning = false,
                isLoading = false,
                statusMessage = "Full flow complete!"
            )
        }
    }

    // ---------------------------------------------------------------------------
    // JSON parsers
    // ---------------------------------------------------------------------------

    /**
     * Parse landscape JSON text.
     *
     * Supported formats:
     * - **V2**: JSON array of `{ lineIndex, azMin, azMax, points: [{azimuth, altitude}] }`
     * - **Legacy (v2 function)**: JSON array of `{ points: [{azimuth, altitude}] }`
     * - **Original legacy**: `{ silhouettelines: [{ azialtdist: [[az, alt], ...] }] }`
     */
    private fun parseLandscapeText(text: String): List<LandscapeLine>? {
        return try {
            val lines = mutableListOf<LandscapeLine>()

            // Try array-based formats first (V2 / legacy-v2)
            if (text.trimStart().startsWith("[")) {
                val jsonArray = JSONArray(text)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val pointsJson = obj.getJSONArray("points")
                    val points = (0 until pointsJson.length()).map { j ->
                        val pt = pointsJson.getJSONObject(j)
                        LandscapePoint(
                            azimuth = pt.getDouble("azimuth").toFloat(),
                            altitude = pt.getDouble("altitude").toFloat()
                        )
                    }
                    val lineIndex = if (obj.has("lineIndex")) obj.getInt("lineIndex") else i
                    val azimuths = points.map { it.azimuth }
                    val azMin = if (obj.has("azMin")) obj.getDouble("azMin").toFloat()
                               else azimuths.minOrNull() ?: 0f
                    val azMax = if (obj.has("azMax")) obj.getDouble("azMax").toFloat()
                               else azimuths.maxOrNull() ?: 0f
                    lines.add(LandscapeLine(lineIndex, azMin, azMax, points))
                }
            } else {
                // Original legacy format: {"silhouettelines": [{"azialtdist": [[az, alt], ...]}]}
                val root = org.json.JSONObject(text)
                val silhouetteLines = root.getJSONArray("silhouettelines")
                for (i in 0 until silhouetteLines.length()) {
                    val lineObj = silhouetteLines.getJSONObject(i)
                    val azialtdist = lineObj.getJSONArray("azialtdist")
                    val points = (0 until azialtdist.length()).map { j ->
                        val coord = azialtdist.getJSONArray(j)
                        LandscapePoint(
                            azimuth = coord.getDouble(0).toFloat(),
                            altitude = coord.getDouble(1).toFloat()
                        )
                    }
                    val azimuths = points.map { it.azimuth }
                    lines.add(
                        LandscapeLine(
                            lineIndex = i,
                            azMin = azimuths.minOrNull() ?: 0f,
                            azMax = azimuths.maxOrNull() ?: 0f,
                            points = points
                        )
                    )
                }
            }
            lines
        } catch (e: JSONException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse POI JSON text.
     * Expected format: JSON array of
     * `{ sectorIndex, azimut, altitude, importance, elevation, distance, name }`
     */
    private fun parsePoiText(text: String): List<PoiEntry>? {
        return try {
            val jsonArray = JSONArray(text)
            (0 until jsonArray.length()).map { i ->
                val obj = jsonArray.getJSONObject(i)
                PoiEntry(
                    sectorIndex = obj.getInt("sectorIndex"),
                    azimut = obj.getDouble("azimut").toFloat(),
                    altitude = obj.getDouble("altitude").toFloat(),
                    importance = obj.getDouble("importance").toFloat(),
                    elevation = obj.getDouble("elevation").toFloat(),
                    distance = obj.getDouble("distance").toFloat(),
                    name = obj.getString("name")
                )
            }
        } catch (e: JSONException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun setLoading(loading: Boolean) {
        _uiState.value = _uiState.value.copy(isLoading = loading)
    }

    override fun onCleared() {
        super.onCleared()
        bleManager.disconnect()
    }
}
