// file: com/example/obsliterecorder/obslite/OBSLiteSession.kt
package com.example.obsliterecorder.obslite

import android.content.Context
import android.location.Location
import android.util.Log
import com.example.obsliterecorder.proto.DistanceMeasurement
import com.example.obsliterecorder.proto.Event
import com.example.obsliterecorder.proto.Geolocation
import com.example.obsliterecorder.proto.Time
import com.example.obsliterecorder.util.CobsUtils
import com.google.protobuf.InvalidProtocolBufferException
import java.util.LinkedList
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.math.max
import kotlin.math.roundToInt

class OBSLiteSession(private val context: Context) {

    private val TAG = "OBSLiteSession"

    var obsLiteStartTime: Long = 0L
    var startTime: Long = -1L

    private var lastLat: Double = 0.0
    private var lastLon: Double = 0.0

    private var totalBytesWritten: Int = 0
    private var totalEvents: Int = 0

    var lastMedianAtPressCm: Int? = null
        private set

    // Beibehalten: Typ & öffentliche API unverändert
    val byteListQueue: ConcurrentLinkedDeque<LinkedList<Byte>> = ConcurrentLinkedDeque()
    var lastByteRead: Byte? = null

    private val prefs = context.getSharedPreferences("obslite_prefs", Context.MODE_PRIVATE)

    private fun getHandlebarWidthCm(): Int {
        return prefs.getInt("handlebar_width_cm", 60)
    }

    private val timeWindowMin = TimeWindowMinimum()

    // --- Debug-Helfer ---
    fun debugGetCompleteBytesSize(): Int = totalBytesWritten
    fun debugGetEventCount(): Int = totalEvents
    fun debugGetQueueSize(): Int = byteListQueue.size

    /**
     * Verarbeitet genau EIN komplettes COBS-Paket (Queue-Kopf).
     * Fixes:
     *  - Atomare Entnahme via pollFirst()
     *  - Dekodierung nur bei vorhandenem 0x00 (vollständiges Frame)
     *  - Generic-Event: keine doppelten Time-Felder (merge -> clearTime -> addTime)
     */
    fun handleEvent(
        lat: Double,
        lon: Double,
        altitude: Double,
        accuracy: Float
    ): ByteArray? {
        // Atomar entnehmen
        val rawList: LinkedList<Byte> = byteListQueue.pollFirst() ?: run {
            if (Log.isLoggable(TAG, Log.WARN)) Log.w(TAG, "handleEvent(): byteListQueue is empty.")
            return null
        }

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(
                TAG,
                "handleEvent(): polled frame, queueSize=${byteListQueue.size}, firstChunkSize=${rawList.size}, lat=$lat, lon=$lon"
            )
        }

        // Nur vollständige Frames dekodieren (wichtig für Robustheit)
        val hasTerminator = rawList.any { it.toInt() == 0x00 }
        if (!hasTerminator) {
            // Frame ist unvollständig → zurücklegen und später erneut versuchen
            byteListQueue.addFirst(rawList)
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(
                    TAG,
                    "handleEvent(): incomplete frame (no 0x00), re-queued; queueSize=${byteListQueue.size}"
                )
            }
            return null
        }

        val decodedData = try {
            CobsUtils.decode(rawList)
        } catch (e: Exception) {
            // Decode-Fehler: Frame verwerfen (telemetry bleibt erhalten)
            Log.e(TAG, "handleEvent(): COBS decode failed, dropping frame.", e)
            return null
        }

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "handleEvent(): decodedData length=${decodedData.size}")
        }

        val outBytes = ArrayList<Byte>()

        try {
            val obsEvent: Event = Event.parseFrom(decodedData)
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "handleEvent(): Event parsed")

            val currentTimeMillis: Long = System.currentTimeMillis()

            if (startTime == -1L && obsEvent.timeCount > 0) {
                startTime = obsEvent.getTime(0).seconds
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "handleEvent(): startTime from OBS: $startTime")
                }
            }

            val smartphoneTime = Time.newBuilder()
                .setSeconds(currentTimeMillis / 1000)
                .setNanoseconds(((currentTimeMillis % 1000) * 1_000_000).toInt())
                .setSourceId(3)
                .setReference(Time.Reference.UNIX)
                .build()

            // GPS-Delta → extra Geolocation-Event
            if (lat != lastLat || lon != lastLon) {
                val geolocation: Geolocation = Geolocation.newBuilder()
                    .setLatitude(lat)
                    .setLongitude(lon)
                    .setAltitude(altitude)
                    // Achtung: accuracy != HDOP; Schema ggf. später korrigieren
                    .setHdop(accuracy)
                    .build()

                val gpsEvent = Event.newBuilder()
                    .setGeolocation(geolocation)
                    .clearTime()               // nur Smartphone-Zeit beilegen
                    .addTime(smartphoneTime)
                    .build()

                val enc = encodeEvent(gpsEvent)
                outBytes.addAll(enc)
                totalBytesWritten += enc.size
                totalEvents++

                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(
                        TAG,
                        "handleEvent(): GPS event, encodedBytes=${enc.size}, totalBytes=$totalBytesWritten, totalEvents=$totalEvents"
                    )
                }

                lastLat = lat
                lastLon = lon
            }

            // === DISTANCE MEASUREMENT ===
            if (obsEvent.hasDistanceMeasurement()) {

                val rawDm = obsEvent.distanceMeasurement
                val rawMeters = rawDm.distance

                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(
                        TAG,
                        "handleEvent(): DM event, sourceId=${rawDm.sourceId}, raw=${rawMeters}m"
                    )
                }

                // --- Korrektur um halbe Lenkerbreite (Werte in cm) ---
                val distanceCm = (rawMeters * 100.0).roundToInt()
                val handlebarCmHalf = getHandlebarWidthCm() / 2.0
                val correctedCm = max(0, (distanceCm - handlebarCmHalf).roundToInt())
                val correctedMeters = correctedCm / 100.0f

                val correctedDm = rawDm.toBuilder()
                    .setDistance(correctedMeters)
                    .build()

                // (1) KORRIGIERTES DistanceMeasurement-Event (nur Smartphone-Zeit)
                val dmEvent = Event.newBuilder()
                    .clearTime()
                    .addTime(smartphoneTime)
                    .setDistanceMeasurement(correctedDm)
                    .build()

                val encDm = encodeEvent(dmEvent)
                outBytes.addAll(encDm)
                totalBytesWritten += encDm.size
                totalEvents++

                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(
                        TAG,
                        "handleEvent(): DM saved (corr=${correctedMeters}m), encodedBytes=${encDm.size}, totalBytes=$totalBytesWritten, totalEvents=$totalEvents"
                    )
                }

                // (2) Median füttern (nur left sensor sourceId==1) mit korrigierten cm
                if (rawDm.sourceId == 1) {
                    timeWindowMin.newValue(correctedCm)
                }

                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(
                        TAG,
                        "handleEvent(): DM timeWindowHasValue=${timeWindowMin.hasValue()}"
                    )
                }

                // === USER INPUT ===
            } else if (obsEvent.hasUserInput()) {

                val ui = obsEvent.userInput
                if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "handleEvent(): UserInput: $ui")

                // (1) UserInput-Event
                val uiEvent = Event.newBuilder()
                    .clearTime()
                    .addTime(smartphoneTime)
                    .setUserInput(ui)
                    .build()

                val encUi = encodeEvent(uiEvent)
                outBytes.addAll(encUi)
                totalBytesWritten += encUi.size
                totalEvents++

                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(
                        TAG,
                        "handleEvent(): UserInput saved, encodedBytes=${encUi.size}, totalBytes=$totalBytesWritten, totalEvents=$totalEvents"
                    )
                }

                // (2) Distanz am Knopfzeitpunkt (Minimum im 5s-Fenster, Portal-kompatibel)
                if (timeWindowMin.hasValue()) {
                    val minCm = timeWindowMin.minimum
                    lastMedianAtPressCm = minCm

                    val dmAtPress = DistanceMeasurement.newBuilder()
                        .setSourceId(1)
                        .setDistance(minCm / 100.0f)
                        .build()

                    val dmPressEvent = Event.newBuilder()
                        .clearTime()
                        .addTime(smartphoneTime)
                        .setDistanceMeasurement(dmAtPress)
                        .build()

                    val encDmPress = encodeEvent(dmPressEvent)
                    outBytes.addAll(encDmPress)
                    totalBytesWritten += encDmPress.size
                    totalEvents++

                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(
                            TAG,
                            "handleEvent(): DM@press saved: ${minCm}cm -> ${dmAtPress.distance}m, encodedBytes=${encDmPress.size}, totalBytes=$totalBytesWritten, totalEvents=$totalEvents"
                        )
                    }
                } else {
                    if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(
                        TAG,
                        "handleEvent(): UserInput: no values in time window yet"
                    )
                }

                // === GENERIC ===
            } else {
                // WICHTIG: keine doppelten Zeiten – nur Smartphone-Zeit setzen
                val genericEvent = Event.newBuilder()
                    .mergeFrom(obsEvent)
                    .clearTime()
                    .addTime(smartphoneTime)
                    .build()

                val encGen = encodeEvent(genericEvent)
                outBytes.addAll(encGen)
                totalBytesWritten += encGen.size
                totalEvents++

                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(
                        TAG,
                        "handleEvent(): Other type saved, encodedBytes=${encGen.size}, totalBytes=$totalBytesWritten, totalEvents=$totalEvents"
                    )
                }
            }

        } catch (e: InvalidProtocolBufferException) {
            Log.e(TAG, "Invalid protobuf in handleEvent", e)
        }

        return if (outBytes.isNotEmpty()) outBytes.toByteArray() else null
    }

    /**
     * Prüfen, ob im ersten Byte-Block ein 0x00 vorkommt (COBS-Paket komplett).
     * Hinweis: handleEvent() prüft zusätzlich den polled Frame nochmals.
     */
    fun completeCobsAvailable(): Boolean {
        val first = byteListQueue.peekFirst() ?: return false
        for (b in first) {
            if (b.toInt() == 0x00) return true
        }
        return false
    }

    /**
     * Bytes vom USB-Stream in COBS-Pakete aufteilen (API unverändert).
     */
    fun fillByteList(data: ByteArray?) {
        if (data == null) return
        for (datum in data) {
            if (lastByteRead?.toInt() == 0x00 || byteListQueue.isEmpty()) {
                val newByteList = LinkedList<Byte>()
                newByteList.add(datum)
                byteListQueue.add(newByteList)
            } else {
                byteListQueue.last.add(datum)
            }
            lastByteRead = datum
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(
                TAG,
                "fillByteList(): added ${data.size} bytes, queueSize=${byteListQueue.size}, lastByteRead=0x${
                    String.format("%02X", lastByteRead)
                }"
            )
        }
    }

    /**
     * Event -> COBS-kodiertes Byte-Array inkl. 0x00-Delimiter.
     */
    private fun encodeEvent(event: Event?): Collection<Byte> {
        if (event == null) {
            Log.w(TAG, "encodeEvent(): event is null.")
            return emptyList()
        }

        val raw = event.toByteArray()
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "encodeEvent(): raw size=${raw.size} bytes")

        val encoded = CobsUtils.encode2(raw)
        val out = ArrayList<Byte>(encoded.size + 1)
        out.addAll(encoded)
        out.add(0.toByte()) // Frame-Delimiter

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "encodeEvent(): encoded size=${out.size} bytes (with 0x00)")
        }
        return out
    }

    /**
     * Streaming-Modus: kein RAM-Accumulation.
     */
    fun getCompleteEvents(): ByteArray {
        Log.w(TAG, "getCompleteEvents(): streaming mode – always empty.")
        return ByteArray(0)
    }

    /**
     * Zusätzliche GPS-Events aus Smartphone-Location.
     */
    fun addGPSEvent(location: Location): ByteArray {
        val geolocation: Geolocation = Geolocation.newBuilder()
            .setLatitude(location.latitude)
            .setLongitude(location.longitude)
            .setAltitude(location.altitude)
            .setHdop(location.accuracy) // Achtung: Semantik siehe Kommentar in handleEvent()
            .build()

        val time: Time = Time.newBuilder()
            .setSeconds(location.time / 1000)
            .setNanoseconds(((location.time % 1000) * 1_000_000).toInt())
            .setSourceId(3)
            .setReference(Time.Reference.UNIX)
            .build()

        val gpsEvent = Event.newBuilder()
            .setGeolocation(geolocation)
            .clearTime()
            .addTime(time)
            .build()

        val enc = encodeEvent(gpsEvent)
        totalBytesWritten += enc.size
        totalEvents++

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(
                TAG,
                "addGPSEvent(): encodedBytes=${enc.size}, totalBytes=$totalBytesWritten, totalEvents=$totalEvents"
            )
        }

        return enc.toByteArray()
    }

    /**
     * 5-Sekunden Time-Window Minimum fuer Distanzwerte (in cm).
     * Portal-kompatibel: Gibt den Minimalwert innerhalb der letzten 5 Sekunden zurueck.
     * (Gleiche Logik wie iOS BluetoothManager.timeWindowMinimum)
     */
    class TimeWindowMinimum {
        private val TAG = "TimeWindowMin_LOG"
        private val windowMs = 5000L // 5 Sekunden
        private val entries = ArrayDeque<Entry>()
        var minimum: Int = 0
            private set

        private data class Entry(val value: Int, val timestampMs: Long)

        fun hasValue(): Boolean = entries.isNotEmpty()

        fun newValue(distanceCm: Int) {
            val now = System.currentTimeMillis()
            entries.addLast(Entry(distanceCm, now))

            // Eintraege aelter als 5 Sekunden entfernen
            while (entries.isNotEmpty() && now - entries.first().timestampMs > windowMs) {
                entries.removeFirst()
            }

            // Minimum im Fenster berechnen
            if (entries.isNotEmpty()) {
                minimum = entries.minOf { it.value }
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "new minimum = $minimum cm (from ${entries.size} samples in 5s window)")
                }
            }
        }
    }
}