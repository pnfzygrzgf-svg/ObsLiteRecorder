// SPDX-License-Identifier: GPL-3.0-or-later

// file: com/example/obsliterecorder/obslite/OBSLiteFileWriter.kt
package com.example.obsliterecorder.obslite

import android.content.Context
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OBSLiteFileWriter(private val context: Context) {

    private val TAG = "OBSLiteFileWriter"

    @Volatile
    private var outputStream: BufferedOutputStream? = null

    @Volatile
    private var fileOutputStream: FileOutputStream? = null // für fd.sync()

    @Volatile
    private var currentFile: File? = null

    /**
     * Startet eine neue Aufnahmesession und öffnet die Zieldatei.
     * Synchronisiert, um parallele Starts/Stops zu entkoppeln.
     * @return true wenn die Datei erfolgreich erstellt wurde, false bei Fehler
     */
    @Synchronized
    fun startSession(): Boolean {
        // Falls vorher nicht sauber beendet wurde
        if (outputStream != null || fileOutputStream != null) {
            Log.w(TAG, "startSession(): previous session still open – closing it now")
            finishSession()
        }

        val baseDir = context.getExternalFilesDir(null)
        if (baseDir == null) {
            Log.e(TAG, "startSession(): external files dir is null (storage unavailable)")
            return false
        }

        val dir = File(baseDir, "obslite")
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "startSession(): cannot create directory: ${dir.absolutePath}")
            return false
        }

        // Kollisionsarm: dd_MM_yyyy_HHmmss
        val formatter = SimpleDateFormat("ddMMyyyy_HHmmss", Locale.ROOT)
        val timestamp = formatter.format(Date())
        val file = File(dir, "fahrt_${timestamp}.bin")

        try {
            val fos = FileOutputStream(file, /*append=*/false)
            fileOutputStream = fos
            outputStream = BufferedOutputStream(fos, 64 * 1024)
            currentFile = file
            Log.d(TAG, "startSession(): file=${file.absolutePath}")
            return true
        } catch (e: IOException) {
            Log.e(TAG, "startSession(): failed to open output file", e)
            // Cleanup auf konsistenten Zustand
            runCatching { outputStream?.close() }
            runCatching { fileOutputStream?.close() }
            outputStream = null
            fileOutputStream = null
            currentFile = null
            runCatching { if (file.exists()) file.delete() }
            return false
        }
    }

    /**
     * Schreibt Daten der laufenden Session.
     * Kein per-Write flush – reduziert I/O-Overhead.
     */
    @Synchronized
    fun writeSessionData(data: ByteArray) {
        val out = outputStream
        if (out == null) {
            Log.e(TAG, "writeSessionData(): outputStream is null – startSession() missing?")
            return
        }
        try {
            out.write(data)
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "writeSessionData(): wrote ${data.size} bytes")
            }
        } catch (e: IOException) {
            Log.e(TAG, "writeSessionData(): write failed", e)
            // Best effort schließen, um Folgefehler zu minimieren
            safeCloseOnError()
        }
    }

    /**
     * Beendet die Session: flush + fsync + close.
     * fsync minimiert Datenverlust bei Crash/Power-Loss.
     */
    @Synchronized
    fun finishSession() {
        val out = outputStream
        val fos = fileOutputStream
        val file = currentFile
        try {
            try {
                out?.flush()
            } catch (e: IOException) {
                Log.e(TAG, "finishSession(): flush failed", e)
            }
            try {
                fos?.fd?.sync()
            } catch (e: IOException) {
                // Warum: Haltbarkeit im Dateisystem sicherstellen
                Log.e(TAG, "finishSession(): fd.sync() failed", e)
            }
            try {
                out?.close()
            } catch (e: IOException) {
                Log.e(TAG, "finishSession(): buffered close failed", e)
            }
            try {
                fos?.close()
            } catch (e: IOException) {
                Log.e(TAG, "finishSession(): fos close failed", e)
            }
            Log.d(TAG, "finishSession(): file closed: ${file?.absolutePath}")
        } finally {
            outputStream = null
            fileOutputStream = null
            currentFile = null
        }
    }

    @Synchronized
    fun getCurrentFileName(): String? = currentFile?.name

    // Nur intern: nach schwerem I/O-Fehler bestmöglich schließen
    private fun safeCloseOnError() {
        try {
            outputStream?.close()
        } catch (_: IOException) {
        }
        try {
            fileOutputStream?.close()
        } catch (_: IOException) {
        }
        outputStream = null
        fileOutputStream = null
    }
}
