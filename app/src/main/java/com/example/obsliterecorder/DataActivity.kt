// DataActivity.kt
package com.example.obsliterecorder

import android.os.Bundle
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DataActivity : ComponentActivity() {

    companion object {
        private const val PREFS_NAME = "obs_upload_prefs"
        private const val PREF_KEY_URL = "obs_url"
        private const val PREF_KEY_API = "obs_api_key"
    }

    private val obsUploader = ObsUploader()

    // ---------- UI State ----------
    private var obsUrl by mutableStateOf("")
    private var apiKey by mutableStateOf("")
    private var hideApiKey by mutableStateOf(true)

    private var uploadStatus by mutableStateOf("Noch kein Upload ausgeführt.")
    private var isUploading by mutableStateOf(false)

    private var binFiles by mutableStateOf<List<File>>(emptyList())
    private var selectedBinFile by mutableStateOf<File?>(null)

    private var recordings by mutableStateOf<List<File>>(emptyList())

    private var showDeleteSingleFor by mutableStateOf<File?>(null)
    private var showDeleteAllDialog by mutableStateOf(false)

    private var isBinChecking by mutableStateOf(false)
    private var binProgress by mutableIntStateOf(0)
    private var binStatus by mutableStateOf("BIN-Check: bereit.")

    private var tab by mutableIntStateOf(0) // 0=Upload, 1=Fahrten, 2=Debug

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        loadUrlAndApiKeyFromPrefs()
        refreshBinFiles()
        refreshRecordings()

        setContent {
            MaterialTheme {
                DataScreen(
                    tab = tab,
                    onTabChange = { tab = it },

                    obsUrl = obsUrl,
                    onObsUrlChange = { obsUrl = it },

                    apiKey = apiKey,
                    onApiKeyChange = { apiKey = it },

                    hideApiKey = hideApiKey,
                    onToggleHideApiKey = { hideApiKey = !hideApiKey },

                    binFiles = binFiles,
                    selectedBinFile = selectedBinFile,
                    onSelectBinFile = { selectedBinFile = it },

                    uploadStatus = uploadStatus,
                    isUploading = isUploading,
                    onUpload = { startUpload() },

                    recordings = recordings,
                    onDeleteSingle = { showDeleteSingleFor = it },
                    onDeleteAll = { showDeleteAllDialog = true },

                    isBinChecking = isBinChecking,
                    binProgress = binProgress,
                    binStatus = binStatus,
                    onRunBinCheck = { runBinCheck() },

                    onBack = { finish() }
                )

                // Dialog: Single delete
                showDeleteSingleFor?.let { file ->
                    ConfirmDialog(
                        title = "Datei löschen?",
                        message = "Möchtest du „${file.name}“ wirklich löschen?",
                        confirmText = "Löschen",
                        destructive = true,
                        onConfirm = {
                            showDeleteSingleFor = null
                            deleteSingle(file)
                        },
                        onDismiss = { showDeleteSingleFor = null }
                    )
                }

                // Dialog: delete all
                if (showDeleteAllDialog) {
                    ConfirmDialog(
                        title = "Alle löschen?",
                        message = "Möchtest du wirklich alle Aufzeichnungen löschen?",
                        confirmText = "Alle löschen",
                        destructive = true,
                        onConfirm = {
                            showDeleteAllDialog = false
                            deleteAll()
                        },
                        onDismiss = { showDeleteAllDialog = false }
                    )
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        saveUrlAndApiKeyToPrefs()
    }

    // ---------- Prefs ----------
    private fun loadUrlAndApiKeyFromPrefs() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        obsUrl = prefs.getString(PREF_KEY_URL, "") ?: ""
        apiKey = prefs.getString(PREF_KEY_API, "") ?: ""
    }

    private fun saveUrlAndApiKeyToPrefs() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit()
            .putString(PREF_KEY_URL, obsUrl.trim())
            .putString(PREF_KEY_API, apiKey.trim())
            .apply()
    }

    // ---------- Files ----------
    private fun refreshBinFiles() {
        val dir = File(getExternalFilesDir(null), "obslite")
        val files = dir.listFiles { _, name -> name.endsWith(".bin") }
            ?.sortedBy { it.lastModified() } ?: emptyList()

        binFiles = files
        if (selectedBinFile == null || selectedBinFile?.exists() != true) {
            selectedBinFile = files.lastOrNull()
        }
    }

    private fun refreshRecordings() {
        val dir = File(getExternalFilesDir(null), "obslite")
        recordings = dir.listFiles { f -> f.isFile && f.name.endsWith(".bin") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    // ---------- Upload ----------
    private fun startUpload() {
        val url = obsUrl.trim()
        val key = apiKey.trim()

        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(key)) {
            Toast.makeText(this, "Bitte URL und API-Key ausfüllen.", Toast.LENGTH_SHORT).show()
            return
        }

        refreshBinFiles()
        val file = selectedBinFile
        if (file == null || !file.exists()) {
            Toast.makeText(this, "Keine gültige .bin Datei ausgewählt.", Toast.LENGTH_SHORT).show()
            uploadStatus = "Upload abgebrochen: keine Datei ausgewählt."
            return
        }

        saveUrlAndApiKeyToPrefs()

        isUploading = true
        uploadStatus = "Upload läuft: ${file.name}"

        Thread {
            try {
                val result = obsUploader.uploadTrack(file, url, key)
                runOnUiThread {
                    isUploading = false
                    uploadStatus =
                        if (result.isSuccessful) {
                            "✅ Upload OK (${result.statusCode}) – ${file.name}\n${result.responseBody}"
                        } else {
                            "❌ Upload Fehler (${result.statusCode}) – ${file.name}\n${result.responseBody}"
                        }

                    Toast.makeText(
                        this,
                        if (result.isSuccessful) "Upload erfolgreich." else "Upload fehlgeschlagen.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    isUploading = false
                    uploadStatus = "❌ Exception: ${e.message ?: "unbekannter Fehler"}"
                    Toast.makeText(this, "Upload-Fehler: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ---------- Delete ----------
    private fun deleteSingle(file: File) {
        val ok = runCatching { file.delete() }.getOrDefault(false)
        Toast.makeText(
            this,
            if (ok) "Datei gelöscht." else "Löschen fehlgeschlagen.",
            Toast.LENGTH_SHORT
        ).show()

        refreshBinFiles()
        refreshRecordings()
    }

    private fun deleteAll() {
        if (recordings.isEmpty()) {
            Toast.makeText(this, "Keine Aufzeichnungen vorhanden.", Toast.LENGTH_SHORT).show()
            return
        }

        var okCount = 0
        var failCount = 0
        recordings.forEach { f ->
            if (runCatching { f.delete() }.getOrDefault(false)) okCount++ else failCount++
        }

        refreshBinFiles()
        refreshRecordings()

        val msg = when {
            failCount == 0 -> "Alle gelöscht."
            okCount == 0 -> "Keine Datei konnte gelöscht werden."
            else -> "$okCount gelöscht, $failCount fehlgeschlagen."
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ---------- BIN Check ----------
    private fun runBinCheck() {
        if (isBinChecking) return
        isBinChecking = true
        binProgress = 0
        binStatus = "BIN-Check läuft…"

        Thread {
            try {
                debugValidateLastBin()
            } finally {
                runOnUiThread { isBinChecking = false }
            }
        }.start()
    }

    private fun debugValidateLastBin() {
        val dir = File(getExternalFilesDir(null), "obslite")
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".bin") }
            ?.sortedBy { it.lastModified() } ?: emptyList()

        if (files.isEmpty()) {
            runOnUiThread {
                binStatus = "Keine .bin Datei gefunden."
                binProgress = 0
            }
            return
        }

        val file = files.last()
        val bytes = file.readBytes()

        runOnUiThread {
            binStatus = "Prüfe: ${file.name} (${bytes.size} Bytes)"
        }

        val chunks: List<ByteArray> = bytes.splitOnByte(0x00.toByte())
        val totalChunks = chunks.size.coerceAtLeast(1)

        var idx = 0
        var okCount = 0
        var errorCount = 0
        var nonEmptyChunks = 0

        for (chunk in chunks) {
            if (chunk.isNotEmpty()) {
                nonEmptyChunks++
                try {
                    val chunkList = java.util.LinkedList<Byte>().apply {
                        chunk.forEach { b -> add(b) }
                    }
                    val decoded: ByteArray =
                        com.example.obsliterecorder.util.CobsUtils.decode(chunkList)
                    com.example.obsliterecorder.proto.Event.parseFrom(decoded)
                    okCount++
                } catch (_: Exception) {
                    errorCount++
                }
            }

            idx++
            val p = (idx * 100) / totalChunks
            runOnUiThread { binProgress = p }
        }

        runOnUiThread {
            binProgress = 100
            binStatus = "Ergebnis: ${file.name}\nnonEmpty=$nonEmptyChunks · ok=$okCount · errors=$errorCount"
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

// ---------------------- UI (Compose) ----------------------

@Composable
private fun DataScreen(
    tab: Int,
    onTabChange: (Int) -> Unit,

    obsUrl: String,
    onObsUrlChange: (String) -> Unit,

    apiKey: String,
    onApiKeyChange: (String) -> Unit,

    hideApiKey: Boolean,
    onToggleHideApiKey: () -> Unit,

    binFiles: List<File>,
    selectedBinFile: File?,
    onSelectBinFile: (File) -> Unit,

    uploadStatus: String,
    isUploading: Boolean,
    onUpload: () -> Unit,

    recordings: List<File>,
    onDeleteSingle: (File) -> Unit,
    onDeleteAll: () -> Unit,

    isBinChecking: Boolean,
    binProgress: Int,
    binStatus: String,
    onRunBinCheck: () -> Unit,

    onBack: () -> Unit
) {
    val bg = Color(0xFFF2F2F7)


    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .padding(WindowInsets.systemBars.asPaddingValues())
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            TopHeaderIOS(title = "Aufzeichnungen", onBack = onBack)
        }

        item {
            SegmentedTabsIOS(
                labels = listOf("Upload", "Fahrten", "Debug"),
                selectedIndex = tab,
                onSelect = onTabChange
            )
        }

        when (tab) {
            0 -> {
                item {
                    UploadCredsCardIOS(
                        obsUrl = obsUrl,
                        onObsUrlChange = onObsUrlChange,
                        apiKey = apiKey,
                        onApiKeyChange = onApiKeyChange,
                        hideApiKey = hideApiKey,
                        onToggleHideApiKey = onToggleHideApiKey
                    )
                }
                item {
                    UploadCardIOS(
                        binFiles = binFiles,
                        selectedBinFile = selectedBinFile,
                        onSelectBinFile = onSelectBinFile,
                        uploadStatus = uploadStatus,
                        isUploading = isUploading,
                        onUpload = onUpload
                    )
                }
            }

            1 -> {
                item {
                    RecordingsHeaderCardIOS(
                        recordingsCount = recordings.size,
                        onDeleteAll = onDeleteAll,
                        deleteEnabled = recordings.isNotEmpty()
                    )
                }
                if (recordings.isEmpty()) {
                    item {
                        CardIOS {
                            Text("Keine Aufzeichnungen vorhanden.", color = Color(0xFF6B7280))
                        }
                    }
                } else {
                    items(recordings, key = { it.absolutePath }) { f ->
                        RecordingRowIOS(file = f, onClick = { onDeleteSingle(f) })
                    }
                }
            }

            else -> {
                item {
                    DebugTabIOS(
                        isRunning = isBinChecking,
                        progress = binProgress,
                        status = binStatus,
                        onRun = onRunBinCheck
                    )
                }
            }
        }
    }
}

@Composable
private fun TopHeaderIOS(
    title: String,
    onBack: () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(36.dp),
            shape = CircleShape,
            color = Color.White,
            tonalElevation = 1.dp,
            shadowElevation = 2.dp
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "Zurück",
                    tint = Color(0xFF0A84FF)
                )
            }
        }

        Text(
            text = title,
            modifier = Modifier.align(Alignment.Center),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SegmentedTabsIOS(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    val outer = RoundedCornerShape(999.dp)

    Surface(
        shape = outer,
        color = Color(0xFFE5E7EB),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(4.dp)) {
            labels.forEachIndexed { idx, label ->
                val selected = idx == selectedIndex
                val bg = if (selected) Color.White else Color.Transparent
                val fg = if (selected) Color(0xFF111827) else Color(0xFF6B7280)

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(999.dp))
                        .background(bg)
                        .clickable { onSelect(idx) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, color = fg, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun UploadCredsCardIOS(
    obsUrl: String,
    onObsUrlChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    hideApiKey: Boolean,
    onToggleHideApiKey: () -> Unit
) {
    CardIOS {
        Text("OBS Portal", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))

        Text("URL", color = Color(0xFF6B7280))
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = obsUrl,
            onValueChange = onObsUrlChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("https://…") }
        )

        Spacer(Modifier.height(12.dp))

        Text("API-Key", color = Color(0xFF6B7280))
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (hideApiKey) PasswordVisualTransformation() else VisualTransformation.None,
            trailingIcon = {
                IconButton(onClick = onToggleHideApiKey) {
                    Icon(
                        imageVector = if (hideApiKey) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                        contentDescription = if (hideApiKey) "API-Key anzeigen" else "API-Key verbergen",
                        tint = Color(0xFF6B7280)
                    )
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UploadCardIOS(
    binFiles: List<File>,
    selectedBinFile: File?,
    onSelectBinFile: (File) -> Unit,
    uploadStatus: String,
    isUploading: Boolean,
    onUpload: () -> Unit
) {
    CardIOS {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.FileUpload,
                contentDescription = null,
                tint = Color(0xFF0A84FF)
            )
            Spacer(Modifier.width(10.dp))
            Text("Upload", fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(10.dp))

        var expanded by remember { mutableStateOf(false) }
        val selectedLabel = selectedBinFile?.name
            ?: if (binFiles.isEmpty()) "Keine .bin Dateien" else "Bitte auswählen"

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (binFiles.isNotEmpty()) expanded = !expanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = selectedLabel,
                onValueChange = {},
                readOnly = true,
                enabled = binFiles.isNotEmpty(),
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
                trailingIcon = {
                    Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = Color(0xFF6B7280))
                }
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                binFiles.forEach { f ->
                    DropdownMenuItem(
                        text = {
                            Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        onClick = {
                            expanded = false
                            onSelectBinFile(f)
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = if (binFiles.isEmpty()) "Keine Aufzeichnungen gefunden." else "${binFiles.size} Dateien verfügbar.",
            color = Color(0xFF6B7280),
            modifier = Modifier.alpha(0.9f)
        )

        Spacer(Modifier.height(12.dp))

        val gradient = Brush.horizontalGradient(listOf(Color(0xFF0A84FF), Color(0xFF007AFF)))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)),
            color = Color.Transparent,
            shadowElevation = 6.dp
        ) {
            Button(
                onClick = onUpload,
                enabled = !isUploading && binFiles.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(gradient, RoundedCornerShape(16.dp))
                    .alpha(if (!isUploading) 1f else 0.75f)
            ) {
                Text(if (isUploading) "Upload läuft…" else "Upload starten", color = Color.White)
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Status", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(uploadStatus, color = Color(0xFF6B7280))
    }
}

@Composable
private fun RecordingsHeaderCardIOS(
    recordingsCount: Int,
    deleteEnabled: Boolean,
    onDeleteAll: () -> Unit
) {
    CardIOS {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Fahrten", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onDeleteAll, enabled = deleteEnabled) {
                Icon(Icons.Filled.Delete, contentDescription = null, tint = Color(0xFFFF3B30))
                Spacer(Modifier.width(6.dp))
                Text("Alle löschen", color = Color(0xFFFF3B30))
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Tippe auf eine Datei, um sie zu löschen.",
            color = Color(0xFF6B7280)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "$recordingsCount Dateien",
            color = Color(0xFF6B7280),
            modifier = Modifier.alpha(0.9f)
        )
    }
}

@Composable
private fun RecordingRowIOS(
    file: File,
    onClick: () -> Unit
) {
    val df = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    val date = df.format(Date(file.lastModified()))
    val size = formatBytes(file.length())

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.White,
        shadowElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                file.name,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text("$date · $size", color = Color(0xFF6B7280))
        }
    }
}

@Composable
private fun DebugTabIOS(
    isRunning: Boolean,
    progress: Int,
    status: String,
    onRun: () -> Unit
) {
    CardIOS {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.BugReport, contentDescription = null, tint = Color(0xFF0A84FF))
            Spacer(Modifier.width(10.dp))
            Text("BIN-Check", fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(10.dp))

        Button(
            onClick = onRun,
            enabled = !isRunning,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(if (isRunning) "Läuft…" else "Letzte BIN prüfen")
        }

        Spacer(Modifier.height(12.dp))

        AnimatedVisibility(visible = isRunning, enter = fadeIn(), exit = fadeOut()) {
            LinearProgressIndicator(
                progress = (progress.coerceIn(0, 100) / 100f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(99.dp)),
                color = Color(0xFF0A84FF),
                trackColor = Color(0xFFE5E7EB)
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(status, color = Color(0xFF6B7280))
    }
}

@Composable
private fun CardIOS(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    destructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmText,
                    color = if (destructive) Color(0xFFFF3B30) else Color(0xFF0A84FF)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen", color = Color(0xFF0A84FF))
            }
        }
    )
}

// ---------- utils ----------
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1 -> String.format(Locale.getDefault(), "%.1f MB", mb)
        kb >= 1 -> String.format(Locale.getDefault(), "%.0f KB", kb)
        else -> "$bytes B"
    }
}
