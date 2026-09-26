package com.stastyle.imumapper.pipeline.pdr

import com.stastyle.imumapper.pipeline.core.Quat
import com.stastyle.imumapper.pipeline.core.Vec3
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CarryChangeDetectorTest {

    private val minTilt = Math.toRadians(30.0)
    private val settleS = 1.5

    /**
     * A 100 Hz orientation track: yaw from [heading], tilt about the device x axis from [tilt], plus a
     * pocket-sized gait sway of 0.15 rad at 1.8 Hz that the detector must see through.
     */
    private fun track(seconds: Double, tilt: (Double) -> Double, heading: (Double) -> Double = { 0.0 }): OrientationTrack {
        val b = OrientationTrack.Builder()
        var k = 0
        while (k * 0.01 <= seconds) {
            val s = k * 0.01
            val sway = Quat.fromAxisAngle(Vec3.UNIT_X, 0.15 * sin(2.0 * PI * 1.8 * s))
            b.add(ns(s), (Quat.yaw(heading(s)) * Quat.fromAxisAngle(Vec3.UNIT_X, tilt(s)) * sway).normalized())
            k++
        }
        return b.build()
    }

    private fun ns(s: Double): Long = 5_000_000_000L + Math.round(s * 1e9)

    private fun seconds(tNs: Long): Double = (tNs - 5_000_000_000L) / 1e9

    /** Linear blend from [a] to [b] starting at [at] over [over] seconds. */
    private fun ramp(s: Double, at: Double, over: Double, a: Double, b: Double): Double = when {
        s < at -> a
        s >= at + over -> b
        else -> a + (b - a) * (s - at) / over
    }

    private fun detect(t: OrientationTrack) = CarryChangeDetector.detect(t, t.firstNs, t.lastNs, minTilt, settleS)

    @Test
    fun steadyCarryHasNoChanges() {
        val t = track(40.0, tilt = { 0.4 })
        assertTrue(detect(t).isEmpty())
        // Near-vertical (pocket) with the same sway is just as steady.
        assertTrue(detect(track(40.0, tilt = { 1.3 })).isEmpty())
    }

    @Test
    fun handToPocketIsOneChangeWithTightBounds() {
        val t = track(30.0, tilt = { s -> ramp(s, 10.0, 1.0, 0.4, 1.3) })
        val changes = detect(t)
        assertEquals(1, changes.size, changes.joinToString { seconds(it.startNs).toString() + "-" + seconds(it.endNs) })
        val c = changes[0]
        assertFalse(c.returned)
        val start = seconds(c.startNs)
        val end = seconds(c.endNs)
        assertTrue(start in 9.5..10.6, "start $start should sit where the tilt began to leave")
        assertTrue(end in 11.0..14.5, "end $end should follow the move once the tilt is steady")
    }

    @Test
    fun glanceThatComesBackIsMarkedReturned() {
        // Up to a steep tilt, held for half a second, then straight back to where it was: never
        // steady in between, so it is one move that ends where it began.
        val t = track(30.0, tilt = { s -> if (s < 10.0) 0.4 else if (s < 11.0) ramp(s, 10.0, 1.0, 0.4, 1.8) else if (s < 11.5) 1.8 else ramp(s, 11.5, 1.0, 1.8, 0.4) })
        val changes = detect(t)
        assertEquals(1, changes.size, changes.joinToString { seconds(it.startNs).toString() + "-" + seconds(it.endNs) + " " + it.returned })
        val c = changes[0]
        val where = seconds(c.startNs).toString() + "-" + seconds(c.endNs) + " returned=" + c.returned
        assertTrue(c.returned, where)
        assertTrue(seconds(c.startNs) in 9.5..10.8, where)
        assertTrue(seconds(c.endNs) in 12.0..17.0, where)
    }

    @Test
    fun longerLookAtTheScreenIsTwoChanges() {
        // Held steady at the top for long enough to count as a carry position of its own, so the
        // offset is re-estimated there and again after the phone goes back.
        val t = track(30.0, tilt = { s -> if (s < 10.0) 0.4 else if (s < 11.0) ramp(s, 10.0, 1.0, 0.4, 1.5) else if (s < 14.0) 1.5 else ramp(s, 14.0, 1.0, 1.5, 0.4) })
        val changes = detect(t)
        assertEquals(2, changes.size, changes.joinToString { seconds(it.startNs).toString() + "-" + seconds(it.endNs) + " " + it.returned })
        assertFalse(changes[0].returned)
        assertFalse(changes[1].returned)
    }

    @Test
    fun slowSlideIsStillCaught() {
        // The phone slides over twenty seconds: far too slow for a before/after window, but it ends
        // up far from where it started and must be re-oriented eventually.
        val t = track(50.0, tilt = { s -> ramp(s, 10.0, 20.0, 0.4, 1.3) })
        val changes = detect(t)
        assertTrue(changes.isNotEmpty())
        assertFalse(changes[0].returned)
        assertTrue(seconds(changes[0].startNs) in 10.0..20.0, "started at " + seconds(changes[0].startNs))
        assertTrue(seconds(changes.last().endNs) <= 35.0, "settled by " + seconds(changes.last().endNs))
    }

    @Test
    fun turnsOfTheWalkerAreNotChanges() {
        // A sharp 90 degree turn, then a slow full circle: yaw only, the tilt never moves.
        val t = track(60.0, tilt = { 0.4 }, heading = { s -> if (s < 10.0) 0.0 else if (s < 20.0) PI / 2 else PI / 2 + (s - 20.0) / 40.0 * 2.0 * PI })
        assertTrue(detect(t).isEmpty())
    }

    @Test
    fun moveThatNeverSettlesRunsToTheEnd() {
        // The log stops before the tilt has been steady for a settle window.
        val t = track(12.0, tilt = { s -> ramp(s, 10.0, 1.0, 0.4, 1.3) })
        val changes = detect(t)
        assertEquals(1, changes.size)
        assertTrue(seconds(changes[0].endNs) >= 11.9, "ended at " + seconds(changes[0].endNs))
        assertFalse(changes[0].returned)
    }

    @Test
    fun emptyOrShortTrackGivesNothing() {
        assertTrue(CarryChangeDetector.detect(OrientationTrack.EMPTY, 0L, 10_000_000_000L, minTilt, settleS).isEmpty())
        assertTrue(detect(track(1.0, tilt = { 0.4 })).isEmpty())
    }
}
