package com.stastyle.imumapper.ui.record

import com.stastyle.imumapper.capture.CompassReading
import com.stastyle.imumapper.capture.CompassStatus
import com.stastyle.imumapper.capture.RecordingState
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.TripMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How [RecordViewModel] takes over a running recording. The view model needs the process-wide
 * RecordingController, which needs an Android context, so the rule lives in [RecordUiState.adopt] and is
 * tested here.
 */
class RecordAdoptionTest {

    private val s = 1_000_000_000L

    private fun recording(
        tripId: Long = 7L,
        mode: TripMode = TripMode.FLASHLIGHT,
        carry: CarryPosition = CarryPosition.CHEST,
        startedNs: Long = 100 * s,
        elapsedNs: Long = 30 * s,
        paused: Boolean = false,
    ) = RecordingState.Recording(
        tripId = tripId,
        mode = mode,
        carryPosition = carry,
        startedNs = startedNs,
        elapsedNs = elapsedNs,
        paused = paused,
        stepCount = 12,
        annotationCount = 1,
        photosDir = File("photos"),
    )

    /** Read the moment it was published: wall time since the start equals the active time. */
    private fun nowFor(r: RecordingState.Recording) = r.startedNs + r.elapsedNs

    @Test
    fun pocketRouteAdoptingAFlashlightRecordingReportsFlashlightAndItsCarry() {
        val setup = RecordUiState(mode = TripMode.POCKET, carry = CarryPosition.HAND)
        val running = recording()
        val ui = setup.adopt(running, nowFor(running))
        assertEquals(RecordPhase.RECORDING, ui.phase)
        assertEquals(TripMode.FLASHLIGHT, ui.mode)
        assertEquals(CarryPosition.CHEST, ui.carry)
        assertEquals(running, ui.recording)
    }

    @Test
    fun ownStartIsAdoptedFromEveryPhaseBeforeStopping() {
        for (phase in listOf(RecordPhase.SETUP, RecordPhase.COMPASS, RecordPhase.STARTING, RecordPhase.RECORDING)) {
            val before = RecordUiState(
                mode = TripMode.POCKET,
                phase = phase,
                error = "Could not start: busy",
                compass = CompassReading(CompassStatus.SETTLING),
            )
            val running = recording(mode = TripMode.POCKET, carry = CarryPosition.POCKET)
            val ui = before.adopt(running, nowFor(running))
            assertEquals(RecordPhase.RECORDING, ui.phase, "from $phase")
            assertEquals(CarryPosition.POCKET, ui.carry, "from $phase")
            assertNull(ui.error, "from $phase")
            assertNull(ui.compass, "from $phase")
        }
    }

    @Test
    fun stoppingKeepsItsPhaseAndMode() {
        val stopping = RecordUiState(mode = TripMode.POCKET, phase = RecordPhase.STOPPING)
        val other = recording(tripId = 8L)
        val ui = stopping.adopt(other, nowFor(other))
        assertEquals(RecordPhase.STOPPING, ui.phase)
        assertEquals(TripMode.POCKET, ui.mode)
    }

    @Test
    fun lateSavedCarryDoesNotRelabelTheAdoptedRecording() {
        val running = recording(carry = CarryPosition.HELMET)
        val adopted = RecordUiState(mode = TripMode.POCKET).adopt(running, nowFor(running))
        assertEquals(CarryPosition.HELMET, adopted.withSavedCarry(CarryPosition.HAND).carry)
        // Before a recording the saved position is the preselection.
        val setup = RecordUiState(mode = TripMode.POCKET)
        assertEquals(CarryPosition.CHEST, setup.withSavedCarry(CarryPosition.CHEST).carry)
    }

    @Test
    fun clockNoteFollowsPausesOfTheSameTrip() {
        val running = recording()
        var ui = RecordUiState(mode = TripMode.FLASHLIGHT).adopt(running, nowFor(running))
        assertFalse(ui.timeExcludesPauses)
        val paused = recording(paused = true)
        ui = ui.adopt(paused, nowFor(paused))
        assertTrue(ui.timeExcludesPauses)
        // Resumed: the clock still leaves the pause out.
        val resumed = recording(elapsedNs = 31 * s)
        ui = ui.adopt(resumed, nowFor(resumed))
        assertTrue(ui.timeExcludesPauses)
        // A different trip starts without the note.
        val next = recording(tripId = 9L)
        assertFalse(ui.adopt(next, nowFor(next)).timeExcludesPauses)
    }

    @Test
    fun screenOpenedAfterAPauseSeesItInTheClocks() {
        // 30 s active but 50 s since the start: the recording was paused before this screen existed.
        val running = recording(elapsedNs = 30 * s)
        val ui = RecordUiState(mode = TripMode.FLASHLIGHT).adopt(running, running.startedNs + 50 * s)
        assertTrue(ui.timeExcludesPauses)
    }
}
