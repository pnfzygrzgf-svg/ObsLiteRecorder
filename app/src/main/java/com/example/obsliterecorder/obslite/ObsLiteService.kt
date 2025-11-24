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

    // Session sammelt die Events (COBS -> Proto -> korrigierte Distanzen -> COBS)
    private lateinit var obsSession: OBSLiteSession

    // FileWriter schreibt am Ende alles in eine .bin
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

        // Neue Session für diese Fahrt (damit Events/Bytes frisch sind)
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
     */
    fun stopRecording() {
        if (!isRecording) {
            Log.d(TAG, "stopRecording(): not recording")
            return
        }
        Log.d(TAG, "stopRecording()")

        try {
            // Alle gesammelten Events der Session holen und in Datei schreiben
            val data = obsSession.getCompleteEvents()
            Log.d(
                TAG,
                "stopRecording(): getCompleteEvents() returned ${data.size} bytes, eventsInSession=${obsSession.debugGetEventCount()}"
            )

            if (data.isNotEmpty()) {
                fileWriter.writeSessionData(data)
                Log.d(TAG, "stopRecording(): data written to file")
            } else {
                Log.w(
                    TAG,
                    "stopRecording(): Session enthält keine Events, es wird nichts geschrieben."
                )
            }

            fileWriter.finishSession()
        } catch (e: Exception) {
            Log.e(TAG, "stopRecording(): Fehler beim Schreiben/Schließen der Datei", e)
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

                if (loc != null) {
                    obsSession.handleEvent(
                        lat = loc.latitude,
                        lon = loc.longitude,
                        altitude = loc.altitude,
                        accuracy = loc.accuracy
                    )
                } else {
                    // Falls noch keine GPS-Position vorhanden ist, Dummy-Position
                    Log.w(TAG, "onUsbData(): keine gültige Location, verwende Dummy-Werte")
                    obsSession.handleEvent(
                        lat = 0.0,
                        lon = 0.0,
                        altitude = 0.0,
                        accuracy = 9999f
                    )
                }

                handledCount++
                Log.d(
                    TAG,
                    "onUsbData(): nach handleEvent -> sessionBytes=${obsSession.debugGetCompleteBytesSize()}, events=${obsSession.debugGetEventCount()}, queueSize=${obsSession.debugGetQueueSize()}"
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
            obsSession.addGPSEvent(location)
            Log.d(
                TAG,
                "onLocationChanged(): GPS-Event hinzugefügt -> sessionBytes=${obsSession.debugGetCompleteBytesSize()}, events=${obsSession.debugGetEventCount()}"
            )
        }
    }
}
