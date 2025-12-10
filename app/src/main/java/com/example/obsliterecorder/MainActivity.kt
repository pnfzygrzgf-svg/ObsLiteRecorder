package com.example.obsliterecorder

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.View
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
import com.google.android.material.button.MaterialButton
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

class MainActivity : AppCompatActivity() {

    // --- Service-Binding zum ObsLiteService ---
    private var obsService: ObsLiteService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ObsLiteService.LocalBinder
            obsService = binder.getService()
            bound = true

            // Aufnahme-Status vom Service übernehmen
            isRecording = obsService?.isRecordingActive() ?: false
            updateRecordingUi()

            // Falls Location-Permission schon da -> Service soll GPS-Updates starten
            if (hasLocationPermission()) {
                obsService?.ensureLocationUpdates()
            } else {
                requestLocationPermission()
            }

            // UI-Status-Updates starten
            startStatusUiUpdates()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            obsService = null
            stopStatusUiUpdates()
        }
    }

    // UI
    private lateinit var btnRecord: MaterialButton
    private lateinit var btnUsb: MaterialButton
    private lateinit var tvUsbStatus: TextView
    private lateinit var tvLeftDistance: TextView
    private lateinit var tvRightDistance: TextView
    private lateinit var tvOvertakeDistance: TextView
    private lateinit var etHandlebarWidth: EditText
    private lateinit var tvGpsStatus: TextView
    private lateinit var mapView: MapView
    private lateinit var btnToggleMap: MaterialButton

    // Neuer Button für Unterseite
    private lateinit var btnOpenData: MaterialButton

    // App beenden
    private lateinit var btnExit: MaterialButton

    // Toggle für Lenkerbreite-Bereich
    private lateinit var btnToggleHandlebar: MaterialButton
    private lateinit var handlebarContent: View
    private var isHandlebarVisible: Boolean = false

    // Toggle für Sensorwerte-Bereich (Links/Rechts)
    private lateinit var btnToggleSensor: MaterialButton
    private lateinit var sensorContent: View
    private var isSensorVisible: Boolean = false

    private var isMapVisible: Boolean = false

    private var locationMarker: Marker? = null
    private var defaultLocationIcon: Drawable? = null

    private var recordOriginalTint: ColorStateList? = null
    private var usbOriginalTint: ColorStateList? = null

    // Lokaler UI-Status (Service hält den echten Aufnahme-Status)
    private var isRecording: Boolean = false

    // SharedPreferences für Lenkerbreite
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(
            "obslite_prefs",
            MODE_PRIVATE
        )
    }

    // Handler für periodische UI-Updates von Service-Daten
    private val uiHandler = Handler(Looper.getMainLooper())
    private val statusRunnable = object : Runnable {
        override fun run() {
            if (bound && obsService != null) {
                try {
                    // USB-Status + Button + Icon
                    if (obsService!!.isUsbConnected()) {
                        tvUsbStatus.text = "USB: verbunden"
                        tvUsbStatus.setTextColor(Color.parseColor("#4CAF50")) // grün

                        btnUsb.text = "OBS Lite trennen"
                        btnUsb.backgroundTintList =
                            ColorStateList.valueOf(Color.parseColor("#4CAF50"))
                        btnUsb.icon = ContextCompat.getDrawable(
                            this@MainActivity,
                            R.drawable.ic_link_disconnect
                        )
                    } else {
                        tvUsbStatus.text = "USB: nicht verbunden"
                        tvUsbStatus.setTextColor(Color.GRAY)

                        btnUsb.text = "OBS Lite verbinden"
                        btnUsb.backgroundTintList = usbOriginalTint
                        btnUsb.icon = ContextCompat.getDrawable(
                            this@MainActivity,
                            R.drawable.ic_link_connect
                        )
                    }

                    // Distanzen (Preview)
                    tvLeftDistance.text = obsService!!.getLeftDistanceText()
                    tvRightDistance.text = obsService!!.getRightDistanceText()
                    tvOvertakeDistance.text = obsService!!.getOvertakeDistanceText()

                    // GPS / Karte
                    val loc = obsService!!.getLastLocation()
                    if (loc != null) {
                        updateMapAndGpsStatus(loc.latitude, loc.longitude, loc.accuracy)
                    } else {
                        updateGpsStatusNoFix()
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "statusRunnable(): error updating UI", t)
                }
            }
            uiHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Splashscreen installieren
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Foreground-Service für ObsLite starten (läuft unabhängig von der Activity weiter)
        val serviceIntent = Intent(this, ObsLiteService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)

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
        btnRecord = findViewById(R.id.btnRecord)
        btnUsb = findViewById(R.id.btnUsb)
        tvUsbStatus = findViewById(R.id.tvUsbStatus)
        tvLeftDistance = findViewById(R.id.tvLeftDistance)
        tvRightDistance = findViewById(R.id.tvRightDistance)
        tvOvertakeDistance = findViewById(R.id.tvOvertakeDistance)
        etHandlebarWidth = findViewById(R.id.etHandlebarWidth)
        tvGpsStatus = findViewById(R.id.tvGpsStatus)
        mapView = findViewById(R.id.mapView)
        btnToggleMap = findViewById(R.id.btnToggleMap)

        btnToggleHandlebar = findViewById(R.id.btnToggleHandlebar)
        handlebarContent = findViewById(R.id.handlebarContent)

        // Sensor-Toggle
        btnToggleSensor = findViewById(R.id.btnToggleSensor)
        sensorContent = findViewById(R.id.sensorContent)

        btnOpenData = findViewById(R.id.btnOpenData)
        btnExit = findViewById(R.id.btnExit)

        // About
        findViewById<TextView>(R.id.tvAbout).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        // Karte konfigurieren
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(18.0)
        mapView.controller.setCenter(GeoPoint(0.0, 0.0))

        // Original-Tints merken (nur Record & USB)
        recordOriginalTint = btnRecord.backgroundTintList
        usbOriginalTint = btnUsb.backgroundTintList

        // Karte initial ausblenden
        isMapVisible = false
        mapView.visibility = View.GONE
        updateMapToggleUi()

        btnToggleMap.setOnClickListener {
            isMapVisible = !isMapVisible
            mapView.visibility = if (isMapVisible) View.VISIBLE else View.GONE
            updateMapToggleUi()
        }

        // Lenkerbreite-Bereich initial ausgeblendet
        isHandlebarVisible = false
        handlebarContent.visibility = View.GONE
        updateHandlebarToggleUi()

        btnToggleHandlebar.setOnClickListener {
            isHandlebarVisible = !isHandlebarVisible
            handlebarContent.visibility = if (isHandlebarVisible) View.VISIBLE else View.GONE
            updateHandlebarToggleUi()
        }

        // Sensorwerte-Bereich initial ausgeblendet
        isSensorVisible = false
        sensorContent.visibility = View.GONE
        updateSensorToggleUi()

        btnToggleSensor.setOnClickListener {
            isSensorVisible = !isSensorVisible
            sensorContent.visibility = if (isSensorVisible) View.VISIBLE else View.GONE
            updateSensorToggleUi()
        }

        // Neuer Button -> Unterseite Daten/Upload/Fahrten
        btnOpenData.setOnClickListener {
            startActivity(Intent(this, DataActivity::class.java))
        }

        // App beenden
        btnExit.setOnClickListener {
            exitApp()
        }

        // Lenkerbreite
        etHandlebarWidth.setText(loadHandlebarWidthCm().toString())

        // Start/Stop mit EINEM Button
        btnRecord.setOnClickListener {
            if (isRecording) {
                obsService?.stopRecording()
                isRecording = false
            } else {
                // Vor Start: Lenkerbreite in Preferences schreiben,
                // damit Service/Session korrekte Werte haben
                saveHandlebarWidthCm(getHandlebarWidthCm())
                obsService?.startRecording()
                isRecording = true
            }
            updateRecordingUi()
        }

        // USB über Service toggeln
        btnUsb.setOnClickListener {
            val svc = obsService
            if (svc != null) {
                if (svc.isUsbConnected()) {
                    svc.disconnectUsbFromUi()
                } else {
                    svc.requestUsbPermissionFromUi()
                }
            }
        }

        // initial UI
        updateRecordingUi()
        updateGpsStatusNoFix()
        btnUsb.icon = ContextCompat.getDrawable(this, R.drawable.ic_link_connect)
    }

    override fun onStart() {
        super.onStart()
        // An Foreground-Service binden (für UI-Status, Start/Stop, USB-Steuerung)
        bindService(Intent(this, ObsLiteService::class.java), connection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        stopStatusUiUpdates()
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
        // Hier NICHT mehr USB/GPS trennen – das macht der Service selbst
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
                obsService?.ensureLocationUpdates()
            } else {
                updateGpsStatusNoFix()
            }
        }
    }

    // --- Aufnahme-UI ---
    private fun updateRecordingUi() {
        if (isRecording) {
            btnRecord.text = "Aufnahme stoppen"
            btnRecord.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor("#F44336")) // rot

            btnRecord.icon = ContextCompat.getDrawable(this, R.drawable.ic_stop)
        } else {
            btnRecord.text = "Aufnahme starten"
            btnRecord.backgroundTintList = recordOriginalTint

            btnRecord.icon = ContextCompat.getDrawable(this, R.drawable.ic_record)
        }
    }

    // --- Lenker-Toggle-UI ---
    private fun updateHandlebarToggleUi() {
        if (isHandlebarVisible) {
            btnToggleHandlebar.text = "Ausblenden"
            btnToggleHandlebar.icon =
                ContextCompat.getDrawable(this, R.drawable.ic_expand_less)
        } else {
            btnToggleHandlebar.text = "Einblenden"
            btnToggleHandlebar.icon =
                ContextCompat.getDrawable(this, R.drawable.ic_expand_more)
        }
    }

    // --- Sensor-Toggle-UI ---
    private fun updateSensorToggleUi() {
        if (isSensorVisible) {
            btnToggleSensor.text = "Werte ausblenden"
            btnToggleSensor.icon =
                ContextCompat.getDrawable(this, R.drawable.ic_expand_less)
        } else {
            btnToggleSensor.text = "Werte einblenden"
            btnToggleSensor.icon =
                ContextCompat.getDrawable(this, R.drawable.ic_expand_more)
        }
    }

    // --- Map-Toggle-UI ---
    private fun updateMapToggleUi() {
        if (isMapVisible) {
            btnToggleMap.text = "Karte ausblenden"
            btnToggleMap.icon = ContextCompat.getDrawable(this, R.drawable.ic_map)
        } else {
            btnToggleMap.text = "Karte anzeigen"
            btnToggleMap.icon = ContextCompat.getDrawable(this, R.drawable.ic_map)
        }
    }

    // --- GPS/UI-Status (nur Anzeige, echte Updates im Service) ---
    private var lastShown: GeoPoint? = null
    private fun updateMapAndGpsStatus(lat: Double, lon: Double, accuracy: Float) {
        // GPS-Status
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

        // Map nur aktualisieren, wenn sichtbar
        if (mapView.visibility != View.VISIBLE) return

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
    }

    private fun updateGpsStatusNoFix() {
        tvGpsStatus.text = "GPS: keine Daten"
        tvGpsStatus.setTextColor(Color.GRAY)
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

    // --- Location-Permission aus Activity heraus anfragen ---
    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }

    private fun startStatusUiUpdates() {
        uiHandler.removeCallbacks(statusRunnable)
        uiHandler.post(statusRunnable)
    }

    private fun stopStatusUiUpdates() {
        uiHandler.removeCallbacks(statusRunnable)
    }

    // --- App komplett beenden ---
    private fun exitApp() {
        // 1) Aufnahme stoppen, falls aktiv
        if (isRecording) {
            obsService?.stopRecording()
            isRecording = false
            updateRecordingUi()
        }

        // 2) USB-Verbindung über Service trennen
        obsService?.disconnectUsbFromUi()

        // 3) Foreground-Service stoppen
        stopService(Intent(this, ObsLiteService::class.java))

        // 4) Service-Binding lösen
        if (bound) {
            try {
                unbindService(connection)
            } catch (_: Exception) {
            }
            bound = false
        }

        // 5) Activity/Task schließen und aus „Letzte Apps“ entfernen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask()
        } else {
            finishAffinity()
        }
    }

    companion object {
        private const val TAG = "MainActivity_USB"
        private const val PREF_KEY_HANDLEBAR_WIDTH_CM = "handlebar_width_cm"
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
    }
}
