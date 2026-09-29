package com.stastyle.imumapper.ui.viewer

import com.stastyle.imumapper.SurveyFixtures
import com.stastyle.imumapper.data.SurveyLoad
import com.stastyle.imumapper.pipeline.survey.CompassReference
import com.stastyle.imumapper.pipeline.survey.NorthSolver
import com.stastyle.imumapper.pipeline.survey.ReferenceLine
import com.stastyle.imumapper.pipeline.survey.SurveyAngles
import kotlin.math.atan2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Compass references, the manual rotation and the Set azimuth preview on the L walk. Start (id 1) to
 * C1 (id 4) is 10 m due north, Start to Junction 1 (id 2) 5 m north, and Start to End (id 3) cuts the
 * corner to (5, 10) over 15 m of path. [geo] is a magnetic run and [relative] the same walk with
 * north from the compass off, so its north is arbitrary.
 */
class SurveyNorthEditsTest {

    private val geo = SurveyGeometry.of(SurveyFixtures.lWalk(), runId = 1, raw = false)
    private val relative = SurveyGeometry.of(SurveyFixtures.lWalk(magnetic = false), runId = 1, raw = false)
    private val seeded = SurveyController.open(SurveyLoad.Missing, geo).state

    private fun t(index: Int): Long = SurveyFixtures.tNs(index)

    private fun chain(vararg ids: Int): SurveyState = ids.fold(seeded) { s, id -> SurveyController.tapStation(s, id) }

    private fun north(state: SurveyState): Double = NorthSolver.solve(state.doc, geo.plain, geo.runId).rotationDeg

    @Test
    fun bearingOnAPairAddsAChordReferenceThatTurnsNorth() {
        val state = SurveyController.addReference(chain(1, 4), geo, 10.0, false, ReferenceLine.CHORD)
        assertEquals(
            listOf(CompassReference(id = 1, fromNs = t(0), toNs = t(20), bearingDeg = 10.0)),
            state.doc.references,
        )
        assertEquals(1, state.undo.size)
        assertEquals(10.0, north(state), 1e-9)

        // Bearings are stored in [0, 360) and ids keep counting.
        val second = SurveyController.addReference(state, geo, 370.0, backBearing = true, line = ReferenceLine.FITTED)
        val added = second.doc.references.last()
        assertEquals(2, added.id)
        assertEquals(10.0, added.bearingDeg, 1e-9)
        assertTrue(added.backBearing)
        assertEquals(ReferenceLine.FITTED, added.line)
    }

    @Test
    fun referenceNeedsAPairOrStretchWithAChordAndAFiniteBearing() {
        assertSame(seeded, SurveyController.addReference(seeded, geo, 10.0, false, ReferenceLine.CHORD))
        val one = chain(1)
        assertSame(one, SurveyController.addReference(one, geo, 10.0, false, ReferenceLine.CHORD))
        val pair = chain(1, 4)
        assertSame(pair, SurveyController.addReference(pair, geo, Double.NaN, false, ReferenceLine.CHORD))
        assertSame(pair, SurveyController.addReference(pair, geo, Double.POSITIVE_INFINITY, false, ReferenceLine.CHORD))
        // A stretch from a station to itself has no chord, so its bearing could never be used.
        val nowhere = SurveyController.selectLeg(seeded, 2, 2)
        assertSame(nowhere, SurveyController.addReference(nowhere, geo, 10.0, false, ReferenceLine.CHORD))
        val stretch = SurveyController.selectLeg(seeded, 2, 4)
        val fitted = SurveyController.addReference(stretch, geo, 0.0, false, ReferenceLine.FITTED)
        assertEquals(1, fitted.doc.references.size)
    }

    @Test
    fun manualRotationIsWrappedTaggedAndRefusedWhileReferencesExist() {
        val manual = SurveyController.setManualRotation(seeded, 190.0, runId = 1)
        assertEquals(-170.0, manual.doc.manualRotationDeg, 1e-9)
        assertEquals(1, manual.doc.manualRotationRunId)
        assertSame(manual, SurveyController.setManualRotation(manual, Double.NaN, runId = 1))

        val referenced = SurveyController.addReference(chain(1, 4), geo, 10.0, false, ReferenceLine.CHORD)
        assertSame(referenced, SurveyController.setManualRotation(referenced, 5.0, runId = 1))
    }

    @Test
    fun confirmingTagsTheManualRotationWithTheShownRun() {
        val manual = SurveyController.setManualRotation(seeded, 5.0, runId = 1)
        val confirmed = SurveyController.confirmManualRotation(manual, runId = 2)
        assertEquals(2, confirmed.doc.manualRotationRunId)
        assertEquals(5.0, confirmed.doc.manualRotationDeg)
        assertEquals(2, confirmed.undo.size)
    }

    @Test
    fun resetClearsReferencesAndTheManualRotationAndDeleteRemovesOne() {
        val manual = SurveyController.setManualRotation(chain(1, 4), 5.0, runId = 1)
        val both = SurveyController.addReference(manual, geo, 10.0, false, ReferenceLine.CHORD)
        assertEquals(1, both.doc.references.size)

        val reset = SurveyController.resetNorth(both)
        assertTrue(reset.doc.references.isEmpty())
        assertEquals(0.0, reset.doc.manualRotationDeg)
        assertNull(reset.doc.manualRotationRunId)
        assertEquals(both.doc.stations, reset.doc.stations)

        val referenceId = both.doc.references.single().id
        assertTrue(SurveyController.deleteReference(both, referenceId).doc.references.isEmpty())
        assertSame(both, SurveyController.deleteReference(both, 99))
    }

    @Test
    fun previewOnAStraightTenMetrePairWarnsOfNothing() {
        val preview = assertNotNull(SurveyController.azimuthPreview(chain(1, 4), geo, 10.0, false, ReferenceLine.CHORD))
        assertEquals(0.0, preview.chordDeg!!, 1e-9)
        assertEquals(0.0, SurveyAngles.wrapDeg(preview.fittedDeg!!), 1e-9)
        assertEquals(10.0, preview.rotationDeg, 1e-9)
        assertEquals(10.0, preview.changeDeg, 1e-9)
        assertEquals(emptySet(), preview.warnings)
        assertNull(SurveyController.azimuthPreview(seeded, geo, 10.0, false, ReferenceLine.CHORD))
        assertNull(SurveyController.azimuthPreview(chain(1, 4), geo, Double.NaN, false, ReferenceLine.CHORD))
    }

    @Test
    fun previewOfFiftyDegreesAsksAboutABackBearing() {
        val preview = assertNotNull(SurveyController.azimuthPreview(chain(1, 4), geo, 50.0, false, ReferenceLine.CHORD))
        assertEquals(setOf(AzimuthWarning.LARGE_CHANGE, AzimuthWarning.BACK_BEARING), preview.warnings)
    }

    @Test
    fun firstReadingOnARelativeRunTurnsTheMapWithoutAskingAboutABackBearing() {
        // Until the first reading north is arbitrary, so any turn is expected and none hints at a back-bearing.
        val preview = assertNotNull(
            SurveyController.azimuthPreview(chain(1, 4), relative, 150.0, false, ReferenceLine.CHORD),
        )
        assertEquals(150.0, preview.changeDeg, 1e-9)
        assertEquals(emptySet(), preview.warnings)
        // The checks on the stretch itself still apply.
        val short = assertNotNull(
            SurveyController.azimuthPreview(chain(1, 2), relative, 150.0, false, ReferenceLine.CHORD),
        )
        assertEquals(setOf(AzimuthWarning.SHORT), short.warnings)
        // A hand turn set on another run is not in use here, so north is still arbitrary.
        val elsewhere = SurveyController.setManualRotation(chain(1, 4), 5.0, runId = 2)
        val stillArbitrary = assertNotNull(
            SurveyController.azimuthPreview(elsewhere, relative, 150.0, false, ReferenceLine.CHORD),
        )
        assertEquals(emptySet(), stillArbitrary.warnings)
    }

    @Test
    fun onceARelativeRunHasAReadingOrAHandTurnALargeChangeWarnsAgain() {
        val first = SurveyController.addReference(chain(1, 4), relative, 150.0, false, ReferenceLine.CHORD)
        // 150 and 270 over equal lengths average to 210: 60 degrees on from the first reading.
        val second = assertNotNull(
            SurveyController.azimuthPreview(first, relative.rotated(150.0), 270.0, false, ReferenceLine.CHORD),
        )
        assertEquals(60.0, second.changeDeg, 1e-9)
        assertEquals(setOf(AzimuthWarning.LARGE_CHANGE, AzimuthWarning.BACK_BEARING), second.warnings)

        // A map turned by hand on this run is no longer random either.
        val turned = SurveyController.setManualRotation(chain(1, 4), 5.0, runId = 1)
        val afterHand = assertNotNull(
            SurveyController.azimuthPreview(turned, relative.rotated(5.0), 150.0, false, ReferenceLine.CHORD),
        )
        assertEquals(145.0, afterHand.changeDeg, 1e-9)
        assertEquals(setOf(AzimuthWarning.LARGE_CHANGE, AzimuthWarning.BACK_BEARING), afterHand.warnings)
    }

    @Test
    fun theBackBearingLineAsksAboutTheBoxOnlyWhileItIsClear() {
        assertEquals("Over 45°: was it a back-bearing?", warningText(AzimuthWarning.BACK_BEARING, backBearing = false))
        assertEquals(
            "Over 45° as a back-bearing: check the reading and the Back-bearing box.",
            warningText(AzimuthWarning.BACK_BEARING, backBearing = true),
        )
        assertEquals(
            warningText(AzimuthWarning.LARGE_CHANGE, backBearing = false),
            warningText(AzimuthWarning.LARGE_CHANGE, backBearing = true),
        )
    }

    @Test
    fun previewOnAFiveMetrePairSaysItIsShort() {
        val preview = assertNotNull(SurveyController.azimuthPreview(chain(1, 2), geo, 0.0, false, ReferenceLine.CHORD))
        assertEquals(setOf(AzimuthWarning.SHORT), preview.warnings)
    }

    @Test
    fun previewOfPointsWithNoDirectionTurnsNothingAndWarnsOfNothing() {
        // A leg from a station to itself is under Measure.MIN_HORIZONTAL_M across, so it has no chord and
        // its reading could never be used: the dialog says why instead of a turn or a SHORT warning.
        val nowhere = SurveyController.selectLeg(seeded, 2, 2)
        val preview = assertNotNull(SurveyController.azimuthPreview(nowhere, geo, 45.0, false, ReferenceLine.CHORD))
        assertNull(preview.chordDeg)
        assertNull(preview.fittedDeg)
        assertEquals(0.0, preview.rotationDeg, 1e-9)
        assertEquals(0.0, preview.changeDeg, 1e-9)
        assertEquals(emptySet(), preview.warnings)
        assertEquals(
            "Pillar A and S2 are less than 0.3 m apart on the plan, so they give no direction. " +
                "Pick points farther apart.",
            noDirectionText("Pillar A", "S2"),
        )
    }

    @Test
    fun previewAcrossTheCornerSaysItIsCrooked() {
        // Start to End reads atan2(5, 10) = 26.6 degrees over 11.2 m, but the path walks 15 m: straightness 0.75.
        val chordDeg = Math.toDegrees(atan2(5.0, 10.0))
        val preview = assertNotNull(
            SurveyController.azimuthPreview(chain(1, 3), geo, chordDeg, false, ReferenceLine.CHORD),
        )
        assertEquals(chordDeg, preview.chordDeg!!, 1e-9)
        assertEquals(0.0, preview.changeDeg, 1e-9)
        assertEquals(setOf(AzimuthWarning.CROOKED), preview.warnings)
    }

    @Test
    fun previewChangeIsMeasuredFromTheRotationInUse() {
        val first = SurveyController.addReference(chain(1, 4), geo, 10.0, false, ReferenceLine.CHORD)
        val preview = assertNotNull(
            SurveyController.azimuthPreview(first, geo.rotated(10.0), 10.0, false, ReferenceLine.CHORD),
        )
        // The pair already reads 010 on the corrected path, and a second identical reading changes nothing.
        assertEquals(10.0, preview.chordDeg!!, 1e-9)
        assertEquals(10.0, preview.rotationDeg, 1e-9)
        assertEquals(0.0, preview.changeDeg, 1e-9)
    }

    @Test
    fun northEditsAreRefusedReadOnlyButThePreviewStillReads() {
        val readOnly = listOf(1, 4).fold(SurveyController.open(SurveyLoad.Malformed("bad"), geo).state) { s, id ->
            SurveyController.tapStation(s, id)
        }
        assertSame(readOnly, SurveyController.addReference(readOnly, geo, 10.0, false, ReferenceLine.CHORD))
        assertSame(readOnly, SurveyController.setManualRotation(readOnly, 5.0, runId = 1))
        assertSame(readOnly, SurveyController.confirmManualRotation(readOnly, runId = 2))
        assertSame(readOnly, SurveyController.resetNorth(readOnly))
        assertNotNull(SurveyController.azimuthPreview(readOnly, geo, 10.0, false, ReferenceLine.CHORD))
    }
}
