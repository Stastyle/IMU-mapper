package com.stastyle.imumapper.calibration

import com.stastyle.imumapper.pipeline.core.HeadingAxisMode
import com.stastyle.imumapper.pipeline.core.Quat
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.ui.calibration.CalibrationMath
import kotlin.math.PI
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StillBiasMathTest {
    @Test
    fun meanAndNoise() {
        val samples = listOf(Vec3(0.01, -0.02, 0.03), Vec3(0.03, 0.0, 0.01), Vec3(0.02, -0.01, 0.02))
        val m = assertNotNull(CalibrationMath.mean(samples))
        assertEquals(0.02, m.x, 1e-12)
        assertEquals(-0.01, m.y, 1e-12)
        assertEquals(0.02, m.z, 1e-12)
        // Deviations are (-0.01,-0.01,0.01), (0.01,0.01,-0.01), (0,0,0): squared sum 6e-4 over 3 samples.
        val rms = assertNotNull(CalibrationMath.noiseRms(samples))
        assertEquals(Math.sqrt(2e-4), rms, 1e-12)
        assertNull(CalibrationMath.mean(emptyList()))
        assertNull(CalibrationMath.noiseRms(listOf(Vec3.ZERO)))
    }

    @Test
    fun magnitudeStdDevIgnoresDirection() {
        // Same magnitude in different directions: zero spread.
        val samples = listOf(Vec3(9.81, 0.0, 0.0), Vec3(0.0, 9.81, 0.0), Vec3(0.0, 0.0, -9.81))
        assertEquals(0.0, assertNotNull(CalibrationMath.magnitudeStdDev(samples)), 1e-12)
        val noisy = listOf(Vec3(0.0, 0.0, 9.0), Vec3(0.0, 0.0, 11.0))
        assertEquals(1.0, assertNotNull(CalibrationMath.magnitudeStdDev(noisy)), 1e-12)
    }
}

class StrideMathTest {
    @Test
    fun strideLength() {
        assertEquals(0.8, assertNotNull(CalibrationMath.strideLengthM(20.0, 25)), 1e-12)
        assertNull(CalibrationMath.strideLengthM(20.0, 0))
        assertNull(CalibrationMath.strideLengthM(0.0, 10))
    }

    @Test
    fun weinbergFitReproducesDistance() {
        val swings = doubleArrayOf(4.0, 6.0, 5.0, 7.0, 4.5)
        val k = assertNotNull(CalibrationMath.fitWeinbergK(10.0, swings, 0.7))
        val total = swings.sumOf { k * it.pow(0.25) }
        assertEquals(10.0, total, 1e-9)
    }

    @Test
    fun weinbergFitAccountsForFallbackSteps() {
        // Two steps without a swing use the fixed stride (0.5 m each); the rest must cover 9 m.
        val swings = doubleArrayOf(4.0, 0.0, 6.0, 0.0)
        val k = assertNotNull(CalibrationMath.fitWeinbergK(10.0, swings, 0.5))
        assertEquals(9.0, k * (4.0.pow(0.25) + 6.0.pow(0.25)), 1e-9)
        assertNull(CalibrationMath.fitWeinbergK(10.0, doubleArrayOf(0.0, 0.0), 0.7))
        assertNull(CalibrationMath.fitWeinbergK(1.0, doubleArrayOf(4.0, 0.0, 0.0), 0.7))
    }
}

class HeadingMathTest {
    @Test
    fun wrap() {
        assertEquals(0.0, CalibrationMath.wrapRad(2 * PI), 1e-12)
        assertEquals(PI, CalibrationMath.wrapRad(PI), 1e-12)
        assertEquals(PI, CalibrationMath.wrapRad(-PI), 1e-12)
        assertEquals(-PI / 2, CalibrationMath.wrapRad(3 * PI / 2), 1e-12)
    }

    @Test
    fun offsetTurnsWalkNorth() {
        // Walk ended 10 m east with offset 0: direction +90 deg, so the offset must be -90 deg.
        val offset = assertNotNull(CalibrationMath.headingOffsetFromEnd(Vec3(10.0, 0.0, 0.0), 0.0))
        assertEquals(-PI / 2, offset, 1e-12)
        // With an existing offset the correction stacks on top of it.
        val stacked = assertNotNull(CalibrationMath.headingOffsetFromEnd(Vec3(10.0, 0.0, 0.0), 0.3))
        assertEquals(0.3 - PI / 2, stacked, 1e-12)
        // A walk that already points north changes nothing.
        assertEquals(0.1, assertNotNull(CalibrationMath.headingOffsetFromEnd(Vec3(0.0, 5.0, 1.0), 0.1)), 1e-12)
        assertNull(CalibrationMath.headingOffsetFromEnd(Vec3(0.2, 0.2, 0.0), 0.0))
    }

    @Test
    fun axisComesFromTheFirstHeadingSegment() {
        fun axis(diag: String?): HeadingAxisMode =
            CalibrationMath.headingAxisFromDiagnostics(if (diag == null) emptyMap() else mapOf("headingAxis" to diag))
        assertEquals(HeadingAxisMode.CAMERA, axis("CAMERA"))
        assertEquals(HeadingAxisMode.FORWARD, axis("FORWARD;CAMERA"))
        // Missing, empty or unknown entries fall back to letting each trip choose.
        assertEquals(HeadingAxisMode.AUTO, axis(null))
        assertEquals(HeadingAxisMode.AUTO, axis(""))
        assertEquals(HeadingAxisMode.AUTO, axis("AUTO"))
        assertEquals(HeadingAxisMode.AUTO, axis("SIDEWAYS"))
    }
}

class SquareMathTest {
    @Test
    fun closure() {
        val points = listOf(
            Vec3.ZERO, Vec3(5.0, 0.0, 0.0), Vec3(5.0, 5.0, 0.0), Vec3(0.0, 5.0, 0.0), Vec3(0.3, 0.4, 0.0),
        )
        assertEquals(0.5, assertNotNull(CalibrationMath.closureErrorM(points)), 1e-12)
        assertEquals(2.5, assertNotNull(CalibrationMath.closurePercent(0.5, 20.0)), 1e-12)
        assertNull(CalibrationMath.closurePercent(0.5, 0.0))
        assertNull(CalibrationMath.closureErrorM(emptyList()))
    }

    @Test
    fun planFitKeepsNorthUpAndCentres() {
        val points = listOf(Vec3(0.0, 0.0, 0.0), Vec3(4.0, 2.0, 0.0))
        val b = assertNotNull(CalibrationMath.planBounds(points))
        assertEquals(4.0, b.width, 1e-12)
        assertEquals(2.0, b.height, 1e-12)
        val t = CalibrationMath.fitPlan(b, 200.0, 100.0, 10.0)
        // Width allows 180 px / 4 m = 45 px/m but height only 80 px / 2 m = 40 px/m; the smaller wins
        // and the centre (2, 1) lands in the middle of the canvas.
        assertEquals(40.0, t.scale, 1e-9)
        assertEquals(100f, t.x(2.0), 1e-4f)
        assertEquals(50f, t.y(1.0), 1e-4f)
        // North (larger y) is higher on the canvas, i.e. a smaller pixel y.
        assertTrue(t.y(2.0) < t.y(0.0))
        assertEquals(20f, t.x(0.0), 1e-4f)
        assertEquals(180f, t.x(4.0), 1e-4f)
        assertEquals(10f, t.y(2.0), 1e-4f)
        assertEquals(90f, t.y(0.0), 1e-4f)
    }

    @Test
    fun singlePointStillFits() {
        val b = assertNotNull(CalibrationMath.planBounds(listOf(Vec3(1.0, 1.0, 0.0))))
        val t = CalibrationMath.fitPlan(b, 100.0, 100.0, 0.0)
        assertTrue(t.scale.isFinite() && t.scale > 0.0)
        assertEquals(50f, t.x(1.0), 1e-4f)
    }
}

class LivePlotMathTest {
    @Test
    fun verticalAccelRemovesGravityForFlatPhone() {
        // Phone flat, screen up: sensor z is up, identity rotation.
        assertEquals(0.0, CalibrationMath.verticalAccel(Quat.IDENTITY, Vec3(0.0, 0.0, 9.81)), 1e-9)
        // Phone rolled 90 deg about its y axis: sensor x now points up (or down); vertical still cancels.
        val q = Quat.fromAxisAngle(Vec3.UNIT_Y, PI / 2)
        val up = q.inverse().rotate(Vec3(0.0, 0.0, 9.81))
        assertEquals(0.0, CalibrationMath.verticalAccel(q, up), 1e-9)
    }

    @Test
    fun headingDegreesClockwiseFromNorth() {
        assertEquals(0.0, CalibrationMath.headingDeg(Quat.IDENTITY), 1e-9)
        // Yaw of -90 deg (clockwise seen from above) turns the top of the phone to the east.
        assertEquals(90.0, CalibrationMath.headingDeg(Quat.yaw(-PI / 2)), 1e-9)
    }
}

class ComparisonMathTest {
    @Test
    fun comparesDistanceAndEndPoints() {
        val pdr = listOf(Vec3.ZERO, Vec3(0.0, 10.0, 0.0))
        val vio = listOf(Vec3.ZERO, Vec3(1.0, 9.0, 0.0))
        val c = CalibrationMath.compare(pdr, 10.0, vio, 9.5)
        assertEquals(0.5, c.distanceDiffM, 1e-12)
        assertEquals(0.5 / 9.5 * 100.0, assertNotNull(c.distanceDiffPct), 1e-9)
        assertEquals(Math.sqrt(2.0), c.endGapM, 1e-12)
        val dir = assertNotNull(c.endDirectionDiffDeg)
        assertEquals(-Math.toDegrees(Math.atan2(1.0, 9.0)), dir, 1e-9)
        assertNull(CalibrationMath.compare(pdr, 10.0, emptyList(), 0.0).distanceDiffPct)
        assertNull(CalibrationMath.compare(pdr, 10.0, emptyList(), 0.0).endDirectionDiffDeg)
    }
}
