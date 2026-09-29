package com.stastyle.imumapper.ui.common

import java.util.Locale
import kotlin.math.roundToLong

/*
 * Number formats shared by the trip cards, the viewer and the recording screen, so the same distance
 * reads the same everywhere. Pure Kotlin, no Android or Compose, so it is unit-tested on the JVM.
 * Locale.US throughout: the decimal separator must not change with the phone's language, and the
 * texts are laid out left to right.
 */

/** Shown for a value that is unknown; `StatTile` speaks it as "not available". */
const val NO_VALUE = "—"

/**
 * "999.9 m" below a kilometre, "1.23 km" from there on, after rounding to 0.1 m (so 999.95 m is
 * "1.00 km", not "1000.0 m"). [NO_VALUE] for null, negative or non-finite input.
 */
fun formatDistance(metres: Double?): String {
    if (metres == null || !metres.isFinite() || metres < 0.0) return NO_VALUE
    val tenths = (metres * 10.0).roundToLong()
    if (tenths < 10_000L) return String.format(Locale.US, "%.1f m", tenths / 10.0)
    // Rounded to whole decametres before formatting, so "%.2f" never meets a tie it would break its own way.
    val decametres = (tenths / 100.0).roundToLong()
    return String.format(Locale.US, "%.2f km", decametres / 100.0)
}

/**
 * "4:05" under an hour, "1:02:09" from there on, rounded to the nearest second as the viewer always
 * showed it. [NO_VALUE] for null, negative or non-finite input.
 */
fun formatDuration(seconds: Double?): String {
    if (seconds == null || !seconds.isFinite() || seconds < 0.0) return NO_VALUE
    val total = seconds.roundToLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%d:%02d", m, s)
    }
}

/**
 * A signed height to 0.1 m, "+5.0 m" or "-0.7 m". A value that rounds to zero is "+0.0 m", never
 * "-0.0 m". [NO_VALUE] for non-finite input.
 */
fun formatHeight(metres: Double): String {
    if (!metres.isFinite()) return NO_VALUE
    // Adding 0.0 turns a negative zero into a positive one.
    val rounded = (metres * 10.0).roundToLong() / 10.0 + 0.0
    return String.format(Locale.US, "%+.1f m", rounded)
}

/** A running clock, always "h:mm:ss" ("0:00:37"), whole seconds truncated. Negative input clamps to zero. */
fun formatClock(elapsedNs: Long): String {
    val totalS = (elapsedNs / 1_000_000_000L).coerceAtLeast(0L)
    val h = totalS / 3600
    val m = (totalS % 3600) / 60
    val s = totalS % 60
    return String.format(Locale.US, "%d:%02d:%02d", h, m, s)
}
