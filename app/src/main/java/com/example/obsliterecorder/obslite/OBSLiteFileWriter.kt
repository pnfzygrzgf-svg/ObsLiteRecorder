package com.example.obsliterecorder.obslite

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OBSLiteFileWriter(private val context: Context) {

    private val TAG = "OBSLiteFileWriter"

    private var outputStream: FileOutputStream? = null
    private var currentFile: File? = null

    /**
     * Neue Aufnahme starten: legt eine neue .bin-Datei an.
     */
    fun startSession() {
        val dir = File(context.getExternalFilesDir(null), "obslite")
        if (!dir.exists()) {
            dir.mkdirs()
        }

        // Datum/Zeit für Dateinamen im Format: fahrt_tag_monat_jahr_uhrzeit.bin
        // Beispiel: fahrt_24_11_2025_1430.bin
        val formatter = SimpleDateFormat("dd_MM_yyyy_HHmm", Locale.getDefault())
        val timestamp = formatter.format(Date())

        currentFile = File(dir, "fahrt_${timestamp}.bin")

        try {
            outputStream = FileOutputStream(currentFile!!)
            Log.d(TAG, "startSession -> file=${currentFile?.absolutePath}")
        } catch (e: IOException) {
            Log.e(TAG, "Fehler beim Öffnen der Ausgabedatei", e)
            outputStream = null
        }
    }

    /**
     * Am Ende der Fahrt aufrufen: schreibt den kompletten Byte-Stream,
     * den OBSLiteSession gesammelt hat, in die Datei.
     */
    fun writeSessionData(data: ByteArray) {
        try {
            if (outputStream == null) {
                Log.e(TAG, "writeSessionData: outputStream ist null, startSession vergessen?")
                return
            }
            outputStream?.write(data)
            outputStream?.flush()
            Log.d(TAG, "writeSessionData -> wrote ${data.size} bytes")
        } catch (e: IOException) {
            Log.e(TAG, "Fehler beim Schreiben der Daten", e)
        }
    }

    /**
     * Aufnahme beenden: Stream schließen.
     */
    fun finishSession() {
        try {
            outputStream?.flush()
            outputStream?.close()
            Log.d(TAG, "finishSession -> file closed: ${currentFile?.absolutePath}")
        } catch (e: IOException) {
            Log.e(TAG, "Fehler beim Schließen der Datei", e)
        } finally {
            outputStream = null
        }
    }

    fun getCurrentFileName(): String? = currentFile?.name
}
