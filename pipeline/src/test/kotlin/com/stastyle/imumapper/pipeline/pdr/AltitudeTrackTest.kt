package com.stastyle.imumapper.pipeline.pdr

import com.stastyle.imumapper.pipeline.core.BaroSample
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AltitudeTrackTest {

    private val s = 1_000_000_000L

    /** Uneven sample times and a height that goes up and down, unsmoothed. */
    private val track = AltitudeTrack.fromBaro(
        listOf(
            BaroSample(10 * s, 1013.25f),
            BaroSample(11 * s, 1013.10f),
            BaroSample(13 * s, 1013.20f),
            BaroSample(13 * s + s / 2, 1012.90f),
            BaroSample(16 * s, 1013.00f),
        ),
        smoothingS = 0.0,
    )!!

    /** Mean of [AltitudeTrack.at] by a fine midpoint sum. */
    private fun numericMean(fromNs: Long, toNs: Long): Double {
        val n = 20_000
        val dt = (toNs - fromNs).toDouble() / n
        var sum = 0.0
        for (k in 0 until n) sum += track.at(fromNs + ((k + 0.5) * dt).toLong())
        return sum / n
    }

    @Test
    fun meanOverMatchesTheInterpolatedHeight() {
        val ranges = listOf(10 * s to 16 * s, 10 * s + s / 3 to 12 * s, 12 * s to 14 * s, 13 * s to 13 * s + s / 4)
        for ((from, to) in ranges) {
            val expected = numericMean(from, to)
            val actual = track.meanOver(from, to)
            assertTrue(abs(expected - actual) < 1e-4, "[$from, $to]: $actual vs $expected")
        }
    }

    @Test
    fun meanOverClampsOutsideTheSamples() {
        assertEquals(track.at(10 * s), track.meanOver(8 * s, 9 * s), 1e-12)
        assertEquals(track.at(16 * s), track.meanOver(17 * s, 20 * s), 1e-12)
        // Half before the first sample, at its height, and half inside.
        val expected = (track.at(10 * s) + track.meanOver(10 * s, 11 * s)) / 2
        assertEquals(expected, track.meanOver(9 * s, 11 * s), 1e-9)
        // Empty and backwards ranges give the value at the end.
        assertEquals(track.at(12 * s), track.meanOver(12 * s, 12 * s), 1e-12)
        assertEquals(track.at(12 * s), track.meanOver(14 * s, 12 * s), 1e-12)
    }
}
