// SensorTab.kt - Hauptansicht für Sensor-Daten (iOS-Style)
package com.example.obsliterecorder.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SensorTab(
    usbConnected: Boolean,
    usbStatusText: String,
    usbDeviceName: String?,
    usbVendorProduct: String?,
    isRecording: Boolean,
    recordingDurationSec: Long = 0L,
    recordingDistanceMeters: Double = 0.0,
    recordingOvertakeCount: Int = 0,
    leftText: String,
    rightText: String,
    overtakeCm: Int?,
    handlebarWidthCm: Int,
    onHandlebarWidthChange: (Int) -> Unit,
    onRecordTap: () -> Unit,
    onInfoTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Sheet-State fuer Sensor-Info
    var showSensorSheet by remember { mutableStateOf(false) }
    // Collapsible states
    var showSideDistances by rememberSaveable { mutableStateOf(false) }
    var showHandlebar by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OBSColors.Background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 18.dp, bottom = 140.dp)
        ) {
            OBSHeader(title = "OBS Recorder", onInfoTap = onInfoTap)

            Spacer(Modifier.height(14.dp))

            // 1) Kompakter Connection-Header (Pill wie iOS)
            CompactConnectionPill(
                connected = usbConnected,
                deviceName = usbDeviceName,
                onClick = { showSensorSheet = true }
            )

            Spacer(Modifier.height(16.dp))

            // 2) Hero-Ueberholabstand (grosse Zahl wie iOS)
            HeroOvertakeCard(
                overtakeCm = overtakeCm,
                connected = usbConnected
            )

            // 3) Live Recording Stats (sichtbar waehrend Aufnahme)
            AnimatedVisibility(
                visible = isRecording,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(150))
            ) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    LiveRecordingStatsBar(
                        durationSec = recordingDurationSec,
                        distanceMeters = recordingDistanceMeters,
                        overtakeCount = recordingOvertakeCount
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // 4) Seitenabstaende (einklappbar wie iOS)
            CollapsibleSideDistancesCard(
                expanded = showSideDistances,
                onToggle = { showSideDistances = !showSideDistances },
                connected = usbConnected,
                leftText = leftText,
                rightText = rightText
            )

            Spacer(Modifier.height(12.dp))

            // 5) Lenkerbreite (einklappbar wie iOS)
            CollapsibleHandlebarCard(
                expanded = showHandlebar,
                onToggle = { showHandlebar = !showHandlebar },
                handlebarWidthCm = handlebarWidthCm,
                onHandlebarWidthChange = onHandlebarWidthChange
            )

            Spacer(Modifier.height(12.dp))
        }

        // Record Button
        OBSPrimaryButton(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 74.dp),
            text = if (isRecording) "Aufnahme stoppen" else "Aufnahme starten",
            enabled = usbConnected,
            isActive = isRecording,
            disabledText = "Sensor nicht verbunden",
            onClick = onRecordTap
        )
    }

    // Sensor-Info Bottom Sheet
    if (showSensorSheet) {
        SensorInfoSheet(
            connected = usbConnected,
            deviceName = usbDeviceName,
            vendorProduct = usbVendorProduct,
            usbStatusText = usbStatusText,
            leftText = leftText,
            rightText = rightText,
            overtakeCm = overtakeCm,
            onDismiss = { showSensorSheet = false }
        )
    }
}

// --- Kompakter Connection-Header (Pill, wie iOS CompactHeaderView) ---

@Composable
private fun CompactConnectionPill(
    connected: Boolean,
    deviceName: String?,
    onClick: () -> Unit
) {
    val dotColor by animateColorAsState(
        if (connected) OBSColors.Good else OBSColors.Gray400,
        label = "pill_dot"
    )
    val statusText = when {
        connected && deviceName != null -> deviceName
        connected -> "Verbunden"
        else -> "Nicht verbunden"
    }

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = OBSColors.CardBackground,
        shadowElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OBSStatusDot(color = dotColor, size = 10)
            Spacer(Modifier.width(10.dp))
            Text(
                statusText,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Details",
                tint = OBSColors.Gray400,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// --- Hero-Ueberholabstand (wie iOS HeroOvertakeView) ---

@Composable
private fun HeroOvertakeCard(
    overtakeCm: Int?,
    connected: Boolean
) {
    val targetValue = overtakeCm ?: 0
    val animatedValue by animateIntAsState(targetValue, label = "hero_value")
    val displayValue = if (!connected || overtakeCm == null) "—" else animatedValue.toString()

    val status = getOvertakeStatus(overtakeCm)
    val barColor by animateColorAsState(
        if (connected) status.color else OBSColors.Gray300,
        label = "hero_bar"
    )

    val targetProgress = if (overtakeCm == null || !connected) 0f
    else (overtakeCm.coerceIn(0, 200) / 200f)
    val animatedProgress by animateFloatAsState(targetProgress, label = "hero_progress")

    OBSCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(8.dp))

            // Grosse Zahl
            Text(
                text = displayValue,
                fontWeight = FontWeight.Bold,
                fontSize = 72.sp,
                color = if (connected) OBSColors.Gray900 else OBSColors.Gray400
            )

            // Label
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (connected && overtakeCm != null) {
                    OBSStatusDot(color = status.color, size = 8)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        status.label,
                        color = status.color,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Text(
                        " · ",
                        color = OBSColors.Gray400,
                        fontSize = 14.sp
                    )
                }
                Text(
                    "Überholabstand",
                    color = OBSColors.Gray500,
                    fontSize = 14.sp
                )
            }

            Spacer(Modifier.height(16.dp))

            // Fortschrittsbalken (0-200cm Skala)
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(99.dp)),
                color = barColor,
                trackColor = OBSColors.Gray200,
            )

            Spacer(Modifier.height(4.dp))

            // Skala-Labels
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("0", color = OBSColors.Gray400, fontSize = 11.sp)
                Text("100", color = OBSColors.Gray400, fontSize = 11.sp)
                Text("200 cm", color = OBSColors.Gray400, fontSize = 11.sp)
            }

            Spacer(Modifier.height(4.dp))
        }
    }
}

// --- Live Recording Stats (wie iOS LiveRecordingStatsView) ---

@Composable
private fun LiveRecordingStatsBar(
    durationSec: Long,
    distanceMeters: Double,
    overtakeCount: Int
) {
    val hours = durationSec / 3600
    val minutes = (durationSec % 3600) / 60
    val seconds = durationSec % 60
    val timeStr = if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
    val distanceKm = distanceMeters / 1000.0
    val distanceStr = String.format("%.2f", distanceKm)

    // Pulsierender REC-Punkt
    val infiniteTransition = rememberInfiniteTransition(label = "rec_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rec_alpha"
    )

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = OBSColors.CardBackground,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // REC + Timer
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier
                        .size(10.dp)
                        .alpha(pulseAlpha),
                    shape = CircleShape,
                    color = OBSColors.Danger
                ) {}
                Spacer(Modifier.width(6.dp))
                Text(
                    "REC",
                    color = OBSColors.Danger,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    timeStr,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }

            // Distanz
            Text(
                "$distanceStr km",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = OBSColors.Gray700
            )

            // Ueberholungen
            Text(
                "$overtakeCount",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = OBSColors.Gray700
            )
        }
    }
}

// --- Einklappbare Seitenabstaende (wie iOS SideDistancesCard) ---

@Composable
private fun CollapsibleSideDistancesCard(
    expanded: Boolean,
    onToggle: () -> Unit,
    connected: Boolean,
    leftText: String,
    rightText: String
) {
    val chevronRotation by animateFloatAsState(
        if (expanded) 180f else 0f,
        label = "chevron_side"
    )

    OBSCard {
        Column(modifier = Modifier.animateContentSize()) {
            // Header (immer sichtbar, klickbar)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Seitenabstände",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Einklappen" else "Ausklappen",
                    tint = OBSColors.Gray400,
                    modifier = Modifier
                        .size(24.dp)
                        .rotate(chevronRotation)
                )
            }

            // Content (einklappbar)
            if (expanded) {
                Spacer(Modifier.height(12.dp))

                if (connected) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        DistanceColumn(
                            title = "Links",
                            text = leftText,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(18.dp))
                        DistanceColumn(
                            title = "Rechts",
                            text = rightText,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Text("Sensor nicht verbunden.", color = OBSColors.Gray500)
                }
            }
        }
    }
}

// --- Einklappbare Lenkerbreite (wie iOS CollapsibleHandlebarView) ---

@Composable
private fun CollapsibleHandlebarCard(
    expanded: Boolean,
    onToggle: () -> Unit,
    handlebarWidthCm: Int,
    onHandlebarWidthChange: (Int) -> Unit
) {
    val chevronRotation by animateFloatAsState(
        if (expanded) 180f else 0f,
        label = "chevron_bar"
    )

    OBSCard {
        Column(modifier = Modifier.animateContentSize()) {
            // Header (immer sichtbar)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.DirectionsBike,
                    contentDescription = null,
                    tint = OBSColors.Gray500,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Lenkerbreite",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "$handlebarWidthCm cm",
                    fontWeight = FontWeight.SemiBold,
                    color = OBSColors.Gray700,
                    fontSize = 15.sp
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Einklappen" else "Ausklappen",
                    tint = OBSColors.Gray400,
                    modifier = Modifier
                        .size(24.dp)
                        .rotate(chevronRotation)
                )
            }

            // Expanded content
            if (expanded) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = OBSColors.CardBorder)
                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$handlebarWidthCm",
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("cm", color = OBSColors.Gray500)
                    Spacer(Modifier.weight(1f))

                    OBSStepper(
                        onMinus = { onHandlebarWidthChange(handlebarWidthCm - 1) },
                        onPlus = { onHandlebarWidthChange(handlebarWidthCm + 1) }
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "Der halbe Lenkerwert wird vom Rohabstand abgezogen, um den tatsächlichen Überholabstand zu berechnen.",
                    color = OBSColors.Gray500,
                    fontSize = 13.sp
                )
            }
        }
    }
}

// --- Sensor Info Bottom Sheet (wie iOS SensorInfoSheet) ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SensorInfoSheet(
    connected: Boolean,
    deviceName: String?,
    vendorProduct: String?,
    usbStatusText: String,
    leftText: String,
    rightText: String,
    overtakeCm: Int?,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = OBSColors.Background,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            // Verbindungsstatus
            Text(
                "Verbindungsstatus",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            Spacer(Modifier.height(12.dp))

            OBSCard {
                Row(verticalAlignment = Alignment.Top) {
                    val dotColor by animateColorAsState(
                        if (connected) OBSColors.Good else OBSColors.Gray400,
                        label = "sheet_dot"
                    )
                    OBSStatusDot(color = dotColor, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.width(10.dp))

                    Column {
                        Text(
                            if (connected) "Verbunden" else "Nicht verbunden",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                        Spacer(Modifier.height(4.dp))

                        if (connected && deviceName != null) {
                            Text(deviceName, color = OBSColors.Gray700)
                        }
                        if (connected && vendorProduct != null) {
                            Text(vendorProduct, color = OBSColors.Gray500, fontSize = 13.sp)
                        }

                        Spacer(Modifier.height(4.dp))
                        Text(usbStatusText, color = OBSColors.Gray500, fontSize = 13.sp)
                    }
                }
            }

            // Aktuelle Messwerte (nur wenn verbunden)
            if (connected) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Aktuelle Messwerte",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Spacer(Modifier.height(12.dp))

                OBSCard {
                    val leftCorrected = extractCorrectedFromText(leftText)
                    val leftRaw = extractRawFromText(leftText)
                    val rightCorrected = extractCorrectedFromText(rightText)
                    val rightRaw = extractRawFromText(rightText)

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (overtakeCm != null) {
                            SheetValueRow("Überholabstand", "${overtakeCm} cm")
                        }
                        SheetValueRow(
                            "Links (korrigiert)",
                            if (leftCorrected != null) "$leftCorrected cm" else "–"
                        )
                        SheetValueRow(
                            "Links (roh)",
                            if (leftRaw != null) "$leftRaw cm" else "–"
                        )
                        SheetValueRow(
                            "Rechts (korrigiert)",
                            if (rightCorrected != null) "$rightCorrected cm" else "–"
                        )
                        SheetValueRow(
                            "Rechts (roh)",
                            if (rightRaw != null) "$rightRaw cm" else "–"
                        )
                    }
                }
            }

            // Hinweis bei Problemen
            if (!connected) {
                Spacer(Modifier.height(16.dp))
                OBSCard {
                    Text("Verbindungsprobleme?", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "1. Stelle sicher, dass der OBS Lite per USB angeschlossen ist.\n" +
                                "2. Erlaube den USB-Zugriff wenn angefragt.\n" +
                                "3. Starte die App ggf. neu.",
                        color = OBSColors.Gray500,
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = OBSColors.Gray500, fontSize = 14.sp)
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

// --- Distance Column (unveraendert) ---

@Composable
private fun DistanceColumn(
    title: String,
    text: String,
    modifier: Modifier = Modifier
) {
    val correctedCm = extractCorrectedFromText(text)
    val rawCm = extractRawFromText(text)

    val targetCm = correctedCm ?: 0
    val animatedCm by animateIntAsState(targetCm, label = "cm_anim")

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)

        val value = if (correctedCm == null) "–" else animatedCm.toString()
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Spacer(Modifier.width(6.dp))
            Text("cm", color = OBSColors.Gray500)
        }

        OBSDistanceProgressBar(valueCm = correctedCm)

        val rawLine = if (rawCm != null) "Roh: $rawCm cm" else "Roh: –"
        Text(
            text = rawLine,
            color = OBSColors.Gray500,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 12.sp
        )
    }
}

// --- Helper functions ---
private fun extractCorrectedFromText(text: String): Int? {
    val regex = Regex("""korrigiert:\s*(\d+)\s*cm""")
    return regex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
}

private fun extractRawFromText(text: String): Int? {
    val regex = Regex("""Roh:\s*(\d+)\s*cm""")
    return regex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
}
