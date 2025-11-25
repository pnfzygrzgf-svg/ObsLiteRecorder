package com.example.obsliterecorder

import android.app.AlertDialog
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class RecordedFilesActivity : AppCompatActivity() {

    private lateinit var listFiles: ListView
    private lateinit var btnDeleteAll: Button

    // Liste der tatsächlichen Dateien
    private val files: MutableList<File> = mutableListOf()

    // Adapter für die Anzeige (nur Dateinamen)
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recorded_files)

        listFiles = findViewById(R.id.listFiles)
        btnDeleteAll = findViewById(R.id.btnDeleteAll)

        adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            mutableListOf<String>()
        )
        listFiles.adapter = adapter

        // Einzelne Fahrt löschen (Tap auf Eintrag)
        listFiles.setOnItemClickListener { _, _, position, _ ->
            if (position < 0 || position >= files.size) return@setOnItemClickListener
            val file = files[position]
            showDeleteSingleDialog(file)
        }

        // Alle Fahrten löschen
        btnDeleteAll.setOnClickListener {
            showDeleteAllDialog()
        }

        refreshFileList()
    }

    private fun refreshFileList() {
        val dir = File(getExternalFilesDir(null), "obslite")
        if (!dir.exists()) {
            files.clear()
            adapter.clear()
            Toast.makeText(this, "Keine Fahrten gefunden", Toast.LENGTH_SHORT).show()
            return
        }

        val foundFiles = dir.listFiles { f ->
            f.isFile && f.name.endsWith(".bin")
        }?.sortedBy { it.lastModified() } ?: emptyList()

        files.clear()
        files.addAll(foundFiles)

        adapter.clear()
        adapter.addAll(files.map { it.name })
        adapter.notifyDataSetChanged()

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
}
