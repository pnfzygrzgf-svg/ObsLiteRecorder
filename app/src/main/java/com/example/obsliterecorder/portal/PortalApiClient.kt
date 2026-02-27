// SPDX-License-Identifier: GPL-3.0-or-later

// PortalApiClient.kt - REST API Client fuer OBS Portal
package com.example.obsliterecorder.portal

import android.util.Log
import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * API Client fuer das OBS Portal
 * Ermoeglicht das Abrufen von hochgeladenen Tracks
 * Verwendet Cookies aus dem WebView fuer Authentifizierung
 */
class PortalApiClient(
    private val baseUrl: String,
    private val apiKey: String
) {
    companion object {
        private const val TAG = "PortalApiClient"
    }

    /**
     * CookieJar das Cookies aus dem Android WebView CookieManager liest
     */
    private val webViewCookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            // Cookies werden vom WebView verwaltet
            val cookieManager = CookieManager.getInstance()
            for (cookie in cookies) {
                cookieManager.setCookie(url.toString(), cookie.toString())
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val cookieManager = CookieManager.getInstance()
            val cookieString = cookieManager.getCookie(url.toString()) ?: return emptyList()

            return cookieString.split(";").mapNotNull { cookiePart ->
                Cookie.parse(url, cookiePart.trim())
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .cookieJar(webViewCookieJar)
        .build()

    /**
     * Holt die Liste der eigenen Tracks vom Portal
     * Verwendet /api/tracks/feed mit Session-Cookies (kein API-Key noetig)
     */
    fun fetchMyTracks(limit: Int = 20, offset: Int = 0): PortalTracksResult {
        val normalizedUrl = baseUrl.trimEnd('/')
        val url = "$normalizedUrl/api/tracks/feed?limit=$limit&offset=$offset&reversed=false"

        Log.d(TAG, "fetchMyTracks: $url")

        // Nur Cookies verwenden, kein API-Key Header (wie iOS)
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    Log.e(TAG, "fetchMyTracks failed: ${response.code} - $body")
                    return PortalTracksResult.Error("HTTP ${response.code}")
                }

                val tracks = parseTracksResponse(body)
                PortalTracksResult.Success(tracks)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchMyTracks exception", e)
            PortalTracksResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Holt Details zu einem einzelnen Track
     * Verwendet Session-Cookies (kein API-Key noetig)
     */
    fun fetchTrackDetail(slug: String): PortalTrackDetailResult {
        val normalizedUrl = baseUrl.trimEnd('/')
        val url = "$normalizedUrl/api/tracks/$slug"

        // Nur Cookies verwenden (wie iOS)
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    Log.e(TAG, "fetchTrackDetail failed: ${response.code}")
                    return PortalTrackDetailResult.Error("HTTP ${response.code}")
                }

                val track = parseTrackDetail(body)
                if (track != null) {
                    PortalTrackDetailResult.Success(track)
                } else {
                    PortalTrackDetailResult.Error("Parse error")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchTrackDetail exception", e)
            PortalTrackDetailResult.Error(e.message ?: "Unknown error")
        }
    }

    private fun parseTracksResponse(json: String): List<PortalTrackSummary> {
        val tracks = mutableListOf<PortalTrackSummary>()

        try {
            val root = JSONObject(json)
            val tracksArray = root.optJSONArray("tracks") ?: return tracks

            for (i in 0 until tracksArray.length()) {
                val trackObj = tracksArray.getJSONObject(i)
                val track = parseTrackSummary(trackObj)
                if (track != null) {
                    tracks.add(track)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseTracksResponse error", e)
        }

        return tracks
    }

    private fun parseTrackSummary(obj: JSONObject): PortalTrackSummary? {
        return try {
            val statsObj = obj.optJSONObject("statistics")

            PortalTrackSummary(
                slug = obj.optString("slug", ""),
                title = obj.optString("title", "Unbenannt"),
                createdAt = obj.optString("createdAt", ""),
                recordedAt = obj.optString("recordedAt", ""),
                visibility = obj.optString("visibility", "private"),
                length = statsObj?.optDouble("length", 0.0) ?: 0.0,
                duration = statsObj?.optDouble("duration", 0.0) ?: 0.0,
                numEvents = statsObj?.optInt("numEvents", 0) ?: 0,
                numMeasurements = statsObj?.optInt("numMeasurements", 0) ?: 0
            )
        } catch (e: Exception) {
            Log.e(TAG, "parseTrackSummary error", e)
            null
        }
    }

    private fun parseTrackDetail(json: String): PortalTrackDetail? {
        return try {
            val obj = JSONObject(json)
            val statsObj = obj.optJSONObject("statistics")

            PortalTrackDetail(
                slug = obj.optString("slug", ""),
                title = obj.optString("title", "Unbenannt"),
                description = obj.optString("description", ""),
                createdAt = obj.optString("createdAt", ""),
                recordedAt = obj.optString("recordedAt", ""),
                visibility = obj.optString("visibility", "private"),
                length = statsObj?.optDouble("length", 0.0) ?: 0.0,
                duration = statsObj?.optDouble("duration", 0.0) ?: 0.0,
                numEvents = statsObj?.optInt("numEvents", 0) ?: 0,
                numMeasurements = statsObj?.optInt("numMeasurements", 0) ?: 0
            )
        } catch (e: Exception) {
            Log.e(TAG, "parseTrackDetail error", e)
            null
        }
    }

    /**
     * Holt die Track-Daten (Route + Events) fuer die Kartenanzeige
     * Endpoint: GET /api/tracks/{slug}/data
     * Returns GeoJSON mit track, trackRaw und events
     * Verwendet Session-Cookies (kein API-Key noetig)
     */
    fun fetchTrackData(slug: String): PortalTrackDataResult {
        val normalizedUrl = baseUrl.trimEnd('/')
        val url = "$normalizedUrl/api/tracks/$slug/data"

        Log.d(TAG, "fetchTrackData: $url")

        // Nur Cookies verwenden (wie iOS)
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    Log.e(TAG, "fetchTrackData failed: ${response.code} - $body")
                    return PortalTrackDataResult.Error("HTTP ${response.code}")
                }

                val trackData = parseTrackData(body)
                if (trackData != null) {
                    PortalTrackDataResult.Success(trackData)
                } else {
                    PortalTrackDataResult.Error("Parse error")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchTrackData exception", e)
            PortalTrackDataResult.Error(e.message ?: "Unknown error")
        }
    }

    private fun parseTrackData(json: String): PortalTrackData? {
        return try {
            val root = JSONObject(json)

            // Parse route (prefer map-matched "track", fallback to "trackRaw")
            val routeCoordinates = parseRouteCoordinates(root.optJSONObject("track"))
                ?: parseRouteCoordinates(root.optJSONObject("trackRaw"))
                ?: emptyList()

            // Parse events
            val events = parseEvents(root.optJSONObject("events"))

            Log.d(TAG, "Parsed ${routeCoordinates.size} route points, ${events.size} events")

            PortalTrackData(
                routeCoordinates = routeCoordinates,
                events = events
            )
        } catch (e: Exception) {
            Log.e(TAG, "parseTrackData error", e)
            null
        }
    }

    private fun parseRouteCoordinates(feature: JSONObject?): List<PortalCoordinate>? {
        if (feature == null) return null

        return try {
            val geometry = feature.optJSONObject("geometry") ?: return null
            val coordsArray = geometry.optJSONArray("coordinates") ?: return null

            val coordinates = mutableListOf<PortalCoordinate>()
            for (i in 0 until coordsArray.length()) {
                val point = coordsArray.optJSONArray(i)
                if (point != null && point.length() >= 2) {
                    val lon = point.optDouble(0)
                    val lat = point.optDouble(1)
                    if (!lon.isNaN() && !lat.isNaN()) {
                        coordinates.add(PortalCoordinate(latitude = lat, longitude = lon))
                    }
                }
            }
            coordinates
        } catch (e: Exception) {
            Log.e(TAG, "parseRouteCoordinates error", e)
            null
        }
    }

    private fun parseEvents(eventsObj: JSONObject?): List<PortalEvent> {
        if (eventsObj == null) return emptyList()

        val events = mutableListOf<PortalEvent>()
        try {
            val featuresArray = eventsObj.optJSONArray("features") ?: return events

            Log.d(TAG, "Parsing ${featuresArray.length()} event features")

            for (i in 0 until featuresArray.length()) {
                val feature = featuresArray.optJSONObject(i) ?: continue
                val geometry = feature.optJSONObject("geometry")
                val properties = feature.optJSONObject("properties")

                if (geometry != null) {
                    val coords = geometry.optJSONArray("coordinates")
                    if (coords != null && coords.length() >= 2) {
                        val lon = coords.optDouble(0)
                        val lat = coords.optDouble(1)

                        // JSON-Key ist "distance_overtaker" (mit Underscore!)
                        val distanceOvertaker = if (properties != null) {
                            val dist = properties.optDouble("distance_overtaker", -1.0)
                            if (dist >= 0) dist else null
                        } else null

                        if (!lon.isNaN() && !lat.isNaN()) {
                            events.add(
                                PortalEvent(
                                    latitude = lat,
                                    longitude = lon,
                                    distanceOvertakerMeters = distanceOvertaker
                                )
                            )
                            Log.d(TAG, "Event $i: lat=$lat, lon=$lon, distance=$distanceOvertaker")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseEvents error", e)
        }

        Log.d(TAG, "Parsed ${events.size} events total")
        return events
    }
}

// --- Result Types ---

sealed class PortalTracksResult {
    data class Success(val tracks: List<PortalTrackSummary>) : PortalTracksResult()
    data class Error(val message: String) : PortalTracksResult()
}

sealed class PortalTrackDetailResult {
    data class Success(val track: PortalTrackDetail) : PortalTrackDetailResult()
    data class Error(val message: String) : PortalTrackDetailResult()
}

// --- Data Models ---

data class PortalTrackSummary(
    val slug: String,
    val title: String,
    val createdAt: String,
    val recordedAt: String,
    val visibility: String,
    val length: Double,        // Strecke in Metern
    val duration: Double,      // Dauer in Sekunden
    val numEvents: Int,        // Anzahl Ueberholungen
    val numMeasurements: Int   // Anzahl Messungen
)

data class PortalTrackDetail(
    val slug: String,
    val title: String,
    val description: String,
    val createdAt: String,
    val recordedAt: String,
    val visibility: String,
    val length: Double,
    val duration: Double,
    val numEvents: Int,
    val numMeasurements: Int
)

// --- Track Data Result (fuer Kartenanzeige) ---

sealed class PortalTrackDataResult {
    data class Success(val data: PortalTrackData) : PortalTrackDataResult()
    data class Error(val message: String) : PortalTrackDataResult()
}

/**
 * Track-Daten fuer die Kartenanzeige
 */
data class PortalTrackData(
    val routeCoordinates: List<PortalCoordinate>,
    val events: List<PortalEvent>
)

/**
 * GPS-Koordinate (lat/lon)
 */
data class PortalCoordinate(
    val latitude: Double,
    val longitude: Double
)

/**
 * Ueberholungs-Event mit Position und Abstand
 */
data class PortalEvent(
    val latitude: Double,
    val longitude: Double,
    val distanceOvertakerMeters: Double?  // Abstand in Metern, null wenn unbekannt
)
