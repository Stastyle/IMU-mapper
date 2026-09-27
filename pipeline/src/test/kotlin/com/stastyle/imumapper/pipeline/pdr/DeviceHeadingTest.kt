package com.stastyle.imumapper.pipeline.pdr

import com.stastyle.imumapper.pipeline.core.Quat
import com.stastyle.imumapper.pipeline.core.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceHeadingTest {

    private fun ns(s: Double): Long = 1_000_000_000L + Math.round(s * 1e9)

    /** Phone pointing at [headingDeg] (clockwise from north) with its top tilted up by [tiltRad]. */
    private fun pose(headingDeg: Double, tiltRad: Double): Quat =
        (Quat.yaw(-Math.toRadians(headingDeg)) * Quat.fromAxisAngle(Vec3.UNIT_X, tiltRad)).normalized()

    @Test
    fun meanHeadingAveragesTheSwayAndSamplesAnEmptyRangeOnce() {
        // Swaying 10 degrees either side of 30 once a second, like a phone carried while walking.
        val b = OrientationTrack.Builder()
        for (k in 0..400) {
            val s = k * 0.01
            b.add(ns(s), pose(30.0 + 10.0 * sin(2.0 * PI * s), 0.4))
        }
        val track = b.build()
        val mean = DeviceHeading.meanHeadingRad(track, HeadingAxis.FORWARD, ns(0.0), ns(2.0))
        assertEquals(30.0, Math.toDegrees(mean), 0.5)
        val once = DeviceHeading.meanHeadingRad(track, HeadingAxis.FORWARD, ns(0.25), ns(0.25))
        assertEquals(40.0, Math.toDegrees(once), 0.5)
    }

    @Test
    fun axisNearVerticalCountsForLittle() {
        // Flat and pointing north for a second, then stood up with the top leaning 5 degrees east:
        // the second half has a heading of 90 degrees that means almost nothing.
        val track = OrientationTrack.Builder()
            .add(ns(0.0), pose(0.0, 0.0))
            .add(ns(0.99), pose(0.0, 0.0))
            .add(ns(1.0), pose(90.0, Math.toRadians(85.0)))
            .add(ns(2.0), pose(90.0, Math.toRadians(85.0)))
            .build()
        val mean = Math.toDegrees(DeviceHeading.meanHeadingRad(track, HeadingAxis.FORWARD, ns(0.0), ns(2.0)))
        assertTrue(abs(mean) < 8.0, "mean heading $mean")
    }
}
