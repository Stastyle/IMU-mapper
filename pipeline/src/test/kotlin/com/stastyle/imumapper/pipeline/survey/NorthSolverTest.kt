package com.stastyle.imumapper.pipeline.survey

import com.stastyle.imumapper.pipeline.pdr.OrientationEstimator
import com.stastyle.imumapper.pipeline.survey.SurveyPaths.T0
import com.stastyle.imumapper.pipeline.survey.SurveyPaths.tNs
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NorthSolverTest {

    /** 10 m due north (points 0-20), then 5 m due east (points 20-30). */
    private val lPath = SurveyPaths.steps(20 to 0.0, 10 to PI / 2)
    private val plain = PathTimeline(lPath)

    private fun northLeg(bearing: Double, id: Int = 1, back: Boolean = false) =
        CompassReference(id, T0, tNs(20), bearing, backBearing = back)

    private fun eastLeg(bearing: Double, id: Int = 2) = CompassReference(id, tNs(20), tNs(30), bearing)

    private fun sinD(deg: Double) = sin(Math.toRadians(deg))
    private fun cosD(deg: Double) = cos(Math.toRadians(deg))

    @Test
    fun oneReferenceIsMatchedExactly() {
        val doc = SurveyDoc(references = listOf(northLeg(45.0)))
        val north = NorthSolver.solve(doc, plain, runId = 1)
        assertEquals(NorthSource.REFERENCES, north.source)
        assertEquals(45.0, north.rotationDeg, 1e-9)
        val fit = north.fits.single()
        assertEquals(1, fit.referenceId)
        assertEquals(0.0, assertNotNull(fit.measuredDeg), 1e-9)
        assertEquals(10.0, fit.horizontalM, 1e-9)
        assertEquals(0.0, assertNotNull(fit.residualDeg), 1e-9)
        assertEquals(1, north.usedCount)
        assertFalse(north.disagree)
        // Turned by the solution, the leg reads what the compass read.
        val turned = PathTimeline(NorthFrame.rotate(SurveyPaths.result(lPath), north.rotationDeg).points)
        assertEquals(45.0, assertNotNull(Measure.leg(turned, T0, tNs(20)).azimuthDeg), 1e-9)
    }

    @Test
    fun twoReferencesAreWeightedByHorizontalLength() {
        // The 10 m north leg reads 4 degrees, the 5 m east leg 92: differences 4 and 2.
        val doc = SurveyDoc(references = listOf(northLeg(4.0), eastLeg(92.0)))
        val north = NorthSolver.solve(doc, plain, runId = 1)
        val expected = Math.toDegrees(atan2(10 * sinD(4.0) + 5 * sinD(2.0), 10 * cosD(4.0) + 5 * cosD(2.0)))
        assertEquals(expected, north.rotationDeg, 1e-9)
        val r1 = assertNotNull(north.fits[0].residualDeg)
        val r2 = assertNotNull(north.fits[1].residualDeg)
        assertEquals(4.0 - expected, r1, 1e-9)
        assertEquals(2.0 - expected, r2, 1e-9)
        // The weighted residuals balance: that is what makes the rotation their best fit.
        assertEquals(0.0, 10 * sinD(r1) + 5 * sinD(r2), 1e-9)
        assertEquals(2, north.usedCount)
        assertFalse(north.disagree, "residuals ${r1} and ${r2} are within 3 degrees")
    }

    @Test
    fun referencesDisagreePastThreeDegrees() {
        // Differences 4 and 10: the rotation lands near 6, leaving about -2 and +4.
        val north = NorthSolver.solve(SurveyDoc(references = listOf(northLeg(4.0), eastLeg(100.0))), plain, 1)
        assertTrue(abs(assertNotNull(north.fits[1].residualDeg)) > NorthSolver.DISAGREE_DEG)
        assertTrue(north.disagree)
    }

    @Test
    fun backBearingAddsHalfATurn() {
        assertEquals(20.0, NorthSolver.forwardBearingDeg(CompassReference(bearingDeg = 200.0, backBearing = true)))
        assertEquals(170.0, NorthSolver.forwardBearingDeg(CompassReference(bearingDeg = 350.0, backBearing = true)))
        assertEquals(90.0, NorthSolver.forwardBearingDeg(CompassReference(bearingDeg = 90.0)))
        val north = NorthSolver.solve(SurveyDoc(references = listOf(northLeg(224.0, back = true))), plain, 1)
        assertEquals(44.0, north.rotationDeg, 1e-9)
        assertEquals(0.0, assertNotNull(north.fits.single().residualDeg), 1e-9)
    }

    @Test
    fun fittedReferenceUsesTheFittedLine() {
        // Sway peak to sway trough: the chord and the fitted line differ by more than 2 degrees.
        val swaying = PathTimeline(SurveyPaths.steps(60 to Math.toRadians(30.0), swayM = 0.5))
        val chordRef = CompassReference(1, tNs(7), tNs(53), 35.0, line = ReferenceLine.CHORD)
        val fittedRef = chordRef.copy(line = ReferenceLine.FITTED)
        val chord = assertNotNull(NorthSolver.measuredDeg(chordRef, swaying))
        val fitted = assertNotNull(NorthSolver.measuredDeg(fittedRef, swaying))
        assertEquals(Measure.leg(swaying, tNs(7), tNs(53)).azimuthDeg, chord)
        assertEquals(Measure.fittedAzimuthDeg(swaying, tNs(7), tNs(53)), fitted)
        assertTrue(abs(fitted - chord) > 2.0, "fitted $fitted, chord $chord")
        val north = NorthSolver.solve(SurveyDoc(references = listOf(fittedRef)), swaying, 1)
        assertEquals(35.0 - fitted, north.rotationDeg, 1e-9)
        assertEquals(fitted, north.fits.single().measuredDeg)
    }

    @Test
    fun referenceShorterThanThirtyCentimetresIsSkipped() {
        // Half a stride along the north leg: 0.25 m.
        val short = CompassReference(3, tNs(3), tNs(3) + 250_000_000L, 170.0)
        val alone = NorthSolver.solve(SurveyDoc(references = listOf(short)), plain, 1)
        assertEquals(NorthSource.NONE, alone.source)
        assertEquals(0.0, alone.rotationDeg)
        val fit = alone.fits.single()
        assertNull(fit.measuredDeg)
        assertNull(fit.residualDeg)
        assertEquals(0.25, fit.horizontalM, 1e-12)
        assertEquals(0, alone.usedCount)
        val mixed = NorthSolver.solve(SurveyDoc(references = listOf(short, northLeg(6.0))), plain, 1)
        assertEquals(6.0, mixed.rotationDeg, 1e-9)
        assertEquals(1, mixed.usedCount)
        assertNull(mixed.fits[0].residualDeg)
        // A reference exists, so the manual rotation stays unused even when none can be measured here:
        // the steppers are disabled while references exist, and a hidden angle must not steer the map.
        val shadowed = NorthSolver.solve(SurveyDoc(references = listOf(short), manualRotationDeg = 5.0), plain, 1)
        assertEquals(NorthSource.NONE, shadowed.source)
        assertEquals(0.0, shadowed.rotationDeg)
        assertEquals(1, shadowed.fits.size)
    }

    @Test
    fun manualRotationOnlyWithoutReferencesAndOnItsRun() {
        val manual = SurveyDoc(manualRotationDeg = -7.5, manualRotationRunId = 3)
        val own = NorthSolver.solve(manual, plain, runId = 3)
        assertEquals(NorthSource.MANUAL, own.source)
        assertEquals(-7.5, own.rotationDeg)
        assertTrue(own.fits.isEmpty())
        val other = NorthSolver.solve(manual, plain, runId = 4)
        assertEquals(NorthSource.NONE, other.source)
        assertEquals(0.0, other.rotationDeg)
        val untagged = NorthSolver.solve(manual.copy(manualRotationRunId = null), plain, runId = 4)
        assertEquals(NorthSource.MANUAL, untagged.source)
        val withReference = NorthSolver.solve(manual.copy(references = listOf(northLeg(2.0))), plain, runId = 3)
        assertEquals(NorthSource.REFERENCES, withReference.source)
        assertEquals(2.0, withReference.rotationDeg, 1e-9)
        assertEquals(NorthSource.NONE, NorthSolver.solve(SurveyDoc(), plain, 3).source)
        assertEquals(-170.0, NorthSolver.solve(SurveyDoc(manualRotationDeg = 190.0), plain, 3).rotationDeg)
    }

    @Test
    fun manualAppliesAndNeedsConfirmation() {
        val manual = SurveyDoc(manualRotationDeg = -7.5, manualRotationRunId = 3)
        assertTrue(NorthSolver.manualApplies(manual, 3))
        assertFalse(NorthSolver.manualApplies(manual, 4))
        assertTrue(NorthSolver.manualApplies(manual.copy(manualRotationRunId = null), 4))
        assertTrue(NorthSolver.manualNeedsConfirmation(manual, 4))
        assertFalse(NorthSolver.manualNeedsConfirmation(manual, 3))
        assertFalse(NorthSolver.manualNeedsConfirmation(manual.copy(references = listOf(northLeg(2.0))), 4))
        assertFalse(NorthSolver.manualNeedsConfirmation(manual.copy(manualRotationDeg = 0.0), 4))
        assertFalse(NorthSolver.manualNeedsConfirmation(manual.copy(manualRotationRunId = null), 4))
    }

    @Test
    fun magneticOnlyFromTheRunOrFromCompassReferences() {
        val none = NorthSolution(0.0, NorthSource.NONE, emptyList())
        val manual = NorthSolution(5.0, NorthSource.MANUAL, emptyList())
        val refs = NorthSolution(5.0, NorthSource.REFERENCES, listOf(ReferenceFit(1, 10.0, 12.0, 0.0)))
        val magnetic = mapOf(OrientationEstimator.NORTH_REFERENCE to OrientationEstimator.MAGNETIC)
        val relative = mapOf(OrientationEstimator.NORTH_REFERENCE to OrientationEstimator.NORTH_OFF)
        assertTrue(NorthSolver.isMagnetic(magnetic, none))
        val padded = mapOf(OrientationEstimator.NORTH_REFERENCE to " ${OrientationEstimator.MAGNETIC} ")
        assertTrue(NorthSolver.isMagnetic(padded, none))
        assertFalse(NorthSolver.isMagnetic(relative, none))
        assertFalse(NorthSolver.isMagnetic(relative, manual), "a hand rotation is not a compass")
        assertFalse(NorthSolver.isMagnetic(emptyMap(), none), "an unknown north is never magnetic")
        assertTrue(NorthSolver.isMagnetic(relative, refs))
        assertTrue(NorthSolver.isMagnetic(emptyMap(), refs))
    }
}
