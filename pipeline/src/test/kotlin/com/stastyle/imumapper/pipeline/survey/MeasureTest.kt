package com.stastyle.imumapper.pipeline.survey

import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.survey.SurveyPaths.T0
import com.stastyle.imumapper.pipeline.survey.SurveyPaths.tNs
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeasureTest {

    /** A 10 m square walked N, E, S, W with exact corners, one corner every 0.5 s. */
    private val square = PathTimeline(
        SurveyPaths.linear(
            Vec3(0.0, 0.0, 0.0), Vec3(0.0, 10.0, 0.0), Vec3(10.0, 10.0, 0.0), Vec3(10.0, 0.0, 0.0), Vec3(0.0, 0.0, 0.0),
        ),
    )

    /** One leg 6 m east, 8 m north, 2 m down: horizontal 10 m. */
    private val ramp = PathTimeline(SurveyPaths.linear(Vec3.ZERO, Vec3(6.0, 8.0, -2.0)))

    @Test
    fun cardinalLegsReadZeroNinetyOneEightyTwoSeventy() {
        val expected = listOf(0.0, 90.0, 180.0, 270.0)
        for (i in 0 until 4) {
            val leg = Measure.leg(square, tNs(i), tNs(i + 1))
            assertEquals(expected[i], assertNotNull(leg.azimuthDeg), 1e-9, "leg $i")
            assertEquals(10.0, leg.lengthM)
            assertEquals(10.0, leg.horizontalM)
            assertEquals(0.0, leg.heightChangeM)
            assertEquals(0.0, leg.slopeDeg)
            assertEquals(0.0, leg.gradePct)
            assertEquals(10.0, leg.pathM)
            assertFalse(leg.curved)
            assertEquals(1.0, leg.straightness)
            assertEquals(tNs(i), leg.fromNs)
            assertEquals(tNs(i + 1), leg.toNs)
        }
    }

    @Test
    fun verticalLegHasNoAzimuthOrGrade() {
        val shaft = PathTimeline(SurveyPaths.linear(Vec3.ZERO, Vec3(0.0, 0.0, 3.0), Vec3.ZERO))
        val up = Measure.leg(shaft, T0, tNs(1))
        assertNull(up.azimuthDeg)
        assertNull(up.gradePct)
        assertEquals(90.0, up.slopeDeg, 1e-9)
        assertEquals(3.0, up.lengthM)
        assertEquals(0.0, up.horizontalM)
        assertEquals(3.0, up.heightChangeM)
        assertTrue(up.slopeUncertain)
        val down = Measure.leg(shaft, tNs(1), tNs(2))
        assertEquals(-90.0, down.slopeDeg, 1e-9)
        assertEquals(-3.0, down.heightChangeM)
    }

    @Test
    fun rampGivesSlopeAndGrade() {
        val leg = Measure.leg(ramp, T0, tNs(1))
        // tan(azimuth) = dE / dN = 6 / 8; tan(slope) = dz / H = -2 / 10.
        assertEquals(Math.toDegrees(atan(0.75)), assertNotNull(leg.azimuthDeg), 1e-9)
        assertEquals(-Math.toDegrees(atan(0.2)), leg.slopeDeg, 1e-9)
        assertEquals(-20.0, assertNotNull(leg.gradePct), 1e-9)
        assertEquals(10.0, leg.horizontalM, 1e-12)
        assertEquals(sqrt(104.0), leg.lengthM, 1e-12)
        assertEquals(-2.0, leg.heightChangeM)
        assertFalse(leg.slopeUncertain)
    }

    @Test
    fun laterFirstMomentGivesTheReverseLeg() {
        val leg = Measure.leg(ramp, tNs(1), T0)
        assertEquals(180.0 + Math.toDegrees(atan(0.75)), assertNotNull(leg.azimuthDeg), 1e-9)
        assertEquals(2.0, leg.heightChangeM)
        assertEquals(20.0, assertNotNull(leg.gradePct), 1e-9)
        assertEquals(sqrt(104.0), leg.pathM, 1e-12)
        assertEquals(Vec3(6.0, 8.0, -2.0), leg.a)
        assertEquals(Vec3.ZERO, leg.b)
    }

    @Test
    fun doglegPathIsLongerThanItsChord() {
        // 4 m north, then 3 m east: chord 5 m, path 7 m, corner 2.4 m off the chord.
        val tl = PathTimeline(SurveyPaths.steps(8 to 0.0, 6 to PI / 2))
        val leg = Measure.leg(tl, T0, tNs(14))
        assertEquals(5.0, leg.lengthM, 1e-9)
        assertEquals(7.0, leg.pathM, 1e-9)
        assertEquals(Math.toDegrees(atan(0.75)), assertNotNull(leg.azimuthDeg), 1e-9)
        assertTrue(leg.curved)
        assertEquals(5.0 / 7.0, assertNotNull(leg.straightness), 1e-9)
        assertEquals(2.4, Measure.maxDeviationM(tl.samplesBetween(T0, tNs(14)), leg.a, leg.b), 1e-9)
    }

    @Test
    fun curvedNeedsThirtyCentimetresOnAShortLeg() {
        // 10 m leg: the limit is max(0.3, 0.2) = 0.3 m.
        val within = PathTimeline(SurveyPaths.linear(Vec3.ZERO, Vec3(0.25, 5.0, 0.0), Vec3(0.0, 10.0, 0.0)))
        assertFalse(Measure.leg(within, T0, tNs(2)).curved)
        val past = PathTimeline(SurveyPaths.linear(Vec3.ZERO, Vec3(0.375, 5.0, 0.0), Vec3(0.0, 10.0, 0.0)))
        assertTrue(Measure.leg(past, T0, tNs(2)).curved)
    }

    @Test
    fun curvedNeedsTwoPercentOnALongLeg() {
        // 40 m leg: the limit is max(0.3, 0.8) = 0.8 m.
        val within = PathTimeline(SurveyPaths.linear(Vec3.ZERO, Vec3(0.75, 20.0, 0.0), Vec3(0.0, 40.0, 0.0)))
        assertFalse(Measure.leg(within, T0, tNs(2)).curved)
        val past = PathTimeline(SurveyPaths.linear(Vec3.ZERO, Vec3(0.875, 20.0, 0.0), Vec3(0.0, 40.0, 0.0)))
        assertTrue(Measure.leg(past, T0, tNs(2)).curved)
    }

    @Test
    fun sameMomentMeasuresNothing() {
        val leg = Measure.leg(square, tNs(1), tNs(1))
        assertEquals(0.0, leg.lengthM)
        assertEquals(0.0, leg.pathM)
        assertNull(leg.azimuthDeg)
        assertNull(leg.gradePct)
        assertNull(leg.straightness)
        assertEquals(0.0, leg.slopeDeg)
        assertFalse(leg.curved)
        assertTrue(leg.slopeUncertain)
    }

    @Test
    fun slopeIsUncertainUnderFiveMetresHorizontal() {
        val tl = PathTimeline(SurveyPaths.linear(Vec3.ZERO, Vec3(0.0, 4.5, 0.5), Vec3(0.0, 9.5, 1.0)))
        assertTrue(Measure.leg(tl, T0, tNs(1)).slopeUncertain)
        assertFalse(Measure.leg(tl, T0, tNs(2)).slopeUncertain)
    }

    @Test
    fun maxDeviationToCoincidentEndsIsTheDistanceToThem() {
        val a = Vec3(1.0, 1.0, 0.0)
        assertEquals(5.0, Measure.maxDeviationM(listOf(a, Vec3(4.0, 5.0, 0.0)), a, a))
        assertEquals(0.0, Measure.maxDeviationM(emptyList(), a, Vec3(2.0, 2.0, 0.0)))
    }

    /**
     * 30 m at 30 degrees with 0.5 m of sway to either side. sin(1.3 k) peaks at step 6 (point 7, 0.9985) and
     * troughs at step 52 (point 53, -0.9984): about 1 m across over 23 m along, so that chord reads about 27.5.
     */
    private val swaying = PathTimeline(SurveyPaths.steps(60 to Math.toRadians(30.0), swayM = 0.5))

    @Test
    fun fitFollowsThePassageWhereTheChordFollowsTheSway() {
        val peakToTrough = Measure.stretch(swaying, tNs(7), tNs(53))
        val chord = assertNotNull(peakToTrough.leg.azimuthDeg)
        val fitted = assertNotNull(peakToTrough.fittedAzimuthDeg)
        assertTrue(abs(chord - 30.0) > 1.0, "chord $chord")
        assertTrue(abs(fitted - 30.0) < 0.5, "fitted $fitted")
        val whole = assertNotNull(Measure.fittedAzimuthDeg(swaying, T0, tNs(60)))
        assertTrue(abs(whole - 30.0) < 0.5, "whole walk fitted $whole")
    }

    @Test
    fun straightWalkFitsItsChord() {
        val tl = PathTimeline(SurveyPaths.steps(20 to Math.toRadians(30.0)))
        assertEquals(30.0, assertNotNull(Measure.fittedAzimuthDeg(tl, T0, tNs(20))), 1e-9)
        assertEquals(30.0, assertNotNull(Measure.leg(tl, T0, tNs(20)).azimuthDeg), 1e-9)
    }

    @Test
    fun fitIsOrientedFromAToB() {
        val forward = assertNotNull(Measure.fittedAzimuthDeg(swaying, tNs(7), tNs(53)))
        val backward = assertNotNull(Measure.fittedAzimuthDeg(swaying, tNs(53), tNs(7)))
        assertEquals(0.0, SurveyAngles.wrapDeg(backward - forward - 180.0), 1e-9)
        // A walk to the south-west fits to the south-west, not to its opposite.
        val southWest = PathTimeline(SurveyPaths.steps(20 to Math.toRadians(-135.0), swayM = 0.2))
        assertTrue(abs(assertNotNull(Measure.fittedAzimuthDeg(southWest, T0, tNs(20))) - 225.0) < 1.0)
    }

    @Test
    fun shortOrClosedStretchHasNoFit() {
        val outAndBack = PathTimeline(SurveyPaths.steps(10 to 0.0, 10 to PI))
        val closed = Measure.stretch(outAndBack, T0, tNs(20))
        assertNull(closed.leg.azimuthDeg)
        assertNull(closed.fittedAzimuthDeg)
        // Half a stride: 0.25 m of chord.
        assertNull(Measure.fittedAzimuthDeg(outAndBack, T0, T0 + 250_000_000L))
    }

    @Test
    fun stretchIsTheLegPlusTheFit() {
        val s = Measure.stretch(swaying, tNs(3), tNs(30))
        assertEquals(Measure.leg(swaying, tNs(3), tNs(30)), s.leg)
        assertEquals(Measure.fittedAzimuthDeg(swaying, tNs(3), tNs(30)), s.fittedAzimuthDeg)
    }
}
