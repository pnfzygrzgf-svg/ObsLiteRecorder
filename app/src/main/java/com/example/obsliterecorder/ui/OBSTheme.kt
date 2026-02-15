// OBSTheme.kt - Zentrale Farben und Styles (wie iOS OBSUIV2)
package com.example.obsliterecorder.ui

import androidx.compose.ui.graphics.Color

object OBSColors {
    // Hintergrund
    val Background = Color(0xFFF2F2F7)

    // Akzentfarben
    val Accent = Color(0xFF0A84FF)
    val AccentDark = Color(0xFF007AFF)

    // Status-Farben
    val Good = Color(0xFF34C759)      // Grün - OK (>= 150cm)
    val Warn = Color(0xFFFFCC00)      // Gelb - Knapp (100-150cm)
    val Danger = Color(0xFFFF3B30)    // Rot - Gefährlich (< 100cm)

    // Neutral
    val Gray100 = Color(0xFFF3F4F6)
    val Gray200 = Color(0xFFE5E7EB)
    val Gray300 = Color(0xFFD1D5DB)
    val Gray400 = Color(0xFF9CA3AF)
    val Gray500 = Color(0xFF6B7280)
    val Gray700 = Color(0xFF374151)
    val Gray900 = Color(0xFF111827)

    // Card
    val CardBackground = Color.White
    val CardBorder = Color(0xFFE5E7EB)

    // Spezial
    val GoodBackground = Color(0xFFE8F7EE)
    val GoodForeground = Color(0xFF1B7A3C)

    // Warnung / Orange (iOS-kompatibel)
    val WarnOrange = Color(0xFFFF9500)

    /**
     * Farbe basierend auf Überholabstand (wie iOS obsOvertakeColorV2)
     * 4 Stufen: <110 Rot, 110-130 Orange, 130-150 Gelb, >=150 Grün
     */
    fun overtakeColor(cm: Int?): Color = when {
        cm == null -> Gray400
        cm >= 150 -> Good
        cm >= 130 -> Warn
        cm >= 110 -> WarnOrange
        else -> Danger
    }
}

/**
 * Status-Label für Überholabstand (iOS-kompatible 4 Stufen)
 */
data class OvertakeStatus(val label: String, val color: Color)

fun getOvertakeStatus(cm: Int?): OvertakeStatus = when {
    cm == null -> OvertakeStatus("–", OBSColors.Gray400)
    cm >= 150 -> OvertakeStatus("OK", OBSColors.Good)
    cm >= 130 -> OvertakeStatus("Knapp", OBSColors.Warn)
    cm >= 110 -> OvertakeStatus("Achtung", OBSColors.WarnOrange)
    else -> OvertakeStatus("Gefährlich", OBSColors.Danger)
}
