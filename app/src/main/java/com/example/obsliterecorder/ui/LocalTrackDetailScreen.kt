// LocalTrackDetailScreen.kt - Detailansicht einer lokalen .bin Datei mit Karte
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
import com.example.obsliterecorder.util.LocalTrackData
import com.example.obsliterecorder.util.LocalTrackParser
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LocalTrackDetailScreen(
    file: File,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var isLoading by remember { mutableStateOf(true) }
    var trackData by remember { mutableStateOf<LocalTrackData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        Configuration.getInstance().userAgentValue = context.packageName
    }

    LaunchedEffect(file.absolutePath) {
        isLoading = true
        error = null

        val result = withContext(Dispatchers.IO) {
            LocalTrackParser.parse(file)
        }

        if (result != null) {
            trackData = result
        } else {
            error = "Datei konnte nicht gelesen werden."
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
            // Header
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
                    text = file.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(12.dp))

            // Info Card
            LocalTrackInfoCard(file = file, trackData = trackData)

            Spacer(Modifier.height(12.dp))

            // Map
            OBSCard(modifier = Modifier.weight(1f)) {
                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        isLoading -> {
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
                                    Text("Datei wird analysiert...", color = OBSColors.Gray500)
                                }
                            }
                        }

                        error != null -> {
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
                                    Text(error!!, color = OBSColors.Gray500)
                                }
                            }
                        }

                        trackData != null -> {
                            val data = trackData!!
                            if (data.routeCoordinates.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("Keine GPS-Daten in dieser Datei.", color = OBSColors.Gray500)
                                }
                            } else {
                                AndroidView(
                                    factory = { ctx ->
                                        MapView(ctx).apply {
                                            setTileSource(LocalCartoDBTileSource)
                                            setMultiTouchControls(true)
                                            controller.setZoom(14.0)

                                            // Route
                                            val polyline = Polyline(this).apply {
                                                setPoints(data.routeCoordinates.map {
                                                    GeoPoint(it.latitude, it.longitude)
                                                })
                                                outlinePaint.color = android.graphics.Color.argb(220, 0, 122, 255)
                                                outlinePaint.strokeWidth = 8f
                                            }
                                            overlays.add(polyline)

                                            // Overtake markers
                                            data.events.forEach { event ->
                                                val marker = Marker(this).apply {
                                                    position = GeoPoint(event.latitude, event.longitude)
                                                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                                    title = "Ueberholung: ${event.distanceCm} cm"

                                                    val color = OBSColors.overtakeColor(event.distanceCm)
                                                    val text = "${event.distanceCm}"
                                                    icon = createLocalMarkerDrawable(ctx, color, text)
                                                }
                                                overlays.add(marker)
                                            }

                                            // Zoom to fit
                                            val allPoints = data.routeCoordinates.map {
                                                GeoPoint(it.latitude, it.longitude)
                                            }
                                            if (allPoints.isNotEmpty()) {
                                                post {
                                                    try {
                                                        val box = BoundingBox.fromGeoPoints(allPoints)
                                                        zoomToBoundingBox(box, true, 50)
                                                    } catch (e: Exception) {
                                                        controller.setCenter(allPoints.first())
                                                    }
                                                }
                                            }
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
            }

            Spacer(Modifier.height(80.dp))
        }
    }
}

@Composable
private fun LocalTrackInfoCard(file: File, trackData: LocalTrackData?) {
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    val date = dateFormat.format(Date(file.lastModified()))
    val sizeKb = file.length() / 1024.0

    OBSCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CalendarToday,
                        contentDescription = null,
                        tint = OBSColors.Gray500,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(date, color = OBSColors.Gray500, fontSize = 13.sp)
                }

                Spacer(Modifier.height(4.dp))

                Text(
                    String.format(Locale.getDefault(), "%.1f KB", sizeKb),
                    color = OBSColors.Gray500,
                    fontSize = 13.sp
                )

                if (trackData != null) {
                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (trackData.events.isNotEmpty()) {
                            LocalStatItem(
                                icon = Icons.Default.Warning,
                                value = "${trackData.events.size}",
                                color = OBSColors.Warn
                            )
                        }
                        if (trackData.routeCoordinates.size > 1) {
                            LocalStatItem(
                                icon = Icons.Default.Route,
                                value = "${trackData.routeCoordinates.size} Punkte",
                                color = OBSColors.Accent
                            )
                        }
                        if (trackData.totalMeasurements > 0) {
                            LocalStatItem(
                                icon = Icons.Default.Speed,
                                value = "${trackData.totalMeasurements}",
                                color = OBSColors.Good
                            )
                        }
                        if (trackData.durationSeconds > 0) {
                            val min = trackData.durationSeconds / 60
                            LocalStatItem(
                                icon = Icons.Default.Timer,
                                value = "$min min",
                                color = OBSColors.Accent
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalStatItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    color: Color
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

private fun createLocalMarkerDrawable(
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

    val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        style = Paint.Style.FILL
    }

    val centerX = width / 2f
    val circleY = pinRadius
    canvas.drawCircle(centerX, circleY, pinRadius, pinPaint)

    val path = android.graphics.Path().apply {
        moveTo(centerX - pinRadius * 0.5f, circleY + pinRadius * 0.6f)
        lineTo(centerX, circleY + pinRadius + pinTip)
        lineTo(centerX + pinRadius * 0.5f, circleY + pinRadius * 0.6f)
        close()
    }
    canvas.drawPath(path, pinPaint)

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.WHITE
        textSize = 11 * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    val textY = circleY + (textPaint.textSize / 3)
    canvas.drawText(text, centerX, textY, textPaint)

    return BitmapDrawable(context.resources, bitmap)
}

private val LocalCartoDBTileSource = object : OnlineTileSourceBase(
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
