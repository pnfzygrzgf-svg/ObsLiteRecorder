// file: com/example/obsliterecorder/obslite/ObsLiteService.kt
package com.example.obsliterecorder.obslite

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.location.Location
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.obsliterecorder.R

class ObsLiteService : Service() {

    companion object {
        const val TAG = "ObsLiteService_LOG"

        private const val CHANNEL_ID = "obs_lite_recording"
        private const val CHANNEL_NAME = "OBS Lite Aufzeichnung"
        private const val NOTIFICATION_ID = 1001
    }

    inner class LocalBinder : Binder() {
        fun getService(): ObsLiteService = this@ObsLiteService
    }

    private val binder = LocalBinder()

    private lateinit var obsSession: OBSLiteSession
    private lateinit var fileWriter: OBSLiteFileWriter

    @Volatile private var isRecording: Boolean = false
    @Volatile private var lastLocation: Location? = null

    // Single-threaded I/O: garantiert Reihenfolge, erspart synchronized
    private lateinit var ioThread: HandlerThread
    private lateinit var ioHandler: Handler

    private var isForeground = false

    fun getLastMedianAtPressCm(): Int? = obsSession.lastMedianAtPressCm

    override fun onCreate() {
        super.onCreate()
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "onCreate()")
        obsSession = OBSLiteSession(this)
        fileWriter = OBSLiteFileWriter(this)

        ioThread = HandlerThread("obs-io")
        ioThread.start()
        ioHandler = Handler(ioThread.looper)

        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "onBind()")
        return binder
    }

    /**
     * Wird aufgerufen, wenn der Service per startForegroundService(...) gestartet wird.
     * Hier starten wir die Foreground-Notification.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "onStartCommand() id=$startId intent=$intent")
        if (!isForeground) {
            startInForeground()
        }
        // START_STICKY: falls der Prozess gekillt wird, versucht Android, den Service neu zu starten
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            val existing = mgr.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Hintergrunddienst für OBS Lite Aufzeichnung"
                }
                mgr.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(isRecordingNow: Boolean): Notification {
        val text = if (isRecordingNow) {
            "Aufnahme läuft – Fahrt wird aufgezeichnet"
        } else {
            "Bereit – Aufnahme kann in der App gestartet werden"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OBS Lite Recorder")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher) // falls du ein eigenes Icon hast: R.drawable.ic_notification
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun startInForeground() {
        val notification = buildNotification(isRecording)
        startForeground(NOTIFICATION_ID, notification)
        isForeground = true
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "startInForeground(): started")
    }

    private fun updateForegroundNotification() {
        if (!isForeground) return
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIFICATION_ID, buildNotification(isRecording))
    }

    fun startRecording() {
        if (isRecording) {
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "startRecording(): already recording")
            return
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "startRecording()")

        // Alles auf IO-Thread ausführen, um Reihenfolge zu garantieren
        ioHandler.post {
            try {
                // frische Session für Fahrt
                obsSession = OBSLiteSession(this)
                // Datei öffnen
                fileWriter.startSession()
                isRecording = true
                updateForegroundNotification()

                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(
                        TAG,
                        "startRecording(): isRecording=$isRecording, sessionBytes=${obsSession.debugGetCompleteBytesSize()}, events=${obsSession.debugGetEventCount()}"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "startRecording(): failed to start session", e)
                isRecording = false
                updateForegroundNotification()
            }
        }
    }

    fun stopRecording() {
        if (!isRecording) {
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "stopRecording(): not recording")
            return
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "stopRecording()")

        ioHandler.post {
            try {
                fileWriter.finishSession()
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(
                        TAG,
                        "stopRecording(): file closed. sessionBytes=${obsSession.debugGetCompleteBytesSize()}, events=${obsSession.debugGetEventCount()}"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "stopRecording(): error closing file", e)
            } finally {
                isRecording = false
                updateForegroundNotification()
                if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "stopRecording(): isRecording=$isRecording")
            }
        }
    }

    /**
     * USB-Daten vom OBS Lite; wird ggf. vom Serial-Callback (Main/UI-Thread) aufgerufen.
     * Wir verschieben sofort auf den IO-Thread.
     */
    fun onUsbData(data: ByteArray) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onUsbData(): ${data.size} bytes, isRecording=$isRecording")
        }

        ioHandler.post {
            // Rohdaten in Session-COBS-Puffer schieben
            obsSession.fillByteList(data)
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onUsbData(): after fillByteList -> queueSize=${obsSession.debugGetQueueSize()}")
            }

            if (!isRecording) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "onUsbData(): not recording, skip file write")
                }
                return@post
            }

            var handledCount = 0
            val maxEvents = 10_000 // verhindert Endlosschleifen bei korrupten Streams
            val startNs = System.nanoTime()
            val maxDurationNs = 50_000_000L // ~50ms pro Batch

            while (obsSession.completeCobsAvailable()) {
                val loc: Location? = lastLocation
                val bytes: ByteArray? = if (loc != null) {
                    obsSession.handleEvent(
                        lat = loc.latitude,
                        lon = loc.longitude,
                        altitude = loc.altitude,
                        accuracy = loc.accuracy
                    )
                } else {
                    // Keine Location verfügbar – neutraler Fallback
                    obsSession.handleEvent(
                        lat = 0.0,
                        lon = 0.0,
                        altitude = 0.0,
                        accuracy = 9999f
                    )
                }

                if (bytes != null && bytes.isNotEmpty()) {
                    try {
                        fileWriter.writeSessionData(bytes)
                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                            Log.d(
                                TAG,
                                "onUsbData(): wrote ${bytes.size} bytes, sessionBytes=${obsSession.debugGetCompleteBytesSize()}, events=${obsSession.debugGetEventCount()}"
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "onUsbData(): write failed", e)
                        // Schreibfehler nicht in Endlosschleife laufen lassen
                        break
                    }
                }

                handledCount++
                if (handledCount >= maxEvents) {
                    Log.e(TAG, "onUsbData(): safety stop after $handledCount events")
                    break
                }
                if (System.nanoTime() - startNs > maxDurationNs) {
                    // Batch begrenzen, um UI/andere Tasks nicht zu verhungern
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "onUsbData(): batching stop after ${handledCount} events / ~50ms")
                    }
                    break
                }
            }

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onUsbData(): handledCount=$handledCount, queueNow=${obsSession.debugGetQueueSize()}")
            }
        }
    }

    /**
     * Wird bei jedem neuen GPS-Fix aufgerufen.
     * Wichtig: Kein zusätzliches GPS-Event schreiben, um Duplikate zu vermeiden.
     * Die Session erzeugt Geolocation-Events beim Positionswechsel in handleEvent().
     */
    fun onLocationChanged(location: Location) {
        lastLocation = location
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(
                TAG,
                "onLocationChanged(): rec=$isRecording, lat=${location.latitude}, lon=${location.longitude}, acc=${location.accuracy}"
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "onDestroy(): service destroyed, isRecording=$isRecording")
        ioHandler.post {
            try {
                if (isRecording) {
                    fileWriter.finishSession()
                }
            } catch (e: Exception) {
                Log.e(TAG, "onDestroy(): error closing file", e)
            } finally {
                ioThread.quitSafely()
            }
        }

        if (isForeground) {
            // Notification entfernen
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
    }
}
