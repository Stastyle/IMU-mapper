package com.stastyle.imumapper.ui.calibration

import com.stastyle.imumapper.data.db.TripEntity
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.Vec3
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Locale-independent number formatting for the calibration and debug screens (pure JVM). */
object Fmt {

    fun num(x: Double, decimals: Int = 2): String = String.format(Locale.US, "%." + decimals + "f", x)

    fun metres(x: Double): String = num(x, 2) + " m"

    fun percent(x: Double?): String = if (x == null) "n/a" else num(x, 1) + " %"

    fun degrees(rad: Double): String = num(Math.toDegrees(rad), 1) + "°"

    fun radS(x: Double): String = String.format(Locale.US, "%.4f rad/s", x)

    fun vec3(v: Vec3, decimals: Int = 4): String =
        "(" + num(v.x, decimals) + ", " + num(v.y, decimals) + ", " + num(v.z, decimals) + ")"

    fun seconds(s: Double): String = num(s, 1) + " s"

    fun carry(position: CarryPosition): String = when (position) {
        CarryPosition.HAND -> "Hand"
        CarryPosition.POCKET -> "Pocket"
        CarryPosition.CHEST -> "Chest"
        CarryPosition.HELMET -> "Helmet"
    }

    /** "Sep 25, 2026 10:12 · 4:05 · 120 m" for trip pickers, dropping unknown parts. */
    fun tripLine(trip: TripEntity): String {
        val parts = ArrayList<String>()
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
        parts.add(dateFormat.format(Date(trip.startedAtEpochMs)))
        val d = trip.durationS
        if (d != null && d >= 0.0) parts.add(durationText(d))
        val m = trip.distanceM
        if (m != null && m >= 0.0) parts.add(num(m, 0) + " m")
        return parts.joinToString(" · ")
    }

    /** "4:05" or "1:02:09". */
    fun durationText(seconds: Double): String {
        val total = Math.round(seconds).toInt().coerceAtLeast(0)
        val h = total / 3600
        val mm = (total % 3600) / 60
        val ss = total % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, mm, ss)
        } else {
            String.format(Locale.US, "%d:%02d", mm, ss)
        }
    }

    /** The values the calibration flows change, one per line, for the summary card. */
    fun configSummary(c: PipelineConfig): List<Pair<String, String>> = listOf(
        "Stride length" to metres(c.strideLengthM),
        "Weinberg k" to (if (c.weinbergK > 0.0) num(c.weinbergK, 3) else "off (fixed stride)"),
        "Heading offset" to degrees(c.headingOffsetRad),
        "Gyro bias (rad/s)" to vec3(c.gyroBias),
        "Magnetometer" to (if (c.useMagnetometer) "used, gate " + num(c.magGateTolerance * 100.0, 0) + " %" else "off"),
        "Hardware steps" to (if (c.preferHardwareSteps) "preferred" else "software detector"),
    )
}
