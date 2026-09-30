package com.stastyle.imumapper.ui.viewer

import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.PathStats
import com.stastyle.imumapper.pipeline.core.PositionSource
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.render.ClimbTotals
import com.stastyle.imumapper.render.PathProfile
import com.stastyle.imumapper.ui.common.NO_VALUE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Trip Summary's and the Graph tab's numbers, and the elevation chart's labels. */
class ViewerStatsTest {

    private fun stats(
        distanceM: Double = 43.3,
        durationS: Double = 125.0,
        minZ: Double = -0.7,
        maxZ: Double = 5.0,
        closureErrorM: Double? = null,
        vioFraction: Double = 0.0,
    ) = PathStats(distanceM, durationS, stepCount = 102, minZ, maxZ, closureErrorM, vioFraction)

    private fun point(i: Int, z: Double) =
        PathPoint(i * 1_000_000_000L, Vec3(i.toDouble(), 0.0, z), PositionSource.PDR, headingRad = 0.0)

    @Test
    fun durationSaysIncludingPausesWhenTheRunRecordsOne() {
        assertEquals(StatText("2:05"), ViewerStats.duration(stats(), emptyMap()))
        // PDR's key, VIO's key, and the PDR steps a VIO run filled gaps with.
        for (key in listOf("pauses", "pauseCount", "pdr.pauses")) {
            assertEquals(StatText("2:05", "incl. pauses"), ViewerStats.duration(stats(), mapOf(key to "2")), key)
        }
        assertFalse(recordsPauses(mapOf("pauses" to "0")))
        assertFalse(recordsPauses(mapOf("pauses" to "several")))
        assertFalse(recordsPauses(mapOf("pausedSteps" to "4")))
        assertTrue(recordsPauses(mapOf("pauseCount" to " 1 ")))
    }

    @Test
    fun verticalIsTheRangeWithTheLowestAndHighestPointUnderIt() {
        val ltr = ViewerStats.LEFT_TO_RIGHT_MARK
        assertEquals(
            StatText("5.7 m", "$ltr-0.7 … +5.0", "5.7 m, from -0.7 m to +5.0 m"),
            ViewerStats.vertical(stats(minZ = -0.7, maxZ = 5.0)),
        )
        // A flat walk never shows "-0.0".
        assertEquals("$ltr+0.0 … +0.0", ViewerStats.vertical(stats(minZ = -0.0, maxZ = 0.0)).detail)
        assertEquals(StatText(NO_VALUE), ViewerStats.vertical(stats(minZ = Double.POSITIVE_INFINITY)))
    }

    @Test
    fun theVerticalDetailStartsWithAStrongLeftToRightCharacter() {
        // Without one, a right-to-left layout would put the highest point first ("+5.0 … -0.7").
        val detail = assertNotNull(ViewerStats.vertical(stats(minZ = -0.7, maxZ = 5.0)).detail)
        assertEquals(Character.DIRECTIONALITY_LEFT_TO_RIGHT, Character.getDirectionality(detail.first()))
        assertTrue(detail.drop(1).none { Character.getDirectionality(it) == Character.DIRECTIONALITY_LEFT_TO_RIGHT })
    }

    @Test
    fun closureSplitsTheErrorFromItsShareOfTheDistance() {
        assertEquals(StatText("0.52 m", "1.2 % of distance"), ViewerStats.closure(stats(closureErrorM = 0.52)))
        // Also null for a Back at start mark with loop closure off, so it does not claim no loop was marked.
        assertEquals(StatText(NO_VALUE, "no loop closed"), ViewerStats.closure(stats(closureErrorM = null)))
        assertNull(ViewerStats.closure(stats(distanceM = 0.0, closureErrorM = 0.1)).detail)
    }

    @Test
    fun theOtherSummaryTiles() {
        assertEquals(StatText("43.3 m"), ViewerStats.distance(stats()))
        assertEquals(StatText("102"), ViewerStats.steps(stats()))
        assertEquals(StatText("46 %", "of points"), ViewerStats.vio(stats(vioFraction = 0.456)))
        assertEquals(StatText("0 %", "of points"), ViewerStats.vio(stats(vioFraction = 0.0)))
    }

    @Test
    fun graphTilesAreSignedHeightsAndDeadBandedClimbs() {
        assertEquals(StatText("-0.7 m"), ViewerStats.min(stats()))
        assertEquals(StatText("+5.0 m"), ViewerStats.max(stats()))
        assertEquals(StatText("-1.3 m"), ViewerStats.netChange(listOf(point(0, 0.2), point(1, 3.0), point(2, -1.06))))
        assertEquals(StatText("+0.0 m"), ViewerStats.netChange(listOf(point(0, 0.0))))
        assertEquals(StatText(NO_VALUE), ViewerStats.netChange(emptyList()))
        assertEquals(StatText("3.0 m", "rises ≥ 0.5 m"), ViewerStats.climb(ClimbTotals(3.04, 2.96)))
        assertEquals(StatText("3.0 m", "drops ≥ 0.5 m"), ViewerStats.descent(ClimbTotals(3.04, 2.96)))
    }

    @Test
    fun axisLabelsAreWholeWhereTheyCanBeAndNeverNegativeZero() {
        assertEquals("6 m", axisLabel(6.0))
        assertEquals("0 m", axisLabel(0.0))
        assertEquals("0 m", axisLabel(-0.0))
        assertEquals("-6 m", axisLabel(-6.0))
        assertEquals("1.5 m", axisLabel(1.5))
        assertEquals("-2.5 m", axisLabel(-2.5))
        assertEquals(NO_VALUE, axisLabel(Double.NaN))
    }

    @Test
    fun theChartNeedsTwoPointsAlongSomeLength() {
        val walk = listOf(point(0, 0.0), point(1, 0.5))
        assertTrue(showsElevation(walk.size, PathProfile.elevation(walk)))
        val single = listOf(point(0, 0.0))
        assertFalse(showsElevation(single.size, PathProfile.elevation(single)))
        val standing = listOf(point(0, 0.0), point(0, 0.0))
        assertFalse(showsElevation(standing.size, PathProfile.elevation(standing)))
    }
}
