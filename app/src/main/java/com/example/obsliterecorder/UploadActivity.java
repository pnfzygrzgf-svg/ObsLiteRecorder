package com.example.obsliterecorder;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

public class UploadActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "obs_upload_prefs";
    private static final String PREF_KEY_URL = "obs_url";
    private static final String PREF_KEY_API = "obs_api_key";

    private EditText etObsUrl;
    private EditText etApiKey;
    private Spinner spFileName;
    private TextView tvUploadStatus;
    private Button btnUpload;

    private final ObsUploader obsUploader = new ObsUploader();

    // Liste der tatsächlichen Dateien, passend zur Spinner-Anzeige
    private File[] binFiles = new File[0];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_upload);

        etObsUrl = findViewById(R.id.etObsUrl);
        etApiKey = findViewById(R.id.etApiKey);
        spFileName = findViewById(R.id.spFileName);
        tvUploadStatus = findViewById(R.id.tvUploadStatus);
        btnUpload = findViewById(R.id.btnUpload);

        // Gespeicherte URL / API-Key aus SharedPreferences laden
        loadUrlAndApiKeyFromPrefs();

        // BIN-Dateien laden und Spinner befüllen
        loadBinFilesIntoSpinner();

        btnUpload.setOnClickListener(v -> startUpload());
    }

    @Override
    protected void onPause() {
        super.onPause();
        // zur Sicherheit bei jedem Pause-Event speichern
        saveUrlAndApiKeyToPrefs();
    }

    /**
     * URL und API-Key aus SharedPreferences laden
     */
    private void loadUrlAndApiKeyFromPrefs() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedUrl = prefs.getString(PREF_KEY_URL, "");
        String savedKey = prefs.getString(PREF_KEY_API, "");

        if (!TextUtils.isEmpty(savedUrl)) {
            etObsUrl.setText(savedUrl);
        }

        if (!TextUtils.isEmpty(savedKey)) {
            etApiKey.setText(savedKey);
        }
    }

    /**
     * Aktuelle URL und API-Key in SharedPreferences speichern.
     */
    private void saveUrlAndApiKeyToPrefs() {
        String url = etObsUrl.getText().toString().trim();
        String apiKey = etApiKey.getText().toString().trim();

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
                .putString(PREF_KEY_URL, url)
                .putString(PREF_KEY_API, apiKey)
                .apply();
    }

    /**
     * Liest alle .bin-Dateien aus getExternalFilesDir(null)/"obslite"
     * und befüllt den Spinner mit den Dateinamen.
     */
    private void loadBinFilesIntoSpinner() {
        File dir = new File(getExternalFilesDir(null), "obslite");

        if (!dir.exists()) {
            tvUploadStatus.setText("Status: Ordner nicht gefunden: " + dir.getAbsolutePath());
            Toast.makeText(this, "Ordner 'obslite' existiert nicht.", Toast.LENGTH_SHORT).show();
            binFiles = new File[0];
            btnUpload.setEnabled(false);
            return;
        }

        File[] files = dir.listFiles((file, name) -> name.endsWith(".bin"));
        if (files == null || files.length == 0) {
            tvUploadStatus.setText("Status: keine .bin-Dateien gefunden.");
            Toast.makeText(this, "Keine .bin-Dateien gefunden.", Toast.LENGTH_SHORT).show();
            binFiles = new File[0];
            btnUpload.setEnabled(false);
            return;
        }

        // nach Datum sortieren (älteste zuerst)
        java.util.Arrays.sort(files, (f1, f2) ->
                Long.compare(f1.lastModified(), f2.lastModified())
        );

        binFiles = files;

        String[] names = new String[binFiles.length];
        for (int i = 0; i < binFiles.length; i++) {
            names[i] = binFiles[i].getName();
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                names
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spFileName.setAdapter(adapter);

        btnUpload.setEnabled(true);
        tvUploadStatus.setText("Status: " + binFiles.length + " BIN-Datei(en) gefunden.");
    }

    private void startUpload() {
        String url = etObsUrl.getText().toString().trim();
        String apiKey = etApiKey.getText().toString().trim();

        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(apiKey)) {
            Toast.makeText(this, "Bitte URL und API-Key ausfüllen.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (binFiles == null || binFiles.length == 0) {
            Toast.makeText(this, "Keine BIN-Dateien zum Hochladen vorhanden.", Toast.LENGTH_SHORT).show();
            return;
        }

        int selectedPosition = spFileName.getSelectedItemPosition();
        if (selectedPosition < 0 || selectedPosition >= binFiles.length) {
            Toast.makeText(this, "Bitte eine BIN-Datei auswählen.", Toast.LENGTH_SHORT).show();
            return;
        }

        File binFile = binFiles[selectedPosition];
        if (!binFile.exists()) {
            tvUploadStatus.setText("Status: Datei existiert nicht mehr: " + binFile.getAbsolutePath());
            Toast.makeText(this, "Datei existiert nicht mehr.", Toast.LENGTH_SHORT).show();
            loadBinFilesIntoSpinner();
            return;
        }

        // Vor dem Upload die aktuellen Werte speichern
        saveUrlAndApiKeyToPrefs();

        tvUploadStatus.setText("Status: Upload läuft…\nDatei: " + binFile.getName());

        new Thread(() -> {
            try {
                ObsUploader.UploadResult result = obsUploader.uploadTrack(binFile, url, apiKey);
                runOnUiThread(() -> {
                    if (result.isSuccessful()) {
                        tvUploadStatus.setText(
                                "Status: Upload erfolgreich (" + result.statusCode + ")\n" +
                                        "Datei: " + binFile.getName() + "\n" +
                                        result.responseBody
                        );
                        Toast.makeText(this, "Upload erfolgreich", Toast.LENGTH_SHORT).show();
                    } else {
                        tvUploadStatus.setText(
                                "Status: Fehler (" + result.statusCode + ")\n" +
                                        "Datei: " + binFile.getName() + "\n" +
                                        result.responseBody
                        );
                        Toast.makeText(this, "Upload fehlgeschlagen", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    tvUploadStatus.setText("Status: Ausnahmefehler: " + e.getMessage());
                    Toast.makeText(this, "Fehler beim Upload: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }
}
