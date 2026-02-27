// SPDX-License-Identifier: GPL-3.0-or-later

// LocalTrackParser.kt - Portal-kompatible Verarbeitung lokaler .bin Dateien
// Logik identisch zum OpenBikeSensor Portal (process_binary)
package com.example.obsliterecorder.util

import android.util.Log
import com.example.obsliterecorder.proto.Event
import com.example.obsliterecorder.proto.Time
import com.google.protobuf.InvalidProtocolBufferException
import java.io.File
import java.io.FileInputStream
import kotlin.math.*

data class LocalTrackData(
    val routeCoordinates: List<LocalCoordinate>,
    val events: List<LocalOvertakeEvent>,
    val totalMeasurements: Int,
    val durationSeconds: Long
)

data class LocalCoordinate(val latitude: Double, val longitude: Double)

data class LocalOvertakeEvent(
    val latitude: Double,
    val longitude: Double,
    val distanceOvertakerCm: Int,
    val distanceStationaryCm: Int?,
    val confirmed: Boolean = true
)

/**
 * Parst eine .bin Datei mit COBS-kodierten Protobuf Events.
 * Logik identisch zum OpenBikeSensor Portal (process_binary):
 * 1. Zeitquelle waehlen (beste UNIX-Quelle)
 * 2. Alle Events auf Referenz-Zeitachse bringen
 * 3. Zeitindizierte Daten extrahieren
 * 4. Track segmentieren
 * 5. Ueberholungen mit GPS-Interpolation und 5s-Zeitfenster zuordnen
 */
object LocalTrackParser {

    private const val TAG = "LocalTrackParser"
    private const val TIME_WINDOW_SIZE = 5.0 // Sekunden
    private const val MIN_UNIX_TIME = 1722958548L // Plausibilitaet (nach OBS Lite Einfuehrung)

    fun parse(file: File): LocalTrackData? {
        if (!file.exists() || file.length() == 0L) return null

        val bytes = try {
            FileInputStream(file).use { it.readBytes() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read file: ${file.name}", e)
            return null
        }

        // --- COBS-Frames dekodieren und Events parsen ---
        val events = decodeEvents(bytes)
        if (events.isEmpty()) {
            Log.w(TAG, "No events parsed from ${file.name}")
            return null
        }
        Log.i(TAG, "Parsed ${events.size} raw events from ${file.name}")

        // --- Schritt 0: Beste Zeitquelle waehlen ---
        val timeSources = mutableMapOf<Int, TimeSource>()
        var bestTime: Time? = null

        for (event in events) {
            for (time in event.timeList) {
                if (time.sourceId !in timeSources) {
                    timeSources[time.sourceId] = TimeSource(time.sourceId, time.reference)
                }
                if (time.reference == Time.Reference.UNIX && time.seconds > MIN_UNIX_TIME) {
                    if (bestTime == null || time.sourceId < bestTime.sourceId) {
                        bestTime = time
                    }
                }
            }
        }

        if (bestTime == null) {
            Log.e(TAG, "No suitable UNIX time source found")
            return null
        }

        val bestSourceId = bestTime.sourceId
        Log.d(TAG, "Best time source: id=$bestSourceId, reference=${bestTime.reference}")

        // --- Schritt 1: Events auf Referenz-Zeitachse bringen ---
        // Kalibrierungsdaten fuer andere Zeitquellen sammeln
        for (event in events) {
            var refTime: Double? = null
            for (t in event.timeList) {
                if (t.sourceId == bestSourceId) {
                    refTime = getSeconds(t)
                    break
                }
            }
            if (refTime != null) {
                for (t in event.timeList) {
                    if (t.sourceId != bestSourceId) {
                        timeSources[t.sourceId]?.addCalibrationPoint(getSeconds(t), refTime)
                    }
                }
            }
        }

        // Lineare Regression fuer jede nicht-primaere Zeitquelle
        for (ts in timeSources.values) {
            if (ts.sourceId != bestSourceId) {
                ts.fit()
            }
        }

        // Event-Zeiten berechnen
        val eventTimes = DoubleArray(events.size)
        for (i in events.indices) {
            val event = events[i]
            if (event.timeCount == 0) continue

            val baseTime = event.timeList.find { it.sourceId == bestSourceId }
            if (baseTime != null) {
                eventTimes[i] = getSeconds(baseTime)
            } else {
                val t = event.getTime(0)
                val ts = timeSources[t.sourceId]
                eventTimes[i] = ts?.forward(getSeconds(t)) ?: getSeconds(t)
            }
        }

        // --- Schritt 2: Zeitindizierte Daten extrahieren ---
        val geolocations = mutableListOf<TimedGeo>()          // (time, lon, lat)
        val distancesOvertaker = mutableListOf<TimedValue>()   // (time, distance_m)
        val distancesStationary = mutableListOf<TimedValue>()  // (time, distance_m)
        val confirmed = mutableListOf<Double>()                // Zeitpunkte
        var measurementCount = 0

        for (i in events.indices) {
            val t = eventTimes[i]
            val event = events[i]

            if (event.hasDistanceMeasurement()) {
                val d = event.distanceMeasurement
                measurementCount++
                if (d.sourceId == 1) {
                    distancesOvertaker.add(TimedValue(t, d.distance.toDouble()))
                } else {
                    distancesStationary.add(TimedValue(t, d.distance.toDouble()))
                }
            }

            if (event.hasGeolocation()) {
                val g = event.geolocation
                if (g.latitude != 0.0 || g.longitude != 0.0) {
                    geolocations.add(TimedGeo(t, g.longitude, g.latitude))
                }
            }

            if (event.hasUserInput()) {
                confirmed.add(t)
            }
        }

        confirmed.sort()

        // --- Schritt 3: Track segmentieren ---
        val segments = splitByDistance(geolocations)
            .filter { it.size >= 10 }

        if (segments.isEmpty()) {
            Log.w(TAG, "No valid track segments found")
            return LocalTrackData(
                routeCoordinates = geolocations.map { LocalCoordinate(it.lat, it.lon) },
                events = emptyList(),
                totalMeasurements = measurementCount,
                durationSeconds = computeDuration(eventTimes)
            )
        }

        // --- Schritt 4: Ueberholungen zuordnen (pro Segment) ---
        val allCoordinates = mutableListOf<LocalCoordinate>()
        val allOvertakeEvents = mutableListOf<LocalOvertakeEvent>()

        for (segment in segments) {
            val tmin = segment.first().time
            val tmax = segment.last().time

            // Segment-Koordinaten fuer Route
            allCoordinates.addAll(segment.map { LocalCoordinate(it.lat, it.lon) })

            // Zeiten und Positionen als Arrays fuer binary search
            val segTimes = segment.map { it.time }.toDoubleArray()
            val segLons = segment.map { it.lon }.toDoubleArray()
            val segLats = segment.map { it.lat }.toDoubleArray()

            var prev: Double? = null

            for (t in confirmed) {
                if (t < tmin || t > tmax) continue

                // GPS-Position interpolieren
                val idx = segTimes.binarySearchInsertionPoint(t)
                val (interpLon, interpLat) = if (idx <= 0) {
                    segLons[0] to segLats[0]
                } else if (idx >= segment.size) {
                    segLons.last() to segLats.last()
                } else {
                    interpolateLocation(
                        segTimes[idx - 1], segLons[idx - 1], segLats[idx - 1],
                        segTimes[idx], segLons[idx], segLats[idx],
                        t
                    )
                }

                // 5s-Zeitfenster: Minimum der Distanzen
                val overtakerWindow = filterWindow(distancesOvertaker, t, prev)
                val stationaryWindow = filterWindow(distancesStationary, t, prev)

                val distOvertaker = if (overtakerWindow.isNotEmpty()) {
                    overtakerWindow.minOf { it.value }
                } else null

                val distStationary = if (stationaryWindow.isNotEmpty()) {
                    stationaryWindow.minOf { it.value }
                } else null

                if (distOvertaker != null) {
                    allOvertakeEvents.add(
                        LocalOvertakeEvent(
                            latitude = interpLat,
                            longitude = interpLon,
                            distanceOvertakerCm = (distOvertaker * 100).roundToInt(),
                            distanceStationaryCm = distStationary?.let { (it * 100).roundToInt() },
                            confirmed = true
                        )
                    )
                }

                prev = t
            }
        }

        return LocalTrackData(
            routeCoordinates = allCoordinates,
            events = allOvertakeEvents,
            totalMeasurements = measurementCount,
            durationSeconds = computeDuration(eventTimes)
        )
    }

    // --- Interne Datenklassen ---

    private data class TimedGeo(val time: Double, val lon: Double, val lat: Double)
    private data class TimedValue(val time: Double, val value: Double)

    private class TimeSource(val sourceId: Int, val reference: Time.Reference) {
        private val calibrationPoints = mutableListOf<Pair<Double, Double>>() // (sourceTime, refTime)
        private var slope = 1.0
        private var intercept = 0.0
        private var fitted = false

        fun addCalibrationPoint(sourceTime: Double, refTime: Double) {
            calibrationPoints.add(sourceTime to refTime)
        }

        fun fit() {
            if (calibrationPoints.size < 2) {
                if (calibrationPoints.size == 1) {
                    // Nur Offset, Slope = 1
                    intercept = calibrationPoints[0].second - calibrationPoints[0].first
                    slope = 1.0
                    fitted = true
                }
                return
            }
            // Lineare Regression: y = slope * x + intercept
            val n = calibrationPoints.size.toDouble()
            val sumX = calibrationPoints.sumOf { it.first }
            val sumY = calibrationPoints.sumOf { it.second }
            val sumXY = calibrationPoints.sumOf { it.first * it.second }
            val sumX2 = calibrationPoints.sumOf { it.first * it.first }

            val denom = n * sumX2 - sumX * sumX
            if (abs(denom) < 1e-12) {
                slope = 1.0
                intercept = sumY / n - sumX / n
            } else {
                slope = (n * sumXY - sumX * sumY) / denom
                intercept = (sumY - slope * sumX) / n
            }
            fitted = true
            Log.d(TAG, "TimeSource $sourceId fitted: ${slope}x + $intercept")
        }

        fun forward(x: Double): Double {
            return if (fitted) slope * x + intercept else x
        }
    }

    // --- Hilfsfunktionen (Portal-Ports) ---

    private fun getSeconds(time: Time): Double {
        return time.seconds.toDouble() + time.nanoseconds * 1e-9
    }

    /** Portal: filter_window() */
    private fun filterWindow(measurements: List<TimedValue>, t: Double, prev: Double?): List<TimedValue> {
        return measurements.filter { x ->
            x.time <= t &&
            x.time >= t - TIME_WINDOW_SIZE &&
            !(prev != null && x.time <= prev)
        }
    }

    /** Portal: interpolate_location() - lineare Interpolation */
    private fun interpolateLocation(
        t0: Double, lon0: Double, lat0: Double,
        t1: Double, lon1: Double, lat1: Double,
        t: Double
    ): Pair<Double, Double> {
        val f = if (abs(t1 - t0) < 1e-9) 0.5 else (t - t0) / (t1 - t0)
        val cf = f.coerceIn(0.0, 1.0)
        return Pair(
            lon0 + (lon1 - lon0) * cf,
            lat0 + (lat1 - lat0) * cf
        )
    }

    /** Portal: split_by_distance() */
    private fun splitByDistance(
        geolocations: List<TimedGeo>,
        maxTime: Double = 10.0,
        maxDistance: Double = 100.0
    ): List<List<TimedGeo>> {
        if (geolocations.isEmpty()) return emptyList()

        val segments = mutableListOf<List<TimedGeo>>()
        var currentSegment = mutableListOf<TimedGeo>()
        var prev = geolocations[0]
        currentSegment.add(prev)

        for (i in 1 until geolocations.size) {
            val current = geolocations[i]

            // Zeitspruenge rueckwaerts ignorieren (wie Portal)
            if (current.time < prev.time) continue

            val distance = haversineMeters(prev.lat, prev.lon, current.lat, current.lon)
            val timeDiff = current.time - prev.time

            if (distance > maxDistance || timeDiff > maxTime) {
                // Segment-Trennung
                if (currentSegment.isNotEmpty()) {
                    segments.add(currentSegment)
                }
                currentSegment = mutableListOf()
            }

            currentSegment.add(current)
            prev = current
        }

        if (currentSegment.isNotEmpty()) {
            segments.add(currentSegment)
        }

        return segments
    }

    /** Haversine-Distanz in Metern */
    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Erdradius in Metern
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    /** Binary search: findet den Index des ersten Elements >= value */
    private fun DoubleArray.binarySearchInsertionPoint(value: Double): Int {
        var low = 0
        var high = size
        while (low < high) {
            val mid = (low + high) / 2
            if (this[mid] < value) low = mid + 1 else high = mid
        }
        return low
    }

    private fun computeDuration(eventTimes: DoubleArray): Long {
        val validTimes = eventTimes.filter { it > 0 }
        if (validTimes.size < 2) return 0L
        return (validTimes.max() - validTimes.min()).toLong()
    }

    /** COBS-Frames dekodieren und Protobuf Events parsen */
    private fun decodeEvents(bytes: ByteArray): List<Event> {
        val events = mutableListOf<Event>()
        var frameStart = 0

        for (i in bytes.indices) {
            if (bytes[i].toInt() == 0x00) {
                if (i > frameStart) {
                    val frame = bytes.copyOfRange(frameStart, i)
                    try {
                        val decoded = CobsUtils.decode(frame)
                        if (decoded.isNotEmpty()) {
                            events.add(Event.parseFrom(decoded))
                        }
                    } catch (_: InvalidProtocolBufferException) {
                        // Malformed event ueberspringen
                    } catch (_: Exception) {
                        // Decode-Fehler ueberspringen
                    }
                }
                frameStart = i + 1
            }
        }

        return events
    }
}
