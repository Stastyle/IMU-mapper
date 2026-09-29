package com.stastyle.imumapper.pipeline.survey

import com.stastyle.imumapper.pipeline.survey.SurveyPaths.T0
import com.stastyle.imumapper.pipeline.survey.SurveyPaths.tNs
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraverseTest {

    /** 10 m north then 5 m east, climbing 0.25 m per step: 7.5 m up in all. */
    private val climbingL = PathTimeline(SurveyPaths.steps(20 to 0.0, 10 to PI / 2, climbPerStepM = 0.25))

    private val start = Station(1, StationKind.START, "Start", T0)
    private val junction = Station(2, StationKind.MARK, "Junction 1", tNs(10))
    private val end = Station(3, StationKind.END, "End", tNs(30))
    private val corner = Station(4, StationKind.CORNER, "C1", tNs(20))

    @Test
    fun legsFollowTimeOrderNotListOrder() {
        val legs = Traverse.legs(listOf(end, start, corner, junction), climbingL)
        assertEquals(listOf(start to junction, junction to corner, corner to end), legs.map { it.from to it.to })
        val azimuths = legs.map { assertNotNull(it.measure.azimuthDeg) }
        assertEquals(0.0, azimuths[0], 1e-9)
        assertEquals(0.0, azimuths[1], 1e-9)
        assertEquals(90.0, azimuths[2], 1e-9)
        assertEquals(Measure.leg(climbingL, tNs(10), tNs(20)), legs[1].measure)
    }

    @Test
    fun totalsSumTheLegs() {
        val legs = Traverse.legs(listOf(start, junction, corner, end), climbingL)
        val totals = Traverse.totals(legs)
        // Each leg is straight: 5 m across and 2.5 m up, so chord and path agree.
        val leg = sqrt(5.0 * 5.0 + 2.5 * 2.5)
        assertEquals(3 * leg, totals.lengthM, 1e-9)
        assertEquals(15.0, totals.horizontalM, 1e-9)
        assertEquals(7.5, totals.heightChangeM, 1e-9)
        assertEquals(climbingL.lengthM, totals.pathM, 1e-9)
        assertEquals(LegTotals(0.0, 0.0, 0.0, 0.0), Traverse.totals(emptyList()))
    }

    @Test
    fun chainOnAnOutAndBack() {
        // 10 m out to the north and 10 m back.
        val outAndBack = PathTimeline(SurveyPaths.steps(20 to 0.0, 20 to PI))
        val partWay = Traverse.chain(listOf(T0, tNs(20), tNs(30)), outAndBack)
        assertEquals(2, partWay.hops.size)
        assertEquals(15.0, partWay.hopLengthSumM, 1e-9)
        assertEquals(15.0, partWay.hopPathSumM, 1e-9)
        assertEquals(5.0, partWay.straight.lengthM, 1e-9)
        assertTrue(partWay.hopLengthSumM > partWay.straight.lengthM)
        val home = Traverse.chain(listOf(T0, tNs(20), tNs(40)), outAndBack)
        assertEquals(outAndBack.lengthM, home.hopPathSumM, 1e-9)
        assertEquals(20.0, home.hopLengthSumM, 1e-9)
        assertTrue(home.straight.lengthM < 1e-9)
        assertNull(home.straight.azimuthDeg)
        assertEquals(Measure.leg(outAndBack, T0, tNs(20)), home.hops[0])
    }

    @Test
    fun fewerThanTwoStationsHaveNoLegs() {
        assertTrue(Traverse.legs(emptyList(), climbingL).isEmpty())
        assertTrue(Traverse.legs(listOf(start), climbingL).isEmpty())
        assertFailsWith<IllegalArgumentException> { Traverse.chain(listOf(T0), climbingL) }
    }
}
