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

/** Trip mode, carry and date text and icons shared by the screens; pure functions so the composables stay small. */
object TripFormat {

    fun modeLabel(mode: TripMode): String = when (mode) {
        TripMode.POCKET -> "Pocket"
        TripMode.FLASHLIGHT -> "Flashlight"
        TripMode.ILLUMINATED -> "Illuminated"
    }

    /** One line per mode for the Record tab's mode cards. */
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
}
