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

    @Volatile
    private var isRecording: Boolean = false

    @Volatile
    private var lastLocation: Location? = null

    // Single-threaded I/O: garantiert Reihenfolge, erspart synchronized
    private lateinit var ioThread: HandlerThread
    private lateinit var ioHandler: Handler

    private var isForeground = false

    // --- USB / OpenBikeSensor Lite ---
    private lateinit var usbManager: UsbManager
    private var usbDevice: UsbDevice? = null
    private var usbIoManager: SerialInputOutputManager? = null
    private lateinit var usbPort: UsbSerialPort

    @Volatile
    private var obsLiteConnected = false

    private var permissionIntent: PendingIntent? = null

    // Status-Text für die UI (MainActivity holt sich das zyklisch)
    @Volatile
    private var usbStatusText: String = "USB: nicht verbunden"

    // Optional: spezifische VID/PID deines Geräts (sonst Fallback auf erstes)
    private val TARGET_VENDOR_ID: Int? = null
    private val TARGET_PRODUCT_ID: Int? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_USB_PERMISSION) {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }

                val granted = intent?.getBooleanExtra(
                    UsbManager.EXTRA_PERMISSION_GRANTED,
                    false
                ) ?: false
                Log.d(TAG, "USB permission result: device=$device granted=$granted")

                if (granted && device != null) {
                    usbDevice = device
                    openUsbDevice()
                } else {
                    updateUsbStatus("USB: Berechtigung verweigert")
                }
            }
        }
    }

    // --- GPS / Location ---
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    // --- Preview-COBS (im Service) ---
    private val byteListQueue = ConcurrentLinkedDeque<LinkedList<Byte>>()
    private var lastByteReadPreview: Byte? = null
    private lateinit var previewThread: HandlerThread
    private lateinit var previewHandler: Handler
    private val previewMedian = OBSLiteSession.MovingMedian()

    @Volatile
    private var leftDistanceText: String = "Links: -"

    @Volatile
    private var rightDistanceText: String = "Rechts: -"

    @Volatile
    private var overtakeDistanceText: String = "Überholabstand: -"

    fun getLastMedianAtPressCm(): Int? = obsSession.lastMedianAtPressCm

    override fun onCreate() {
        super.onCreate()
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "onCreate()")
        obsSession = OBSLiteSession(this)
        fileWriter = OBSLiteFileWriter(this)

        ioThread = HandlerThread("obs-io")
        ioThread.start()
        ioHandler = Handler(ioThread.looper)

        // USB
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        val explicitIntent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE
        else
            0
        permissionIntent = PendingIntent.getBroadcast(this, 0, explicitIntent, flag)
        registerReceiver(usbReceiver, IntentFilter(ACTION_USB_PERMISSION))

        // GPS
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        ensureLocationUpdates()

        // Preview-Thread
        previewThread = HandlerThread("preview-io")
        previewThread.start()
        previewHandler = Handler(previewThread.looper)

        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "onBind()")
        return binder
    }

    /**
     * Wird aufgerufen, wenn der Service per startForegroundService(...) gestartet wird.
     * Hier starten wir die Foreground-Notification.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onStartCommand() id=$startId intent=$intent flags=$flags")
        }
        if (!isForeground) {
            startInForeground()
        }
        // WICHTIG: nicht automatisch neu starten, wenn der Prozess gekillt wird
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            val existing = mgr.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
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
            .setSmallIcon(R.mipmap.ic_launcher) // ggf. eigenes Icon
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun startInForeground() {
        val notification = buildNotification(isRecording)
        startForeground(NOTIFICATION_ID, notification)
        isForeground = true
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "startInForeground(): started")
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

    fun requestUsbPermissionFromUi() {
        requestUsbPermission()
    }

    fun disconnectUsbFromUi() {
        disconnectUsb()
    }

    fun ensureLocationUpdates() {
        if (locationCallback != null) return
        if (!hasLocationPermission()) {
            Log.w(TAG, "ensureLocationUpdates(): no location permission yet")
            return
        }

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1000L
        ).setMinUpdateIntervalMillis(500L).build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                onLocationChangedInternal(loc)
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback!!,
                Looper.getMainLooper()
            )
            Log.d(TAG, "ensureLocationUpdates(): location updates requested")
        } catch (e: SecurityException) {
            Log.e(TAG, "ensureLocationUpdates(): missing permission?", e)
        }
    }

    private fun stopLocationUpdatesInternal() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
        locationCallback = null
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    // --- Recording-API ---

    fun startRecording() {
        if (isRecording) {
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "startRecording(): already recording")
            return
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "startRecording()")

        // Alles auf IO-Thread ausführen, um Reihenfolge zu garantieren
        ioHandler.post {
            try {
                // frische Session für Fahrt
                obsSession = OBSLiteSession(this)
                // Datei öffnen
                fileWriter.startSession()
                isRecording = true
                updateForegroundNotification()

                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(
                        TAG,
                        "startRecording(): isRecording=$isRecording, sessionBytes=${obsSession.debugGetCompleteBytesSize()}, events=${obsSession.debugGetEventCount()}"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "startRecording(): failed to start session", e)
                isRecording = false
                updateForegroundNotification()
            }
        }
    }

    fun stopRecording() {
        if (!isRecording) {
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "stopRecording(): not recording")
            return
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "stopRecording()")

        ioHandler.post {
            try {
                fileWriter.finishSession()
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(
                        TAG,
                        "stopRecording(): file closed. sessionBytes=${obsSession.debugGetCompleteBytesSize()}, events=${obsSession.debugGetEventCount()}"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "stopRecording(): error closing file", e)
            } finally {
                isRecording = false
                updateForegroundNotification()
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "stopRecording(): isRecording=$isRecording")
                }
            }
        }
    }

    /**
     * Wird aufgerufen, wenn der Nutzer die App im „Recents / Quadrat“-Screen wegwischt.
     * → hier stoppen wir Aufnahme & Service sicher.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "onTaskRemoved(): app task removed, stopping recording & service")

        // Aufnahme stoppen (falls aktiv)
        stopRecording()

        // GPS/USB aufräumen
        stopLocationUpdatesInternal()
        if (obsLiteConnected) {
            disconnectUsb()
        }

        // Foreground-Notification entfernen und Service beenden
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }

        stopSelf()

        super.onTaskRemoved(rootIntent)
    }

    /**
     * USB-Daten vom OBS Lite; wird direkt vom Serial-Callback (im Service) aufgerufen.
     * Wir verschieben sofort auf den IO-Thread.
     */
    fun onUsbData(data: ByteArray) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onUsbData(): ${data.size} bytes, isRecording=$isRecording")
        }

        ioHandler.post {
            // Rohdaten in Session-COBS-Puffer schieben
            obsSession.fillByteList(data)
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(
                    TAG,
                    "onUsbData(): after fillByteList -> queueSize=${obsSession.debugGetQueueSize()}"
                )
            }

            if (!isRecording) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "onUsbData(): not recording, skip file write")
                }
                return@post
            }

            var handledCount = 0
            val maxEvents = 10_000 // verhindert Endlosschleifen bei korrupten Streams
            val startNs = System.nanoTime()
            val maxDurationNs = 50_000_000L // ~50ms pro Batch

            while (obsSession.completeCobsAvailable()) {
                val loc: Location? = lastLocation
                val bytes: ByteArray? = if (loc != null) {
                    obsSession.handleEvent(
                        lat = loc.latitude,
                        lon = loc.longitude,
                        altitude = loc.altitude,
                        accuracy = loc.accuracy
                    )
                } else {
                    // Keine Location verfügbar – neutraler Fallback
                    obsSession.handleEvent(
                        lat = 0.0,
                        lon = 0.0,
                        altitude = 0.0,
                        accuracy = 9999f
                    )
                }

                if (bytes != null && bytes.isNotEmpty()) {
                    try {
                        fileWriter.writeSessionData(bytes)
                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                            Log.d(
                                TAG,
                                "onUsbData(): wrote ${bytes.size} bytes, sessionBytes=${obsSession.debugGetCompleteBytesSize()}, events=${obsSession.debugGetEventCount()}"
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "onUsbData(): write failed", e)
                        // Schreibfehler nicht in Endlosschleife laufen lassen
                        break
                    }
                }

                handledCount++
                if (handledCount >= maxEvents) {
                    Log.e(TAG, "onUsbData(): safety stop after $handledCount events")
                    break
                }
                if (System.nanoTime() - startNs > maxDurationNs) {
                    // Batch begrenzen, um UI/andere Tasks nicht zu verhungern
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(
                            TAG,
                            "onUsbData(): batching stop after ${handledCount} events / ~50ms"
                        )
                    }
                    break
                }
            }

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(
                    TAG,
                    "onUsbData(): handledCount=$handledCount, queueNow=${obsSession.debugGetQueueSize()}"
                )
            }
        }
    }

    /**
     * Wird bei jedem neuen GPS-Fix aufgerufen.
     * Die Session erzeugt Geolocation-Events beim Positionswechsel in handleEvent().
     */
    private fun onLocationChangedInternal(location: Location) {
        lastLocation = location
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(
                TAG,
                "onLocationChanged(): rec=$isRecording, lat=${location.latitude}, lon=${location.longitude}, acc=${location.accuracy}"
            )
        }
    }

    // --- USB intern ---

    private fun requestUsbPermission() {
        val devices = usbManager.deviceList.values
        if (devices.isEmpty()) {
            updateUsbStatus("USB: kein Gerät gefunden")
            return
        }
        val target = devices.firstOrNull { d ->
            (TARGET_VENDOR_ID == null || d.vendorId == TARGET_VENDOR_ID) &&
                    (TARGET_PRODUCT_ID == null || d.productId == TARGET_PRODUCT_ID)
        } ?: devices.first()

        usbDevice = target
        updateUsbStatus("USB: Gerät gefunden, frage Berechtigung...")
        usbManager.requestPermission(target, permissionIntent)
    }

    private fun openUsbDevice() {
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            updateUsbStatus("USB: kein serieller Treiber gefunden")
            return
        }

        // Treiber passend zum freigegebenen Device wählen
        val dev = usbDevice
        val driver = if (dev != null) {
            availableDrivers.firstOrNull { it.device.deviceId == dev.deviceId }
                ?: availableDrivers.first()
        } else {
            availableDrivers.first()
        }

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
            updateUsbStatus("USB: verbunden")
            Log.d(TAG, "USB serial port opened")
        } catch (e: Exception) {
            Log.e(TAG, "USB open error", e)
            updateUsbStatus("USB: Fehler beim Öffnen")
        }
    }

    private fun disconnectUsb() {
        try {
            usbIoManager?.stop()
        } catch (_: Exception) {
        }
        usbIoManager = null
        try {
            if (this::usbPort.isInitialized) usbPort.close()
        } catch (_: Exception) {
        }

        obsLiteConnected = false
        updateUsbStatus("USB: nicht verbunden")
    }

    private fun updateUsbStatus(text: String) {
        usbStatusText = text
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "USB status: $text")
        }
    }

    // --- Preview-COBS/Protobuf (Live-Anzeige) ---

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
            // unvollständig -> wieder vorne einreihen
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

                // Lenkerbreite aus SharedPreferences (wie in OBSLiteSession)
                val handlebarWidthCm = getHandlebarWidthCm()
                val corrected = ((rawCm - handlebarWidthCm / 2.0).coerceAtLeast(0.0)).roundToInt()

                if (sourceId == 1) previewMedian.newValue(corrected)

                val text = "Roh: ${rawCm} cm  |  korrigiert: ${corrected} cm"
                if (sourceId == 1) {
                    leftDistanceText = "Links (ID 1): $text"
                } else {
                    rightDistanceText = "Rechts (ID $sourceId): $text"
                }
            } else if (event.hasUserInput()) {
                val uiType = event.userInput.type
                usbStatusText = "UserInput: $uiType"
                overtakeDistanceText = if (previewMedian.hasMedian()) {
                    "Überholabstand: ${previewMedian.median} cm"
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
        val prefs = getSharedPreferences("obslite_prefs", Context.MODE_PRIVATE)
        return prefs.getInt("handlebar_width_cm", 60)
    }

    // --- SerialInputOutputManager.Listener ---

    override fun onNewData(data: ByteArray?) {
        if (data == null) return
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onNewData: ${data.size} Bytes")
        }

        updateUsbStatus("USB: Daten ${data.size} B")
        onUsbData(data)

        // Preview-Verarbeitung auf Hintergrund-Thread
        previewHandler.post {
            previewFillByteList(data)
            var loops = 0
            val maxLoops = 1000
            while (previewCompleteCobsAvailable()) {
                handlePreviewEventSafe()
                loops++
                if (loops >= maxLoops) {
                    Log.e(TAG, "Preview loop safety break ($loops)")
                    break
                }
            }
        }
    }

    override fun onRunError(e: Exception?) {
        Log.e(TAG, "Serial IO error", e)
        updateUsbStatus("USB: Fehler im Datenstrom")
    }

    override fun onDestroy() {
        Log.w(TAG, "onDestroy(): service destroyed, isRecording=$isRecording")

        // USB Receiver und Preview-Thread aufräumen
        runCatching { unregisterReceiver(usbReceiver) }
        previewThread.quitSafely()

        // Falls noch Aufnahme läuft, Session sauber beenden
        ioHandler.post {
            try {
                if (isRecording) {
                    fileWriter.finishSession()
                }
            } catch (e: Exception) {
                Log.e(TAG, "onDestroy(): error closing file", e)
            } finally {
                isRecording = false
                ioThread.quitSafely()
            }
        }

        stopLocationUpdatesInternal()

        if (obsLiteConnected) {
            disconnectUsb()
        }

        if (isForeground) {
            // Notification entfernen
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }

        super.onDestroy()
    }
}
