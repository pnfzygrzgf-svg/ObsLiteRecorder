package com.example.obsliterecorder

import android.app.AlertDialog
import android.content.SharedPreferences
import android.os.Bundle
import android.text.TextUtils
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class DataActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "obs_upload_prefs"
        private const val PREF_KEY_URL = "obs_url"
        private const val PREF_KEY_API = "obs_api_key"
    }

    // Upload-UI
    private lateinit var etObsUrl: EditText
    private lateinit var etApiKey: EditText
    private lateinit var spFileName: Spinner
    private lateinit var tvUploadStatus: TextView
    private lateinit var btnUpload: Button

    // Fahrtenliste-UI
    private lateinit var listFiles: ListView
    private lateinit var btnDeleteAll: Button

    // BIN-Check-UI
    private lateinit var btnDebugBin: Button
    private lateinit var tvBinStatus: TextView

    private val obsUploader = ObsUploader()

    // Dateien für Upload (Spinner)
    private var binFiles: Array<File> = emptyArray()

    // Dateien für Liste + Löschen
    private val files: MutableList<File> = mutableListOf()
    private lateinit var filesAdapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data)

        // Upload-Views
        etObsUrl = findViewById(R.id.etObsUrl)
        etApiKey = findViewById(R.id.etApiKey)
        spFileName = findViewById(R.id.spFileName)
        tvUploadStatus = findViewById(R.id.tvUploadStatus)
        btnUpload = findViewById(R.id.btnUpload)

        // Fahrtenliste-Views
        listFiles = findViewById(R.id.listFiles)
        btnDeleteAll = findViewById(R.id.btnDeleteAll)

        // BIN-Check-Views
        btnDebugBin = findViewById(R.id.btnDebugBin)
        tvBinStatus = findViewById(R.id.tvBinStatus)

        // URL / API-Key laden
        loadUrlAndApiKeyFromPrefs()

        // Upload: BIN-Dateien in Spinner
        loadBinFilesIntoSpinner()

        btnUpload.setOnClickListener { startUpload() }

        // Fahrtenliste
        filesAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            mutableListOf<String>()
        )
        listFiles.adapter = filesAdapter

        listFiles.setOnItemClickListener { _, _, position, _ ->
            if (position < 0 || position >= files.size) return@setOnItemClickListener
            val file = files[position]
            showDeleteSingleDialog(file)
        }

        btnDeleteAll.setOnClickListener {
            showDeleteAllDialog()
        }

        refreshFileList()

        // BIN-Check
        btnDebugBin.setOnClickListener {
            tvBinStatus.text = "BIN-Check läuft..."
            debugValidateLastBin()
        }
    }

    override fun onPause() {
        super.onPause()
        saveUrlAndApiKeyToPrefs()
    }

    // --- URL / API-Key (wie in UploadActivity) ---
    private fun loadUrlAndApiKeyFromPrefs() {
        val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedUrl = prefs.getString(PREF_KEY_URL, "") ?: ""
        val savedKey = prefs.getString(PREF_KEY_API, "") ?: ""

        if (savedUrl.isNotEmpty()) etObsUrl.setText(savedUrl)
        if (savedKey.isNotEmpty()) etApiKey.setText(savedKey)
    }

    private fun saveUrlAndApiKeyToPrefs() {
        val url = etObsUrl.text.toString().trim()
        val apiKey = etApiKey.text.toString().trim()

        val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit()
            .putString(PREF_KEY_URL, url)
            .putString(PREF_KEY_API, apiKey)
            .apply()
    }

    // --- Upload: BIN-Dateien in Spinner ---
    private fun loadBinFilesIntoSpinner() {
        val dir = File(getExternalFilesDir(null), "obslite")

        if (!dir.exists()) {
            tvUploadStatus.text = "Status: Ordner nicht gefunden: ${dir.absolutePath}"
            Toast.makeText(this, "Ordner 'obslite' existiert nicht.", Toast.LENGTH_SHORT).show()
            binFiles = emptyArray()
            btnUpload.isEnabled = false
            return
        }

        val files = dir.listFiles { _, name -> name.endsWith(".bin") }
        if (files == null || files.isEmpty()) {
            tvUploadStatus.text = "Status: keine .bin-Dateien gefunden."
            Toast.makeText(this, "Keine .bin-Dateien gefunden.", Toast.LENGTH_SHORT).show()
            binFiles = emptyArray()
            btnUpload.isEnabled = false
            return
        }

        // Nach Datum sortieren (älteste zuerst)
        files.sortBy { it.lastModified() }

        binFiles = files

        val names = binFiles.map { it.name }

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            names
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spFileName.adapter = adapter

        btnUpload.isEnabled = true
        tvUploadStatus.text = "Status: ${binFiles.size} BIN-Datei(en) gefunden."
    }

    private fun startUpload() {
        val url = etObsUrl.text.toString().trim()
        val apiKey = etApiKey.text.toString().trim()

        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(apiKey)) {
            Toast.makeText(this, "Bitte URL und API-Key ausfüllen.", Toast.LENGTH_SHORT).show()
            return
        }

        if (binFiles.isEmpty()) {
            Toast.makeText(this, "Keine BIN-Dateien zum Hochladen vorhanden.", Toast.LENGTH_SHORT).show()
            return
        }

        val selectedPosition = spFileName.selectedItemPosition
        if (selectedPosition < 0 || selectedPosition >= binFiles.size) {
            Toast.makeText(this, "Bitte eine BIN-Datei auswählen.", Toast.LENGTH_SHORT).show()
            return
        }

        val binFile = binFiles[selectedPosition]
        if (!binFile.exists()) {
            tvUploadStatus.text = "Status: Datei existiert nicht mehr: ${binFile.absolutePath}"
            Toast.makeText(this, "Datei existiert nicht mehr.", Toast.LENGTH_SHORT).show()
            loadBinFilesIntoSpinner()
            refreshFileList()
            return
        }

        // Vor dem Upload speichern
        saveUrlAndApiKeyToPrefs()

        tvUploadStatus.text = "Status: Upload läuft…\nDatei: ${binFile.name}"

        Thread {
            try {
                val result = obsUploader.uploadTrack(binFile, url, apiKey)
                runOnUiThread {
                    if (result.isSuccessful) {
                        tvUploadStatus.text =
                            "Status: Upload erfolgreich (${result.statusCode})\n" +
                                    "Datei: ${binFile.name}\n" +
                                    result.responseBody
                        Toast.makeText(this, "Upload erfolgreich", Toast.LENGTH_SHORT).show()
                    } else {
                        tvUploadStatus.text =
                            "Status: Fehler (${result.statusCode})\n" +
                                    "Datei: ${binFile.name}\n" +
                                    result.responseBody
                        Toast.makeText(this, "Upload fehlgeschlagen", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    tvUploadStatus.text = "Status: Ausnahmefehler: ${e.message}"
                    Toast.makeText(this, "Fehler beim Upload: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // --- Fahrtenliste (aus RecordedFilesActivity) ---
    private fun refreshFileList() {
        val dir = File(getExternalFilesDir(null), "obslite")
        if (!dir.exists()) {
            files.clear()
            if (::filesAdapter.isInitialized) {
                filesAdapter.clear()
            }
            Toast.makeText(this, "Keine Fahrten gefunden", Toast.LENGTH_SHORT).show()
            return
        }

        val foundFiles = dir.listFiles { f -> f.isFile && f.name.endsWith(".bin") }
            ?.sortedBy { it.lastModified() } ?: emptyList()

        files.clear()
        files.addAll(foundFiles)

        filesAdapter.clear()
        filesAdapter.addAll(files.map { it.name })
        filesAdapter.notifyDataSetChanged()

        if (files.isEmpty()) {
            Toast.makeText(this, "Keine Fahrten vorhanden", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDeleteSingleDialog(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Fahrt löschen?")
            .setMessage("Möchtest du die Fahrt \"${file.name}\" wirklich löschen?")
            .setPositiveButton("Löschen") { _, _ ->
                if (file.delete()) {
                    Toast.makeText(this, "Fahrt gelöscht", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Löschen fehlgeschlagen", Toast.LENGTH_SHORT).show()
                }
                loadBinFilesIntoSpinner()
                refreshFileList()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showDeleteAllDialog() {
        if (files.isEmpty()) {
            Toast.makeText(this, "Keine Fahrten zum Löschen", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Alle Fahrten löschen?")
            .setMessage("Möchtest du wirklich alle aufgezeichneten Fahrten löschen? Diese Aktion kann nicht rückgängig gemacht werden.")
            .setPositiveButton("Alle löschen") { _, _ ->
                var successCount = 0
                var failCount = 0

                files.forEach { file ->
                    if (file.delete()) {
                        successCount++
                    } else {
                        failCount++
                    }
                }

                loadBinFilesIntoSpinner()
                refreshFileList()

                val msg = when {
                    failCount == 0 -> "Alle Fahrten gelöscht"
                    successCount == 0 -> "Keine Fahrt konnte gelöscht werden"
                    else -> "$successCount Fahrten gelöscht, $failCount fehlgeschlagen"
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // --- BIN-Check (aus MainActivity) ---
    private fun debugValidateLastBin() {
        val dir = File(getExternalFilesDir(null), "obslite")
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".bin") }
            ?.sortedBy { it.lastModified() } ?: emptyList()
        if (files.isEmpty()) {
            android.util.Log.e("BIN_DEBUG", "Keine .bin-Datei gefunden")
            runOnUiThread { tvBinStatus.text = "BIN-Check: keine .bin-Datei gefunden" }
            return
        }

        val file = files.last()
        val bytes = file.readBytes()
        android.util.Log.d("BIN_DEBUG", "Prüfe Datei: ${file.absolutePath}")
        android.util.Log.d("BIN_DEBUG", "Dateigröße: ${bytes.size} Bytes")

        val previewLen = minOf(64, bytes.size)
        val hexPreview = buildString {
            for (i in 0 until previewLen) append(String.format("%02X ", bytes[i]))
        }
        android.util.Log.d("BIN_DEBUG", "Hex-Vorschau (max 64 B): $hexPreview")
        runOnUiThread { tvBinStatus.text = "BIN-Check: ${file.name} (${bytes.size} B)" }

        val chunks: List<ByteArray> = bytes.splitOnByte(0x00.toByte())
        android.util.Log.d("BIN_DEBUG", "Anzahl Chunks (inkl. evtl. leerer): ${chunks.size}")

        var idx = 0; var okCount = 0; var errorCount = 0; var nonEmptyChunks = 0
        for (chunk in chunks) {
            if (chunk.isEmpty()) {
                android.util.Log.d("BIN_DEBUG", "#$idx leerer Chunk")
                idx++
                continue
            }
            nonEmptyChunks++
            try {
                val chunkList = java.util.LinkedList<Byte>(); chunk.forEach { b -> chunkList.add(b) }
                val decoded: ByteArray = com.example.obsliterecorder.util.CobsUtils.decode(chunkList)
                val event = com.example.obsliterecorder.proto.Event.parseFrom(decoded)
                android.util.Log.d(
                    "BIN_DEBUG",
                    "#$idx OK (Chunklen=${chunk.size}, decoded=${decoded.size}) : $event"
                )
                okCount++
            } catch (e: Exception) {
                android.util.Log.e(
                    "BIN_DEBUG",
                    "#$idx FEHLER beim Parsen (Chunklen=${chunk.size})",
                    e
                )
                errorCount++
            }
            idx++
        }
        android.util.Log.d(
            "BIN_DEBUG",
            "Auswertung: nonEmptyChunks=$nonEmptyChunks, ok=$okCount, errors=$errorCount"
        )
        runOnUiThread {
            tvBinStatus.text =
                "BIN-Check fertig: ${bytes.size} B, Chunks=$nonEmptyChunks, OK=$okCount, Fehler=$errorCount (Logcat: BIN_DEBUG)"
        }
    }

    private fun ByteArray.splitOnByte(separator: Byte): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var start = 0
        for (i in indices) {
            if (this[i] == separator) {
                result.add(if (i > start) copyOfRange(start, i) else ByteArray(0))
                start = i + 1
            }
        }
        if (start < size) result.add(copyOfRange(start, size))
        return result
    }
}
