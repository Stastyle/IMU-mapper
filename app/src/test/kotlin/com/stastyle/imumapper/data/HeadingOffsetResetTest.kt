package com.stastyle.imumapper.data

import com.stastyle.imumapper.pipeline.core.HeadingAxisMode
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.ui.debug.ConfigDraft
import com.stastyle.imumapper.ui.debug.ConfigFields
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeadingOffsetResetTest {

    private class Flag(var done: Boolean = false) {
        var marks = 0
        suspend fun isDone(): Boolean = done
        suspend fun markDone() {
            done = true
            marks++
        }
    }

    private val calibrated = PipelineConfig(
        strideLengthM = 0.78,
        weinbergK = 0.45,
        headingOffsetRad = Math.toRadians(-63.0),
        headingAxis = HeadingAxisMode.CAMERA,
        useMagnetometer = false,
        gyroBias = Vec3(0.001, -0.002, 0.003),
        smoothingWindow = 5,
    )

    @Test
    fun resetsTheOffsetAndAxisAndKeepsEverythingElse() = runBlocking {
        val repo = FakeCalibrationRepository(calibrated)
        val flag = Flag()
        assertTrue(HeadingOffsetReset.runOnce(repo, flag::isDone, flag::markDone))
        assertEquals(calibrated.copy(headingOffsetRad = 0.0, headingAxis = HeadingAxisMode.AUTO), repo.config)
        assertEquals(listOf(HeadingOffsetReset.NOTE), repo.savedNotes)
        assertTrue(flag.done)
    }

    @Test
    fun runsOnlyOnce() = runBlocking {
        val repo = FakeCalibrationRepository(calibrated)
        val flag = Flag()
        HeadingOffsetReset.runOnce(repo, flag::isDone, flag::markDone)
        // An offset calibrated after the reset is the user's own and must survive later starts.
        val recalibrated =
            repo.config.copy(headingOffsetRad = Math.toRadians(12.0), headingAxis = HeadingAxisMode.FORWARD)
        repo.saveConfig(recalibrated, "Heading offset 12.0°")
        assertFalse(HeadingOffsetReset.runOnce(repo, flag::isDone, flag::markDone))
        assertEquals(recalibrated, repo.config)
        assertEquals(1, flag.marks)
    }

    @Test
    fun anAxisAloneIsResetToo() = runBlocking {
        val repo = FakeCalibrationRepository(PipelineConfig(headingAxis = HeadingAxisMode.FORWARD))
        val flag = Flag()
        assertTrue(HeadingOffsetReset.runOnce(repo, flag::isDone, flag::markDone))
        assertEquals(PipelineConfig(), repo.config)
    }

    @Test
    fun nothingToResetStillMarksItDone() = runBlocking {
        val repo = FakeCalibrationRepository(PipelineConfig(strideLengthM = 0.8))
        val flag = Flag()
        assertFalse(HeadingOffsetReset.runOnce(repo, flag::isDone, flag::markDone))
        assertTrue(repo.savedNotes.isEmpty())
        assertTrue(flag.done)
    }

    @Test
    fun aHeadingOffsetCalibratedAfterAFailedResetSurvivesTheRetry() = runBlocking {
        val repo = FakeCalibrationRepository(calibrated)
        val flag = Flag()
        // The first start's reset fails, so the stale offset stays and the reset is not marked done.
        repo.saveFailure = IOException("disk full")
        assertFailsWith<IOException> { HeadingOffsetReset.runOnce(repo, flag::isDone, flag::markDone) }
        repo.saveFailure = null
        // The user calibrates the heading before the next start and happens to measure the same -63
        // degrees: measured, so the user's own although nothing changed.
        val measured = calibrated
        val failure = HeadingOffsetReset.saveCalibration(
            repo, measured, "Heading offset -63.0°", flag::markDone, offsetMeasured = true,
        )
        assertNull(failure)
        assertTrue(flag.done)
        // The retry at the next start leaves the offset the user just measured.
        assertFalse(HeadingOffsetReset.runOnce(repo, flag::isDone, flag::markDone))
        assertEquals(measured, repo.config)
    }

    @Test
    fun aTypedOffsetCountsAsTheUsersOwnButACarriedAlongOneDoesNot() = runBlocking {
        val repo = FakeCalibrationRepository(calibrated)
        val flag = Flag()
        // Saving another value keeps the stored (stale) offset: the reset still has to run.
        HeadingOffsetReset.saveCalibration(repo, repo.config.copy(strideLengthM = 0.8), "debug editor", flag::markDone)
        assertFalse(flag.done)
        // An offset or an axis the user changed is theirs.
        val axisChanged = repo.config.copy(headingAxis = HeadingAxisMode.FORWARD)
        HeadingOffsetReset.saveCalibration(repo, axisChanged, "", flag::markDone)
        assertTrue(flag.done)
        val retyped = Flag()
        HeadingOffsetReset.saveCalibration(repo, repo.config.copy(headingOffsetRad = 0.2), "", retyped::markDone)
        assertTrue(retyped.done)
    }

    @Test
    fun aDebugEditorSaveCarriesAStaleOffsetAlongWithoutMarkingTheResetDone() = runBlocking {
        // The editor shows the offset in degrees with six decimals and parses it back, so the stale
        // 1.234 rad returns about a nanoradian off. That is the stored offset carried along, not the user's.
        val stale = calibrated.copy(headingOffsetRad = 1.234)
        val repo = FakeCalibrationRepository(stale)
        val flag = Flag()
        val roundTripped = assertNotNull(ConfigFields.parse(ConfigDraft.from(stale)).config)
        assertNotEquals(stale.headingOffsetRad, roundTripped.headingOffsetRad)
        assertNull(HeadingOffsetReset.saveCalibration(repo, roundTripped, "debug editor", flag::markDone))
        assertFalse(flag.done)
        // So the reset still clears it at the next start.
        assertTrue(HeadingOffsetReset.runOnce(repo, flag::isDone, flag::markDone))
        assertEquals(0.0, repo.config.headingOffsetRad)
    }

    @Test
    fun aFailedMarkIsReportedButTheSaveStands() = runBlocking {
        val repo = FakeCalibrationRepository(calibrated)
        val measured = calibrated.copy(headingOffsetRad = 0.1)
        val failure = HeadingOffsetReset.saveCalibration(
            repo, measured, "Heading offset", { throw IOException("store unreadable") }, offsetMeasured = true,
        )
        assertTrue(failure is IOException)
        assertEquals(measured, repo.config)
    }

    @Test
    fun aFailedCalibrationSaveMarksNothing() = runBlocking {
        val repo = FakeCalibrationRepository(calibrated)
        repo.saveFailure = IOException("disk full")
        val flag = Flag()
        assertFailsWith<IOException> {
            HeadingOffsetReset.saveCalibration(repo, calibrated.copy(headingOffsetRad = 0.1), "", flag::markDone, true)
        }
        assertFalse(flag.done)
    }

    @Test
    fun aFailedSaveIsTriedAgainAtTheNextStart() = runBlocking {
        val repo = FakeCalibrationRepository(calibrated)
        repo.saveFailure = IOException("disk full")
        val flag = Flag()
        assertFailsWith<IOException> { HeadingOffsetReset.runOnce(repo, flag::isDone, flag::markDone) }
        assertFalse(flag.done)
        repo.saveFailure = null
        assertTrue(HeadingOffsetReset.runOnce(repo, flag::isDone, flag::markDone))
        assertEquals(0.0, repo.config.headingOffsetRad)
    }
}
