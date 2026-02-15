// MainActivity.kt - Tab-Host für die App (wie iOS)
package com.example.obsliterecorder

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.obsliterecorder.obslite.ObsLiteService
import com.example.obsliterecorder.portal.PortalApiClient
import com.example.obsliterecorder.portal.PortalLoginActivity
import com.example.obsliterecorder.portal.PortalTrackSummary
import com.example.obsliterecorder.portal.PortalTracksResult
import androidx.core.content.FileProvider
import com.example.obsliterecorder.ui.*
import com.example.obsliterecorder.util.PrefsHelper
import com.example.obsliterecorder.util.SessionStats
import com.example.obsliterecorder.util.TotalStats
import java.io.File

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private var obsService: ObsLiteService? = null
    private var bound = false

    private val prefs: PrefsHelper by lazy { PrefsHelper(this) }
    private val obsUploader = ObsUploader()

    // --- Service state (polled) ---
    private var usbConnected by mutableStateOf(false)
    private var usbStatusText by mutableStateOf("USB: nicht verbunden")

    private var leftDistanceText by mutableStateOf("Links: -")
    private var rightDistanceText by mutableStateOf("Rechts: -")
    private var overtakeDistanceText by mutableStateOf("Überholabstand: -")
    private var overtakeDistanceCm by mutableStateOf<Int?>(null)

    private var isRecording by mutableStateOf(false)
    private var handlebarWidthCm by mutableIntStateOf(60)

    // --- Device Info ---
    private var usbDeviceName by mutableStateOf<String?>(null)
    private var usbVendorProduct by mutableStateOf<String?>(null)

    // --- Live Recording Stats ---
    private var recordingDurationSec by mutableStateOf(0L)
    private var recordingDistanceMeters by mutableStateOf(0.0)
    private var recordingOvertakeCount by mutableIntStateOf(0)

    // --- Location state ---
    private var currentLatitude by mutableStateOf<Double?>(null)
    private var currentLongitude by mutableStateOf<Double?>(null)

    // --- Map state ---
    private var overtakeEvents = mutableStateListOf<OvertakeEvent>()
    private var lastOvertakeEvent by mutableStateOf<OvertakeEvent?>(null)

    // --- Recordings state ---
    private var obsUrl by mutableStateOf("")
    private var apiKey by mutableStateOf("")
    private var binFiles by mutableStateOf<List<File>>(emptyList())
    private var selectedBinFile by mutableStateOf<File?>(null)
    private var uploadStatus by mutableStateOf("Noch kein Upload ausgeführt.")
    private var isUploading by mutableStateOf(false)
    private var recordings by mutableStateOf<List<File>>(emptyList())

    // --- Portal Tracks state ---
    private var portalTracks by mutableStateOf<List<PortalTrackSummary>>(emptyList())
    private var isLoadingPortalTracks by mutableStateOf(false)
    private var portalError by mutableStateOf<String?>(null)
    private var selectedPortalTrack by mutableStateOf<PortalTrackSummary?>(null)

    // --- Stats ---
    private val sessionStats: SessionStats by lazy { SessionStats(this) }
    private var totalStats by mutableStateOf<TotalStats?>(null)

    // --- Upload Progress ---
    private var uploadProgress by mutableStateOf(0f)

    // --- Local Track Detail ---
    private var selectedLocalFile by mutableStateOf<File?>(null)

    // --- Dialogs ---
    private var showDeleteSingleFor by mutableStateOf<File?>(null)
    private var showDeleteAllDialog by mutableStateOf(false)
    private var showSaveToast by mutableStateOf(false)
    private var showSettingsSaved by mutableStateOf(false)

    // --- Portal Login ---
    private val portalLoginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                // Nach erfolgreichem Login Tracks laden
                loadPortalTracks()
            }
        }

    private val requestLocationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                obsService?.ensureLocationUpdates()
            } else {
                Log.w(TAG, "Location permission denied")
            }
        }

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.d(TAG, "Notification permission granted=$granted")
        }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ObsLiteService.LocalBinder
            obsService = binder.getService()
            bound = true

            isRecording = obsService?.isRecordingActive() ?: false
            handlebarWidthCm = prefs.handlebarWidthCm

            if (hasLocationPermission()) {
                obsService?.ensureLocationUpdates()
            } else {
                requestLocationPermission()
            }

            startStatusUiUpdates()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            obsService = null
            stopStatusUiUpdates()
        }
    }

    private val uiHandler = Handler(Looper.getMainLooper())
    private var lastOvertakeCmForEvent: Int? = null

    private val statusRunnable = object : Runnable {
        override fun run() {
            val svc = obsService
            if (bound && svc != null) {
                try {
                    usbConnected = svc.isUsbConnected()
                    usbStatusText = svc.getUsbStatus()

                    val wasRecording = isRecording
                    isRecording = svc.isRecordingActive()

                    // Show save toast when recording stops
                    if (wasRecording && !isRecording) {
                        showSaveToast = true
                        uiHandler.postDelayed({ showSaveToast = false }, 2000)
                        refreshFiles()
                    }

                    leftDistanceText = svc.getLeftDistanceText()
                    rightDistanceText = svc.getRightDistanceText()
                    overtakeDistanceText = svc.getOvertakeDistanceText()
                    overtakeDistanceCm = extractCmNumber(overtakeDistanceText)

                    usbDeviceName = svc.getUsbDeviceName()
                    usbVendorProduct = svc.getUsbVendorProduct()

                    // Live Recording Stats
                    if (isRecording) {
                        val startMs = svc.getRecordingStartTimeMs()
                        recordingDurationSec = if (startMs > 0) (System.currentTimeMillis() - startMs) / 1000 else 0
                        recordingDistanceMeters = svc.getCurrentDistanceMeters()
                        recordingOvertakeCount = svc.getCurrentOvertakeCount()
                    } else {
                        recordingDurationSec = 0L
                        recordingDistanceMeters = 0.0
                        recordingOvertakeCount = 0
                    }

                    // Update location
                    val loc = svc.getLastLocation()
                    if (loc != null) {
                        currentLatitude = loc.latitude
                        currentLongitude = loc.longitude

                        // Detect new overtake event (when value changes significantly)
                        val newCm = overtakeDistanceCm
                        if (newCm != null && newCm != lastOvertakeCmForEvent && isRecording) {
                            // Only add if it's a meaningful measurement
                            if (newCm < 200) {
                                val event = OvertakeEvent(
                                    latitude = loc.latitude,
                                    longitude = loc.longitude,
                                    distanceCm = newCm
                                )
                                overtakeEvents.add(event)
                                lastOvertakeEvent = event

                                // Limit to 500 events
                                if (overtakeEvents.size > 500) {
                                    overtakeEvents.removeAt(0)
                                }
                            }
                            lastOvertakeCmForEvent = newCm
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "statusRunnable(): UI update error", t)
                }
            }
            uiHandler.postDelayed(this, 200L) // ~5 Hz polling for smooth UI updates
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Load prefs
        handlebarWidthCm = prefs.handlebarWidthCm
        obsUrl = prefs.obsUrl
        apiKey = prefs.apiKey

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Start service
        ContextCompat.startForegroundService(this, Intent(this, ObsLiteService::class.java))

        // Load files and stats
        refreshFiles()
        refreshStats()

        setContent {
            MaterialTheme {
                var selectedTab by rememberSaveable { mutableIntStateOf(0) }

                // Show detail screens
                val currentTrack = selectedPortalTrack
                val currentLocalFile = selectedLocalFile
                if (currentTrack != null) {
                    PortalTrackDetailScreen(
                        track = currentTrack,
                        baseUrl = obsUrl,
                        apiKey = apiKey,
                        onBack = { selectedPortalTrack = null }
                    )
                } else if (currentLocalFile != null) {
                    LocalTrackDetailScreen(
                        file = currentLocalFile,
                        onBack = { selectedLocalFile = null }
                    )
                } else {
                    MainScreen(
                        selectedTab = selectedTab,
                        onSelectTab = { selectedTab = it },

                        // Sensor Tab
                        usbConnected = usbConnected,
                        usbStatusText = usbStatusText,
                        usbDeviceName = usbDeviceName,
                        usbVendorProduct = usbVendorProduct,
                        isRecording = isRecording,
                        recordingDurationSec = recordingDurationSec,
                        recordingDistanceMeters = recordingDistanceMeters,
                        recordingOvertakeCount = recordingOvertakeCount,
                        leftText = leftDistanceText,
                        rightText = rightDistanceText,
                        overtakeCm = overtakeDistanceCm,
                        handlebarWidthCm = handlebarWidthCm,
                        onHandlebarWidthChange = { newValue ->
                            handlebarWidthCm = newValue.coerceIn(
                                PrefsHelper.MIN_HANDLEBAR_WIDTH_CM,
                                PrefsHelper.MAX_HANDLEBAR_WIDTH_CM
                            )
                            prefs.handlebarWidthCm = handlebarWidthCm
                        },
                        onRecordTap = { handleRecordTap() },
                        onInfoTap = { startActivity(Intent(this, AboutActivity::class.java)) },

                        // Map Tab
                        overtakeEvents = overtakeEvents,
                        lastOvertakeEvent = lastOvertakeEvent,
                        currentLatitude = currentLatitude,
                        currentLongitude = currentLongitude,
                        onClearEvents = {
                            overtakeEvents.clear()
                            lastOvertakeEvent = null
                        },

                        // Recordings Tab
                        obsUrl = obsUrl,
                        onObsUrlChange = { obsUrl = it },
                        apiKey = apiKey,
                        onApiKeyChange = { apiKey = it },
                        onSaveSettings = { savePortalSettings() },
                        showSettingsSaved = showSettingsSaved,
                        onPortalLogin = { openPortalLogin() },
                        binFiles = binFiles,
                        selectedFile = selectedBinFile,
                        onSelectFile = { selectedBinFile = it },
                        uploadStatus = uploadStatus,
                        isUploading = isUploading,
                        onUpload = { startUpload() },
                        recordings = recordings,
                        onDeleteSingle = { showDeleteSingleFor = it },
                        onDeleteAll = { showDeleteAllDialog = true },
                        onShareFile = { shareFile(it) },
                        onViewFile = { selectedLocalFile = it },
                        onUploadFile = { uploadSpecificFile(it) },
                        uploadProgress = uploadProgress,

                        // Portal Tracks
                        portalTracks = portalTracks,
                        isLoadingPortalTracks = isLoadingPortalTracks,
                        portalError = portalError,
                        onRefreshPortalTracks = { loadPortalTracks() },
                        onPortalTrackClick = { track -> selectedPortalTrack = track },

                        // Stats
                        totalStats = totalStats,
                        getRecordingStats = { fileName -> sessionStats.getStats(fileName) },

                        // Toast
                        showSaveToast = showSaveToast
                    )

                // Dialogs
                showDeleteSingleFor?.let { file ->
                    OBSConfirmDialog(
                        title = "Datei loeschen?",
                        message = "Moechtest du '${file.name}' wirklich loeschen?",
                        confirmText = "Loeschen",
                        destructive = true,
                        onConfirm = {
                            showDeleteSingleFor = null
                            deleteSingle(file)
                        },
                        onDismiss = { showDeleteSingleFor = null }
                    )
                }

                if (showDeleteAllDialog) {
                    OBSConfirmDialog(
                        title = "Alle loeschen?",
                        message = "Moechtest du wirklich alle Aufzeichnungen loeschen?",
                        confirmText = "Alle loeschen",
                        destructive = true,
                        onConfirm = {
                            showDeleteAllDialog = false
                            deleteAll()
                        },
                        onDismiss = { showDeleteAllDialog = false }
                    )
                }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
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

    override fun onPause() {
        super.onPause()
        prefs.handlebarWidthCm = handlebarWidthCm
        prefs.obsUrl = obsUrl
        prefs.apiKey = apiKey
    }

    override fun onResume() {
        super.onResume()
        refreshFiles()
    }

    // --- Recording ---
    private fun handleRecordTap() {
        val svc = obsService ?: return
        if (!usbConnected) return

        if (isRecording) {
            svc.stopRecording()
            isRecording = false
        } else {
            prefs.handlebarWidthCm = handlebarWidthCm
            svc.startRecording()
            isRecording = true
        }
    }

    // --- Files ---
    private fun refreshFiles() {
        val dir = File(getExternalFilesDir(null), "obslite")
        val files = dir.listFiles { _, name -> name.endsWith(".bin") }
            ?.sortedBy { it.lastModified() } ?: emptyList()

        binFiles = files
        if (selectedBinFile == null || selectedBinFile?.exists() != true) {
            selectedBinFile = files.lastOrNull()
        }

        recordings = dir.listFiles { f -> f.isFile && f.name.endsWith(".bin") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    // --- Upload ---
    private fun uploadSpecificFile(file: File) {
        val url = obsUrl.trim()
        val key = apiKey.trim()

        if (url.isBlank() || key.isBlank()) {
            Toast.makeText(this, "Bitte URL und API-Key ausfüllen.", Toast.LENGTH_SHORT).show()
            return
        }

        if (!file.exists()) {
            Toast.makeText(this, "Datei existiert nicht.", Toast.LENGTH_SHORT).show()
            return
        }

        isUploading = true
        uploadProgress = 0f
        uploadStatus = "Upload läuft: ${file.name}"

        Thread {
            try {
                val result = obsUploader.uploadTrack(file, url, key) { progress ->
                    runOnUiThread { uploadProgress = progress }
                }
                runOnUiThread {
                    isUploading = false
                    uploadProgress = 1f
                    uploadStatus = if (result.isSuccessful) {
                        "Upload OK (${result.statusCode}) – ${file.name}"
                    } else {
                        "Fehler (${result.statusCode}) – ${file.name}\n${result.responseBody}"
                    }
                    Toast.makeText(
                        this,
                        if (result.isSuccessful) "Upload erfolgreich." else "Upload fehlgeschlagen.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    isUploading = false
                    uploadProgress = 0f
                    uploadStatus = "Fehler: ${e.message}"
                    Toast.makeText(this, "Upload-Fehler: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun startUpload() {
        val url = obsUrl.trim()
        val key = apiKey.trim()

        if (url.isBlank() || key.isBlank()) {
            Toast.makeText(this, "Bitte URL und API-Key ausfüllen.", Toast.LENGTH_SHORT).show()
            return
        }

        refreshFiles()
        val file = selectedBinFile
        if (file == null || !file.exists()) {
            Toast.makeText(this, "Keine gültige Datei ausgewählt.", Toast.LENGTH_SHORT).show()
            uploadStatus = "Upload abgebrochen: keine Datei."
            return
        }

        isUploading = true
        uploadProgress = 0f
        uploadStatus = "Upload läuft: ${file.name}"

        Thread {
            try {
                val result = obsUploader.uploadTrack(file, url, key) { progress ->
                    runOnUiThread { uploadProgress = progress }
                }
                runOnUiThread {
                    isUploading = false
                    uploadProgress = 1f
                    uploadStatus = if (result.isSuccessful) {
                        "Upload OK (${result.statusCode}) – ${file.name}"
                    } else {
                        "Fehler (${result.statusCode}) – ${file.name}\n${result.responseBody}"
                    }
                    Toast.makeText(
                        this,
                        if (result.isSuccessful) "Upload erfolgreich." else "Upload fehlgeschlagen.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    isUploading = false
                    uploadProgress = 0f
                    uploadStatus = "Fehler: ${e.message}"
                    Toast.makeText(this, "Upload-Fehler: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // --- Share ---
    private fun shareFile(file: File) {
        val uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Datei teilen"))
    }

    // --- Delete ---
    private fun deleteSingle(file: File) {
        val fileName = file.name
        val ok = runCatching { file.delete() }.getOrDefault(false)
        if (ok) {
            sessionStats.deleteStats(fileName)
        }
        Toast.makeText(
            this,
            if (ok) "Datei geloescht." else "Loeschen fehlgeschlagen.",
            Toast.LENGTH_SHORT
        ).show()
        refreshFiles()
        refreshStats()
    }

    private fun deleteAll() {
        if (recordings.isEmpty()) {
            Toast.makeText(this, "Keine Aufzeichnungen vorhanden.", Toast.LENGTH_SHORT).show()
            return
        }

        var okCount = 0
        recordings.forEach { f ->
            if (runCatching { f.delete() }.getOrDefault(false)) okCount++
        }

        refreshFiles()
        refreshStats()
        Toast.makeText(this, "$okCount Dateien geloescht.", Toast.LENGTH_SHORT).show()
    }

    // --- Portal Tracks ---
    private fun loadPortalTracks() {
        if (obsUrl.isBlank() || apiKey.isBlank()) {
            portalError = "Portal nicht konfiguriert"
            return
        }

        isLoadingPortalTracks = true
        portalError = null

        Thread {
            val client = PortalApiClient(obsUrl, apiKey)
            val result = client.fetchMyTracks(limit = 50)

            runOnUiThread {
                isLoadingPortalTracks = false
                when (result) {
                    is PortalTracksResult.Success -> {
                        portalTracks = result.tracks
                        portalError = null
                    }
                    is PortalTracksResult.Error -> {
                        portalError = result.message
                    }
                }
            }
        }.start()
    }

    // --- Stats ---
    private fun refreshStats() {
        totalStats = sessionStats.getTotalStats()
    }

    // --- Portal Settings ---
    private fun savePortalSettings() {
        prefs.obsUrl = obsUrl
        prefs.apiKey = apiKey

        // Feedback anzeigen
        showSettingsSaved = true
        uiHandler.postDelayed({ showSettingsSaved = false }, 2000)

        Toast.makeText(this, "Einstellungen gespeichert", Toast.LENGTH_SHORT).show()
    }

    private fun openPortalLogin() {
        if (obsUrl.isBlank()) {
            Toast.makeText(this, "Bitte zuerst Portal-URL eingeben", Toast.LENGTH_SHORT).show()
            return
        }

        // Einstellungen vor dem Login speichern
        prefs.obsUrl = obsUrl
        prefs.apiKey = apiKey

        val intent = Intent(this, PortalLoginActivity::class.java)
        intent.putExtra(PortalLoginActivity.EXTRA_BASE_URL, obsUrl)
        portalLoginLauncher.launch(intent)
    }

    // --- Permissions ---
    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    private fun requestLocationPermission() {
        requestLocationPermissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
    }

    // --- UI Updates ---
    private fun startStatusUiUpdates() {
        uiHandler.removeCallbacks(statusRunnable)
        uiHandler.post(statusRunnable)
    }

    private fun stopStatusUiUpdates() {
        uiHandler.removeCallbacks(statusRunnable)
    }

    private fun extractCmNumber(text: String): Int? {
        val regex = Regex("""(\d+)\s*cm""")
        return regex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }
}

/**
 * Haupt-Screen mit Tab-Navigation
 */
@Composable
private fun MainScreen(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,

    // Sensor
    usbConnected: Boolean,
    usbStatusText: String,
    usbDeviceName: String?,
    usbVendorProduct: String?,
    isRecording: Boolean,
    recordingDurationSec: Long,
    recordingDistanceMeters: Double,
    recordingOvertakeCount: Int,
    leftText: String,
    rightText: String,
    overtakeCm: Int?,
    handlebarWidthCm: Int,
    onHandlebarWidthChange: (Int) -> Unit,
    onRecordTap: () -> Unit,
    onInfoTap: () -> Unit,

    // Map
    overtakeEvents: List<OvertakeEvent>,
    lastOvertakeEvent: OvertakeEvent?,
    currentLatitude: Double?,
    currentLongitude: Double?,
    onClearEvents: () -> Unit,

    // Recordings
    obsUrl: String,
    onObsUrlChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    onSaveSettings: () -> Unit,
    showSettingsSaved: Boolean,
    onPortalLogin: () -> Unit,
    binFiles: List<File>,
    selectedFile: File?,
    onSelectFile: (File) -> Unit,
    uploadStatus: String,
    isUploading: Boolean,
    uploadProgress: Float,
    onUpload: () -> Unit,
    recordings: List<File>,
    onDeleteSingle: (File) -> Unit,
    onDeleteAll: () -> Unit,
    onShareFile: (File) -> Unit,
    onViewFile: (File) -> Unit,
    onUploadFile: (File) -> Unit,

    // Portal Tracks
    portalTracks: List<PortalTrackSummary>,
    isLoadingPortalTracks: Boolean,
    portalError: String?,
    onRefreshPortalTracks: () -> Unit,
    onPortalTrackClick: (PortalTrackSummary) -> Unit,

    // Stats
    totalStats: TotalStats?,
    getRecordingStats: (String) -> com.example.obsliterecorder.util.RecordingStats?,

    // Toast
    showSaveToast: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OBSColors.Background)
    ) {
        // Tab Content
        when (selectedTab) {
            0 -> SensorTab(
                usbConnected = usbConnected,
                usbStatusText = usbStatusText,
                usbDeviceName = usbDeviceName,
                usbVendorProduct = usbVendorProduct,
                isRecording = isRecording,
                recordingDurationSec = recordingDurationSec,
                recordingDistanceMeters = recordingDistanceMeters,
                recordingOvertakeCount = recordingOvertakeCount,
                leftText = leftText,
                rightText = rightText,
                overtakeCm = overtakeCm,
                handlebarWidthCm = handlebarWidthCm,
                onHandlebarWidthChange = onHandlebarWidthChange,
                onRecordTap = onRecordTap,
                onInfoTap = onInfoTap
            )

            1 -> MapTab(
                overtakeEvents = overtakeEvents,
                lastOvertakeEvent = lastOvertakeEvent,
                currentLatitude = currentLatitude,
                currentLongitude = currentLongitude,
                onClearEvents = onClearEvents
            )

            2 -> RecordingsTab(
                obsUrl = obsUrl,
                onObsUrlChange = onObsUrlChange,
                apiKey = apiKey,
                onApiKeyChange = onApiKeyChange,
                onSaveSettings = onSaveSettings,
                showSettingsSaved = showSettingsSaved,
                onPortalLogin = onPortalLogin,
                binFiles = binFiles,
                selectedFile = selectedFile,
                onSelectFile = onSelectFile,
                uploadStatus = uploadStatus,
                isUploading = isUploading,
                uploadProgress = uploadProgress,
                onUpload = onUpload,
                recordings = recordings,
                onDeleteSingle = onDeleteSingle,
                onDeleteAll = onDeleteAll,
                onShareFile = onShareFile,
                onViewFile = onViewFile,
                onUploadFile = onUploadFile,
                portalTracks = portalTracks,
                isLoadingPortalTracks = isLoadingPortalTracks,
                portalError = portalError,
                onRefreshPortalTracks = onRefreshPortalTracks,
                onPortalTrackClick = onPortalTrackClick,
                totalStats = totalStats,
                getRecordingStats = getRecordingStats
            )
        }

        // Bottom Tab Bar
        OBSBottomTabBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            items = listOf("Sensor", "Karte", "Aufzeichnungen"),
            selectedIndex = selectedTab,
            onSelect = onSelectTab
        )

        // Save Toast
        androidx.compose.animation.AnimatedVisibility(
            visible = showSaveToast,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 80.dp),
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut()
        ) {
            androidx.compose.material3.Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                color = OBSColors.Good,
                shadowElevation = 4.dp
            ) {
                androidx.compose.material3.Text(
                    "Aufnahme gespeichert",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    color = androidx.compose.ui.graphics.Color.White,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
            }
        }
    }
}
