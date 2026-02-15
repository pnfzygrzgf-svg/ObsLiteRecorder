// SessionStats.kt - Statistiken fuer Aufnahme-Sessions
package com.example.obsliterecorder.util

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Speichert und laedt Session-Statistiken
 */
class SessionStats(context: Context) {

    companion object {
        private const val PREFS_NAME = "session_stats"
        private const val KEY_PREFIX = "stats_"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Speichert Statistiken fuer eine Datei
     */
    fun saveStats(fileName: String, stats: RecordingStats) {
        val json = JSONObject().apply {
            put("overtakeCount", stats.overtakeCount)
            put("distanceMeters", stats.distanceMeters)
            put("durationSeconds", stats.durationSeconds)
            put("minOvertakeCm", stats.minOvertakeCm)
            put("maxOvertakeCm", stats.maxOvertakeCm)
            put("avgOvertakeCm", stats.avgOvertakeCm)
        }
        prefs.edit().putString(KEY_PREFIX + fileName, json.toString()).apply()
    }

    /**
     * Laedt Statistiken fuer eine Datei
     */
    fun getStats(fileName: String): RecordingStats? {
        val jsonStr = prefs.getString(KEY_PREFIX + fileName, null) ?: return null

        return try {
            val json = JSONObject(jsonStr)
            RecordingStats(
                overtakeCount = json.optInt("overtakeCount", 0),
                distanceMeters = json.optDouble("distanceMeters", 0.0),
                durationSeconds = json.optLong("durationSeconds", 0),
                minOvertakeCm = json.optInt("minOvertakeCm", 0),
                maxOvertakeCm = json.optInt("maxOvertakeCm", 0),
                avgOvertakeCm = json.optInt("avgOvertakeCm", 0)
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Loescht Statistiken fuer eine Datei
     */
    fun deleteStats(fileName: String) {
        prefs.edit().remove(KEY_PREFIX + fileName).apply()
    }

    /**
     * Holt Gesamtstatistiken ueber alle Sessions
     */
    fun getTotalStats(): TotalStats {
        var totalOvertakes = 0
        var totalDistance = 0.0
        var totalDuration = 0L
        var sessionCount = 0

        prefs.all.forEach { (key, value) ->
            if (key.startsWith(KEY_PREFIX) && value is String) {
                try {
                    val json = JSONObject(value)
                    totalOvertakes += json.optInt("overtakeCount", 0)
                    totalDistance += json.optDouble("distanceMeters", 0.0)
                    totalDuration += json.optLong("durationSeconds", 0)
                    sessionCount++
                } catch (_: Exception) {}
            }
        }

        return TotalStats(
            sessionCount = sessionCount,
            totalOvertakes = totalOvertakes,
            totalDistanceKm = totalDistance / 1000.0,
            totalDurationMinutes = totalDuration / 60
        )
    }
}

/**
 * Statistiken fuer eine einzelne Aufnahme
 */
data class RecordingStats(
    val overtakeCount: Int = 0,
    val distanceMeters: Double = 0.0,
    val durationSeconds: Long = 0,
    val minOvertakeCm: Int = 0,
    val maxOvertakeCm: Int = 0,
    val avgOvertakeCm: Int = 0
)

/**
 * Gesamtstatistiken ueber alle Aufnahmen
 */
data class TotalStats(
    val sessionCount: Int = 0,
    val totalOvertakes: Int = 0,
    val totalDistanceKm: Double = 0.0,
    val totalDurationMinutes: Long = 0
)
