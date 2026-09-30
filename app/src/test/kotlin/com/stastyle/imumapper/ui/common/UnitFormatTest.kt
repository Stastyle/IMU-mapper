package com.stastyle.imumapper.ui.common

import kotlin.test.Test
import kotlin.test.assertEquals

/** The shared distance, duration, height and clock texts. */
class UnitFormatTest {

    @Test
    fun distancesRoundToATenthOfAMetreAndSwitchToKilometresAtOneThousand() {
        assertEquals("0.0 m", formatDistance(0.0))
        assertEquals("0.1 m", formatDistance(0.05))
        assertEquals("68.1 m", formatDistance(68.14))
        assertEquals("999.9 m", formatDistance(999.94))
        // 999.95 rounds to 1000.0 m, which is a kilometre, not "1000.0 m".
        assertEquals("1.00 km", formatDistance(999.95))
        assertEquals("1.00 km", formatDistance(1000.0))
        assertEquals("1.23 km", formatDistance(1234.5))
        assertEquals("1.24 km", formatDistance(1235.0))
        assertEquals("12.35 km", formatDistance(12_345.0))
    }

    @Test
    fun unknownDistancesAreADash() {
        assertEquals(NO_VALUE, formatDistance(null))
        assertEquals(NO_VALUE, formatDistance(Double.NaN))
        assertEquals(NO_VALUE, formatDistance(Double.POSITIVE_INFINITY))
        assertEquals(NO_VALUE, formatDistance(-1.0))
        assertEquals(NO_VALUE, formatDistance(-0.01))
    }

    @Test
    fun durationsShowMinutesUnderAnHourAndHoursFromThereOn() {
        assertEquals("0:00", formatDuration(0.0))
        assertEquals("0:00", formatDuration(0.4))
        assertEquals("1:05", formatDuration(65.4))
        // Rounded to the nearest second, as the viewer did.
        assertEquals("1:00", formatDuration(59.5))
        assertEquals("59:59", formatDuration(3599.0))
        assertEquals("1:00:00", formatDuration(3600.0))
        assertEquals("1:02:09", formatDuration(3729.0))
        assertEquals("27:46:40", formatDuration(100_000.0))
    }

    @Test
    fun unknownOrNegativeDurationsAreADash() {
        assertEquals(NO_VALUE, formatDuration(null))
        assertEquals(NO_VALUE, formatDuration(-1.0))
        assertEquals(NO_VALUE, formatDuration(Double.NaN))
        assertEquals(NO_VALUE, formatDuration(Double.POSITIVE_INFINITY))
    }

    @Test
    fun heightsAreSignedAndNeverNegativeZero() {
        assertEquals("+5.0 m", formatHeight(5.0))
        assertEquals("-0.7 m", formatHeight(-0.7))
        assertEquals("+2.1 m", formatHeight(2.06))
        assertEquals("+0.0 m", formatHeight(0.0))
        assertEquals("+0.0 m", formatHeight(-0.0))
        assertEquals("+0.0 m", formatHeight(-0.04))
        assertEquals("+0.0 m", formatHeight(0.04))
        assertEquals("-0.1 m", formatHeight(-0.06))
        assertEquals("-12.3 m", formatHeight(-12.34))
        assertEquals(NO_VALUE, formatHeight(Double.NaN))
    }

    @Test
    fun theClockAlwaysShowsHoursAndTruncatesToTheSecond() {
        assertEquals("0:00:00", formatClock(0L))
        assertEquals("0:00:37", formatClock(37_000_000_000L))
        assertEquals("0:00:37", formatClock(37_999_999_999L))
        assertEquals("0:01:05", formatClock(65_000_000_000L))
        assertEquals("1:02:09", formatClock(3_729_000_000_000L))
        assertEquals("12:00:00", formatClock(43_200_000_000_000L))
    }

    @Test
    fun aNegativeClockClampsToZero() {
        assertEquals("0:00:00", formatClock(-1L))
        assertEquals("0:00:00", formatClock(-5_000_000_000L))
        assertEquals("0:00:00", formatClock(Long.MIN_VALUE))
    }
}
