// SPDX-License-Identifier: GPL-3.0-or-later

// RecordingsTab.kt - Aufzeichnungen verwalten (iOS-Struktur: Hub + 3 Unterseiten)
package com.example.obsliterecorder.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.obsliterecorder.portal.PortalTrackSummary
import com.example.obsliterecorder.util.RecordingStats
import com.example.obsliterecorder.util.TotalStats
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Navigation-Zustand fuer den Aufzeichnungen-Tab
 */
enum class RecordingsSubPage {
    HUB, LOCAL_FILES, PORTAL_TRACKS, PORTAL_SETTINGS
}

/**
 * Recordings-Tab: Hub mit Statistik + 3 Navigations-Zeilen (wie iOS PortalHomeView)
 */
@Composable
fun RecordingsTab(
    // Portal settings
    obsUrl: String,
    onObsUrlChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    onSaveSettings: () -> Unit = {},
    showSettingsSaved: Boolean = false,

    // Portal Login
    onPortalLogin: () -> Unit = {},

    // Upload state
    binFiles: List<File>,
    selectedFile: File?,
    onSelectFile: (File) -> Unit,
    uploadStatus: String,
    isUploading: Boolean,
    uploadProgress: Float = 0f,
    onUpload: () -> Unit,
    onUploadFile: (File) -> Unit = {},

    // Recordings
    recordings: List<File>,
    onDeleteSingle: (File) -> Unit,
    onDeleteAll: () -> Unit,
    onShareFile: (File) -> Unit = {},
    onViewFile: (File) -> Unit = {},

    // Portal tracks
    portalTracks: List<PortalTrackSummary> = emptyList(),
    isLoadingPortalTracks: Boolean = false,
    portalError: String? = null,
    onRefreshPortalTracks: () -> Unit = {},
    onPortalTrackClick: (PortalTrackSummary) -> Unit = {},

    // Stats
    totalStats: TotalStats? = null,
    getRecordingStats: (String) -> RecordingStats? = { null },

    modifier: Modifier = Modifier
) {
    var subPage by remember { mutableStateOf(RecordingsSubPage.HUB) }

    when (subPage) {
        RecordingsSubPage.HUB -> RecordingsHub(
            totalStats = totalStats,
            recordingsCount = recordings.size,
            portalTracksCount = portalTracks.size,
            hasPortalConfig = obsUrl.isNotBlank() && apiKey.isNotBlank(),
            onNavigateLocal = { subPage = RecordingsSubPage.LOCAL_FILES },
            onNavigatePortal = { subPage = RecordingsSubPage.PORTAL_TRACKS },
            onNavigateSettings = { subPage = RecordingsSubPage.PORTAL_SETTINGS },
            modifier = modifier
        )

        RecordingsSubPage.LOCAL_FILES -> LocalFilesScreen(
            recordings = recordings,
            getRecordingStats = getRecordingStats,
            onBack = { subPage = RecordingsSubPage.HUB },
            onDeleteSingle = onDeleteSingle,
            onDeleteAll = onDeleteAll,
            onShareFile = onShareFile,
            onViewFile = onViewFile,
            onUploadFile = onUploadFile,
            hasPortalConfig = obsUrl.isNotBlank() && apiKey.isNotBlank(),
            modifier = modifier
        )

        RecordingsSubPage.PORTAL_TRACKS -> PortalTracksScreen(
            portalTracks = portalTracks,
            isLoading = isLoadingPortalTracks,
            error = portalError,
            hasConfig = obsUrl.isNotBlank() && apiKey.isNotBlank(),
            onRefresh = onRefreshPortalTracks,
            onTrackClick = onPortalTrackClick,
            onPortalLogin = onPortalLogin,
            onBack = { subPage = RecordingsSubPage.HUB },
            modifier = modifier
        )

        RecordingsSubPage.PORTAL_SETTINGS -> PortalSettingsScreen(
            obsUrl = obsUrl,
            onObsUrlChange = onObsUrlChange,
            apiKey = apiKey,
            onApiKeyChange = onApiKeyChange,
            onSave = onSaveSettings,
            showSaved = showSettingsSaved,
            onLogin = onPortalLogin,
            onBack = { subPage = RecordingsSubPage.HUB },
            modifier = modifier
        )
    }
}

// ===========================================
// Hub (Hauptseite)
// ===========================================

@Composable
private fun RecordingsHub(
    totalStats: TotalStats?,
    recordingsCount: Int,
    portalTracksCount: Int,
    hasPortalConfig: Boolean,
    onNavigateLocal: () -> Unit,
    onNavigatePortal: () -> Unit,
    onNavigateSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(OBSColors.Background)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 18.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { OBSHeader(title = "Aufzeichnungen") }

        // Statistik-Card
        if (totalStats != null && totalStats.sessionCount > 0) {
            item { StatisticsCard(stats = totalStats) }
        }

        // Navigation
        item {
            Text(
                "Aufzeichnungen",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Verwalte lokale Fahrten, Portal-Tracks und Einstellungen.",
                color = OBSColors.Gray500,
                fontSize = 13.sp
            )
        }

        // 3 Navigations-Zeilen (wie iOS OBSRowCardV2)
        item {
            NavRowCard(
                icon = Icons.Default.FolderOpen,
                iconColor = OBSColors.Accent,
                title = "Lokale Fahrten",
                subtitle = "$recordingsCount Dateien",
                onClick = onNavigateLocal
            )
        }

        item {
            NavRowCard(
                icon = Icons.Default.Cloud,
                iconColor = OBSColors.Good,
                title = "Meine Portal-Fahrten",
                subtitle = if (hasPortalConfig) "$portalTracksCount Tracks" else "Nicht konfiguriert",
                onClick = onNavigatePortal
            )
        }

        item {
            NavRowCard(
                icon = Icons.Default.Settings,
                iconColor = OBSColors.Gray500,
                title = "Portal-Einstellungen",
                subtitle = if (hasPortalConfig) "Konfiguriert" else "Nicht eingerichtet",
                onClick = onNavigateSettings
            )
        }
    }
}

@Composable
private fun StatisticsCard(stats: TotalStats) {
    OBSCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.BarChart,
                contentDescription = null,
                tint = OBSColors.Accent,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text("Deine Statistik", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatColumn(
                value = "${stats.sessionCount}",
                label = "Fahrten"
            )
            StatColumn(
                value = "${stats.totalOvertakes}",
                label = "Ueberholungen"
            )
            StatColumn(
                value = String.format(Locale.getDefault(), "%.1f", stats.totalDistanceKm),
                label = "km"
            )
        }
    }
}

@Composable
private fun StatColumn(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            color = OBSColors.Accent
        )
        Text(
            label,
            color = OBSColors.Gray500,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun NavRowCard(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = OBSColors.CardBackground,
        shadowElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = OBSColors.Gray500, fontSize = 13.sp)
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = OBSColors.Gray400,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ===========================================
// Unterseite: Lokale Fahrten
// ===========================================

@Composable
private fun LocalFilesScreen(
    recordings: List<File>,
    getRecordingStats: (String) -> RecordingStats?,
    onBack: () -> Unit,
    onDeleteSingle: (File) -> Unit,
    onDeleteAll: () -> Unit,
    onShareFile: (File) -> Unit,
    onViewFile: (File) -> Unit,
    onUploadFile: (File) -> Unit,
    hasPortalConfig: Boolean,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(OBSColors.Background)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 18.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            OBSHeaderWithBack(
                title = "Lokale Fahrten",
                onBack = onBack,
                backIcon = Icons.AutoMirrored.Filled.KeyboardArrowLeft
            )
        }

        // Header mit Dateianzahl + Alle loeschen
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${recordings.size} Dateien",
                    color = OBSColors.Gray500,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                if (recordings.isNotEmpty()) {
                    TextButton(onClick = onDeleteAll) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = OBSColors.Danger,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Alle loeschen", color = OBSColors.Danger, fontSize = 13.sp)
                    }
                }
            }
        }

        // Portal-Hinweis wenn nicht konfiguriert
        if (!hasPortalConfig) {
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = OBSColors.WarnOrange.copy(alpha = 0.1f)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = OBSColors.WarnOrange,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Portal nicht verbunden. Konfiguriere es in den Einstellungen zum Hochladen.",
                            color = OBSColors.Gray700,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        if (recordings.isEmpty()) {
            item {
                OBSCard {
                    Text("Keine Aufzeichnungen vorhanden.", color = OBSColors.Gray500)
                }
            }
        } else {
            items(recordings, key = { it.absolutePath }) { file ->
                val stats = getRecordingStats(file.name)
                FileRowWithMenu(
                    file = file,
                    stats = stats,
                    hasPortalConfig = hasPortalConfig,
                    onView = { onViewFile(file) },
                    onShare = { onShareFile(file) },
                    onUpload = { onUploadFile(file) },
                    onDelete = { onDeleteSingle(file) }
                )
            }
        }
    }
}

@Composable
private fun FileRowWithMenu(
    file: File,
    stats: RecordingStats?,
    hasPortalConfig: Boolean,
    onView: () -> Unit,
    onShare: () -> Unit,
    onUpload: () -> Unit,
    onDelete: () -> Unit
) {
    val df = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    val date = df.format(Date(file.lastModified()))
    val size = formatBytes(file.length())
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = OBSColors.CardBackground,
        shadowElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onView)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        file.name,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text("$date  |  $size", color = OBSColors.Gray500, fontSize = 13.sp)
                }

                // Kontextmenu (wie iOS ellipsis.circle)
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "Aktionen",
                            tint = OBSColors.Gray500,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Auf Karte anzeigen") },
                            onClick = { showMenu = false; onView() },
                            leadingIcon = {
                                Icon(Icons.Default.Map, contentDescription = null, tint = OBSColors.Accent)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Teilen") },
                            onClick = { showMenu = false; onShare() },
                            leadingIcon = {
                                Icon(Icons.Default.Share, contentDescription = null, tint = OBSColors.Accent)
                            }
                        )
                        if (hasPortalConfig) {
                            DropdownMenuItem(
                                text = { Text("Hochladen") },
                                onClick = { showMenu = false; onUpload() },
                                leadingIcon = {
                                    Icon(Icons.Default.CloudUpload, contentDescription = null, tint = OBSColors.Good)
                                }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Loeschen", color = OBSColors.Danger) },
                            onClick = { showMenu = false; onDelete() },
                            leadingIcon = {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = OBSColors.Danger)
                            }
                        )
                    }
                }
            }

            // Stats
            if (stats != null && (stats.overtakeCount > 0 || stats.distanceMeters > 0)) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (stats.overtakeCount > 0) {
                        SmallStatBadge(
                            icon = Icons.Default.Warning,
                            value = "${stats.overtakeCount}",
                            color = OBSColors.Warn
                        )
                    }
                    if (stats.distanceMeters > 0) {
                        SmallStatBadge(
                            icon = Icons.Default.Route,
                            value = String.format(Locale.getDefault(), "%.1f km", stats.distanceMeters / 1000),
                            color = OBSColors.Accent
                        )
                    }
                }
            }
        }
    }
}

// ===========================================
// Unterseite: Portal Tracks
// ===========================================

@Composable
private fun PortalTracksScreen(
    portalTracks: List<PortalTrackSummary>,
    isLoading: Boolean,
    error: String?,
    hasConfig: Boolean,
    onRefresh: () -> Unit,
    onTrackClick: (PortalTrackSummary) -> Unit,
    onPortalLogin: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(OBSColors.Background)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 18.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OBSHeaderWithBack(
                    title = "Portal-Fahrten",
                    onBack = onBack,
                    backIcon = Icons.AutoMirrored.Filled.KeyboardArrowLeft
                )
            }
        }

        // Toolbar: Refresh + Login
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasConfig) {
                    TextButton(onClick = onPortalLogin) {
                        Text("Login", color = OBSColors.Accent, fontSize = 14.sp)
                    }
                    IconButton(onClick = onRefresh, enabled = !isLoading) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Aktualisieren", tint = OBSColors.Accent)
                        }
                    }
                }
            }
        }

        if (!hasConfig) {
            item {
                OBSCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = OBSColors.Warn)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Keine Portal-URL eingetragen", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Bitte konfiguriere das Portal in den Einstellungen.",
                                color = OBSColors.Gray500,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        } else if (error != null) {
            item {
                OBSCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Error, contentDescription = null, tint = OBSColors.Danger)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Fehler", fontWeight = FontWeight.SemiBold)
                            Text(error, color = OBSColors.Gray500, fontSize = 13.sp)
                        }
                    }
                }
            }
        } else if (isLoading) {
            item {
                OBSCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = OBSColors.Accent,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("Lade Tracks...", color = OBSColors.Gray500)
                    }
                }
            }
        } else if (portalTracks.isEmpty()) {
            item {
                OBSCard {
                    Text("Keine Tracks im Portal gefunden.", color = OBSColors.Gray500)
                }
            }
        } else {
            items(portalTracks, key = { it.slug }) { track ->
                PortalTrackRow(track = track, onClick = { onTrackClick(track) })
            }
        }
    }
}

@Composable
private fun PortalTrackRow(
    track: PortalTrackSummary,
    onClick: () -> Unit
) {
    val df = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }
    val date = try {
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
        val parsed = isoFormat.parse(track.recordedAt.take(19))
        if (parsed != null) df.format(parsed) else track.recordedAt.take(10)
    } catch (e: Exception) {
        track.recordedAt.take(10)
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = OBSColors.CardBackground,
        shadowElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        track.title.ifBlank { "Unbenannt" },
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(date, color = OBSColors.Gray500, fontSize = 13.sp)
                }

                val visibilityColor = when (track.visibility) {
                    "public" -> OBSColors.Good
                    "private" -> OBSColors.Gray400
                    else -> OBSColors.Warn
                }
                val visibilityIcon = when (track.visibility) {
                    "public" -> Icons.Default.Public
                    "private" -> Icons.Default.Lock
                    else -> Icons.Default.Group
                }
                Icon(
                    visibilityIcon,
                    contentDescription = track.visibility,
                    tint = visibilityColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (track.numEvents > 0) {
                    SmallStatBadge(icon = Icons.Default.Warning, value = "${track.numEvents}", color = OBSColors.Warn)
                }
                if (track.length > 0) {
                    SmallStatBadge(
                        icon = Icons.Default.Route,
                        value = String.format(Locale.getDefault(), "%.1f km", track.length / 1000),
                        color = OBSColors.Accent
                    )
                }
                if (track.numMeasurements > 0) {
                    SmallStatBadge(icon = Icons.Default.Speed, value = "${track.numMeasurements}", color = OBSColors.Good)
                }
            }
        }
    }
}

// ===========================================
// Unterseite: Portal-Einstellungen
// ===========================================

@Composable
private fun PortalSettingsScreen(
    obsUrl: String,
    onObsUrlChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    onSave: () -> Unit,
    showSaved: Boolean,
    onLogin: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var hideApiKey by remember { mutableStateOf(true) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(OBSColors.Background)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 18.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            OBSHeaderWithBack(
                title = "Portal-Einstellungen",
                onBack = onBack,
                backIcon = Icons.AutoMirrored.Filled.KeyboardArrowLeft
            )
        }

        // Status-Chip
        item {
            val configured = obsUrl.isNotBlank() && apiKey.isNotBlank()
            OBSCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("OBS-Portal", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    OBSStatusChip(
                        text = if (configured) "Bereit" else "Nicht eingerichtet",
                        backgroundColor = if (configured) OBSColors.GoodBackground else OBSColors.WarnOrange.copy(alpha = 0.15f),
                        textColor = if (configured) OBSColors.GoodForeground else OBSColors.WarnOrange
                    )
                }
            }
        }

        // URL + API-Key
        item {
            OBSCard {
                Text("Portal-URL", color = OBSColors.Gray500, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = obsUrl,
                    onValueChange = onObsUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("https://obs.example.com") },
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(Modifier.height(16.dp))

                Text("API-Key", color = OBSColors.Gray500, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = onApiKeyChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (hideApiKey) PasswordVisualTransformation() else VisualTransformation.None,
                    shape = RoundedCornerShape(12.dp),
                    trailingIcon = {
                        IconButton(onClick = { hideApiKey = !hideApiKey }) {
                            Icon(
                                imageVector = if (hideApiKey) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = if (hideApiKey) "Anzeigen" else "Verbergen",
                                tint = OBSColors.Gray500
                            )
                        }
                    }
                )

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onSave,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (showSaved) OBSColors.Good else OBSColors.Accent
                        )
                    ) {
                        if (showSaved) {
                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Gespeichert")
                        } else {
                            Text("Speichern")
                        }
                    }

                    OutlinedButton(
                        onClick = onLogin,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        enabled = obsUrl.isNotBlank()
                    ) {
                        Text("Im Portal einloggen")
                    }
                }
            }
        }

        // Anleitung
        item {
            OBSCard {
                Text("So funktioniert der Login", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "1. Trage die URL deines OBS-Portals ein.\n" +
                            "2. Tippe auf \"Im Portal einloggen\".\n" +
                            "3. Melde dich im Browser an.\n" +
                            "4. Die Session wird automatisch uebernommen.",
                    color = OBSColors.Gray500,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
            }
        }
    }
}

// ===========================================
// Gemeinsame Hilfs-Composables
// ===========================================

@Composable
private fun SmallStatBadge(
    icon: ImageVector,
    value: String,
    color: Color
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1 -> String.format(Locale.getDefault(), "%.1f MB", mb)
        kb >= 1 -> String.format(Locale.getDefault(), "%.0f KB", kb)
        else -> "$bytes B"
    }
}
