package com.stastyle.imumapper.pipeline.survey

import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.PositionSource
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.survey.SurveyPaths.T0
import com.stastyle.imumapper.pipeline.survey.SurveyPaths.tNs
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PathTimelineTest {

    private val second = 1_000_000_000L

    /** A north walk (point i at y = 0.5 i) whose points after [afterIndex] come [gapNs] later. */
    private fun northWithGap(steps: Int, afterIndex: Int, gapNs: Long): List<PathPoint> =
        SurveyPaths.steps(steps to 0.0).mapIndexed { i, p -> if (i > afterIndex) p.copy(tNs = p.tNs + gapNs) else p }

    /**
     * Across the gap after point [i] the walker stands at p(i), then covers the last stride in the
     * last 1.5 x 0.5 s = 0.75 s before point i + 1: halfway 0.375 s before it.
     */
    private fun assertHoldsThenMoves(points: List<PathPoint>, i: Int) {
        val tl = PathTimeline(points)
        assertEquals(SurveyPaths.STEP_NS, tl.medianStepNs)
        val before = points[i].tNs
        val after = points[i + 1].tNs
        val here = Vec3(0.0, 0.5 * i, 0.0)
        assertEquals(here, tl.positionAt(before + second))
        assertEquals(here, tl.positionAt((before + after) / 2))
        assertEquals(here, tl.positionAt(after - 750_000_000L), "the move starts 0.75 s before the next step")
        assertEquals(Vec3(0.0, 0.5 * i + 0.25, 0.0), tl.positionAt(after - 375_000_000L))
        assertEquals(Vec3(0.0, 0.5 * (i + 1), 0.0), tl.positionAt(after))
        assertEquals(0.5 * i, tl.distanceAt(before + 10 * second))
        assertEquals(0.5 * i + 0.25, tl.distanceAt(after - 375_000_000L))
    }

    @Test
    fun standingGapHoldsAtTheEarlierStep() {
        // Standing 20 s after the fifth stride.
        assertHoldsThenMoves(northWithGap(10, afterIndex = 5, gapNs = 20 * second), 5)
    }

    @Test
    fun pauseGapHoldsTheSameWay() {
        // A 60 s pause: the recorder dropped no point, the gap is only longer.
        assertHoldsThenMoves(northWithGap(8, afterIndex = 3, gapNs = 60 * second), 3)
    }

    @Test
    fun normalStepsAreLinear() {
        val tl = PathTimeline(SurveyPaths.steps(10 to 0.0))
        // Gap 0.5 s is shorter than the 0.75 s move window, so the whole gap is the move.
        assertEquals(Vec3(0.0, 1.25, 0.0), tl.positionAt(tNs(2) + 250_000_000L))
        assertEquals(1.1, tl.distanceAt(tNs(2) + 100_000_000L), 1e-12)
        assertEquals(5.0, tl.lengthM)
        assertEquals(T0, tl.startNs)
        assertEquals(tNs(10), tl.endNs)
        assertEquals(2.5, tl.distanceOfPoint(5))
    }

    @Test
    fun vioSegmentsAreLinearOverAnyGap() {
        val tenSeconds = 10 * second
        val tl = PathTimeline(
            SurveyPaths.linear(Vec3(0.0, 0.0, 0.0), Vec3(0.0, 4.0, 0.0), Vec3(4.0, 4.0, 0.0), stepNs = tenSeconds),
        )
        assertEquals(PathTimeline.DEFAULT_STEP_NS, tl.medianStepNs, "a VIO path has no steps")
        assertEquals(Vec3(0.0, 1.0, 0.0), tl.positionAt(T0 + 2_500_000_000L))
        assertEquals(Vec3(2.0, 4.0, 0.0), tl.positionAt(T0 + 15 * second))
        assertEquals(6.0, tl.distanceAt(T0 + 15 * second))
        assertEquals(T0 + 15 * second, tl.timeAtDistance(6.0))
        val gapFill = SurveyPaths.linear(
            Vec3.ZERO, Vec3(0.0, 4.0, 0.0), stepNs = tenSeconds, source = PositionSource.INTERPOLATED,
        )
        val interpolated = PathTimeline(gapFill)
        assertEquals(Vec3(0.0, 1.0, 0.0), interpolated.positionAt(T0 + 2_500_000_000L))
    }

    @Test
    fun medianStepIgnoresTheOriginAndLongGaps() {
        assertEquals(600_000_000L, PathTimeline(SurveyPaths.steps(9 to 0.0, stepNs = 600_000_000L)).medianStepNs)
        // One step: no gap between two step points, so the default cadence sets the move window.
        val oneStep = listOf(
            PathPoint(T0, Vec3.ZERO, PositionSource.PDR, 0.0, -1),
            PathPoint(T0 + 10 * second, Vec3(0.0, 0.5, 0.0), PositionSource.PDR, 0.0, 0),
        )
        val tl = PathTimeline(oneStep)
        assertEquals(PathTimeline.DEFAULT_STEP_NS, tl.medianStepNs)
        // Window 1.5 x 0.55 s = 0.825 s; halfway is 0.4125 s before the step.
        assertEquals(Vec3.ZERO, tl.positionAt(T0 + 10 * second - 825_000_000L))
        assertEquals(Vec3(0.0, 0.25, 0.0), tl.positionAt(T0 + 10 * second - 412_500_000L))
    }

    @Test
    fun timesOutsideThePathAreClamped() {
        val tl = PathTimeline(SurveyPaths.steps(10 to 0.0))
        assertEquals(Vec3.ZERO, tl.positionAt(T0 - second))
        assertEquals(Vec3(0.0, 5.0, 0.0), tl.positionAt(tNs(10) + 5 * second))
        assertEquals(0.0, tl.distanceAt(T0 - second))
        assertEquals(5.0, tl.distanceAt(tNs(10) + 5 * second))
        assertEquals(Vec3.ZERO, tl.positionAtDistance(-1.0))
        assertEquals(Vec3(0.0, 5.0, 0.0), tl.positionAtDistance(8.0))
        assertEquals(T0, tl.timeAtDistance(-1.0))
        assertEquals(tNs(10), tl.timeAtDistance(8.0))
    }

    @Test
    fun timeAtDistanceInvertsDistanceAt() {
        val points = northWithGap(10, afterIndex = 5, gapNs = 20 * second)
        val tl = PathTimeline(points)
        for (t in listOf(tNs(1) + 123_456_789L, tNs(3) + 100_000_000L, points[6].tNs - 375_000_000L, points[9].tNs)) {
            val back = tl.timeAtDistance(tl.distanceAt(t))
            assertTrue(abs(back - t) <= 1, "time $t came back as $back")
        }
        // Standing still takes no distance: a moment during the stand maps to its first moment.
        assertEquals(points[5].tNs, tl.timeAtDistance(tl.distanceAt(points[5].tNs + 10 * second)))
        assertEquals(points[6].tNs - 375_000_000L, tl.timeAtDistance(2.75))
        assertEquals(T0, tl.timeAtDistance(0.0))
    }

    @Test
    fun positionAtDistanceIsLinearByDistance() {
        val tl = PathTimeline(SurveyPaths.linear(Vec3.ZERO, Vec3(0.0, 4.0, 0.0), Vec3(4.0, 4.0, 2.0)))
        assertEquals(Vec3(0.0, 1.0, 0.0), tl.positionAtDistance(1.0))
        assertEquals(Vec3(0.0, 4.0, 0.0), tl.positionAtDistance(4.0))
        // The second segment is sqrt(20) long; halfway along it is (2, 4, 1).
        assertNear(Vec3(2.0, 4.0, 1.0), tl.positionAtDistance(4.0 + (tl.lengthM - 4.0) / 2), 1e-12)
    }

    @Test
    fun samplesBetweenInEitherOrder() {
        val tl = PathTimeline(SurveyPaths.steps(10 to 0.0))
        val a = tNs(2) + 250_000_000L
        val b = tNs(5)
        val expected = listOf(Vec3(0.0, 1.25, 0.0), Vec3(0.0, 1.5, 0.0), Vec3(0.0, 2.0, 0.0), Vec3(0.0, 2.5, 0.0))
        assertEquals(expected, tl.samplesBetween(a, b))
        assertEquals(expected, tl.samplesBetween(b, a))
        assertEquals(listOf(Vec3(0.0, 1.5, 0.0), Vec3(0.0, 1.5, 0.0)), tl.samplesBetween(tNs(3), tNs(3)))
    }

    @Test
    fun previousAndNextPoint() {
        val tl = PathTimeline(SurveyPaths.steps(10 to 0.0))
        assertEquals(tNs(2), tl.previousPointNs(tNs(3)))
        assertEquals(tNs(3), tl.previousPointNs(tNs(3) + 1))
        assertEquals(T0, tl.previousPointNs(T0))
        assertEquals(T0, tl.previousPointNs(T0 - second))
        assertEquals(tNs(4), tl.nextPointNs(tNs(3)))
        assertEquals(tNs(3), tl.nextPointNs(tNs(3) - 1))
        assertEquals(tNs(10), tl.nextPointNs(tNs(10)))
        assertEquals(T0, tl.nextPointNs(T0 - second))
    }

    @Test
    fun onePointPath() {
        val p = Vec3(1.0, 2.0, 3.0)
        val tl = PathTimeline(listOf(PathPoint(T0, p, PositionSource.PDR, 0.0, -1)))
        assertEquals(0.0, tl.lengthM)
        assertEquals(T0, tl.startNs)
        assertEquals(T0, tl.endNs)
        assertEquals(p, tl.positionAt(T0 + second))
        assertEquals(p, tl.positionAt(T0 - second))
        assertEquals(0.0, tl.distanceAt(T0 + second))
        assertEquals(p, tl.positionAtDistance(3.0))
        assertEquals(T0, tl.timeAtDistance(3.0))
        assertEquals(listOf(p, p), tl.samplesBetween(T0, T0 + second))
        // The point itself lies strictly between an earlier and a later moment.
        assertEquals(listOf(p, p, p), tl.samplesBetween(T0 - second, T0 + second))
        assertEquals(T0, tl.previousPointNs(T0 + second))
        assertEquals(T0, tl.nextPointNs(T0 - second))
        assertEquals(PathTimeline.DEFAULT_STEP_NS, tl.medianStepNs)
    }

    @Test
    fun emptyPathIsRefused() {
        assertFailsWith<IllegalArgumentException> { PathTimeline(emptyList()) }
    }
}
