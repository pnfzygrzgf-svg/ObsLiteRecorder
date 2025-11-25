package com.example.obsliterecorder.obslite

import android.app.Service
import android.content.Intent
import android.location.Location
import android.os.Binder
import android.os.IBinder
import android.util.Log

class ObsLiteService : Service() {

    companion object {
        const val TAG = "ObsLiteService_LOG"
    }

    // Binder für die Activity (MainActivity bindet sich an den Service)
    inner class LocalBinder : Binder() {
        fun getService(): ObsLiteService = this@ObsLiteService
    }

    private val binder = LocalBinder()

    // Session verarbeitet Events (COBS -> Proto -> korrigierte Distanzen -> COBS)
    // und liefert COBS-kodierte Bytes zurück
    private lateinit var obsSession: OBSLiteSession

    // FileWriter schreibt während der Fahrt fortlaufend in eine .bin
    private lateinit var fileWriter: OBSLiteFileWriter

    // Merkt sich, ob gerade aufgezeichnet wird
    private var isRecording: Boolean = false

    // Letzte bekannte GPS-Position (für handleEvent)
    private var lastLocation: Location? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate()")
        obsSession = OBSLiteSession(this)
        fileWriter = OBSLiteFileWriter(this)
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "onBind()")
        return binder
    }

    /**
     * Wird von MainActivity aufgerufen, wenn der Start-Button gedrückt wird.
     */
    fun startRecording() {
        if (isRecording) {
            Log.d(TAG, "startRecording(): already recording")
            return
        }
        Log.d(TAG, "startRecording()")

        // Neue Session für diese Fahrt (damit Statistiken/State frisch sind)
        obsSession = OBSLiteSession(this)

        // Neue Datei anlegen
        fileWriter.startSession()

        isRecording = true
        Log.d(
            TAG,
            "startRecording(): isRecording=$isRecording, sessionBytes=${obsSession.debugGetCompleteBytesSize()}, events=${obsSession.debugGetEventCount()}"
        )
    }

    /**
     * Wird von MainActivity aufgerufen, wenn der Stop-Button gedrückt wird.
     * Im Streaming-Modus ist hier nur noch das saubere Schließen der Datei nötig.
     */
    fun stopRecording() {
        if (!isRecording) {
            Log.d(TAG, "stopRecording(): not recording")
            return
        }
        Log.d(TAG, "stopRecording()")

        try {
            // Alle Events wurden bereits während der Aufnahme in die Datei geschrieben.
            fileWriter.finishSession()
            Log.d(
                TAG,
                "stopRecording(): file closed. sessionBytes=${obsSession.debugGetCompleteBytesSize()}, events=${obsSession.debugGetEventCount()}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "stopRecording(): Fehler beim Schließen der Datei", e)
        } finally {
            isRecording = false
            Log.d(TAG, "stopRecording(): isRecording=$isRecording")
        }
    }

    /**
     * Wird von MainActivity aufgerufen, wenn neue USB-Daten vom OBS Lite ankommen.
     * (MainActivity ruft das aus onNewData(...) des SerialInputOutputManager auf)
     */
    fun onUsbData(data: ByteArray) {
        Log.d(TAG, "onUsbData(): ${data.size} Bytes, isRecording=$isRecording")

        // Rohdaten in Session-COBS-Puffer schieben
        obsSession.fillByteList(data)
        Log.d(
            TAG,
            "onUsbData(): nach fillByteList -> queueSize=${obsSession.debugGetQueueSize()}"
        )

        if (!isRecording) {
            // Wenn nicht aufgezeichnet wird, nur für Live-Preview in MainActivity interessant
            Log.d(TAG, "onUsbData(): not recording, Session wird nicht in Datei geschrieben")
            return
        }

        try {
            var handledCount = 0
            // So lange komplette COBS-Pakete vorhanden sind, Events erzeugen
            while (obsSession.completeCobsAvailable()) {
                val loc = lastLocation

                Log.d(
                    TAG,
                    "onUsbData(): completeCobsAvailable=true, handledCount=$handledCount, queueSize=${obsSession.debugGetQueueSize()}"
                )

                // handleEvent liefert jetzt die COBS-kodierten Bytes der erzeugten Events zurück
                val bytes: ByteArray? = if (loc != null) {
                    obsSession.handleEvent(
                        lat = loc.latitude,
                        lon = loc.longitude,
                        altitude = loc.altitude,
                        accuracy = loc.accuracy
                    )
                } else {
                    // Falls noch keine GPS-Position vorhanden ist, Dummy-Werte
                    Log.w(TAG, "onUsbData(): keine gültige Location, verwende Dummy-Werte")
                    obsSession.handleEvent(
                        lat = 0.0,
                        lon = 0.0,
                        altitude = 0.0,
                        accuracy = 9999f
                    )
                }

                // Wenn Events erzeugt wurden: sofort in die Datei schreiben
                if (bytes != null && bytes.isNotEmpty()) {
                    synchronized(fileWriter) {
                        fileWriter.writeSessionData(bytes)
                    }
                    Log.d(
                        TAG,
                        "onUsbData(): ${bytes.size} Bytes in Datei geschrieben, sessionBytes=${obsSession.debugGetCompleteBytesSize()}, events=${obsSession.debugGetEventCount()}"
                    )
                }

                handledCount++
                Log.d(
                    TAG,
                    "onUsbData(): nach handleEvent -> handledCount=$handledCount, queueSize=${obsSession.debugGetQueueSize()}"
                )

                // Sicherheitsbremse
                if (handledCount > 1000) {
                    Log.e(
                        TAG,
                        "onUsbData(): Sicherheitsabbruch nach 1000 Events, evtl. Queue-Problem."
                    )
                    break
                }
            }

            Log.d(TAG, "onUsbData(): handledCount=$handledCount")
        } catch (e: Exception) {
            Log.e(TAG, "onUsbData(): Fehler beim Verarbeiten der COBS-Pakete", e)
        }
    }

    /**
     * Wird von MainActivity bei jedem neuen GPS-Fix aufgerufen.
     */
    fun onLocationChanged(location: Location) {
        lastLocation = location
        Log.d(
            TAG,
            "onLocationChanged(): isRecording=$isRecording, lat=${location.latitude}, lon=${location.longitude}, acc=${location.accuracy}"
        )

        // WICHTIG: GPS-Event explizit in die Session schreiben, wenn aufgenommen wird
        if (isRecording) {
            // addGPSEvent liefert jetzt direkt die COBS-kodierten Bytes zurück
            val bytes = obsSession.addGPSEvent(location)
            synchronized(fileWriter) {
                fileWriter.writeSessionData(bytes)
            }
            Log.d(
                TAG,
                "onLocationChanged(): GPS-Event geschrieben -> bytes=${bytes.size}, sessionBytes=${obsSession.debugGetCompleteBytesSize()}, events=${obsSession.debugGetEventCount()}"
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "onDestroy(): Service wird zerstört, isRecording=$isRecording")
        // Falls der Service unerwartet beendet wird, Datei sicherheitshalber schließen
        try {
            if (isRecording) {
                fileWriter.finishSession()
            }
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy(): Fehler beim Schließen der Datei", e)
        }
        isRecording = false
    }
}
