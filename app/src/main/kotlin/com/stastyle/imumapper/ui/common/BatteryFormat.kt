package com.stastyle.imumapper.ui.common

/**
 * What the battery icon shows. The composable maps [Bar0]..[Bar6] and [Full] to the matching
 * Material battery glyphs and [Charging] to the charging one, so the bucketing stays testable.
 */
enum class BatteryBucket { Bar0, Bar1, Bar2, Bar3, Bar4, Bar5, Bar6, Full, Charging }

/** Battery texts and icon choice; pure, so `Battery.kt` keeps only the broadcast plumbing. */
object BatteryFormat {

    /** At or below this the level is shown as low (a warning colour and a spoken "low"). */
    const val LOW_PERCENT = 15

    /** From this level up the icon is full; below it, seven bars share 0..94 %. */
    private const val FULL_PERCENT = 95

    /**
     * The level in whole percent from the broadcast's `EXTRA_LEVEL` and `EXTRA_SCALE`, clamped to
     * 0..100; null when either is missing (negative) or the scale is zero.
     */
    fun percent(level: Int, scale: Int): Int? {
        if (level < 0 || scale <= 0) return null
        return ((level * 100L + scale / 2) / scale).toInt().coerceIn(0, 100)
    }

    /** "92 %", with the space the app puts before every unit. */
    fun levelText(percent: Int): String = "$percent %"

    /** Low only while not charging: a phone on the charger needs no warning. */
    fun isLow(percent: Int, charging: Boolean): Boolean = !charging && percent <= LOW_PERCENT

    fun bucket(percent: Int, charging: Boolean): BatteryBucket {
        if (charging) return BatteryBucket.Charging
        val p = percent.coerceIn(0, 100)
        if (p >= FULL_PERCENT) return BatteryBucket.Full
        return BatteryBucket.entries[p * 7 / 100]
    }

    /** What TalkBack reads for the indicator: "Battery 12 percent, low", or "Battery level unknown" for null. */
    fun spoken(percent: Int?, charging: Boolean): String = when {
        percent == null -> "Battery level unknown"
        charging -> "Battery $percent percent, charging"
        isLow(percent, charging) -> "Battery $percent percent, low"
        else -> "Battery $percent percent"
    }
}
