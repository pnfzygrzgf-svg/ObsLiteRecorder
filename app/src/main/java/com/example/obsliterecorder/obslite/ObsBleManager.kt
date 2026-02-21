package com.example.obsliterecorder.obslite

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID

/**
 * BLE-Manager fuer OBS Lite Sensor (Nordic UART Service).
 *
 * Scannt nach dem OBS-BLE-Service, verbindet sich, subscribed auf TX-Notifications.
 * Jede BLE-Notification enthaelt ein komplettes rohes Protobuf-Event (kein COBS).
 * Die Bytes werden direkt per Callback weitergegeben.
 */
@SuppressLint("MissingPermission")
class ObsBleManager(
    private val context: Context,
    private val onData: (ByteArray) -> Unit,
    private val onConnectionChanged: (connected: Boolean, deviceName: String?) -> Unit
) {
    companion object {
        private const val TAG = "ObsBleManager"

        private val OBS_SERVICE_UUID =
            UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val OBS_TX_CHAR_UUID =
            UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private val CLIENT_CONFIG_DESCRIPTOR =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var scanning = false

    @Volatile var isConnected = false
        private set
    @Volatile var deviceName: String? = null
        private set
    @Volatile var statusText: String = "BLE: nicht verbunden"
        private set

    // ---- Scan ----

    fun startScan() {
        if (scanning) return

        // Check BLE permissions at runtime (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                statusText = "BLE: Berechtigung fehlt"
                Log.w(TAG, "BLE permissions not granted, skipping scan")
                return
            }
        }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            statusText = "BLE: Bluetooth nicht verfuegbar"
            return
        }

        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            statusText = "BLE: Scanner nicht verfuegbar"
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(OBS_SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanning = true
        statusText = "BLE: suche Sensor..."
        Log.d(TAG, "BLE scan started")

        scanner?.startScan(listOf(filter), settings, scanCallback)
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stopScan error", e)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = device.name ?: "OBS Lite"
            Log.d(TAG, "Found device: $name (${device.address})")
            stopScan()
            connectToDevice(device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed: errorCode=$errorCode")
            scanning = false
            statusText = "BLE: Scan fehlgeschlagen ($errorCode)"
        }
    }

    // ---- Connect ----

    private fun connectToDevice(device: BluetoothDevice) {
        if (!hasBlePermissions()) return
        statusText = "BLE: verbinde..."
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun hasBlePermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    fun disconnect() {
        stopScan()
        try {
            gatt?.let {
                it.disconnect()
                it.close()
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "disconnect: missing permission", e)
        }
        gatt = null
        isConnected = false
        deviceName = null
        statusText = "BLE: nicht verbunden"
        onConnectionChanged(false, null)
    }

    fun close() {
        disconnect()
    }

    // ---- GATT Callback ----

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "GATT connected, requesting MTU...")
                    statusText = "BLE: verbunden, MTU-Aushandlung..."
                    // Request larger MTU first - protobuf events can be >20 bytes
                    g.requestMtu(247)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "GATT disconnected")
                    isConnected = false
                    deviceName = null
                    statusText = "BLE: Verbindung verloren"
                    onConnectionChanged(false, null)
                    try { gatt?.close() } catch (_: SecurityException) {}
                    gatt = null
                    // Auto-reconnect: restart scan
                    startScan()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU changed to $mtu (status=$status)")
            statusText = "BLE: MTU=$mtu, suche Services..."
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                statusText = "BLE: Service-Discovery fehlgeschlagen"
                return
            }

            val service = g.getService(OBS_SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "OBS service not found")
                statusText = "BLE: OBS-Service nicht gefunden"
                return
            }

            val txChar = service.getCharacteristic(OBS_TX_CHAR_UUID)
            if (txChar == null) {
                Log.e(TAG, "TX characteristic not found")
                statusText = "BLE: TX-Characteristic nicht gefunden"
                return
            }

            // Enable notifications
            g.setCharacteristicNotification(txChar, true)
            val descriptor = txChar.getDescriptor(CLIENT_CONFIG_DESCRIPTOR)
            if (descriptor != null) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(descriptor)
                }
            }

            val name = g.device.name ?: "OBS Lite"
            isConnected = true
            deviceName = name
            statusText = "BLE: verbunden"
            Log.d(TAG, "BLE fully connected to $name")
            onConnectionChanged(true, name)
        }

        // Android 13+ (API 33): new callback with value parameter
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == OBS_TX_CHAR_UUID) {
                handleBleData(value)
            }
        }

        // Android 12 and below: deprecated callback
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == OBS_TX_CHAR_UUID) {
                handleBleData(characteristic.value ?: return)
            }
        }
    }

    private fun handleBleData(data: ByteArray) {
        if (data.isEmpty()) return
        // Jede BLE-Notification = ein komplettes rohes Protobuf-Event
        onData(data)
    }
}
