package com.stastyle.imumapper.pipeline.survey

import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.survey.SurveyPaths.T0
import com.stastyle.imumapper.pipeline.survey.SurveyPaths.tNs
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CornerDetectorTest {

    private val deg40 = Math.toRadians(40.0)

    /** Plan distance, since corners are found on the plan. */
    private fun planDistance(a: Vec3, b: Vec3) = hypot(a.x - b.x, a.y - b.y)

    @Test
    fun swayingRectangleHasThreeCornersAtEveryDetail() {
        // 10 m N, 6 m E, 10 m S, 6 m W, swaying 0.15 m: corners at (0, 10), (6, 10) and (6, 0).
        val points = SurveyPaths.steps(20 to 0.0, 12 to PI / 2, 20 to PI, 12 to -PI / 2, swayM = 0.15)
        val tl = PathTimeline(points)
        val trueCorners = listOf(Vec3(0.0, 10.0, 0.0), Vec3(6.0, 10.0, 0.0), Vec3(6.0, 0.0, 0.0))
        for (detail in Detail.entries) {
            val times = CornerDetector.corners(tl, detail, listOf(tl.startNs, tl.endNs))
            assertEquals(3, times.size, "$detail: ${times.map { tl.positionAt(it) }}")
            for (k in 0 until 3) {
                val at = tl.positionAt(times[k])
                assertTrue(planDistance(trueCorners[k], at) < 0.6, "$detail corner $k at $at")
            }
            assertTrue(times.all { t -> points.any { it.tNs == t } }, "corners are moments the walker stood at a point")
        }
    }

    @Test
    fun shallowDoglegNeedsNormalDetail() {
        // 10 m N, 3 m at 40 degrees, 10 m N: the jog's two bends lie 0.86 m off the start-to-end chord.
        val tl = PathTimeline(SurveyPaths.steps(20 to 0.0, 6 to deg40, 20 to 0.0))
        val kept = listOf(tl.startNs, tl.endNs)
        assertEquals(emptyList(), CornerDetector.corners(tl, Detail.COARSE, kept))
        assertEquals(listOf(tNs(20), tNs(26)), CornerDetector.corners(tl, Detail.NORMAL, kept))
        assertEquals(listOf(tNs(20), tNs(26)), CornerDetector.corners(tl, Detail.FINE, kept))
    }

    @Test
    fun gentleBendIsNotACorner() {
        // 10 m N, then 10 m at 10 degrees: the bend is 0.87 m off the chord, so RDP keeps it, but it turns only 10.
        val points = SurveyPaths.steps(20 to 0.0, 20 to Math.toRadians(10.0))
        val vertices = CornerDetector.simplify(points, Detail.NORMAL.toleranceM)
        assertContentEquals(intArrayOf(0, 20, 40), vertices)
        assertEquals(emptyList(), CornerDetector.turning(points, vertices))
        val loose = CornerDetector.turning(points, vertices, minTurnDeg = 5.0).single()
        assertEquals(20, loose.pointIndex)
        assertEquals(tNs(20), loose.tNs)
        assertEquals(10.0, loose.turnDeg, 1e-9)
        val tl = PathTimeline(points)
        assertEquals(emptyList(), CornerDetector.corners(tl, Detail.NORMAL, listOf(tl.startNs, tl.endNs)))
    }

    @Test
    fun cornersOneMetreApartKeepOne() {
        // A hairpin: 10 m N, 1 m E, 10 m S. Both bends turn exactly 90 degrees, so the earlier one wins.
        val hairpin = PathTimeline(
            SurveyPaths.linear(Vec3.ZERO, Vec3(0.0, 10.0, 0.0), Vec3(1.0, 10.0, 0.0), Vec3(1.0, 0.0, 0.0)),
        )
        assertEquals(listOf(tNs(1)), CornerDetector.corners(hairpin, Detail.NORMAL, listOf(T0, tNs(3))))
        // Coming back to the south-west makes the second bend the stronger (about 101 degrees): it wins.
        val skewed = PathTimeline(
            SurveyPaths.linear(Vec3.ZERO, Vec3(0.0, 10.0, 0.0), Vec3(1.0, 10.0, 0.0), Vec3(-1.0, 0.0, 0.0)),
        )
        assertEquals(listOf(tNs(2)), CornerDetector.corners(skewed, Detail.NORMAL, listOf(T0, tNs(3))))
    }

    @Test
    fun cornerNearAKeptStationIsDropped() {
        // L walk: the corner is point 20, 10 m along. A station at point 18 (1 m before) hides it; one at
        // point 16 (2 m before) does not.
        val tl = PathTimeline(SurveyPaths.steps(20 to 0.0, 10 to PI / 2))
        val ends = listOf(tl.startNs, tl.endNs)
        assertEquals(listOf(tNs(20)), CornerDetector.corners(tl, Detail.NORMAL, ends))
        assertEquals(emptyList(), CornerDetector.corners(tl, Detail.NORMAL, ends + tNs(18)))
        assertEquals(listOf(tNs(20)), CornerDetector.corners(tl, Detail.NORMAL, ends + tNs(16)))
    }

    @Test
    fun simplifyKeepsTheEndsAndSortedIndices() {
        val points = SurveyPaths.steps(20 to 0.0, 10 to PI / 2)
        assertContentEquals(intArrayOf(0, 20, 30), CornerDetector.simplify(points, 0.5))
        assertContentEquals(intArrayOf(0, 1), CornerDetector.simplify(points.take(2), 0.5))
        assertContentEquals(intArrayOf(0), CornerDetector.simplify(points.take(1), 0.5))
        // An out-and-back ends where it started; the far end still counts.
        val outAndBack = SurveyPaths.steps(10 to 0.0, 10 to PI)
        assertContentEquals(intArrayOf(0, 10, 20), CornerDetector.simplify(outAndBack, 0.5))
        val reversal = CornerDetector.turning(outAndBack, intArrayOf(0, 10, 20)).single()
        assertEquals(180.0, reversal.turnDeg, 1e-9)
    }

    @Test
    fun vertexNextToAStandStillTurnsByZero() {
        // Points 1 and 2 coincide (the walker stood), so each touches a segment with no direction.
        val points = SurveyPaths.linear(Vec3.ZERO, Vec3(0.0, 5.0, 0.0), Vec3(0.0, 5.0, 0.0), Vec3(5.0, 5.0, 0.0))
        val all = CornerDetector.turning(points, intArrayOf(0, 1, 2, 3), minTurnDeg = 0.0)
        assertEquals(listOf(0.0, 0.0), all.map { it.turnDeg })
        // Removing the weaker (the earlier, on a tie) re-checks its neighbour, which now turns 90 degrees.
        val corner = CornerDetector.turning(points, intArrayOf(0, 1, 2, 3)).single()
        assertEquals(2, corner.pointIndex)
        assertEquals(90.0, corner.turnDeg, 1e-9)
    }
}
