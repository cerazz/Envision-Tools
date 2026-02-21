package com.example.envisiontools.viewmodel

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.envisiontools.ble.CalibrationData
import com.example.envisiontools.ble.EnvisionBleManager
import com.example.envisiontools.ble.EnvisionProtocol
import com.example.envisiontools.ble.FileEntry
import com.example.envisiontools.ble.UserConfigData
import com.example.envisiontools.ble.WmmFieldData
import com.example.envisiontools.ble.fileDelete
import com.example.envisiontools.ble.fileList
import com.example.envisiontools.ble.formatPartition
import com.example.envisiontools.ble.getBrightness
import com.example.envisiontools.ble.getCalibration
import com.example.envisiontools.ble.getUserConfig
import com.example.envisiontools.ble.getWmmField
import com.example.envisiontools.ble.sendPosition
import com.example.envisiontools.ble.sendStageCommand
import com.example.envisiontools.ble.sendTarget
import com.example.envisiontools.ble.sendTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    val currentFilePath: String = "/"
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
                statusMessage = if (cal != null) "Calibration received (${cal.raw.size}B)" else "Calibration read failed"
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

    private fun setLoading(loading: Boolean) {
        _uiState.value = _uiState.value.copy(isLoading = loading)
    }

    override fun onCleared() {
        super.onCleared()
        bleManager.disconnect()
    }
}
