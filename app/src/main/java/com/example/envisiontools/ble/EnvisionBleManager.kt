package com.example.envisiontools.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

@SuppressLint("MissingPermission")
class EnvisionBleManager(private val scope: CoroutineScope) {

    private var gatt: BluetoothGatt? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var rxChar: BluetoothGattCharacteristic? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _rxFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val rxFlow: Flow<ByteArray> = _rxFlow.asSharedFlow()

    // Deferred that resolves when services are discovered and notifications are ready
    private var readyDeferred: CompletableDeferred<Unit>? = null

    // Deferred + mutex for serialising BLE writes (Android GATT requires one write at a time)
    private val writeMutex = Mutex()
    private var writeDeferred: CompletableDeferred<Unit>? = null

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.requestMtu(512)
            } else {
                _isConnected.value = false
                txChar = null
                rxChar = null
                readyDeferred?.cancel()
                readyDeferred = null
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                readyDeferred?.cancel()
                return
            }
            for (service in gatt.services) {
                for (char in service.characteristics) {
                    when (char.uuid) {
                        EnvisionProtocol.UUID_TX -> txChar = char
                        EnvisionProtocol.UUID_RX -> rxChar = char
                    }
                }
            }
            enableRxNotifications(gatt)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.characteristic.uuid == EnvisionProtocol.UUID_RX) {
                _isConnected.value = true
                readyDeferred?.complete(Unit)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            writeDeferred?.complete(Unit)
            writeDeferred = null
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == EnvisionProtocol.UUID_RX) {
                val value = characteristic.value ?: return
                scope.launch { _rxFlow.emit(value) }
            }
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == EnvisionProtocol.UUID_RX) {
                scope.launch { _rxFlow.emit(value) }
            }
        }
    }

    /**
     * Connect to the given device. Returns a [CompletableDeferred] that completes when the
     * device is ready (services discovered + notifications enabled) or is cancelled on failure.
     */
    fun connect(device: BluetoothDevice, context: Context): CompletableDeferred<Unit> {
        val deferred = CompletableDeferred<Unit>()
        readyDeferred = deferred
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        return deferred
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        txChar = null
        rxChar = null
        _isConnected.value = false
    }

    /**
     * Write a TLV frame to the TX characteristic and suspend until the GATT write-ack
     * is received. This serialises all writes so the BLE queue never overflows.
     */
    suspend fun sendFrame(frame: ByteArray) {
        val gatt = gatt ?: return
        val char = txChar ?: return
        writeMutex.withLock {
            val deferred = CompletableDeferred<Unit>()
            writeDeferred = deferred
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(char, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                char.value = frame
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }
            try {
                withTimeout(3000) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                writeDeferred = null
            }
        }
    }

    fun startNotifications() {
        val gatt = gatt ?: return
        enableRxNotifications(gatt)
    }

    fun stopNotifications() {
        val gatt = gatt ?: return
        val char = rxChar ?: return
        gatt.setCharacteristicNotification(char, false)
        val descriptor = char.getDescriptor(EnvisionProtocol.UUID_CCCD) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    /**
     * Collect incoming BLE notifications, accumulate into a buffer, and return the payload of
     * the first TLV frame with [expectedCmd]. Returns null on timeout.
     */
    suspend fun receiveResponse(expectedCmd: Int, timeoutMs: Long = 5000): ByteArray? {
        return try {
            withTimeout(timeoutMs) {
                val accumulator = mutableListOf<Byte>()
                var result: ByteArray? = null
                rxFlow.transformWhile { chunk ->
                    accumulator.addAll(chunk.toList())
                    val frame = EnvisionProtocol.parseTlvFrame(accumulator.toByteArray())
                    if (frame != null && frame.cmd == expectedCmd) {
                        result = frame.payload
                        emit(Unit)
                        false
                    } else {
                        true
                    }
                }.collect {}
                result
            }
        } catch (e: TimeoutCancellationException) {
            null
        }
    }

    // --- Private helpers ---

    private fun enableRxNotifications(gatt: BluetoothGatt) {
        val char = rxChar ?: return
        gatt.setCharacteristicNotification(char, true)
        val descriptor = char.getDescriptor(EnvisionProtocol.UUID_CCCD) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }
}
