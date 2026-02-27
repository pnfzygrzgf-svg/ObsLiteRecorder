// SPDX-License-Identifier: GPL-3.0-or-later

// PrefsHelper.kt - Zentraler SharedPreferences Helper
package com.example.obsliterecorder.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Zentraler Helper für alle App-Einstellungen
 */
class PrefsHelper(context: Context) {

    companion object {
        private const val PREFS_NAME = "obslite_prefs"

        // Keys
        private const val KEY_HANDLEBAR_WIDTH_CM = "handlebar_width_cm"
        private const val KEY_OBS_URL = "obs_url"
        private const val KEY_API_KEY = "obs_api_key"

        // Defaults
        const val DEFAULT_HANDLEBAR_WIDTH_CM = 60
        const val MIN_HANDLEBAR_WIDTH_CM = 30
        const val MAX_HANDLEBAR_WIDTH_CM = 120
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- Handlebar Width ---

    var handlebarWidthCm: Int
        get() = prefs.getInt(KEY_HANDLEBAR_WIDTH_CM, DEFAULT_HANDLEBAR_WIDTH_CM)
        set(value) {
            val clamped = value.coerceIn(MIN_HANDLEBAR_WIDTH_CM, MAX_HANDLEBAR_WIDTH_CM)
            prefs.edit().putInt(KEY_HANDLEBAR_WIDTH_CM, clamped).apply()
        }

    // --- OBS Portal ---

    var obsUrl: String
        get() = prefs.getString(KEY_OBS_URL, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_OBS_URL, value.trim()).apply()
        }

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_API_KEY, value.trim()).apply()
        }

    /**
     * Prüft ob Portal-Konfiguration vorhanden ist
     */
    fun hasPortalConfig(): Boolean = obsUrl.isNotBlank() && apiKey.isNotBlank()
}
