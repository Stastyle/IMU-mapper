package com.stastyle.imumapper.ui.triplist

import com.stastyle.imumapper.capture.RecordingState
import com.stastyle.imumapper.data.db.TripEntity
import com.stastyle.imumapper.data.db.TripStatus
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.TripMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which card reopens the recording screen, and in which mode. */
class RecordingModeForTest {

    private fun item(id: Long, status: TripStatus, mode: TripMode = TripMode.POCKET) = TripListItem(
        trip = TripEntity(
            id = id, name = "trip", mode = mode, carryPosition = CarryPosition.HAND, startedAtEpochMs = 1L,
            status = status,
        ),
        durationS = null,
        distanceM = null,
        steps = null,
    )

    private fun recording(tripId: Long, mode: TripMode = TripMode.FLASHLIGHT, paused: Boolean = false) =
        RecordingState.Recording(
            tripId = tripId,
            mode = mode,
            carryPosition = CarryPosition.HAND,
            startedNs = 0L,
            elapsedNs = 5_000_000_000L,
            paused = paused,
            stepCount = 3,
            annotationCount = 0,
            photosDir = File("photos"),
        )

    @Test
    fun theRunningTripOpensInTheRunningMode() {
        // The running recording's mode wins over the row's, so the screen always joins what is really recording.
        assertEquals(TripMode.FLASHLIGHT, recordingModeFor(item(7, TripStatus.RECORDING), recording(7)))
        assertEquals(
            TripMode.ILLUMINATED,
            recordingModeFor(item(7, TripStatus.RECORDING), recording(7, TripMode.ILLUMINATED, paused = true)),
        )
    }

    @Test
    fun anotherRecordingRowOpensTheViewer() {
        // Left RECORDING by a process that died; the recording running now is a different trip.
        assertNull(recordingModeFor(item(3, TripStatus.RECORDING), recording(7)))
    }

    @Test
    fun theSameIdWithAFinishedStatusOpensTheViewer() {
        for (status in listOf(TripStatus.RECORDED, TripStatus.PROCESSED, TripStatus.FAILED)) {
            assertNull(recordingModeFor(item(7, status), recording(7)), "status $status")
        }
    }

    @Test
    fun nothingRunningOpensTheViewer() {
        assertNull(recordingModeFor(item(7, TripStatus.RECORDING), RecordingState.Idle))
        assertNull(recordingModeFor(item(7, TripStatus.RECORDING), RecordingState.Stopping))
    }
}
