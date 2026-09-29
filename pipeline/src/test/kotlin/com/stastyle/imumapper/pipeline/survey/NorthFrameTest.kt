package com.stastyle.imumapper.pipeline.survey

import com.stastyle.imumapper.pipeline.core.AnnotationKind
import com.stastyle.imumapper.pipeline.core.PathAnnotation
import com.stastyle.imumapper.pipeline.core.PathKeyframe
import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.PositionSource
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.post.LoopClosure
import com.stastyle.imumapper.pipeline.post.Smoothing
import com.stastyle.imumapper.pipeline.survey.SurveyPaths.T0
import com.stastyle.imumapper.pipeline.survey.SurveyPaths.tNs
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class NorthFrameTest {

    private fun pt(i: Int, x: Double, y: Double, z: Double = 0.0, heading: Double = 0.0) =
        PathPoint(tNs(i), Vec3(x, y, z), PositionSource.PDR, heading, i - 1)

    @Test
    fun ninetyDegreesTakesNorthToEast() {
        val points = listOf(pt(0, 0.0, 0.0), pt(1, 0.0, 1.0, 0.5))
        val turned = NorthFrame.rotatePoints(points, Vec3.ZERO, 90.0)
        assertNear(Vec3(1.0, 0.0, 0.5), turned[1].p, 1e-12)
        assertEquals(PI / 2, turned[1].headingRad, 1e-12)
        // About a pivot that is not the origin.
        val shifted = NorthFrame.rotatePoints(listOf(pt(0, 2.0, 3.0), pt(1, 2.0, 4.0)), Vec3(2.0, 3.0, 0.0), 90.0)
        assertNear(Vec3(3.0, 3.0, 0.0), shifted[1].p, 1e-12)
    }

    @Test
    fun rotateTurnsEveryPartOfTheResultAboutTheFirstPoint() {
        val points = listOf(pt(0, 1.0, 1.0), pt(1, 1.0, 2.0, 0.5, heading = 3.0))
        val result = SurveyPaths.result(
            points,
            annotations = listOf(PathAnnotation(tNs(1), AnnotationKind.JUNCTION, "fork", Vec3(1.0, 3.0, 1.0))),
        ).copy(
            rawPoints = listOf(pt(0, 1.0, 1.0), pt(1, 1.0, 2.5)),
            keyframes = listOf(PathKeyframe(tNs(1), "k.jpg", Vec3(0.0, 1.0, 0.0), 0.75 * PI)),
            pointCloud = listOf(Vec3(1.0, 0.0, 2.0)),
        )
        val r = NorthFrame.rotate(result, 90.0)
        // O = (1, 1): (0, +1) from O goes to (+1, 0), (-1, 0) to (0, +1), (0, -1) to (-1, 0).
        assertNear(Vec3(1.0, 1.0, 0.0), r.points[0].p, 1e-12)
        assertNear(Vec3(2.0, 1.0, 0.5), r.points[1].p, 1e-12)
        assertNear(Vec3(2.5, 1.0, 0.0), r.rawPoints[1].p, 1e-12)
        assertNear(Vec3(3.0, 1.0, 1.0), r.annotations[0].p, 1e-12)
        assertNear(Vec3(1.0, 2.0, 0.0), r.keyframes[0].p, 1e-12)
        assertNear(Vec3(0.0, 1.0, 2.0), r.pointCloud[0], 1e-12)
        // Headings wrap: 3.0 + pi/2 is past pi, and 0.75 pi + 0.5 pi is -0.75 pi.
        assertEquals(3.0 - 1.5 * PI, r.points[1].headingRad, 1e-12)
        assertEquals(-0.75 * PI, r.keyframes[0].headingRad, 1e-12)
        assertEquals(result.stats, r.stats)
        assertEquals(result.points.map { it.tNs }, r.points.map { it.tNs })
        assertEquals(result.points.map { it.source }, r.points.map { it.source })
        assertEquals(result.points.map { it.stepIndex }, r.points.map { it.stepIndex })
        assertEquals("fork", r.annotations[0].note)
        assertEquals("k.jpg", r.keyframes[0].fileName)
        assertEquals(result.diagnostics, r.diagnostics)
    }

    @Test
    fun zeroOrEmptyIsTheSameInstance() {
        val result = SurveyPaths.result(SurveyPaths.steps(4 to 0.0))
        assertSame(result, NorthFrame.rotate(result, 0.0))
        val empty = SurveyPaths.result(emptyList())
        assertSame(empty, NorthFrame.rotate(empty, 30.0))
        assertSame(result.points, NorthFrame.rotatePoints(result.points, Vec3.ZERO, 0.0))
    }

    @Test
    fun tenDegreesRaisesEveryLegAzimuthByTen() {
        // Legs to about 17, 115, 217 and 354 degrees: the last one wraps past north.
        val points = SurveyPaths.steps(8 to 0.3, 6 to 2.0, 10 to -2.5, 6 to -0.1, climbPerStepM = 0.05)
        val plain = PathTimeline(points)
        val turned = PathTimeline(NorthFrame.rotate(SurveyPaths.result(points), 10.0).points)
        val moments = listOf(T0, tNs(4), tNs(8), tNs(11), tNs(14), tNs(20), tNs(24), tNs(27), tNs(30))
        for ((from, to) in moments.zipWithNext()) {
            val before = Measure.leg(plain, from, to)
            val after = Measure.leg(turned, from, to)
            val b = assertNotNull(before.azimuthDeg)
            val a = assertNotNull(after.azimuthDeg)
            assertEquals(0.0, SurveyAngles.wrapDeg(a - b - 10.0), 1e-9, "leg $from-$to: $b became $a")
            assertEquals(before.lengthM, after.lengthM, 1e-9)
            assertEquals(before.horizontalM, after.horizontalM, 1e-9)
            assertEquals(before.heightChangeM, after.heightChangeM, 1e-12)
            assertEquals(before.slopeDeg, after.slopeDeg, 1e-9)
            assertEquals(before.pathM, after.pathM, 1e-9)
        }
    }

    /** A lap that misses its start by (0.6, -0.4), moved off the origin so the pivot matters. */
    private fun openLap(): List<PathPoint> =
        SurveyPaths.steps(10 to 0.2, 8 to 1.9, 12 to -2.8, 6 to -1.2, swayM = 0.2, climbPerStepM = 0.02)
            .map { it.copy(p = it.p + Vec3(3.0, -2.0, 1.0)) }

    private fun assertSamePoints(expected: List<PathPoint>, actual: List<PathPoint>) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertNear(expected[i].p, actual[i].p, 1e-9, "point $i")
            assertEquals(expected[i].headingRad, actual[i].headingRad, 1e-12)
            assertEquals(expected[i].tNs, actual[i].tNs)
        }
    }

    @Test
    fun rotationCommutesWithLoopClosure() {
        val points = openLap()
        val origin = points[0].p
        val closeAt = listOf(points.last().tNs)
        val closed = assertNotNull(LoopClosure.apply(points, closeAt)).points
        val closedThenTurned = NorthFrame.rotatePoints(closed, origin, 25.0)
        val turned = NorthFrame.rotatePoints(points, origin, 25.0)
        val turnedThenClosed = assertNotNull(LoopClosure.apply(turned, closeAt)).points
        assertSamePoints(closedThenTurned, turnedThenClosed)
    }

    @Test
    fun rotationCommutesWithSmoothing() {
        val points = openLap()
        val origin = points[0].p
        val smoothedThenTurned = NorthFrame.rotatePoints(Smoothing.movingAverage(points, 5), origin, -40.0)
        val turnedThenSmoothed = Smoothing.movingAverage(NorthFrame.rotatePoints(points, origin, -40.0), 5)
        assertSamePoints(smoothedThenTurned, turnedThenSmoothed)
    }
}
