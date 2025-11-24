package com.example.obsliterecorder

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.obsliterecorder.obslite.ObsLiteService
import com.example.obsliterecorder.proto.Event
import com.example.obsliterecorder.util.CobsUtils
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
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
    private lateinit var tvUsbStatus: TextView
    private lateinit var tvLeftDistance: TextView
    private lateinit var tvRightDistance: TextView
    private lateinit var etHandlebarWidth: EditText
    private lateinit var tvFiles: TextView
    private lateinit var tvGpsStatus: TextView
    private lateinit var mapView: MapView

    // Marker für aktuelle Position
    private var locationMarker: Marker? = null

    // Originalfarben der Buttons merken
    private var startOriginalTint: ColorStateList? = null
    private var stopOriginalTint: ColorStateList? = null

    // Aufnahmezustand
    private var isRecording: Boolean = false

    // COBS-Puffer für Live-Anzeige
    private var byteListQueue = ConcurrentLinkedDeque<LinkedList<Byte>>()
    private var lastByteRead: Byte? = null

    // SharedPreferences für Lenkerbreite
    private val prefs by lazy {
        getSharedPreferences("obslite_prefs", Context.MODE_PRIVATE)
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_USB_PERMISSION) {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }

                val granted =
                    intent?.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) ?: false
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
        super.onCreate(savedInstanceState)

        // osmdroid-Konfiguration (User-Agent setzen)
        Configuration.getInstance().load(
            applicationContext,
            getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
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

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnUsb = findViewById(R.id.btnUsb)
        tvUsbStatus = findViewById(R.id.tvUsbStatus)
        tvLeftDistance = findViewById(R.id.tvLeftDistance)
        tvRightDistance = findViewById(R.id.tvRightDistance)
        etHandlebarWidth = findViewById(R.id.etHandlebarWidth)
        tvFiles = findViewById(R.id.tvFiles)
        tvGpsStatus = findViewById(R.id.tvGpsStatus)
        mapView = findViewById(R.id.mapView)

        // NEU: "Über diese App"-Link unten verdrahten
        val tvAbout: TextView = findViewById(R.id.tvAbout)
        tvAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        // Optionaler Debug-Button (im Layout mit id=btnDebugBin)
        val btnDebugBin: Button? = findViewById(R.id.btnDebugBin)
        Log.d(TAG, "btnDebugBin gefunden: ${btnDebugBin != null}")
        btnDebugBin?.setOnClickListener {
            Log.d(TAG, "btnDebugBin geklickt")
            tvUsbStatus.text = "BIN-Check läuft..."
            debugValidateLastBin()
        }

        // MapView konfigurieren
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(14.0)
        mapView.controller.setCenter(GeoPoint(0.0, 0.0))

        // Originalfarben merken
        startOriginalTint = btnStart.backgroundTintList
        stopOriginalTint = btnStop.backgroundTintList

        // Gespeicherte Lenkerbreite laden und ins Feld setzen (Default: 60 cm)
        val savedWidth = loadHandlebarWidthCm()
        etHandlebarWidth.setText(savedWidth.toString())

        // initial Dateiliste anzeigen
        refreshFileList()

        // Service-Buttons
        btnStart.setOnClickListener {
            // Aufnahme im Service starten, GPS läuft unabhängig davon
            obsService?.startRecording()

            isRecording = true
            updateRecordingUi()
        }

        btnStop.setOnClickListener {
            // Aufnahme im Service stoppen (wenn gebunden)
            obsService?.stopRecording()

            // GPS NICHT stoppen – Karte soll immer Position zeigen,
            // solange Activity sichtbar ist
            isRecording = false
            updateRecordingUi()
            refreshFileList() // neue Datei anzeigen
        }

        // USB-Setup
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        val explicitIntent = Intent(ACTION_USB_PERMISSION)
        explicitIntent.setPackage(packageName)

        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }

        permissionIntent = PendingIntent.getBroadcast(this, 0, explicitIntent, flag)
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        registerReceiver(usbReceiver, filter)

        btnUsb.setOnClickListener {
            if (obsLiteConnected) {
                disconnectUsb()
            } else {
                requestUsbPermission()
            }
        }

        // initial UI-Zustand
        updateRecordingUi()
        updateGpsStatusNoFix()
    }

    override fun onStart() {
        super.onStart()

        // GPS-Updates IMMER starten, sobald die Activity sichtbar ist
        startLocationUpdates()

        val intent = Intent(this, ObsLiteService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        // Wenn Activity nicht mehr sichtbar ist, GPS wieder stoppen
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
        // aktuellen Wert aus dem EditText lesen und speichern
        val currentWidth = getHandlebarWidthCm()
        saveHandlebarWidthCm(currentWidth)
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectUsb()
        unregisterReceiver(usbReceiver)
    }

    // --- Aufnahme-UI (Button-Farben) ---

    private fun updateRecordingUi() {
        if (isRecording) {
            // Start-Button grün, Stop-Button rot
            btnStart.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
            btnStop.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F44336"))
            btnStart.isEnabled = false
            btnStop.isEnabled = true
        } else {
            // Ursprungsfarben wiederherstellen
            btnStart.backgroundTintList = startOriginalTint
            btnStop.backgroundTintList = stopOriginalTint
            btnStart.isEnabled = true
            btnStop.isEnabled = true
        }
    }

    // --- Dateiliste aktualisieren ---

    private fun refreshFileList() {
        val dir = File(getExternalFilesDir(null), "obslite")
        if (!dir.exists()) {
            tvFiles.text = "Keine Dateien"
            return
        }
        val files = dir.listFiles { f ->
            f.isFile && f.name.endsWith(".bin")
        }?.sortedBy { it.lastModified() } ?: emptyList()

        tvFiles.text = if (files.isEmpty()) {
            "Keine Dateien"
        } else {
            files.joinToString("\n") { it.name }
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

        // Schon aktiv? Dann nicht noch einmal registrieren
        if (locationCallback != null) {
            return
        }

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1000L // Intervall: alle 1 Sekunde
        ).setMinUpdateIntervalMillis(500L).build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                super.onLocationResult(result)
                val loc = result.lastLocation ?: return
                if (bound && obsService != null) {
                    obsService?.onLocationChanged(loc)
                }
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
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
        locationCallback = null
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun updateMapAndGpsStatus(lat: Double, lon: Double, accuracy: Float) {
        val geoPoint = GeoPoint(lat, lon)

        // Karte auf Position zentrieren
        mapView.controller.setCenter(geoPoint)

        // Marker für aktuelle Position setzen / aktualisieren
        if (locationMarker == null) {
            locationMarker = Marker(mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapView.overlays.add(locationMarker)
        }
        locationMarker?.position = geoPoint
        mapView.invalidate()

        val acc = accuracy.toInt()
        val statusText = when {
            acc <= 10 -> "GPS: gut (±${acc} m)"
            acc <= 30 -> "GPS: ok (±${acc} m)"
            else -> "GPS: schwach (±${acc} m)"
        }
        val color = when {
            acc <= 10 -> Color.parseColor("#4CAF50") // grün
            acc <= 30 -> Color.parseColor("#FFC107") // gelb
            else -> Color.parseColor("#F44336")      // rot
        }

        tvGpsStatus.text = statusText
        tvGpsStatus.setTextColor(color)
    }

    private fun updateGpsStatusNoFix() {
        tvGpsStatus.text = "GPS: keine Daten"
        tvGpsStatus.setTextColor(Color.GRAY)
    }

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
                // nach Erlaubnis direkt GPS starten
                startLocationUpdates()
            } else {
                updateGpsStatusNoFix()
            }
        }
    }

    // --- USB / OpenBikeSensor Lite ---

    private fun requestUsbPermission() {
        val deviceList = usbManager.deviceList
        if (deviceList.isEmpty()) {
            updateUsbStatus("USB: kein Gerät gefunden")
            return
        }

        usbDevice = deviceList.values.first()
        updateUsbStatus("USB: Gerät gefunden, frage Berechtigung...")

        usbManager.requestPermission(usbDevice, permissionIntent)
    }

    private fun openUsbDevice() {
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            updateUsbStatus("USB: kein serieller Treiber gefunden")
            return
        }

        val driver = availableDrivers[0]
        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            updateUsbStatus("USB: konnte Gerät nicht öffnen")
            return
        }

        usbPort = driver.ports[0] // meist nur 1 Port
        try {
            usbPort.open(connection)
            usbPort.setParameters(
                115200,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            usbIoManager = SerialInputOutputManager(usbPort, this)
            usbIoManager?.start()

            obsLiteConnected = true
            updateUsbStatus("USB: verbunden")
            btnUsb.text = "OBS Lite trennen"
            Log.d(TAG, "USB serial port opened")
        } catch (e: Exception) {
            e.printStackTrace()
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
            if (this::usbPort.isInitialized) {
                usbPort.close()
            }
        } catch (_: Exception) {
        }

        obsLiteConnected = false
        btnUsb.text = "OBS Lite verbinden"
        updateUsbStatus("USB: nicht verbunden")
    }

    private fun updateUsbStatus(text: String) {
        runOnUiThread {
            tvUsbStatus.text = text
        }
    }

    // --- Lenkerbreite persistieren ---

    private fun loadHandlebarWidthCm(): Int {
        return prefs.getInt(PREF_KEY_HANDLEBAR_WIDTH_CM, 60)
    }

    private fun saveHandlebarWidthCm(widthCm: Int) {
        prefs.edit().putInt(PREF_KEY_HANDLEBAR_WIDTH_CM, widthCm).apply()
    }

    // --- Hilfsfunktion: Lenkerbreite aus EditText lesen ---

    private fun getHandlebarWidthCm(): Int {
        val text = etHandlebarWidth.text?.toString()?.trim()
        // Wenn Eingabe ungültig/leer ist, auf gespeicherten Wert (oder Default) zurückfallen
        return text?.toIntOrNull() ?: loadHandlebarWidthCm()
    }

    // --- COBS-Handling für Live-Anzeige ---

    private fun fillByteList(data: ByteArray) {
        for (datum in data) {
            if (lastByteRead?.toInt() == 0x00) { // neues Paket nach 0x00
                val newList = LinkedList<Byte>()
                newList.add(datum)
                byteListQueue.add(newList)
            } else {
                if (byteListQueue.isNotEmpty()) {
                    byteListQueue.last.add(datum)
                } else {
                    val newList = LinkedList<Byte>()
                    newList.add(datum)
                    byteListQueue.add(newList)
                }
            }
            lastByteRead = datum
        }
    }

    private fun completeCobsAvailable(): Boolean {
        val first = byteListQueue.peekFirst() ?: return false
        for (b in first) {
            if (b.toInt() == 0x00) return true
        }
        return false
    }

    private fun handlePreviewEvent() {
        val list = byteListQueue.firstOrNull() ?: return

        val decodedData = CobsUtils.decode(list.toByteArray())

        try {
            val event: Event = Event.parseFrom(decodedData)

            if (event.hasDistanceMeasurement() && event.distanceMeasurement.distance < 5f) {
                val rawDistanceCm = (event.distanceMeasurement.distance * 100).toInt()
                val sourceId = event.distanceMeasurement.sourceId

                // Lenkerbreite: Hälfte abziehen
                val handlebarWidthCm = getHandlebarWidthCm()
                val halfHandlebar = handlebarWidthCm / 2
                val corrected = (rawDistanceCm - halfHandlebar).coerceAtLeast(0)

                Log.d(
                    TAG,
                    "Event Distance: src=$sourceId, raw=${rawDistanceCm}cm, handlebar=$handlebarWidthCm, corr=${corrected}cm"
                )

                runOnUiThread {
                    val text = "Roh: ${rawDistanceCm} cm  |  korrigiert: ${corrected} cm"
                    if (sourceId == 1) {
                        tvLeftDistance.text = "Links (ID 1): $text"
                    } else {
                        tvRightDistance.text = "Rechts (ID $sourceId): $text"
                    }
                }

            } else if (event.hasUserInput()) {
                val uiType = event.userInput.type
                Log.d(TAG, "UserInput: $uiType")
                runOnUiThread {
                    tvUsbStatus.text = "UserInput: $uiType"
                }
            }

        } catch (e: InvalidProtocolBufferException) {
            Log.e(TAG, "Fehler beim Parsen von Event", e)
            runOnUiThread {
                tvUsbStatus.text = "USB: Parse-Fehler"
            }
        } finally {
            byteListQueue.removeFirst()
        }
    }

    // --- Debug: letzte .bin-Datei wie im Portal parsen und Probleme loggen ---

    private fun debugValidateLastBin() {
        val dir = File(getExternalFilesDir(null), "obslite")
        val files = dir.listFiles { f ->
            f.isFile && f.name.endsWith(".bin")
        }?.sortedBy { it.lastModified() } ?: emptyList()

        if (files.isEmpty()) {
            Log.e("BIN_DEBUG", "Keine .bin-Datei gefunden")
            runOnUiThread {
                tvUsbStatus.text = "BIN-Check: keine .bin-Datei gefunden"
            }
            return
        }

        val file = files.last()
        val bytes = file.readBytes()

        Log.d("BIN_DEBUG", "Prüfe Datei: ${file.absolutePath}")
        Log.d("BIN_DEBUG", "Dateigröße: ${bytes.size} Bytes")

        // Erste 64 Bytes als Hex ausgeben (Vorschau)
        val previewLen = minOf(64, bytes.size)
        val hexPreview = buildString {
            for (i in 0 until previewLen) {
                append(String.format("%02X ", bytes[i]))
            }
        }
        Log.d("BIN_DEBUG", "Hex-Vorschau (max 64 B): $hexPreview")

        runOnUiThread {
            tvUsbStatus.text = "BIN-Check: ${file.name} (${bytes.size} B)"
        }

        // wie im Portal: an 0x00 splitten
        val chunks: List<ByteArray> = bytes.splitOnByte(0x00.toByte())
        Log.d("BIN_DEBUG", "Anzahl Chunks (inkl. evtl. leerer): ${chunks.size}")

        var idx = 0
        var okCount = 0
        var errorCount = 0
        var nonEmptyChunks = 0

        for (chunk in chunks) {
            if (chunk.isEmpty()) {
                Log.d("BIN_DEBUG", "#$idx leerer Chunk")
                idx++
                continue
            }

            nonEmptyChunks++

            try {
                // COBS decode wie im Portal, dann Event.parseFrom
                val chunkList = LinkedList<Byte>()
                chunk.forEach { b -> chunkList.add(b) }

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
                "BIN-Check fertig: ${bytes.size} B, Chunks=$nonEmptyChunks, OK=$okCount, Fehler=$errorCount (Details in Logcat: BIN_DEBUG)"
        }
    }

    // --- Hilfs-Extension: ByteArray an einem Byte splitten wie Python .split(b"\x00") ---

    private fun ByteArray.splitOnByte(separator: Byte): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var start = 0
        for (i in indices) {
            if (this[i] == separator) {
                if (i > start) {
                    result.add(copyOfRange(start, i))
                } else {
                    result.add(ByteArray(0))
                }
                start = i + 1
            }
        }
        if (start < size) {
            result.add(copyOfRange(start, size))
        }
        return result
    }

    // --- SerialInputOutputManager.Listener ---

    override fun onNewData(data: ByteArray?) {
        if (data == null) return

        Log.d(TAG, "onNewData: ${data.size} Bytes")
        runOnUiThread {
            tvUsbStatus.text = "USB: Daten ${data.size} B"
        }

        // 1. Rohdaten an den Service weitergeben (Aufzeichnung)
        if (bound && obsService != null) {
            obsService?.onUsbData(data)
        }

        // 2. Live-Anzeige
        runOnUiThread {
            fillByteList(data)
            if (completeCobsAvailable()) {
                handlePreviewEvent()
            }
        }
    }

    override fun onRunError(e: Exception?) {
        e?.printStackTrace()
        updateUsbStatus("USB: Fehler im Datenstrom")
    }

    companion object {
        private const val TAG = "MainActivity_USB"
        private const val PREF_KEY_HANDLEBAR_WIDTH_CM = "handlebar_width_cm"
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
    }
}
