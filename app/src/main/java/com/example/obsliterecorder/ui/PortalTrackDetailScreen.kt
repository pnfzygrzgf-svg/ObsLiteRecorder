// SPDX-License-Identifier: GPL-3.0-or-later

// PortalTrackDetailScreen.kt - Detailansicht eines Portal-Tracks mit Karte
package com.example.obsliterecorder.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.obsliterecorder.portal.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Detail-Screen fuer einen Portal-Track mit Kartenanzeige
 */
@Composable
fun PortalTrackDetailScreen(
    track: PortalTrackSummary,
    baseUrl: String,
    apiKey: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var mapView by remember { mutableStateOf<MapView?>(null) }

    // Loading state
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var trackData by remember { mutableStateOf<PortalTrackData?>(null) }

    // OSMDroid config
    LaunchedEffect(Unit) {
        Configuration.getInstance().userAgentValue = context.packageName
    }

    // Load track data on IO thread
    LaunchedEffect(track.slug) {
        isLoading = true
        error = null

        val result = withContext(Dispatchers.IO) {
            val client = PortalApiClient(baseUrl, apiKey)
            client.fetchTrackData(track.slug)
        }

        when (result) {
            is PortalTrackDataResult.Success -> {
                trackData = result.data
                error = null
            }
            is PortalTrackDataResult.Error -> {
                error = result.message
            }
        }
        isLoading = false
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OBSColors.Background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .padding(top = 18.dp)
        ) {
            // Header with back button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Zurueck",
                        tint = OBSColors.Accent
                    )
                }
                Text(
                    text = track.title.ifBlank { "Track Details" },
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(12.dp))

            // Track Info Card
            TrackInfoCard(track = track)

            Spacer(Modifier.height(12.dp))

            // Map Card
            OBSCard(modifier = Modifier.weight(1f)) {
                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        isLoading -> {
                            // Loading state
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(
                                        color = OBSColors.Accent,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Text("Lade Kartendaten...", color = OBSColors.Gray500)
                                }
                            }
                        }

                        error != null -> {
                            // Error state
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Default.Error,
                                        contentDescription = null,
                                        tint = OBSColors.Danger,
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        "Fehler: $error",
                                        color = OBSColors.Gray500,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }

                        trackData != null -> {
                            // Map with route and events
                            val data = trackData!!
                            AndroidView(
                                factory = { ctx ->
                                    MapView(ctx).apply {
                                        // Karte ohne POIs (CartoDB Positron - clean style)
                                        setTileSource(CartoDBPositronTileSource)
                                        setMultiTouchControls(true)
                                        controller.setZoom(14.0)

                                        // Add route polyline
                                        if (data.routeCoordinates.isNotEmpty()) {
                                            val polyline = Polyline(this).apply {
                                                setPoints(data.routeCoordinates.map {
                                                    GeoPoint(it.latitude, it.longitude)
                                                })
                                                outlinePaint.color = android.graphics.Color.argb(220, 0, 122, 255)
                                                outlinePaint.strokeWidth = 8f
                                            }
                                            overlays.add(polyline)
                                        }

                                        // Add event markers with distance text
                                        data.events.forEach { event ->
                                            val marker = Marker(this).apply {
                                                position = GeoPoint(event.latitude, event.longitude)
                                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

                                                val distanceM = event.distanceOvertakerMeters
                                                title = if (distanceM != null) {
                                                    String.format(Locale.getDefault(), "Ueberholung: %.2f m", distanceM)
                                                } else {
                                                    "Ueberholung"
                                                }

                                                // Marker mit Abstandswert als Text (wie iOS)
                                                val color = eventColorForDistance(distanceM)
                                                val text = if (distanceM != null) {
                                                    String.format(Locale.getDefault(), "%.2f", distanceM)
                                                } else {
                                                    "-"
                                                }
                                                icon = createTextMarkerDrawable(ctx, color, text)
                                            }
                                            overlays.add(marker)
                                        }

                                        // Zoom to fit route + events
                                        val allPoints = mutableListOf<GeoPoint>()
                                        data.routeCoordinates.forEach {
                                            allPoints.add(GeoPoint(it.latitude, it.longitude))
                                        }
                                        data.events.forEach {
                                            allPoints.add(GeoPoint(it.latitude, it.longitude))
                                        }

                                        if (allPoints.isNotEmpty()) {
                                            post {
                                                try {
                                                    val boundingBox = BoundingBox.fromGeoPoints(allPoints)
                                                    zoomToBoundingBox(boundingBox, true, 50)
                                                } catch (e: Exception) {
                                                    // Fallback to first point
                                                    controller.setCenter(allPoints.first())
                                                }
                                            }
                                        }

                                        mapView = this
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(12.dp))
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(80.dp)) // Space for bottom elements
        }
    }
}

@Composable
private fun TrackInfoCard(track: PortalTrackSummary) {
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    val isoFormat = remember { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()) }

    val recordedDate = try {
        val parsed = isoFormat.parse(track.recordedAt.take(19))
        if (parsed != null) dateFormat.format(parsed) else track.recordedAt.take(10)
    } catch (e: Exception) {
        track.recordedAt.take(10)
    }

    OBSCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // Date
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CalendarToday,
                        contentDescription = null,
                        tint = OBSColors.Gray500,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        recordedDate,
                        color = OBSColors.Gray500,
                        fontSize = 13.sp
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Stats row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Distance
                    if (track.length > 0) {
                        StatItem(
                            icon = Icons.Default.Route,
                            value = String.format(Locale.getDefault(), "%.1f km", track.length / 1000),
                            color = OBSColors.Accent
                        )
                    }

                    // Duration
                    if (track.duration > 0) {
                        val minutes = (track.duration / 60).toInt()
                        StatItem(
                            icon = Icons.Default.Timer,
                            value = "$minutes min",
                            color = OBSColors.Accent
                        )
                    }

                    // Events
                    if (track.numEvents > 0) {
                        StatItem(
                            icon = Icons.Default.Warning,
                            value = "${track.numEvents}",
                            color = OBSColors.Warn
                        )
                    }

                    // Measurements
                    if (track.numMeasurements > 0) {
                        StatItem(
                            icon = Icons.Default.Speed,
                            value = "${track.numMeasurements}",
                            color = OBSColors.Good
                        )
                    }
                }
            }

            // Visibility badge
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
    }
}

@Composable
private fun StatItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    color: Color
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            value,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp
        )
    }
}

/**
 * Farbkodierung basierend auf Ueberholabstand (wie iOS)
 */
private fun eventColorForDistance(distanceMeters: Double?): Color {
    if (distanceMeters == null) return OBSColors.Gray400

    val cm = (distanceMeters * 100).toInt()
    return OBSColors.overtakeColor(cm)
}

/**
 * Erstellt einen Marker mit farbigem Pin und Abstandswert als Text (wie iOS MKMarkerAnnotationView)
 */
private fun createTextMarkerDrawable(
    context: android.content.Context,
    color: Color,
    text: String
): Drawable {
    val density = context.resources.displayMetrics.density
    val width = (48 * density).toInt()
    val height = (56 * density).toInt()
    val pinRadius = (18 * density)
    val pinTip = (8 * density)

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Pin-Farbe
    val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        style = Paint.Style.FILL
    }

    // Pin-Kreis
    val centerX = width / 2f
    val circleY = pinRadius
    canvas.drawCircle(centerX, circleY, pinRadius, pinPaint)

    // Pin-Spitze (Dreieck nach unten)
    val path = android.graphics.Path().apply {
        moveTo(centerX - pinRadius * 0.5f, circleY + pinRadius * 0.6f)
        lineTo(centerX, circleY + pinRadius + pinTip)
        lineTo(centerX + pinRadius * 0.5f, circleY + pinRadius * 0.6f)
        close()
    }
    canvas.drawPath(path, pinPaint)

    // Text im Pin
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.WHITE
        textSize = 11 * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    // Text zentrieren
    val textY = circleY + (textPaint.textSize / 3)
    canvas.drawText(text, centerX, textY, textPaint)

    return BitmapDrawable(context.resources, bitmap)
}

/**
 * CartoDB Positron TileSource - Clean-Style Karte mit Strassennamen, wenig POIs
 */
private val CartoDBPositronTileSource = object : OnlineTileSourceBase(
    "CartoDB Positron",
    0, 19, 256, ".png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/light_all/",
        "https://b.basemaps.cartocdn.com/light_all/",
        "https://c.basemaps.cartocdn.com/light_all/"
    )
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "$baseUrl$zoom/$x/$y$mImageFilenameEnding"
    }
}
