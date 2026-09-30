package com.stastyle.imumapper.ui.nav

import com.stastyle.imumapper.capture.RecordingState
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.TripMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Where "Return to recording" and the trip list's recording row lead. */
class RecordRouteTest {

    private fun recording(mode: TripMode) = RecordingState.Recording(
        tripId = 7L,
        mode = mode,
        carryPosition = CarryPosition.HAND,
        startedNs = 1_000L,
        elapsedNs = 5_000_000_000L,
        paused = false,
        stepCount = 12,
        annotationCount = 0,
        photosDir = File("photos"),
    )

    @Test
    fun runningRecordingOpensItsOwnMode() {
        // Whatever the Settings default is (POCKET out of the box), the running FLASHLIGHT trip is reopened.
        assertEquals(Routes.record(TripMode.FLASHLIGHT), recordRouteFor(recording(TripMode.FLASHLIGHT)))
        assertEquals("record/FLASHLIGHT", recordRouteFor(recording(TripMode.FLASHLIGHT)))
        for (mode in TripMode.entries) assertEquals(Routes.record(mode), recordRouteFor(recording(mode)))
    }

    @Test
    fun nothingToReturnToWhenIdleOrStopping() {
        assertNull(recordRouteFor(RecordingState.Idle))
        assertNull(recordRouteFor(RecordingState.Stopping))
    }
}
