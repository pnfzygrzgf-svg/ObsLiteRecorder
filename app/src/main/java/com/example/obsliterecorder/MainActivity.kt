// file: com/example/obsliterecorder/MainActivity.kt
package com.example.obsliterecorder

import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.obsliterecorder.obslite.ObsLiteService
import com.example.obsliterecorder.obslite.OBSLiteSession
import com.example.obsliterecorder.proto.Event
import com.example.obsliterecorder.util.CobsUtils
import com.google.android.gms.location.*
import com.google.protobuf.InvalidProtocolBufferException
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.util.LinkedList
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), SerialInputOutputManager.Listener {

    // --- Service-Binding zum ObsLiteService ---
    private var obsService: ObsLiteService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ObsLiteService.LocalBinder
            obsService = binder.getService()
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            obsService = null
        }
    }

    // --- Location / GPS ---
    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }
    private var locationCallback: LocationCallback? = null

    // --- USB / OpenBikeSensor Lite ---
    private lateinit var usbManager: UsbManager
    private var usbDevice: UsbDevice? = null
    private var usbIoManager: SerialInputOutputManager? = null
    private lateinit var usbPort: UsbSerialPort
    private var obsLiteConnected = false

    private val ACTION_USB_PERMISSION = "com.example.obsliterecorder.USB_PERMISSION"
    private var permissionIntent: PendingIntent? = null

    // UI
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnUsb: Button
    private lateinit var btnOpenUpload: Button
    private lateinit var btnShowFiles: Button
    private lateinit var tvUsbStatus: TextView
    private lateinit var tvLeftDistance: TextView
    private lateinit var tvRightDistance: TextView
    private lateinit var tvOvertakeDistance: TextView
    private lateinit var etHandlebarWidth: EditText
    private lateinit var tvGpsStatus: TextView
    private lateinit var mapView: MapView

    private var locationMarker: Marker? = null
    private var defaultLocationIcon: Drawable? = null

    private var startOriginalTint: android.content.res.ColorStateList? = null
    private var stopOriginalTint: android.content.res.ColorStateList? = null

    private var isRecording: Boolean = false

    // --- Preview-COBS (off-UI) ---
    private val byteListQueue = ConcurrentLinkedDeque<LinkedList<Byte>>()
    private var lastByteRead: Byte? = null

    // Hintergrund-Thread für Preview
    private lateinit var previewThread: HandlerThread
    private lateinit var previewHandler: Handler

    // Gleitender Median für Live-Anzeige (links, korrigiert)
    private val previewMedian = OBSLiteSession.MovingMedian()

    // SharedPreferences für Lenkerbreite
    private val prefs by lazy { getSharedPreferences("obslite_prefs", MODE_PRIVATE) }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        // Splashscreen installieren
        installSplashScreen()
        super.onCreate(savedInstanceState)

        Configuration.getInstance().load(
            applicationContext,
            getSharedPreferences("osmdroid", MODE_PRIVATE)
        )
        Configuration.getInstance().userAgentValue = packageName

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val root: View = findViewById(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Views
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnUsb = findViewById(R.id.btnUsb)
        btnOpenUpload = findViewById(R.id.btnOpenUpload)
        btnShowFiles = findViewById(R.id.btnShowFiles)
        tvUsbStatus = findViewById(R.id.tvUsbStatus)
        tvLeftDistance = findViewById(R.id.tvLeftDistance)
        tvRightDistance = findViewById(R.id.tvRightDistance)
        tvOvertakeDistance = findViewById(R.id.tvOvertakeDistance)
        etHandlebarWidth = findViewById(R.id.etHandlebarWidth)
        tvGpsStatus = findViewById(R.id.tvGpsStatus)
        mapView = findViewById(R.id.mapView)

        // About
        findViewById<TextView>(R.id.tvAbout).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        // Debug .bin
        findViewById<Button?>(R.id.btnDebugBin)?.setOnClickListener {
            tvUsbStatus.text = "BIN-Check läuft..."
            debugValidateLastBin()
        }

        // Upload / Files
        btnOpenUpload.setOnClickListener {
            startActivity(Intent(this, UploadActivity::class.java))
        }
        btnShowFiles.setOnClickListener {
            startActivity(Intent(this, RecordedFilesActivity::class.java))
        }

        // Map
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(18.0)
        mapView.controller.setCenter(GeoPoint(0.0, 0.0))

        // Button-Farben
        startOriginalTint = btnStart.backgroundTintList
        stopOriginalTint = btnStop.backgroundTintList

        // Lenkerbreite
        etHandlebarWidth.setText(loadHandlebarWidthCm().toString())

        // Start/Stop
        btnStart.setOnClickListener {
            obsService?.startRecording()
            isRecording = true
            updateRecordingUi()
        }
        btnStop.setOnClickListener {
            obsService?.stopRecording()
            isRecording = false
            updateRecordingUi()
        }

        // USB
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        val explicitIntent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE
        else
            0
        permissionIntent = PendingIntent.getBroadcast(this, 0, explicitIntent, flag)
        registerReceiver(usbReceiver, IntentFilter(ACTION_USB_PERMISSION))
        btnUsb.setOnClickListener {
            if (obsLiteConnected) disconnectUsb() else requestUsbPermission()
        }

        // Preview-Thread
        previewThread = HandlerThread("preview-io")
        previewThread.start()
        previewHandler = Handler(previewThread.looper)

        // initial UI
        updateRecordingUi()
        updateGpsStatusNoFix()
    }

    override fun onStart() {
        super.onStart()
        startLocationUpdates()
        bindService(Intent(this, ObsLiteService::class.java), connection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        stopLocationUpdates()
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        saveHandlebarWidthCm(getHandlebarWidthCm())
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectUsb()
        runCatching { unregisterReceiver(usbReceiver) }
        previewThread.quitSafely()
    }

    // --- Permission-Callback für GPS ---
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                startLocationUpdates()
            } else {
                updateGpsStatusNoFix()
            }
        }
    }

    // --- Aufnahme-UI (Button-Farben) ---
    private fun updateRecordingUi() {
        if (isRecording) {
            btnStart.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50"))
            btnStop.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor("#F44336"))
            btnStart.isEnabled = false
            btnStop.isEnabled = true
        } else {
            btnStart.backgroundTintList = startOriginalTint
            btnStop.backgroundTintList = stopOriginalTint
            btnStart.isEnabled = true
            btnStop.isEnabled = true
        }
    }

    // --- GPS-Status / Map-Update ---
    private fun startLocationUpdates() {
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
            return
        }
        if (locationCallback != null) return

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1000L
        ).setMinUpdateIntervalMillis(500L).build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                if (bound) obsService?.onLocationChanged(loc)
                updateMapAndGpsStatus(loc.latitude, loc.longitude, loc.accuracy)
            }
        }
        fusedLocationClient.requestLocationUpdates(
            request,
            locationCallback!!,
            mainLooper
        )
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    private var lastShown: GeoPoint? = null
    private fun updateMapAndGpsStatus(lat: Double, lon: Double, accuracy: Float) {
        val p = GeoPoint(lat, lon)
        if (lastShown == null || p.distanceToAsDouble(lastShown) > 10.0) {
            mapView.controller.setCenter(p)
            lastShown = p
        }

        if (locationMarker == null) {
            locationMarker = Marker(mapView).apply {
                defaultLocationIcon = icon
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapView.overlays.add(locationMarker)
        }
        locationMarker?.apply {
            position = p
            icon = if (isRecording)
                ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_location_bike)
            else
                defaultLocationIcon
        }
        mapView.invalidate()

        val acc = accuracy.toInt()
        val statusText = when {
            acc <= 10 -> "GPS: gut (±${acc} m)"
            acc <= 30 -> "GPS: ok (±${acc} m)"
            else -> "GPS: schwach (±${acc} m)"
        }
        val color = when {
            acc <= 10 -> Color.parseColor("#4CAF50")
            acc <= 30 -> Color.parseColor("#FFC107")
            else -> Color.parseColor("#F44336")
        }
        tvGpsStatus.text = statusText
        tvGpsStatus.setTextColor(color)
    }

    private fun updateGpsStatusNoFix() {
        tvGpsStatus.text = "GPS: keine Daten"
        tvGpsStatus.setTextColor(Color.GRAY)
    }

    // --- USB / OpenBikeSensor Lite ---
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
            btnUsb.text = "OBS Lite trennen"
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
        btnUsb.text = "OBS Lite verbinden"
        updateUsbStatus("USB: nicht verbunden")
    }

    private fun updateUsbStatus(text: String) {
        runOnUiThread { tvUsbStatus.text = text }
    }

    // --- Lenkerbreite persistieren ---
    private fun loadHandlebarWidthCm(): Int = prefs.getInt(PREF_KEY_HANDLEBAR_WIDTH_CM, 60)
    private fun saveHandlebarWidthCm(widthCm: Int) {
        prefs.edit().putInt(PREF_KEY_HANDLEBAR_WIDTH_CM, widthCm).apply()
    }
    private fun getHandlebarWidthCm(): Int {
        val text = etHandlebarWidth.text?.toString()?.trim()
        return text?.toIntOrNull() ?: loadHandlebarWidthCm()
    }

    // --- COBS-Handling: jetzt auf Preview-Thread robust ---
    private fun previewFillByteList(data: ByteArray) {
        for (b in data) {
            if (lastByteRead?.toInt() == 0x00 || byteListQueue.isEmpty()) {
                val newList = LinkedList<Byte>()
                newList.add(b)
                byteListQueue.add(newList)
            } else {
                byteListQueue.last.add(b)
            }
            lastByteRead = b
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
            CobsUtils.decode(list) // nutzt Overload mit Collection<Byte>
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

                if (sourceId == 1) previewMedian.newValue(corrected)

                runOnUiThread {
                    val text = "Roh: ${rawCm} cm  |  korrigiert: ${corrected} cm"
                    if (sourceId == 1) {
                        tvLeftDistance.text = "Links (ID 1): $text"
                    } else {
                        tvRightDistance.text = "Rechts (ID $sourceId): $text"
                    }
                }
            } else if (event.hasUserInput()) {
                val uiType = event.userInput.type
                runOnUiThread {
                    tvUsbStatus.text = "UserInput: $uiType"
                    tvOvertakeDistance.text = if (previewMedian.hasMedian()) {
                        "Überholabstand: ${previewMedian.median} cm"
                    } else {
                        "Überholabstand: -"
                    }
                }
            }
        } catch (e: InvalidProtocolBufferException) {
            Log.e(TAG, "Preview: parse error", e)
            runOnUiThread { tvUsbStatus.text = "USB: Parse-Fehler" }
        }
    }

    // --- Debug: letzte .bin-Datei prüfen (IO off-UI empfohlen) ---
    private fun debugValidateLastBin() {
        val dir = File(getExternalFilesDir(null), "obslite")
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".bin") }
            ?.sortedBy { it.lastModified() } ?: emptyList()
        if (files.isEmpty()) {
            Log.e("BIN_DEBUG", "Keine .bin-Datei gefunden")
            runOnUiThread { tvUsbStatus.text = "BIN-Check: keine .bin-Datei gefunden" }
            return
        }

        val file = files.last()
        val bytes = file.readBytes()
        Log.d("BIN_DEBUG", "Prüfe Datei: ${file.absolutePath}")
        Log.d("BIN_DEBUG", "Dateigröße: ${bytes.size} Bytes")

        val previewLen = minOf(64, bytes.size)
        val hexPreview = buildString {
            for (i in 0 until previewLen) append(String.format("%02X ", bytes[i]))
        }
        Log.d("BIN_DEBUG", "Hex-Vorschau (max 64 B): $hexPreview")
        runOnUiThread { tvUsbStatus.text = "BIN-Check: ${file.name} (${bytes.size} B)" }

        val chunks: List<ByteArray> = bytes.splitOnByte(0x00.toByte())
        Log.d("BIN_DEBUG", "Anzahl Chunks (inkl. evtl. leerer): ${chunks.size}")

        var idx = 0; var okCount = 0; var errorCount = 0; var nonEmptyChunks = 0
        for (chunk in chunks) {
            if (chunk.isEmpty()) {
                Log.d("BIN_DEBUG", "#$idx leerer Chunk")
                idx++
                continue
            }
            nonEmptyChunks++
            try {
                val chunkList = LinkedList<Byte>(); chunk.forEach { b -> chunkList.add(b) }
                val decoded: ByteArray = CobsUtils.decode(chunkList)
                val event = Event.parseFrom(decoded)
                Log.d(
                    "BIN_DEBUG",
                    "#$idx OK (Chunklen=${chunk.size}, decoded=${decoded.size}) : $event"
                )
                okCount++
            } catch (e: Exception) {
                Log.e(
                    "BIN_DEBUG",
                    "#$idx FEHLER beim Parsen (Chunklen=${chunk.size})",
                    e
                )
                errorCount++
            }
            idx++
        }
        Log.d(
            "BIN_DEBUG",
            "Auswertung: nonEmptyChunks=$nonEmptyChunks, ok=$okCount, errors=$errorCount"
        )
        runOnUiThread {
            tvUsbStatus.text =
                "BIN-Check fertig: ${bytes.size} B, Chunks=$nonEmptyChunks, OK=$okCount, Fehler=$errorCount (Logcat: BIN_DEBUG)"
        }
    }

    private fun ByteArray.splitOnByte(separator: Byte): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var start = 0
        for (i in indices) {
            if (this[i] == separator) {
                result.add(if (i > start) copyOfRange(start, i) else ByteArray(0))
                start = i + 1
            }
        }
        if (start < size) result.add(copyOfRange(start, size))
        return result
    }

    // --- SerialInputOutputManager.Listener ---
    override fun onNewData(data: ByteArray?) {
        if (data == null) return
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onNewData: ${data.size} Bytes")
        }

        runOnUiThread { tvUsbStatus.text = "USB: Daten ${data.size} B" }
        if (bound) obsService?.onUsbData(data)

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

    companion object {
        private const val TAG = "MainActivity_USB"
        private const val PREF_KEY_HANDLEBAR_WIDTH_CM = "handlebar_width_cm"
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
    }
}
