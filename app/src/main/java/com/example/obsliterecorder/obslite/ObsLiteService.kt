// ObsLiteService.kt

package com.example.obsliterecorder.obslite

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.location.Location
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.obsliterecorder.R
import com.example.obsliterecorder.proto.Event
import com.example.obsliterecorder.util.CobsUtils
import com.example.obsliterecorder.util.RecordingStats
import com.example.obsliterecorder.util.SessionStats
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.protobuf.InvalidProtocolBufferException
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.LinkedList
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.math.roundToInt
import android.content.pm.PackageManager

class ObsLiteService : Service(), SerialInputOutputManager.Listener {

    companion object {
        const val TAG = "ObsLiteService_LOG"

        private const val CHANNEL_ID = "obs_lite_recording"
        private const val CHANNEL_NAME = "OBS Lite Aufzeichnung"
        private const val NOTIFICATION_ID = 1001

        private const val ACTION_USB_PERMISSION = "com.example.obsliterecorder.USB_PERMISSION"
    }

    inner class LocalBinder : Binder() {
        fun getService(): ObsLiteService = this@ObsLiteService
    }

    private val binder = LocalBinder()

    private lateinit var obsSession: OBSLiteSession
    private lateinit var fileWriter: OBSLiteFileWriter

    @Volatile private var isRecording: Boolean = false
    @Volatile private var lastLocation: Location? = null

    // --- Live Recording Stats (wie iOS) ---
    @Volatile private var recordingStartTimeMs: Long = 0L
    @Volatile private var currentOvertakeCount: Int = 0
    @Volatile private var currentDistanceMeters: Double = 0.0
    private var prevLocationForDistance: Location? = null
    private var currentRecordingFileName: String? = null

    private lateinit var ioThread: HandlerThread
    private lateinit var ioHandler: Handler

    private var isForeground = false

    // --- USB / OpenBikeSensor Lite ---
    private lateinit var usbManager: UsbManager
    private var usbDevice: UsbDevice? = null
    private var usbIoManager: SerialInputOutputManager? = null
    private lateinit var usbPort: UsbSerialPort

    @Volatile private var obsLiteConnected = false
    private var permissionIntent: PendingIntent? = null

    @Volatile
    private var usbStatusText: String = "USB: nicht verbunden"

    @Volatile
    private var usbDeviceName: String? = null
    @Volatile
    private var usbVendorProduct: String? = null

    // Optional: VID/PID fixieren (sonst erstes Gerät)
    private val TARGET_VENDOR_ID: Int? = null
    private val TARGET_PRODUCT_ID: Int? = null

    // --- BLE / OpenBikeSensor Lite ---
    private var bleManager: ObsBleManager? = null

    @Volatile private var bleConnected = false
    @Volatile private var bleStatusText: String = "BLE: nicht verbunden"
    @Volatile private var bleDeviceName: String? = null

    // --- GPS / Location ---
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    // --- Preview-COBS (im Service) ---
    private val byteListQueue = ConcurrentLinkedDeque<LinkedList<Byte>>()
    private var lastByteReadPreview: Byte? = null
    private lateinit var previewThread: HandlerThread
    private lateinit var previewHandler: Handler
    private val previewTimeWindowMin = OBSLiteSession.TimeWindowMinimum()

    @Volatile private var leftDistanceText: String = "Links: -"
    @Volatile private var rightDistanceText: String = "Rechts: -"
    @Volatile private var overtakeDistanceText: String = "Überholabstand: -"

    // UI-Throttling: max 10 Updates/Sekunde (100ms) wie iOS
    @Volatile private var lastPreviewUpdateMs: Long = 0L
    private val previewThrottleMs = 100L

    // Receiver: Permission + Attached/Detached
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return

            when (action) {
                ACTION_USB_PERMISSION -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    val granted = intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED,
                        false
                    )
                    Log.d(TAG, "USB permission result: device=$device granted=$granted")

                    if (granted && device != null) {
                        usbDevice = device
                        openUsbDevice()
                    } else {
                        updateUsbStatus("USB: Berechtigung verweigert")
                    }
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    if (device != null && isTargetDevice(device)) {
                        Log.d(TAG, "USB attached: $device -> auto connect")
                        usbDevice = device
                        autoConnectUsbIfPossible(device)
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    if (device != null) {
                        val current = usbDevice
                        if (current != null && device.deviceId == current.deviceId) {
                            Log.d(TAG, "USB detached: $device -> disconnect")
                            disconnectUsb()
                            usbDevice = null
                        }
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "onCreate()")

        obsSession = OBSLiteSession(this)
        fileWriter = OBSLiteFileWriter(this)

        ioThread = HandlerThread("obs-io")
        ioThread.start()
        ioHandler = Handler(ioThread.looper)

        // USB setup
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        val explicitIntent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        permissionIntent = PendingIntent.getBroadcast(this, 0, explicitIntent, flag)

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        // BLE setup
        bleManager = ObsBleManager(
            context = this,
            onData = { data -> onBleData(data) },
            onConnectionChanged = { connected, name ->
                bleConnected = connected
                bleDeviceName = name
                bleStatusText = if (connected) "BLE: verbunden" else "BLE: nicht verbunden"
            }
        )
        bleManager?.startScan()

        // GPS
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        ensureLocationUpdates()

        // Preview thread
        previewThread = HandlerThread("preview-io")
        previewThread.start()
        previewHandler = Handler(previewThread.looper)

        createNotificationChannel()

        // NEW: Auto-connect at service start (if device already plugged)
        autoConnectOnServiceStart()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isForeground) startInForeground()
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Hintergrunddienst für OBS Lite Aufzeichnung"
                }
                mgr.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(isRecordingNow: Boolean): Notification {
        val text = if (isRecordingNow) {
            "Aufnahme läuft – Fahrt wird aufgezeichnet"
        } else {
            "Bereit – Aufnahme kann in der App gestartet werden"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OBS Lite Recorder")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun startInForeground() {
        startForeground(NOTIFICATION_ID, buildNotification(isRecording))
        isForeground = true
    }

    private fun updateForegroundNotification() {
        if (!isForeground) return
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIFICATION_ID, buildNotification(isRecording))
    }

    // --- API für Activity ---
    fun isRecordingActive(): Boolean = isRecording
    fun getLastLocation(): Location? = lastLocation
    fun getUsbStatus(): String = usbStatusText
    fun isUsbConnected(): Boolean = obsLiteConnected
    fun getLeftDistanceText(): String = leftDistanceText
    fun getRightDistanceText(): String = rightDistanceText
    fun getOvertakeDistanceText(): String = overtakeDistanceText
    fun getUsbDeviceName(): String? = usbDeviceName
    fun getUsbVendorProduct(): String? = usbVendorProduct
    fun getRecordingStartTimeMs(): Long = recordingStartTimeMs
    fun getCurrentOvertakeCount(): Int = currentOvertakeCount
    fun getCurrentDistanceMeters(): Double = currentDistanceMeters

    fun requestUsbPermissionFromUi() = requestUsbPermission()
    fun disconnectUsbFromUi() = disconnectUsb()

    // --- BLE API ---
    fun isBleConnected(): Boolean = bleConnected
    fun getBleStatus(): String = bleStatusText
    fun getBleDeviceName(): String? = bleDeviceName
    fun isDeviceConnected(): Boolean = obsLiteConnected || bleConnected
    fun getConnectionType(): String = when {
        obsLiteConnected -> "USB"
        bleConnected -> "BLE"
        else -> ""
    }
    fun startBleScan() { bleManager?.startScan() }
    fun disconnectBle() { bleManager?.disconnect() }

    // --- Location ---
    fun ensureLocationUpdates() {
        if (locationCallback != null) return
        if (!hasLocationPermission()) {
            Log.w(TAG, "ensureLocationUpdates(): no location permission yet")
            return
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                lastLocation = loc

                // Distanz akkumulieren waehrend Aufnahme (wie iOS, 3-2000m Filter)
                if (isRecording) {
                    val prev = prevLocationForDistance
                    if (prev != null) {
                        val segment = prev.distanceTo(loc).toDouble()
                        if (segment > 3.0 && segment < 2000.0) {
                            currentDistanceMeters += segment
                        }
                    }
                    prevLocationForDistance = loc
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback!!,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "ensureLocationUpdates(): missing permission?", e)
        }
    }

    private fun stopLocationUpdatesInternal() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    // --- Recording ---
    fun startRecording() {
        if (isRecording) return
        ioHandler.post {
            try {
                obsSession = OBSLiteSession(this)
                val success = fileWriter.startSession()
                if (success) {
                    isRecording = true
                    recordingStartTimeMs = System.currentTimeMillis()
                    currentOvertakeCount = 0
                    currentDistanceMeters = 0.0
                    prevLocationForDistance = null
                    currentRecordingFileName = fileWriter.getCurrentFileName()
                    Log.d(TAG, "startRecording(): recording started successfully")
                } else {
                    isRecording = false
                    Log.e(TAG, "startRecording(): failed to create recording file")
                }
                updateForegroundNotification()
            } catch (e: Exception) {
                Log.e(TAG, "startRecording(): failed", e)
                isRecording = false
                updateForegroundNotification()
            }
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        ioHandler.post {
            try {
                fileWriter.finishSession()

                // Stats speichern (wie iOS OvertakeStatsStore)
                val fileName = currentRecordingFileName
                if (fileName != null && (currentOvertakeCount > 0 || currentDistanceMeters > 0.0)) {
                    val durationSec = (System.currentTimeMillis() - recordingStartTimeMs) / 1000
                    val stats = RecordingStats(
                        overtakeCount = currentOvertakeCount,
                        distanceMeters = currentDistanceMeters,
                        durationSeconds = durationSec
                    )
                    SessionStats(this@ObsLiteService).saveStats(fileName, stats)
                    Log.d(TAG, "stopRecording(): stats saved for $fileName: overtakes=$currentOvertakeCount, dist=${currentDistanceMeters}m, dur=${durationSec}s")
                }
            } catch (e: Exception) {
                Log.e(TAG, "stopRecording(): error", e)
            } finally {
                isRecording = false
                recordingStartTimeMs = 0L
                currentRecordingFileName = null
                updateForegroundNotification()
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopRecording()
        stopLocationUpdatesInternal()
        if (obsLiteConnected) disconnectUsb()
        bleManager?.close()

        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    // --- USB auto connect helpers ---
    private fun isTargetDevice(d: UsbDevice): Boolean {
        return (TARGET_VENDOR_ID == null || d.vendorId == TARGET_VENDOR_ID) &&
                (TARGET_PRODUCT_ID == null || d.productId == TARGET_PRODUCT_ID)
    }

    private fun pickTargetDevice(): UsbDevice? {
        val devices = usbManager.deviceList.values
        if (devices.isEmpty()) return null
        return devices.firstOrNull { isTargetDevice(it) } ?: devices.first()
    }

    private fun autoConnectOnServiceStart() {
        val dev = pickTargetDevice() ?: run {
            updateUsbStatus("USB: kein Gerät")
            return
        }
        usbDevice = dev
        autoConnectUsbIfPossible(dev)
    }

    private fun autoConnectUsbIfPossible(device: UsbDevice) {
        // Wenn Permission schon da → sofort öffnen
        if (usbManager.hasPermission(device)) {
            updateUsbStatus("USB: öffne Gerät…")
            openUsbDevice()
        } else {
            // Permission noch nicht da → anfragen (User-Dialog)
            updateUsbStatus("USB: frage Berechtigung…")
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    private fun requestUsbPermission() {
        val target = pickTargetDevice()
        if (target == null) {
            updateUsbStatus("USB: kein Gerät gefunden")
            return
        }
        usbDevice = target
        updateUsbStatus("USB: frage Berechtigung…")
        usbManager.requestPermission(target, permissionIntent)
    }

    private fun openUsbDevice() {
        if (obsLiteConnected) return

        val dev = usbDevice ?: run {
            updateUsbStatus("USB: kein Gerät")
            return
        }

        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            updateUsbStatus("USB: kein serieller Treiber gefunden")
            return
        }

        val driver = availableDrivers.firstOrNull { it.device.deviceId == dev.deviceId }
            ?: availableDrivers.first()

        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            updateUsbStatus("USB: konnte Gerät nicht öffnen")
            return
        }

        usbPort = driver.ports.first()
        try {
            usbPort.open(connection)
            usbPort.setParameters(
                115200,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )

            usbIoManager = SerialInputOutputManager(usbPort, this).also { it.start() }

            obsLiteConnected = true

            // Geraete-Info aus USB-Descriptor lesen
            usbDeviceName = driver.device.productName ?: driver.device.deviceName
            val vid = String.format("%04X", driver.device.vendorId)
            val pid = String.format("%04X", driver.device.productId)
            usbVendorProduct = "VID:$vid PID:$pid"

            updateUsbStatus("USB: verbunden")
            Log.d(TAG, "USB serial port opened – device=${usbDeviceName}, $usbVendorProduct")
        } catch (e: Exception) {
            Log.e(TAG, "USB open error", e)
            updateUsbStatus("USB: Fehler beim Öffnen")
        }
    }

    private fun disconnectUsb() {
        runCatching { usbIoManager?.stop() }
        usbIoManager = null
        runCatching { if (this::usbPort.isInitialized) usbPort.close() }

        obsLiteConnected = false
        usbDeviceName = null
        usbVendorProduct = null
        updateUsbStatus("USB: nicht verbunden")
    }

    private fun updateUsbStatus(text: String) {
        usbStatusText = text
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "USB status: $text")
    }

    // --- Serial callbacks ---
    override fun onNewData(data: ByteArray?) {
        if (data == null) return
        updateUsbStatus("USB: Daten ${data.size} B")
        onUsbData(data)

        previewHandler.post {
            previewFillByteList(data)
            var loops = 0
            while (previewCompleteCobsAvailable()) {
                handlePreviewEventSafe()
                loops++
                if (loops >= 1000) break
            }
        }
    }

    override fun onRunError(e: Exception?) {
        Log.e(TAG, "Serial IO error", e)
        updateUsbStatus("USB: Fehler im Datenstrom")
    }

    // --- Session write path ---
    fun onUsbData(data: ByteArray) {
        ioHandler.post {
            obsSession.fillByteList(data)
            if (!isRecording) return@post

            var handledCount = 0
            val maxEvents = 10_000
            val startNs = System.nanoTime()
            val maxDurationNs = 50_000_000L

            while (obsSession.completeCobsAvailable()) {
                val loc = lastLocation
                val bytes = if (loc != null) {
                    obsSession.handleEvent(
                        lat = loc.latitude,
                        lon = loc.longitude,
                        altitude = loc.altitude,
                        accuracy = loc.accuracy
                    )
                } else {
                    obsSession.handleEvent(
                        lat = 0.0, lon = 0.0, altitude = 0.0, accuracy = 9999f
                    )
                }

                if (bytes != null && bytes.isNotEmpty()) {
                    try {
                        fileWriter.writeSessionData(bytes)
                    } catch (e: Exception) {
                        Log.e(TAG, "onUsbData(): write failed", e)
                        break
                    }
                }

                handledCount++
                if (handledCount >= maxEvents) break
                if (System.nanoTime() - startNs > maxDurationNs) break
            }
        }
    }

    // --- BLE data path (direkt Protobuf, wie iOS) ---
    private fun onBleData(rawProtobuf: ByteArray) {
        bleStatusText = "BLE: Daten ${rawProtobuf.size} B"

        // (1) Preview: Protobuf direkt parsen (kein COBS-Roundtrip)
        try {
            val event: Event = Event.parseFrom(rawProtobuf)
            handlePreviewEvent(event)
        } catch (e: Exception) {
            Log.e(TAG, "BLE preview: protobuf parse error (${rawProtobuf.size} bytes)", e)
        }

        // (2) Recording: COBS-encode + 0x00 und in Session-Pipeline schreiben
        if (isRecording) {
            val cobsEncoded = CobsUtils.encode2(rawProtobuf)
            val framed = ByteArray(cobsEncoded.size + 1)
            for (i in cobsEncoded.indices) {
                framed[i] = cobsEncoded[i]
            }
            framed[framed.size - 1] = 0x00
            onUsbData(framed)
        }
    }

    /** Verarbeitet ein bereits geparstes Protobuf-Event fuer die Preview-Anzeige. */
    private fun handlePreviewEvent(event: Event) {
        if (event.hasDistanceMeasurement() && event.distanceMeasurement.distance < 5f) {
            val rawCm = (event.distanceMeasurement.distance * 100).roundToInt()
            val sourceId = event.distanceMeasurement.sourceId

            val handlebarWidthCm = getHandlebarWidthCm()
            val corrected = ((rawCm - handlebarWidthCm / 2.0).coerceAtLeast(0.0)).roundToInt()
            if (sourceId == 1) previewTimeWindowMin.newValue(corrected)

            val now = System.currentTimeMillis()
            if (now - lastPreviewUpdateMs >= previewThrottleMs) {
                lastPreviewUpdateMs = now
                val text = "Roh: ${rawCm} cm  |  korrigiert: ${corrected} cm"
                if (sourceId == 1) {
                    leftDistanceText = "Links (ID 1): $text"
                } else {
                    rightDistanceText = "Rechts (ID $sourceId): $text"
                }
            }
        } else if (event.hasUserInput()) {
            val uiType = event.userInput.type
            bleStatusText = "UserInput: $uiType"
            if (isRecording) {
                currentOvertakeCount++
            }
            overtakeDistanceText = if (previewTimeWindowMin.hasValue()) {
                "Überholabstand: ${previewTimeWindowMin.minimum} cm"
            } else {
                "Überholabstand: -"
            }
        }
    }

    // --- Preview path ---
    private fun previewFillByteList(data: ByteArray) {
        for (b in data) {
            if (lastByteReadPreview?.toInt() == 0x00 || byteListQueue.isEmpty()) {
                val newList = LinkedList<Byte>()
                newList.add(b)
                byteListQueue.add(newList)
            } else {
                byteListQueue.last.add(b)
            }
            lastByteReadPreview = b
        }
    }

    private fun previewCompleteCobsAvailable(): Boolean {
        val first = byteListQueue.peekFirst() ?: return false
        for (b in first) if (b.toInt() == 0x00) return true
        return false
    }

    private fun handlePreviewEventSafe() {
        val list = byteListQueue.pollFirst() ?: return
        val hasZero = list.any { it.toInt() == 0x00 }
        if (!hasZero) {
            byteListQueue.addFirst(list)
            return
        }

        val decodedData = try {
            CobsUtils.decode(list)
        } catch (e: Exception) {
            Log.e(TAG, "Preview: COBS decode failed", e)
            return
        }

        try {
            val event: Event = Event.parseFrom(decodedData)

            if (event.hasDistanceMeasurement() && event.distanceMeasurement.distance < 5f) {
                val rawCm = (event.distanceMeasurement.distance * 100).roundToInt()
                val sourceId = event.distanceMeasurement.sourceId

                val handlebarWidthCm = getHandlebarWidthCm()
                val corrected = ((rawCm - handlebarWidthCm / 2.0).coerceAtLeast(0.0)).roundToInt()
                if (sourceId == 1) previewTimeWindowMin.newValue(corrected)

                // UI-Throttling: max 10 Updates/Sekunde (wie iOS)
                val now = System.currentTimeMillis()
                if (now - lastPreviewUpdateMs >= previewThrottleMs) {
                    lastPreviewUpdateMs = now
                    val text = "Roh: ${rawCm} cm  |  korrigiert: ${corrected} cm"
                    if (sourceId == 1) {
                        leftDistanceText = "Links (ID 1): $text"
                    } else {
                        rightDistanceText = "Rechts (ID $sourceId): $text"
                    }
                }
            } else if (event.hasUserInput()) {
                val uiType = event.userInput.type
                usbStatusText = "UserInput: $uiType"
                // Ueberholungs-Counter hochzaehlen (wie iOS)
                if (isRecording) {
                    currentOvertakeCount++
                }
                overtakeDistanceText = if (previewTimeWindowMin.hasValue()) {
                    "Überholabstand: ${previewTimeWindowMin.minimum} cm"
                } else {
                    "Überholabstand: -"
                }
            }
        } catch (e: InvalidProtocolBufferException) {
            Log.e(TAG, "Preview: parse error", e)
            usbStatusText = "USB: Parse-Fehler"
        }
    }

    private fun getHandlebarWidthCm(): Int {
        val prefs = getSharedPreferences("obslite_prefs", MODE_PRIVATE)
        return prefs.getInt("handlebar_width_cm", 60)
    }

    override fun onDestroy() {
        Log.w(TAG, "onDestroy(): service destroyed, isRecording=$isRecording")

        runCatching { unregisterReceiver(usbReceiver) }
        previewThread.quitSafely()

        ioHandler.post {
            try {
                if (isRecording) fileWriter.finishSession()
            } catch (e: Exception) {
                Log.e(TAG, "onDestroy(): error closing file", e)
            } finally {
                isRecording = false
                ioThread.quitSafely()
            }
        }

        stopLocationUpdatesInternal()

        if (obsLiteConnected) disconnectUsb()
        bleManager?.close()
        bleManager = null

        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }

        super.onDestroy()
    }
}
