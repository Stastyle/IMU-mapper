package com.stastyle.imumapper.ui.triplist

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.ui.graphics.vector.ImageVector
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.TripMode
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Text and icons for the trip list; pure functions so the composables stay small. */
object TripFormat {

    fun modeLabel(mode: TripMode): String = when (mode) {
        TripMode.POCKET -> "Pocket"
        TripMode.FLASHLIGHT -> "Flashlight"
        TripMode.ILLUMINATED -> "Illuminated"
    }

    /** One line per mode for the new-trip dialog. */
    fun modeExplanation(mode: TripMode): String = when (mode) {
        TripMode.POCKET -> "IMU only. Phone in a pocket, screen off is fine, works in the dark. Step-based path."
        TripMode.FLASHLIGHT -> "Camera tracking with the torch on. Hold the phone facing forward. Falls back to steps."
        TripMode.ILLUMINATED -> "Camera tracking in a lit space with automatic photos along the path."
    }

    fun modeIcon(mode: TripMode): ImageVector = when (mode) {
        TripMode.POCKET -> Icons.Filled.Smartphone
        TripMode.FLASHLIGHT -> Icons.Filled.Highlight
        TripMode.ILLUMINATED -> Icons.Filled.CameraAlt
    }

    fun carryLabel(position: CarryPosition): String = when (position) {
        CarryPosition.HAND -> "Hand"
        CarryPosition.POCKET -> "Pocket"
        CarryPosition.CHEST -> "Chest"
        CarryPosition.HELMET -> "Helmet"
    }

    fun date(epochMs: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()).format(Date(epochMs))

    /** "4:05" or "1:02:09"; null for an unknown duration. */
    fun duration(seconds: Double?): String? {
        if (seconds == null || seconds < 0.0) return null
        val total = seconds.roundToInt()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** "12 m" or "1.25 km"; null for an unknown distance. */
    fun distance(metres: Double?): String? {
        if (metres == null || metres < 0.0) return null
        return if (metres >= 1000.0) String.format(Locale.US, "%.2f km", metres / 1000.0)
        else String.format(Locale.US, "%.0f m", metres)
    }

    /** "Sep 25, 2026 10:12 · 4:05 · 120 m", dropping the parts that are unknown. */
    fun summaryLine(startedAtEpochMs: Long, durationS: Double?, distanceM: Double?): String =
        listOfNotNull(date(startedAtEpochMs), duration(durationS), distance(distanceM)).joinToString(" · ")
}
