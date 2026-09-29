package com.stastyle.imumapper.pipeline.survey

import kotlin.test.Test
import kotlin.test.assertEquals

class SurveyAnglesTest {

    /** assertEquals on boxed doubles tells 0.0 from -0.0, but say it outright. */
    private fun assertPositiveZero(actual: Double, what: String) =
        assertEquals(0L, actual.toRawBits(), "$what should be +0.0, got $actual")

    @Test
    fun cardinalDirections() {
        assertEquals(0.0, SurveyAngles.azimuthDeg(0.0, 1.0), 1e-9)
        assertEquals(90.0, SurveyAngles.azimuthDeg(1.0, 0.0), 1e-9)
        assertEquals(180.0, SurveyAngles.azimuthDeg(0.0, -1.0), 1e-9)
        // atan2(-0.0, -1) is -180 degrees: south must still read 180, not -180.
        assertEquals(180.0, SurveyAngles.azimuthDeg(-0.0, -1.0), 1e-9)
        assertEquals(270.0, SurveyAngles.azimuthDeg(-1.0, 0.0), 1e-9)
        assertEquals(45.0, SurveyAngles.azimuthDeg(2.0, 2.0), 1e-9)
        assertEquals(315.0, SurveyAngles.azimuthDeg(-2.0, 2.0), 1e-9)
        assertPositiveZero(SurveyAngles.azimuthDeg(0.0, 1.0), "north")
    }

    @Test
    fun to360Edges() {
        assertPositiveZero(SurveyAngles.to360(360.0), "360")
        assertPositiveZero(SurveyAngles.to360(-0.0), "-0.0")
        // -1e-15 + 360 rounds to 360.0 itself: it must come back as north.
        assertPositiveZero(SurveyAngles.to360(-1e-15), "tiny negative")
        assertEquals(180.0, SurveyAngles.to360(540.0))
        assertEquals(270.0, SurveyAngles.to360(-90.0))
        assertEquals(359.5, SurveyAngles.to360(-0.5))
        assertEquals(12.25, SurveyAngles.to360(12.25))
    }

    @Test
    fun wrapDegEdges() {
        assertEquals(180.0, SurveyAngles.wrapDeg(180.0))
        assertEquals(180.0, SurveyAngles.wrapDeg(-180.0))
        assertEquals(-170.0, SurveyAngles.wrapDeg(190.0))
        assertEquals(170.0, SurveyAngles.wrapDeg(-190.0))
        assertEquals(180.0, SurveyAngles.wrapDeg(540.0))
        assertEquals(-90.0, SurveyAngles.wrapDeg(270.0))
        assertEquals(4.5, SurveyAngles.wrapDeg(364.5))
        assertPositiveZero(SurveyAngles.wrapDeg(-0.0), "-0.0")
        assertPositiveZero(SurveyAngles.wrapDeg(-360.0), "-360")
    }
}
