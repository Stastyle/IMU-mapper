package com.stastyle.imumapper.ui.triplist

import com.stastyle.imumapper.capture.RecordingController
import com.stastyle.imumapper.data.db.TripEntity
import com.stastyle.imumapper.data.db.TripRow
import com.stastyle.imumapper.data.db.TripStatus
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.PathStats
import com.stastyle.imumapper.pipeline.core.TripMode
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A card's numbers all come from one run, and what cannot be known stays unknown. */
class TripListItemTest {

    // The app's settings (AppContainer.json).
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    private fun trip(latestRunId: Int?, durationS: Double? = 95.0, distanceM: Double? = 33.0, notes: String = "") =
        TripEntity(
            id = 4, name = "walk", mode = TripMode.POCKET, carryPosition = CarryPosition.HAND, startedAtEpochMs = 1L,
            status = if (latestRunId == null) TripStatus.RECORDED else TripStatus.PROCESSED,
            latestRunId = latestRunId, durationS = durationS, distanceM = distanceM, notes = notes,
        )

    private fun statsJson(distanceM: Double = 68.1, durationS: Double = 123.0, steps: Int = 102): String =
        json.encodeToString(
            PathStats.serializer(),
            PathStats(distanceM = distanceM, durationS = durationS, stepCount = steps, minZ = -0.7, maxZ = 5.0),
        )

    @Test
    fun decodedStatsGiveAllThreeNumbers() {
        val item = TripListItem.of(TripRow(trip(latestRunId = 2), statsJson(), "PDR"), json)
        assertEquals(123.0, item.durationS)
        assertEquals(68.1, item.distanceM)
        assertEquals(102, item.steps)
        assertEquals(2, item.runId)
    }

    @Test
    fun statsWithUnknownKeysStillDecode() {
        val text = statsJson().dropLast(1) + ",\"addedLater\":1.5}"
        val item = TripListItem.of(TripRow(trip(latestRunId = 1), text, "PDR"), json)
        assertEquals(102, item.steps)
    }

    @Test
    fun undecodableStatsFallBackToTheTripColumnsWithoutSteps() {
        for (text in listOf(null, "{}", "{\"distanceM\":80.0}", "not json")) {
            val item = TripListItem.of(TripRow(trip(latestRunId = 3), text, null), json)
            assertEquals(95.0, item.durationS, "stats: $text")
            assertEquals(33.0, item.distanceM, "stats: $text")
            assertNull(item.steps, "stats: $text")
        }
    }

    @Test
    fun withoutARunOnlyTheDurationIsKnown() {
        // A ZIP imported without results keeps its manifest's distance, which describes no run here.
        val item = TripListItem.of(TripRow(trip(latestRunId = null), statsJson(), null), json)
        assertEquals(95.0, item.durationS)
        assertNull(item.distanceM)
        assertNull(item.steps)
        assertNull(item.runId)
    }

    @Test
    fun recordingTripWithoutAnyNumberShowsNone() {
        val recording = trip(latestRunId = null, durationS = null, distanceM = null)
        val item = TripListItem.of(TripRow(recording, null, null), json)
        assertNull(item.durationS)
        assertNull(item.distanceM)
        assertNull(item.steps)
    }

    @Test
    fun negativeNumbersAreUnknown() {
        val fromStats = TripListItem.of(
            TripRow(trip(latestRunId = 1), statsJson(distanceM = -1.0, durationS = -5.0, steps = -1), "PDR"),
            json,
        )
        assertNull(fromStats.durationS)
        assertNull(fromStats.distanceM)
        assertNull(fromStats.steps)
        val fromColumns = TripListItem.of(TripRow(trip(latestRunId = 1, durationS = -2.0), null, null), json)
        assertNull(fromColumns.durationS)
    }

    @Test
    fun onlyTheRecorderNoteMarksAnUnexpectedEnd() {
        val orphan = trip(latestRunId = null, notes = RecordingController.ENDED_UNEXPECTEDLY_NOTE)
        assertTrue(TripListItem.of(TripRow(orphan, null, null), json).endedUnexpectedly)
        val noted = trip(latestRunId = null, notes = "Recording ended unexpectedly near the lake")
        assertFalse(TripListItem.of(TripRow(noted, null, null), json).endedUnexpectedly)
        assertFalse(TripListItem.of(TripRow(trip(latestRunId = null), null, null), json).endedUnexpectedly)
    }
}
