// SPDX-License-Identifier: GPL-3.0-or-later

// MapTab.kt - Karten-Ansicht mit Live-Überholvorgängen
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

/**
 * Datenklasse für einen Überholvorgang auf der Karte
 */
data class OvertakeEvent(
    val latitude: Double,
    val longitude: Double,
    val distanceCm: Int,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Map-Tab: Zeigt aktuelle Position und Überholvorgänge
 */
@Composable
fun MapTab(
    overtakeEvents: List<OvertakeEvent>,
    lastOvertakeEvent: OvertakeEvent?,
    currentLatitude: Double?,
    currentLongitude: Double?,
    onClearEvents: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }

    // OSMDroid config
    LaunchedEffect(Unit) {
        Configuration.getInstance().userAgentValue = context.packageName
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
            OBSHeader(title = "Karte")

            Spacer(Modifier.height(14.dp))

            // Map Container
            OBSCard(modifier = Modifier.weight(1f)) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { ctx ->
                            MapView(ctx).apply {
                                // Karte ohne POIs (CartoDB Positron)
                                setTileSource(CartoDBPositronTileSource)
                                setMultiTouchControls(true)
                                controller.setZoom(16.0)

                                // My Location Overlay
                                val locationOverlay = MyLocationNewOverlay(
                                    GpsMyLocationProvider(ctx), this
                                )
                                locationOverlay.enableMyLocation()
                                locationOverlay.enableFollowLocation()
                                overlays.add(locationOverlay)

                                // Initial position
                                if (currentLatitude != null && currentLongitude != null) {
                                    controller.setCenter(GeoPoint(currentLatitude, currentLongitude))
                                } else {
                                    // Default: Deutschland Mitte
                                    controller.setCenter(GeoPoint(51.1657, 10.4515))
                                }

                                mapView = this
                            }
                        },
                        update = { view ->
                            // Update markers for overtake events
                            view.overlays.removeAll { it is Marker }

                            overtakeEvents.forEach { event ->
                                val marker = Marker(view).apply {
                                    position = GeoPoint(event.latitude, event.longitude)
                                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                    title = "${event.distanceCm} cm"

                                    // Marker mit Abstandswert als Text
                                    val color = OBSColors.overtakeColor(event.distanceCm)
                                    val distanceM = event.distanceCm / 100.0
                                    val text = String.format(java.util.Locale.getDefault(), "%.2f", distanceM)
                                    icon = createTextMarker(context, color, text)
                                }
                                view.overlays.add(marker)
                            }

                            view.invalidate()
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                    )

                    // Recenter Button
                    FloatingActionButton(
                        onClick = {
                            mapView?.let { map ->
                                if (currentLatitude != null && currentLongitude != null) {
                                    map.controller.animateTo(GeoPoint(currentLatitude, currentLongitude))
                                }
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp)
                            .size(48.dp),
                        containerColor = Color.White,
                        contentColor = OBSColors.Accent
                    ) {
                        Icon(Icons.Default.MyLocation, "Zentrieren")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Stats Card
            OBSCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Überholvorgänge", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))

                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                "${overtakeEvents.size}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 24.sp
                            )
                            Spacer(Modifier.width(8.dp))
                            if (lastOvertakeEvent != null) {
                                val status = getOvertakeStatus(lastOvertakeEvent.distanceCm)
                                OBSStatusChip(
                                    text = "Letzter: ${lastOvertakeEvent.distanceCm} cm",
                                    backgroundColor = status.color.copy(alpha = 0.15f),
                                    textColor = status.color
                                )
                            }
                        }
                    }

                    if (overtakeEvents.isNotEmpty()) {
                        IconButton(
                            onClick = { showClearDialog = true }
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Events löschen",
                                tint = OBSColors.Danger
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(80.dp)) // Space for bottom tab bar
        }
    }

    // Clear Dialog
    if (showClearDialog) {
        OBSConfirmDialog(
            title = "Events löschen?",
            message = "Möchtest du alle ${overtakeEvents.size} Überholvorgänge von der Karte entfernen?",
            confirmText = "Löschen",
            destructive = true,
            onConfirm = {
                showClearDialog = false
                onClearEvents()
            },
            onDismiss = { showClearDialog = false }
        )
    }
}

/**
 * Erstellt einen Marker mit farbigem Pin und Abstandswert als Text
 */
private fun createTextMarker(
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
