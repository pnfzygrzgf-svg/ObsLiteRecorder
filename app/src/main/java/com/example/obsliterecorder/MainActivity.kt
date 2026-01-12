// MainActivity.kt
package com.example.obsliterecorder

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.obsliterecorder.obslite.ObsLiteService

class MainActivity : ComponentActivity() {

    private var obsService: ObsLiteService? = null
    private var bound = false

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("obslite_prefs", MODE_PRIVATE)
    }

    // Service state (polled)
    private var usbConnected by mutableStateOf(false)
    private var usbStatusText by mutableStateOf("USB: nicht verbunden")

    private var leftDistanceText by mutableStateOf("Links: -")
    private var rightDistanceText by mutableStateOf("Rechts: -")
    private var overtakeDistanceText by mutableStateOf("Überholabstand: -")
    private var overtakeDistanceCm by mutableStateOf<Int?>(null)

    private var isRecording by mutableStateOf(false)

    // UI state
    private var handlebarWidthCm by mutableIntStateOf(60)

    private val requestLocationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                obsService?.ensureLocationUpdates()
            } else {
                Log.w(TAG, "Location permission denied - recording will have no GPS.")
            }
        }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ObsLiteService.LocalBinder
            obsService = binder.getService()
            bound = true

            isRecording = obsService?.isRecordingActive() ?: false
            handlebarWidthCm = loadHandlebarWidthCm()

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
    private val statusRunnable = object : Runnable {
        override fun run() {
            val svc = obsService
            if (bound && svc != null) {
                try {
                    usbConnected = svc.isUsbConnected()
                    usbStatusText = svc.getUsbStatus()
                    isRecording = svc.isRecordingActive()

                    leftDistanceText = svc.getLeftDistanceText()
                    rightDistanceText = svc.getRightDistanceText()

                    overtakeDistanceText = svc.getOvertakeDistanceText()
                    overtakeDistanceCm = extractCmNumber(overtakeDistanceText)
                } catch (t: Throwable) {
                    Log.e(TAG, "statusRunnable(): UI update error", t)
                }
            }
            // Level 1 smoothing happens via animations in UI
            uiHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        ContextCompat.startForegroundService(this, Intent(this, ObsLiteService::class.java))

        setContent {
            MaterialTheme {
                // FIX: device label stable (no "–")
                val deviceLabel = "OBS Lite"

                // FIX: real selected state (tab bar not "fake")
                var selectedIndex by rememberSaveable { mutableIntStateOf(0) }

                IOSLikeRoot(
                    usbConnected = usbConnected,
                    usbStatusText = usbStatusText,
                    isRecording = isRecording,
                    deviceLabel = deviceLabel,
                    leftText = leftDistanceText,
                    rightText = rightDistanceText,
                    overtakeCm = overtakeDistanceCm,
                    handlebarWidthCm = handlebarWidthCm,
                    onHandlebarWidthChange = { newValue ->
                        handlebarWidthCm = newValue.coerceIn(30, 120)
                        saveHandlebarWidthCm(handlebarWidthCm)
                    },
                    selectedIndex = selectedIndex,
                    onSelectTab = { idx ->
                        selectedIndex = idx
                        if (idx == 1) startActivity(Intent(this, DataActivity::class.java))
                    },
                    onInfoTap = { startActivity(Intent(this, AboutActivity::class.java)) },
                    onRecordTap = { handleRecordTap() }
                )
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
        saveHandlebarWidthCm(handlebarWidthCm)
    }

    private fun handleRecordTap() {
        val svc = obsService ?: return
        if (!usbConnected) return

        if (isRecording) {
            svc.stopRecording()
            isRecording = false
        } else {
            saveHandlebarWidthCm(handlebarWidthCm)
            svc.startRecording()
            isRecording = true
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    private fun requestLocationPermission() {
        requestLocationPermissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun startStatusUiUpdates() {
        uiHandler.removeCallbacks(statusRunnable)
        uiHandler.post(statusRunnable)
    }

    private fun stopStatusUiUpdates() {
        uiHandler.removeCallbacks(statusRunnable)
    }

    private fun loadHandlebarWidthCm(): Int = prefs.getInt(PREF_KEY_HANDLEBAR_WIDTH_CM, 60)

    private fun saveHandlebarWidthCm(widthCm: Int) {
        prefs.edit().putInt(PREF_KEY_HANDLEBAR_WIDTH_CM, widthCm).apply()
    }

    private fun extractCmNumber(text: String): Int? {
        val regex = Regex("""(\d+)\s*cm""")
        val m = regex.find(text) ?: return null
        return m.groupValues.getOrNull(1)?.toIntOrNull()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val PREF_KEY_HANDLEBAR_WIDTH_CM = "handlebar_width_cm"
    }
}

@Composable
private fun IOSLikeRoot(
    usbConnected: Boolean,
    usbStatusText: String,
    isRecording: Boolean,
    deviceLabel: String,
    leftText: String,
    rightText: String,
    overtakeCm: Int?,
    handlebarWidthCm: Int,
    onHandlebarWidthChange: (Int) -> Unit,
    selectedIndex: Int,
    onSelectTab: (Int) -> Unit,
    onInfoTap: () -> Unit,
    onRecordTap: () -> Unit
) {
    val bg = Color(0xFFF2F2F7)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 18.dp, bottom = 140.dp)
        ) {
            HeaderIOS(title = "OBS Recorder", onInfoTap = onInfoTap)

            // Reduced "air"
            Spacer(Modifier.height(14.dp))

            StatusCardIOS(
                deviceLabel = deviceLabel,
                connected = usbConnected,
                usbText = usbStatusText
            )

            Spacer(Modifier.height(12.dp))

            // No toggle; show values if connected, otherwise hint (smooth)
            SensorCardIOS(
                connected = usbConnected,
                leftText = leftText,
                rightText = rightText,
                overtakeCm = overtakeCm
            )

            Spacer(Modifier.height(12.dp))

            HandlebarCardIOS(
                handlebarWidthCm = handlebarWidthCm,
                onHandlebarWidthChange = onHandlebarWidthChange
            )

            Spacer(Modifier.height(12.dp))
        }

        RecordPillIOS(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 74.dp),
            enabled = usbConnected,
            isRecording = isRecording,
            onTap = onRecordTap
        )

        BottomTabBarIOS(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp),
            selectedIndex = selectedIndex,
            onSelect = onSelectTab
        )
    }
}

@Composable
private fun HeaderIOS(
    title: String,
    onInfoTap: () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            modifier = Modifier.align(Alignment.Center),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Surface(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(36.dp),
            shape = CircleShape,
            color = Color.White,
            tonalElevation = 1.dp,
            shadowElevation = 2.dp
        ) {
            TextButton(onClick = onInfoTap) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = "Info",
                    tint = Color(0xFF0A84FF)
                )
            }
        }
    }
}

@Composable
private fun StatusCardIOS(
    deviceLabel: String,
    connected: Boolean,
    usbText: String
) {
    // Connection colors are neutral (no orange warning semantics)
    val dotTarget = if (connected) Color(0xFF34C759) else Color(0xFF9CA3AF)
    val dotColor by animateColorAsState(dotTarget, label = "conn_dot")

    val stateLabel = if (connected) "Verbunden" else "Nicht verbunden"
    val pillBg = if (connected) Color(0xFFE8F7EE) else Color(0xFFF3F4F6)
    val pillFg = if (connected) Color(0xFF1B7A3C) else Color(0xFF374151)

    CardIOS {
        Column(modifier = Modifier.animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Gerät", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = deviceLabel,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF111111)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = pillBg
                ) {
                    Text(
                        text = stateLabel,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        color = pillFg,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Divider(color = Color(0xFFE5E7EB))
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.Top) {
                DotIcon(color = dotColor)
                Spacer(Modifier.width(10.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = if (connected) "Mit Sensor verbunden" else "Keine Sensorverbindung",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (connected) "Messwerte werden empfangen." else "Bitte OBS Lite verbinden.",
                        color = Color(0xFF6B7280)
                    )

                    Spacer(Modifier.height(6.dp))
                    Text(usbText, color = Color(0xFF6B7280))
                }
            }
        }
    }
}

@Composable
private fun SensorCardIOS(
    connected: Boolean,
    leftText: String,
    rightText: String,
    overtakeCm: Int?
) {
    CardIOS {
        Column(modifier = Modifier.animateContentSize()) {
            Text("Sensorwerte", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))

            // Option B (stable): AnimatedVisibility instead of AnimatedContent
            AnimatedVisibility(
                visible = connected,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(120))
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        DistanceColumnIOS(
                            title = "Abstand links",
                            text = leftText,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(18.dp))
                        DistanceColumnIOS(
                            title = "Abstand rechts",
                            text = rightText,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = !connected,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(120))
            ) {
                Text("Sensor nicht verbunden.", color = Color(0xFF6B7280))
            }

            Spacer(Modifier.height(12.dp))
            Divider(color = Color(0xFFE5E7EB))
            Spacer(Modifier.height(12.dp))

            OvertakeKpiRow(overtakeCm)
        }
    }
}

private data class StatusLabel(val label: String, val color: Color)

private fun overtakeStatus(cm: Int?): StatusLabel = when {
    cm == null -> StatusLabel("–", Color(0xFF9CA3AF))
    cm >= 150 -> StatusLabel("OK", Color(0xFF34C759))
    cm >= 100 -> StatusLabel("Knapp", Color(0xFFFFCC00))
    else -> StatusLabel("Gefährlich", Color(0xFFFF3B30))
}

@Composable
private fun OvertakeKpiRow(overtakeCm: Int?) {
    Text("Überholabstand", fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))

    val status = overtakeStatus(overtakeCm)
    val animatedDot by animateColorAsState(status.color, label = "overtake_dot")

    // Level 1 smoothing: animate number changes
    val targetValue = overtakeCm ?: 0
    val animatedValue by animateIntAsState(targetValue = targetValue, label = "overtake_value")

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics {
            stateDescription = when (status.label) {
                "OK" -> "Überholabstand ok"
                "Knapp" -> "Überholabstand knapp"
                "Gefährlich" -> "Überholabstand gefährlich"
                else -> "Überholabstand unbekannt"
            }
        }
    ) {
        Surface(
            modifier = Modifier.size(10.dp),
            shape = CircleShape,
            color = animatedDot
        ) {}
        Spacer(Modifier.width(8.dp))

        Text(status.label, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(10.dp))

        val showValue = if (overtakeCm == null) "–" else animatedValue.toString()
        Text(showValue, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(6.dp))
        Text("cm", color = Color(0xFF6B7280))
    }
}

@Composable
private fun HandlebarCardIOS(
    handlebarWidthCm: Int,
    onHandlebarWidthChange: (Int) -> Unit
) {
    CardIOS {
        Text("Lenkerbreite", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$handlebarWidthCm", fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Text("cm", color = Color(0xFF6B7280))
            Spacer(Modifier.weight(1f))

            StepperIOS(
                onMinus = { onHandlebarWidthChange(handlebarWidthCm - 1) },
                onPlus = { onHandlebarWidthChange(handlebarWidthCm + 1) }
            )
        }

        Spacer(Modifier.height(8.dp))
        Text("Wird zur Berechnung des Überholabstands verwendet.", color = Color(0xFF6B7280))
    }
}

@Composable
private fun RecordPillIOS(
    modifier: Modifier,
    enabled: Boolean,
    isRecording: Boolean,
    onTap: () -> Unit
) {
    val gradient = if (isRecording) {
        Brush.horizontalGradient(listOf(Color(0xFFFF453A), Color(0xFFFF3B30)))
    } else {
        Brush.horizontalGradient(listOf(Color(0xFF34C759), Color(0xFF30D158)))
    }

    val text = if (isRecording) "Aufnahme stoppen" else "Aufnahme starten"

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .heightIn(min = 58.dp)
            .clip(RoundedCornerShape(18.dp))
            .semantics {
                stateDescription = when {
                    !enabled -> "Sensor nicht verbunden"
                    isRecording -> "Aufnahme läuft"
                    else -> "Bereit zur Aufnahme"
                }
            },
        shadowElevation = if (enabled) 10.dp else 0.dp,
        color = Color.Transparent
    ) {
        Button(
            onClick = onTap,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(gradient, RoundedCornerShape(18.dp))
                .alpha(if (enabled) 1f else 0.55f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(12.dp),
                    shape = CircleShape,
                    color = Color.White
                ) {}

                Spacer(Modifier.width(10.dp))

                Text(
                    text = text,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (!enabled) {
                    Text(
                        "Sensor nicht verbunden",
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomTabBarIOS(
    modifier: Modifier,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    val outer = RoundedCornerShape(999.dp)

    Surface(
        modifier = modifier
            .padding(horizontal = 28.dp)
            .fillMaxWidth(),
        shape = outer,
        color = Color.White.copy(alpha = 0.92f),
        shadowElevation = 12.dp
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            val gap = 8.dp
            val itemWidth = (maxWidth - gap) / 2

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap)
            ) {
                TabPillTextItem(
                    modifier = Modifier.width(itemWidth),
                    selected = (selectedIndex == 0),
                    label = "Sensor",
                    onClick = { onSelect(0) }
                )
                TabPillTextItem(
                    modifier = Modifier.width(itemWidth),
                    selected = (selectedIndex == 1),
                    label = "Aufzeichnungen",
                    onClick = { onSelect(1) }
                )
            }
        }
    }
}

@Composable
private fun TabPillTextItem(
    modifier: Modifier = Modifier,
    selected: Boolean,
    label: String,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(999.dp)
    val bg = if (selected) Color(0xFFE5E7EB) else Color.Transparent
    val fg = if (selected) Color(0xFF111827) else Color(0xFF6B7280)

    Box(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = fg, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun StepperIOS(
    onMinus: () -> Unit,
    onPlus: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFFE5E7EB)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepperButton(text = "–", onClick = onMinus)
            Divider(
                modifier = Modifier
                    .height(26.dp)
                    .width(1.dp),
                color = Color(0xFFD1D5DB)
            )
            StepperButton(text = "+", onClick = onPlus)
        }
    }
}

@Composable
private fun StepperButton(
    text: String,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(text, fontWeight = FontWeight.Bold, color = Color(0xFF111827))
    }
}

@Composable
private fun DistanceColumnIOS(
    title: String,
    text: String,
    modifier: Modifier = Modifier
) {
    // Parse service text: "Links (ID 1): Roh: 123 cm  |  korrigiert: 98 cm"
    val correctedCm = extractCorrectedFromText(text)
    val rawCm = extractRawFromText(text)
    val labelPrefix = extractLabelPrefix(text) // "Links (ID 1)" / "Rechts (ID 2)" ...

    // Level 1 smoothing: animate displayed number + progress (based on corrected)
    val targetCm = correctedCm ?: 0
    val animatedCm by animateIntAsState(targetValue = targetCm, label = "cm_anim")

    val targetProgress = progressForCm(correctedCm)
    val animatedProgress by animateFloatAsState(targetValue = targetProgress, label = "prog_anim")

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)

        // Primary: corrected cm
        val value = if (correctedCm == null) "–" else animatedCm.toString()
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Text("cm", color = Color(0xFF6B7280))
        }

        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(99.dp)),
            progress = animatedProgress,
            color = overtakeColorIOS(correctedCm),
            trackColor = Color(0xFFE5E7EB)
        )

        // Secondary: raw value (short, never ends at "Roh:")
        val rawLine = if (rawCm != null) "Roh: $rawCm cm" else "Roh: –"
        Text(
            text = rawLine,
            color = Color(0xFF6B7280),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Optional: show prefix (ID) in compact form
        if (!labelPrefix.isNullOrBlank()) {
            Text(
                text = labelPrefix,
                color = Color(0xFF6B7280),
                modifier = Modifier.alpha(0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun extractCorrectedFromText(text: String): Int? {
    val regex = Regex("""korrigiert:\s*(\d+)\s*cm""")
    val m = regex.find(text) ?: return null
    return m.groupValues.getOrNull(1)?.toIntOrNull()
}

private fun extractRawFromText(text: String): Int? {
    val regex = Regex("""Roh:\s*(\d+)\s*cm""")
    val m = regex.find(text) ?: return null
    return m.groupValues.getOrNull(1)?.toIntOrNull()
}

private fun extractLabelPrefix(text: String): String? {
    // Everything before ": Roh:" -> "Links (ID 1)" / "Rechts (ID 2)" etc.
    val regex = Regex("""^(.+?):\s*Roh:""")
    val m = regex.find(text) ?: return null
    return m.groupValues.getOrNull(1)?.trim()
}

private fun progressForCm(cm: Int?): Float {
    if (cm == null) return 0f
    val max = 200f
    return (cm.coerceIn(0, 200) / max)
}

private fun overtakeColorIOS(cm: Int?): Color {
    if (cm == null) return Color(0xFF9CA3AF)
    return when {
        cm >= 150 -> Color(0xFF34C759)
        cm >= 100 -> Color(0xFFFFCC00)
        else -> Color(0xFFFF3B30)
    }
}

@Composable
private fun DotIcon(color: Color) {
    Surface(
        modifier = Modifier.size(18.dp),
        shape = CircleShape,
        color = Color.Transparent
    ) {
        Surface(
            modifier = Modifier
                .padding(4.dp)
                .size(10.dp),
            shape = CircleShape,
            color = color
        ) {}
    }
}

@Composable
private fun CardIOS(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}
