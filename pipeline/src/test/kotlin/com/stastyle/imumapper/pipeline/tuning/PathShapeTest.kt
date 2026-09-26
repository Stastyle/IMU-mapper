package com.stastyle.imumapper.pipeline.tuning

import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.PositionSource
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.pdr.Angles
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PathShapeTest {

    /** Synthetic path: legs of (steps, heading) with a fixed stride, one point per step. */
    private fun path(vararg legs: Pair<Int, Double>, strideM: Double = 0.7, wobbleRad: Double = 0.0): List<PathPoint> {
        val out = ArrayList<PathPoint>()
        var x = 0.0
        var y = 0.0
        var t = 1_000_000_000L
        var index = 0
        out.add(PathPoint(t, Vec3.ZERO, PositionSource.PDR, legs[0].second, -1))
        for ((steps, heading) in legs) {
            for (k in 0 until steps) {
                val h = Angles.wrap(heading + wobbleRad * sin(index * 1.7))
                x += strideM * sin(h)
                y += strideM * cos(h)
                t += 500_000_000L
                out.add(PathPoint(t, Vec3(x, y, 0.0), PositionSource.PDR, h, index++))
            }
        }
        return out
    }

    @Test
    fun straightWalkHasNoTurnsAndOneLeg() {
        val s = PathShape.analyse(path(30 to 0.0, wobbleRad = 0.15))
        assertEquals(0, s.turns.size)
        assertEquals(1, s.legs.size)
        assertTrue(abs(s.legs[0].lengthM - 21.0) < 0.5, "leg length ${s.legs[0].lengthM}")
        assertTrue(abs(s.legs[0].headingDeg) < 3.0, "leg heading ${s.legs[0].headingDeg}")
    }

    @Test
    fun rectangleGivesThreeRightTurnsAndFourLegs() {
        val s = PathShape.analyse(path(10 to 0.0, 6 to PI / 2, 10 to PI, 6 to -PI / 2, wobbleRad = 0.1))
        assertEquals(3, s.turns.size, "turns: " + s.turns.map { it.angleDeg })
        for (t in s.turns) assertTrue(abs(t.angleDeg - 90.0) < 12.0, "turn angle ${t.angleDeg}")
        assertEquals(4, s.legs.size)
        val expectedHeadings = listOf(0.0, 90.0, 180.0, -90.0)
        for (i in 0 until 4) {
            val d = Math.toDegrees(Angles.diff(Math.toRadians(s.legs[i].headingDeg), Math.toRadians(expectedHeadings[i])))
            assertTrue(abs(d) < 8.0, "leg $i heading ${s.legs[i].headingDeg}")
        }
        assertTrue(abs(s.legs[0].lengthM - 7.0) < 0.3 && abs(s.legs[1].lengthM - 4.2) < 0.6, "leg lengths " + s.legs.map { it.lengthM })
        // The first turn sits where the second leg begins: about 7 m in.
        assertTrue(abs(s.turns[0].atDistanceM - 7.0) < 1.5, "first turn at ${s.turns[0].atDistanceM}")
    }

    @Test
    fun outAndBackIsOneHalfTurn() {
        val s = PathShape.analyse(path(15 to 0.3, 15 to 0.3 + PI))
        assertEquals(1, s.turns.size)
        assertTrue(abs(abs(s.turns[0].angleDeg) - 180.0) < 5.0, "angle ${s.turns[0].angleDeg}")
        assertEquals(2, s.legs.size)
    }

    @Test
    fun leftTurnIsNegative() {
        val s = PathShape.analyse(path(10 to 0.0, 10 to -PI / 2))
        assertEquals(1, s.turns.size)
        assertTrue(s.turns[0].angleDeg < -80.0, "angle ${s.turns[0].angleDeg}")
    }

    @Test
    fun gentleDriftIsNotATurn() {
        // 60 steps drifting 30 degrees in total: under the threshold at every window.
        val pts = ArrayList<PathPoint>()
        var x = 0.0
        var y = 0.0
        pts.add(PathPoint(0L, Vec3.ZERO, PositionSource.PDR, 0.0, -1))
        for (i in 0 until 60) {
            val h = Math.toRadians(i * 0.5)
            x += 0.7 * sin(h)
            y += 0.7 * cos(h)
            pts.add(PathPoint((i + 1) * 500_000_000L, Vec3(x, y, 0.0), PositionSource.PDR, h, i))
        }
        assertEquals(0, PathShape.analyse(pts).turns.size)
    }

    @Test
    fun tinyPathsDoNotCrash() {
        assertEquals(0, PathShape.analyse(emptyList()).turns.size)
        assertEquals(0, PathShape.analyse(path(1 to 0.0)).turns.size)
        assertEquals(1, PathShape.analyse(path(1 to 0.0)).legs.size)
        assertEquals(0, PathShape.analyse(path(3 to 0.0, 3 to PI / 2)).turns.size, "shorter than two windows")
    }
}
