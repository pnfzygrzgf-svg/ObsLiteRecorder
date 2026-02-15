// LocalTrackParser.kt - Parst lokale .bin Dateien (COBS-kodierte Protobuf Events)
package com.example.obsliterecorder.util

import android.util.Log
import com.example.obsliterecorder.proto.Event
import com.google.protobuf.InvalidProtocolBufferException
import java.io.File
import java.io.FileInputStream

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
    val distanceCm: Int
)

/**
 * Parst eine .bin Datei mit COBS-kodierten Protobuf Events.
 * Format: [COBS-Frame][0x00][COBS-Frame][0x00]...
 */
object LocalTrackParser {

    private const val TAG = "LocalTrackParser"

    fun parse(file: File): LocalTrackData? {
        if (!file.exists() || file.length() == 0L) return null

        val bytes = try {
            FileInputStream(file).use { it.readBytes() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read file: ${file.name}", e)
            return null
        }

        val coordinates = mutableListOf<LocalCoordinate>()
        val overtakeEvents = mutableListOf<LocalOvertakeEvent>()
        var measurementCount = 0
        var firstTimeSec = 0L
        var lastTimeSec = 0L

        // Track last known position for associating with overtake events
        var lastLat = 0.0
        var lastLon = 0.0
        var isUserInputNext = false

        // Split by 0x00 delimiter and decode each COBS frame
        var frameStart = 0
        for (i in bytes.indices) {
            if (bytes[i].toInt() == 0x00) {
                if (i > frameStart) {
                    val frame = bytes.copyOfRange(frameStart, i)
                    try {
                        val decoded = CobsUtils.decode(frame)
                        if (decoded.isNotEmpty()) {
                            val event = Event.parseFrom(decoded)
                            processEvent(
                                event, coordinates, overtakeEvents,
                                lastLat, lastLon,
                                { lat -> lastLat = lat },
                                { lon -> lastLon = lon },
                                { isUserInputNext = it },
                                isUserInputNext
                            )

                            // Track timestamps
                            if (event.timeCount > 0) {
                                val sec = event.getTime(0).seconds
                                if (sec > 0) {
                                    if (firstTimeSec == 0L) firstTimeSec = sec
                                    lastTimeSec = sec
                                }
                            }

                            if (event.hasDistanceMeasurement()) measurementCount++
                        }
                    } catch (e: InvalidProtocolBufferException) {
                        // Skip malformed events
                    } catch (e: Exception) {
                        // Skip decode errors
                    }
                }
                frameStart = i + 1
            }
        }

        val duration = if (firstTimeSec > 0 && lastTimeSec > firstTimeSec) {
            lastTimeSec - firstTimeSec
        } else 0L

        return LocalTrackData(
            routeCoordinates = coordinates,
            events = overtakeEvents,
            totalMeasurements = measurementCount,
            durationSeconds = duration
        )
    }

    private fun processEvent(
        event: Event,
        coordinates: MutableList<LocalCoordinate>,
        overtakeEvents: MutableList<LocalOvertakeEvent>,
        lastLat: Double,
        lastLon: Double,
        setLat: (Double) -> Unit,
        setLon: (Double) -> Unit,
        setUserInput: (Boolean) -> Unit,
        wasUserInput: Boolean
    ) {
        when {
            event.hasGeolocation() -> {
                val geo = event.geolocation
                if (geo.latitude != 0.0 && geo.longitude != 0.0) {
                    coordinates.add(LocalCoordinate(geo.latitude, geo.longitude))
                    setLat(geo.latitude)
                    setLon(geo.longitude)
                }
            }

            event.hasUserInput() -> {
                setUserInput(true)
            }

            event.hasDistanceMeasurement() -> {
                val dm = event.distanceMeasurement
                // After UserInput, the next DM is the overtake distance
                if (wasUserInput && lastLat != 0.0 && lastLon != 0.0) {
                    val cm = (dm.distance * 100).toInt()
                    overtakeEvents.add(LocalOvertakeEvent(lastLat, lastLon, cm))
                    setUserInput(false)
                }
            }
        }
    }
}
