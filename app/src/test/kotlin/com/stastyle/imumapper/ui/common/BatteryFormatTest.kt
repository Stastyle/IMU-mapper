package com.stastyle.imumapper.ui.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The battery level, its icon bucket, the low warning and the spoken text. */
class BatteryFormatTest {

    @Test
    fun percentComesFromLevelAndScale() {
        assertEquals(92, BatteryFormat.percent(level = 92, scale = 100))
        assertEquals(0, BatteryFormat.percent(level = 0, scale = 100))
        assertEquals(100, BatteryFormat.percent(level = 100, scale = 100))
        // Some devices report on another scale; the result is rounded.
        assertEquals(50, BatteryFormat.percent(level = 1, scale = 2))
        assertEquals(67, BatteryFormat.percent(level = 2, scale = 3))
        // A level past the scale is clamped rather than shown as 104 %.
        assertEquals(100, BatteryFormat.percent(level = 104, scale = 100))
    }

    @Test
    fun aMissingLevelOrScaleGivesNoPercent() {
        assertNull(BatteryFormat.percent(level = -1, scale = 100))
        assertNull(BatteryFormat.percent(level = 50, scale = -1))
        assertNull(BatteryFormat.percent(level = 50, scale = 0))
    }

    @Test
    fun levelTextHasTheAppsSpaceBeforePercent() {
        assertEquals("92 %", BatteryFormat.levelText(92))
        assertEquals("5 %", BatteryFormat.levelText(5))
    }

    @Test
    fun lowIsAtOrBelowFifteenPercentAndNeverWhileCharging() {
        assertTrue(BatteryFormat.isLow(15, charging = false))
        assertTrue(BatteryFormat.isLow(0, charging = false))
        assertFalse(BatteryFormat.isLow(16, charging = false))
        assertFalse(BatteryFormat.isLow(5, charging = true))
    }

    @Test
    fun bucketsSplitTheLevelIntoSevenBarsBelowFull() {
        assertEquals(BatteryBucket.Bar0, BatteryFormat.bucket(0, charging = false))
        assertEquals(BatteryBucket.Bar0, BatteryFormat.bucket(14, charging = false))
        assertEquals(BatteryBucket.Bar1, BatteryFormat.bucket(15, charging = false))
        assertEquals(BatteryBucket.Bar3, BatteryFormat.bucket(50, charging = false))
        assertEquals(BatteryBucket.Bar6, BatteryFormat.bucket(94, charging = false))
        assertEquals(BatteryBucket.Full, BatteryFormat.bucket(95, charging = false))
        assertEquals(BatteryBucket.Full, BatteryFormat.bucket(100, charging = false))
    }

    @Test
    fun bucketsRiseWithTheLevel() {
        val buckets = (0..100).map { BatteryFormat.bucket(it, charging = false).ordinal }
        assertEquals(buckets.sorted(), buckets)
        assertEquals(BatteryBucket.Full.ordinal, buckets.max())
    }

    @Test
    fun chargingOverridesTheLevelBucket() {
        assertEquals(BatteryBucket.Charging, BatteryFormat.bucket(3, charging = true))
        assertEquals(BatteryBucket.Charging, BatteryFormat.bucket(100, charging = true))
    }

    @Test
    fun spokenTextNamesTheStateInWords() {
        assertEquals("Battery 92 percent", BatteryFormat.spoken(92, charging = false))
        assertEquals("Battery 12 percent, low", BatteryFormat.spoken(12, charging = false))
        assertEquals("Battery 12 percent, charging", BatteryFormat.spoken(12, charging = true))
        assertEquals("Battery level unknown", BatteryFormat.spoken(null, charging = false))
    }
}
