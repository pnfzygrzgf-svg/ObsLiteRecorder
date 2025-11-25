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
import java.util.TreeSet
import java.util.concurrent.ConcurrentLinkedDeque

class OBSLiteSession(private val context: Context) {

    private val TAG = "OBSLiteSession"

    var obsLiteStartTime: Long = 0L
    var startTime: Long = -1L

    private var lastLat: Double = 0.0
    private var lastLon: Double = 0.0

    // Statt alle Events im RAM zu halten, nur noch Statistiken:
    private var totalBytesWritten: Int = 0
    private var totalEvents: Int = 0

    // Letzter Median beim Knopfdruck (in cm), null wenn noch keiner vorhanden
    var lastMedianAtPressCm: Int? = null
        private set

    // COBS-Puffer für USB-Daten
    val byteListQueue: ConcurrentLinkedDeque<LinkedList<Byte>> = ConcurrentLinkedDeque()
    var lastByteRead: Byte? = null

    // Lenkerbreite aus SharedPreferences (in cm)
    private val prefs = context.getSharedPreferences("obslite_prefs", Context.MODE_PRIVATE)

    private fun getHandlebarWidthCm(): Int {
        // Default: 60 cm, falls noch nichts gespeichert ist
        return prefs.getInt("handlebar_width_cm", 60)
    }

    // Gleitender Median (in cm) über die zuletzt gemessenen Distanzen
    private val movingMedian = MovingMedian()

    // --- Debug-Helfer für den Service ---
    fun debugGetCompleteBytesSize(): Int = totalBytesWritten
    fun debugGetEventCount(): Int = totalEvents
    fun debugGetQueueSize(): Int = byteListQueue.size

    /**
     * Verarbeitet genau EIN komplettes COBS-Paket (byteListQueue.first),
     * erzeugt daraus Events (DistanceMeasurement, UserInput, Geolocation, …)
     * und gibt die COBS-kodierten Bytes dieser Events zurück.
     *
     * Alle Events bekommen genau EINE Zeitquelle:
     *   - Smartphone-Zeit (UNIX, source_id = 3)
     *
     * Rückgabe:
     *  - ByteArray der neu erzeugten Events (Cobs-encodiert, inkl. 0x00-Terminatoren)
     *  - null, falls nichts erzeugt wurde oder ein Fehler auftrat
     */
    fun handleEvent(
        lat: Double,
        lon: Double,
        altitude: Double,
        accuracy: Float
    ): ByteArray? {
        if (byteListQueue.isEmpty()) {
            Log.w(TAG, "handleEvent(): byteListQueue is empty, nichts zu tun.")
            return null
        }

        val rawList = byteListQueue.first
        Log.d(
            TAG,
            "handleEvent(): queueSize=${byteListQueue.size}, firstChunkSize=${rawList.size}, lat=$lat, lon=$lon"
        )

        val decodedData = try {
            CobsUtils.decode(rawList)
        } catch (e: Exception) {
            Log.e(TAG, "handleEvent(): Fehler beim COBS-Decode, Chunk wird verworfen.", e)
            byteListQueue.removeFirst()
            return null
        }

        Log.d(TAG, "handleEvent(): decodedData length=${decodedData.size}")

        // Hier sammeln wir alle Bytes, die während dieses handleEvent-Aufrufs erzeugt werden
        val outBytes = ArrayList<Byte>()

        try {
            val obsEvent: Event = Event.parseFrom(decodedData)
            Log.d(TAG, "handleEvent(): Event erfolgreich geparst")

            val currentTimeMillis: Long = System.currentTimeMillis()

            // Startzeit aus erstem OBS-Zeitstempel übernehmen (falls vorhanden)
            if (startTime == -1L && obsEvent.timeCount > 0) {
                startTime = obsEvent.getTime(0).seconds
                Log.d(TAG, "handleEvent(): startTime vom OBS übernommen: $startTime")
            }

            // Zeit vom Smartphone (UNIX, für's Portal wichtig)
            val smartphoneTime = Time.newBuilder()
                .setSeconds(currentTimeMillis / 1000)
                .setNanoseconds(((currentTimeMillis % 1000) * 1_000_000).toInt())
                .setSourceId(3) // 3 = Smartphone
                .setReference(Time.Reference.UNIX)
                .build()

            // Falls GPS-Position neu: extra Geolocation-Event erzeugen
            if (lat != lastLat || lon != lastLon) {
                val geolocation: Geolocation = Geolocation.newBuilder()
                    .setLatitude(lat)
                    .setLongitude(lon)
                    .setAltitude(altitude)
                    .setHdop(accuracy)
                    .build()

                val gpsEvent = Event.newBuilder()
                    .setGeolocation(geolocation)
                    .addTime(smartphoneTime)
                    .build()

                val enc = encodeEvent(gpsEvent)
                outBytes.addAll(enc)
                totalBytesWritten += enc.size
                totalEvents++

                Log.d(
                    TAG,
                    "handleEvent(): GPS-Event erzeugt, encodedBytes=${enc.size}, totalBytes=$totalBytesWritten, totalEvents=$totalEvents"
                )

                lastLat = lat
                lastLon = lon
            }

            // === DISTANCE MEASUREMENT (Abstand) ===
            if (obsEvent.hasDistanceMeasurement()) {

                val rawDm = obsEvent.distanceMeasurement
                val rawMeters = rawDm.distance

                Log.d(
                    TAG,
                    "handleEvent(): DistanceMeasurement-Event, sourceId=${rawDm.sourceId}, raw=${rawMeters}m"
                )

                // 1) Event mit *ROHEN* Metern so speichern
                val dmEvent = Event.newBuilder()
                    .addTime(smartphoneTime)
                    .setDistanceMeasurement(rawDm)
                    .build()

                val encDm = encodeEvent(dmEvent)
                outBytes.addAll(encDm)
                totalBytesWritten += encDm.size
                totalEvents++

                Log.d(
                    TAG,
                    "handleEvent(): DM-Event gespeichert, encodedBytes=${encDm.size}, totalBytes=$totalBytesWritten, totalEvents=$totalEvents"
                )

                // 2) Für den gleitenden Median die Lenkerbreite berücksichtigen (in cm)
                val handlebarCm = getHandlebarWidthCm()         // z.B. 60
                val distanceCm = (rawMeters * 100.0f).toInt()   // m -> cm
                val correctedCm = (distanceCm - handlebarCm / 2).coerceAtLeast(0)

                // nur für left sensor (sourceId == 1) Median berechnen
                if (rawDm.sourceId == 1) {
                    movingMedian.newValue(correctedCm)
                }

                Log.d(
                    TAG,
                    "handleEvent(): DM event: raw=${rawMeters}m, handlebar=${handlebarCm}cm, corrected=${correctedCm}cm, medianHas=${movingMedian.hasMedian()}"
                )

                // === USER INPUT (Knopf) ===
            } else if (obsEvent.hasUserInput()) {

                val ui = obsEvent.userInput
                Log.d(TAG, "handleEvent(): UserInput event: $ui")

                // 1) UserInput-Event *immer* speichern
                val uiEvent = Event.newBuilder()
                    .addTime(smartphoneTime)
                    .setUserInput(ui)
                    .build()

                val encUi = encodeEvent(uiEvent)
                outBytes.addAll(encUi)
                totalBytesWritten += encUi.size
                totalEvents++

                Log.d(
                    TAG,
                    "handleEvent(): UserInput-Event gespeichert, encodedBytes=${encUi.size}, totalBytes=$totalBytesWritten, totalEvents=$totalEvents"
                )

                // 2) Zusätzlich ein Distanz-Event zum Knopfzeitpunkt speichern,
                //    falls wir schon einen Median haben.
                if (movingMedian.hasMedian()) {
                    val medianCm = movingMedian.median

                    // Für die UI merken (Überholabstand)
                    lastMedianAtPressCm = medianCm

                    val dmAtPress = DistanceMeasurement.newBuilder()
                        .setSourceId(1)
                        .setDistance(medianCm / 100.0f) // cm -> m
                        .build()

                    val dmPressEvent = Event.newBuilder()
                        .addTime(smartphoneTime)
                        .setDistanceMeasurement(dmAtPress)
                        .build()

                    val encDmPress = encodeEvent(dmPressEvent)
                    outBytes.addAll(encDmPress)
                    totalBytesWritten += encDmPress.size
                    totalEvents++

                    Log.d(
                        TAG,
                        "handleEvent(): Distance-at-press-Event gespeichert: median=${medianCm}cm -> ${dmAtPress.distance}m, encodedBytes=${encDmPress.size}, totalBytes=$totalBytesWritten, totalEvents=$totalEvents"
                    )
                } else {
                    Log.d(TAG, "handleEvent(): UserInput: no median yet, no distance-at-press event")
                }

                // === ALLE ANDEREN EVENT-TYPEN ===
            } else {
                val genericEvent = Event.newBuilder()
                    .addTime(smartphoneTime)
                    .mergeFrom(obsEvent) // andere Felder übernehmen
                    .build()

                val encGen = encodeEvent(genericEvent)
                outBytes.addAll(encGen)
                totalBytesWritten += encGen.size
                totalEvents++

                Log.d(
                    TAG,
                    "handleEvent(): Other event type stored, encodedBytes=${encGen.size}, totalBytes=$totalBytesWritten, totalEvents=$totalEvents"
                )
            }

        } catch (e: InvalidProtocolBufferException) {
            Log.e(TAG, "Invalid protobuf in handleEvent", e)
        } finally {
            // erstes Paket aus Queue entfernen – sonst Endlosschleife
            if (byteListQueue.isNotEmpty()) {
                byteListQueue.removeFirst()
            }
            Log.d(TAG, "handleEvent(): Paket entfernt, neue queueSize=${byteListQueue.size}")
        }

        return if (outBytes.isNotEmpty()) {
            outBytes.toByteArray()
        } else {
            null
        }
    }

    /**
     * Prüfen, ob im ersten Byte-Block ein 0x00 vorkommt (COBS-Paket komplett).
     */
    fun completeCobsAvailable(): Boolean {
        val first = byteListQueue.peekFirst() ?: return false
        for (b in first) {
            if (b.toInt() == 0x00) return true
        }
        return false
    }

    /**
     * Bytes vom USB-Stream in COBS-Pakete aufteilen.
     * Jedes Element der Queue entspricht: [COBS-kodiertes Event + 0x00 Terminator]
     */
    fun fillByteList(data: ByteArray?) {
        if (data == null) return
        for (datum in data) {
            // neues Paket, wenn letztes Byte 0x00 war oder es noch kein Paket gibt
            if (lastByteRead?.toInt() == 0x00 || byteListQueue.isEmpty()) {
                val newByteList = LinkedList<Byte>()
                newByteList.add(datum)
                byteListQueue.add(newByteList)
            } else {
                // aktuelles Paket fortsetzen
                byteListQueue.last.add(datum)
            }
            lastByteRead = datum
        }
        Log.d(
            TAG,
            "fillByteList(): added ${data.size} bytes, queueSize=${byteListQueue.size}, lastByteRead=0x${
                String.format(
                    "%02X",
                    lastByteRead
                )
            }"
        )
    }

    /**
     * Event -> COBS-kodiertes Byte-Array:
     *
     *   [ COBS(event.toByteArray()) + 0x00 ]
     *
     * encode2() liefert NUR die COBS-Daten, hier fügen wir den 0x00-Delimiter an.
     */
    private fun encodeEvent(event: Event?): Collection<Byte> {
        if (event == null) {
            Log.w(TAG, "encodeEvent(): event ist null, nichts zu encodieren.")
            return emptyList()
        }

        val raw = event.toByteArray()
        Log.d(TAG, "encodeEvent(): raw size=${raw.size} bytes")

        val encoded = CobsUtils.encode2(raw) // ohne 0x00
        val out = ArrayList<Byte>(encoded.size + 1)
        out.addAll(encoded)
        out.add(0.toByte()) // <- WICHTIG: Frame-Delimiter anhängen!

        Log.d(
            TAG,
            "encodeEvent(): encoded size=${out.size} bytes (inkl. 0x00-Terminator)"
        )
        return out
    }

    /**
     * Früher wurde hier der komplette Inhalt der Session zurückgegeben.
     * Im Streaming-Modus wird nichts mehr im RAM gesammelt, daher immer leer.
     */
    fun getCompleteEvents(): ByteArray {
        Log.w(
            TAG,
            "getCompleteEvents(): Streaming-Modus aktiv – keine Events im RAM. Rückgabe ist immer leer."
        )
        return ByteArray(0)
    }

    /**
     * Zusätzliche GPS-Events aus der Smartphone-Location einfügen.
     * Rückgabe: COBS-kodierte Bytes (inkl. 0x00), bereit zum Schreiben in die Datei.
     */
    fun addGPSEvent(location: Location): ByteArray {
        val geolocation: Geolocation = Geolocation.newBuilder()
            .setLatitude(location.latitude)
            .setLongitude(location.longitude)
            .setAltitude(location.altitude)
            .setHdop(location.accuracy)
            .build()

        val time: Time = Time.newBuilder()
            .setSeconds(location.time / 1000)
            .setNanoseconds(((location.time % 1000) * 1_000_000).toInt())
            .setSourceId(3)
            .setReference(Time.Reference.UNIX)
            .build()

        val gpsEvent = Event.newBuilder()
            .setGeolocation(geolocation)
            .addTime(time)
            .build()

        val enc = encodeEvent(gpsEvent)
        totalBytesWritten += enc.size
        totalEvents++

        Log.d(
            TAG,
            "addGPSEvent(): GPS-Event hinzugefügt, encodedBytes=${enc.size}, totalBytes=$totalBytesWritten, totalEvents=$totalEvents"
        )

        return enc.toByteArray()
    }

    /**
     * Gleitender Median für die Distanzwerte (in cm).
     */
    class MovingMedian {
        private val TAG = "MovingMedian_LOG"
        private var distanceArray: ArrayList<Int> = ArrayList()
        var windowSize = 3
        var median: Int = 0
            private set

        fun hasMedian(): Boolean = distanceArray.size >= windowSize

        // Pair-Klasse für Wert + Index
        class Pair(private var value: Int, private var index: Int) : Comparable<Pair?> {
            override fun compareTo(other: Pair?): Int {
                return if (index == other?.index) {
                    0
                } else if (value == other?.value) {
                    index.compareTo(other.index)
                } else {
                    value.compareTo(other!!.value)
                }
            }

            fun value(): Int = value

            fun renew(v: Int, p: Int) {
                value = v
                index = p
            }

            override fun toString(): String {
                return String.format("(%d, %d)", value, index)
            }
        }

        private fun printMedian(
            minSet: TreeSet<Pair?>,
            maxSet: TreeSet<Pair?>,
            window: Int
        ): Int {
            return if (window % 2 == 0) {
                (((minSet.last()!!.value() + maxSet.first()!!.value()) / 2.0).toInt())
            } else {
                if (minSet.size > maxSet.size) minSet.last()!!.value() else maxSet.first()!!.value()
            }
        }

        private fun findMedian(arr: ArrayList<Int>, k: Int): ArrayList<Int> {
            val minSet = TreeSet<Pair?>()
            val maxSet = TreeSet<Pair?>()

            val result: ArrayList<Int> = ArrayList()

            val windowPairs = arrayOfNulls<Pair>(k)
            for (i in 0 until k) {
                windowPairs[i] = Pair(arr[i], i)
            }

            for (i in 0 until (k / 2)) {
                maxSet.add(windowPairs[i])
            }
            for (i in k / 2 until k) {
                if (arr[i] < maxSet.first()!!.value()) {
                    minSet.add(windowPairs[i])
                } else {
                    minSet.add(maxSet.pollFirst())
                    maxSet.add(windowPairs[i])
                }
            }
            result.add(printMedian(minSet, maxSet, k))
            for (i in k until arr.size) {
                val temp = windowPairs[i % k]
                if (temp!!.value() <= minSet.last()!!.value()) {
                    minSet.remove(temp)
                    temp.renew(arr[i], i)
                    if (temp.value() < maxSet.first()!!.value()) {
                        minSet.add(temp)
                    } else {
                        minSet.add(maxSet.pollFirst())
                        maxSet.add(temp)
                    }
                } else {
                    maxSet.remove(temp)
                    temp.renew(arr[i], i)
                    if (temp.value() > minSet.last()!!.value()) {
                        maxSet.add(temp)
                    } else {
                        maxSet.add(minSet.pollLast())
                        minSet.add(temp)
                    }
                }
                result.add(printMedian(minSet, maxSet, k))
            }
            return result
        }

        fun newValue(distanceCm: Int) {
            // maximal ~5 s Historie (122 Werte), älteste Werte wegwerfen
            if (distanceArray.size >= 122) {
                distanceArray = ArrayList(distanceArray.drop(1))
            }
            distanceArray.add(distanceCm)
            if (distanceArray.size >= windowSize) {
                val medians = findMedian(distanceArray, windowSize)
                median = medians.last()
                Log.d(TAG, "new median = $median cm (from ${distanceArray.size} samples)")
            }
        }
    }
}
