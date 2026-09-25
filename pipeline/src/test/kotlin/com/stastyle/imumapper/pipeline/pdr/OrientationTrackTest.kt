package com.stastyle.imumapper.pipeline.pdr

import com.stastyle.imumapper.pipeline.core.Quat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrientationTrackTest {

    private fun track(): OrientationTrack = OrientationTrack.Builder(4)
        .add(1_000L, Quat.yaw(0.0))
        .add(2_000L, Quat.yaw(PI / 2))
        .add(3_000L, Quat.yaw(PI / 2))
        .add(2_500L, Quat.yaw(PI)) // out of order: dropped
        .build()

    @Test
    fun interpolatesBetweenNeighboursAndClampsAtEnds() {
        val t = track()
        assertEquals(3, t.size)
        assertTrue(abs(t.at(1_500L).forwardHeadingRad() - (-PI / 4)) < 1e-9)
        assertTrue(abs(t.at(0L).forwardHeadingRad()) < 1e-9)
        assertTrue(abs(t.at(9_000L).forwardHeadingRad() - (-PI / 2)) < 1e-9)
        assertEquals(Quat.IDENTITY, OrientationTrack.EMPTY.at(5L))
    }

    @Test
    fun cursorMatchesRandomAccess() {
        val t = track()
        val c = t.cursor()
        for (time in longArrayOf(500L, 1_000L, 1_250L, 1_999L, 2_000L, 2_600L, 3_000L, 4_000L, 1_500L)) {
            assertEquals(t.at(time), c.at(time), "at $time")
        }
    }
}
