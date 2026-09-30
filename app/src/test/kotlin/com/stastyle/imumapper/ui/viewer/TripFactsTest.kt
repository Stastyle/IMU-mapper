package com.stastyle.imumapper.ui.viewer

import com.stastyle.imumapper.capture.RecordingController
import com.stastyle.imumapper.data.db.TripEntity
import com.stastyle.imumapper.data.db.TripStatus
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.TripMode
import com.stastyle.imumapper.pipeline.pdr.OrientationEstimator
import com.stastyle.imumapper.ui.common.NO_VALUE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Details tab's trip facts, and what they say about a trip whose recording ended unexpectedly. */
class TripFactsTest {

    /** Stands in for TripFormat.date, whose output follows the phone's locale. */
    private val date: (Long) -> String = { ms -> "t$ms" }

    private fun trip(
        status: TripStatus = TripStatus.PROCESSED,
        endedAtEpochMs: Long? = 1_130_000L,
        notes: String = "",
        rawLogSizeBytes: Long = 140_200_000L,
    ) = TripEntity(
        name = "Cave loop",
        mode = TripMode.FLASHLIGHT,
        carryPosition = CarryPosition.CHEST,
        startedAtEpochMs = 1_000_000L,
        endedAtEpochMs = endedAtEpochMs,
        status = status,
        rawLogSizeBytes = rawLogSizeBytes,
        notes = notes,
    )

    private fun TripFacts.value(label: String): String? = rows.firstOrNull { it.label == label }?.value

    @Test
    fun aFinishedTripListsItsModeCarryTimesLogAndNorth() {
        val facts = tripFacts(trip(), runDurationS = 125.0, north = "Magnetic", date = date)
        assertFalse(facts.endedUnexpectedly)
        assertEquals(
            listOf(
                TripFact("Mode", "Flashlight"),
                TripFact("Carried in", "Chest"),
                TripFact("Started", "t1000000"),
                TripFact("Ended", "t1130000"),
                TripFact("Raw log", "140.2 MB"),
                TripFact("North", "Magnetic"),
            ),
            facts.rows,
        )
    }

    @Test
    fun aTripThatEndedUnexpectedlyHasNoEndTimeButItsLastData() {
        // endedAtEpochMs is when the app noticed, which must not be shown as the end.
        val orphan = trip(endedAtEpochMs = 9_999_000L, notes = RecordingController.ENDED_UNEXPECTEDLY_NOTE)
        val facts = tripFacts(orphan, runDurationS = 125.4, north = null, date = date)
        assertTrue(facts.endedUnexpectedly)
        assertEquals("unknown", facts.value("Ended"))
        assertEquals("≈ t1125400", facts.value("Last data"))
        assertEquals(NO_VALUE, facts.value("North"))

        // Without a run the log's length is not known, so there is no last-data time either.
        val unprocessed = tripFacts(orphan, runDurationS = null, north = null, date = date)
        assertEquals("unknown", unprocessed.value("Ended"))
        assertEquals(null, unprocessed.value("Last data"))
    }

    @Test
    fun otherNotesAndMissingEndsReadPlainly() {
        assertFalse(tripFacts(trip(notes = "wet"), null, null, date).endedUnexpectedly)
        val recording = trip(status = TripStatus.RECORDING, endedAtEpochMs = null)
        assertEquals("still recording", tripFacts(recording, null, null, date).value("Ended"))
        assertEquals(NO_VALUE, tripFacts(trip(endedAtEpochMs = null), null, null, date).value("Ended"))
    }

    @Test
    fun rawLogSizesUseDecimalUnits() {
        assertEquals(NO_VALUE, fileSize(0L))
        assertEquals("1 kB", fileSize(200L))
        assertEquals("850 kB", fileSize(850_000L))
        assertEquals("140.2 MB", fileSize(140_200_000L))
        assertEquals("1.25 GB", fileSize(1_250_000_000L))
        // A size that rounds up to the next unit is written in it, never as "1000 kB" or "1000.0 MB".
        assertEquals("999 kB", fileSize(999_499L))
        assertEquals("1.0 MB", fileSize(999_500L))
        assertEquals("999.9 MB", fileSize(999_949_999L))
        assertEquals("1.00 GB", fileSize(999_950_000L))
    }

    @Test
    fun northSaysMagneticOrWhyItIsRelative() {
        assertEquals(null, northText(null))
        val magnetic = mapOf(OrientationEstimator.NORTH_REFERENCE to OrientationEstimator.MAGNETIC)
        assertEquals("Magnetic", northText(magnetic))
        assertEquals(
            "Relative (north from compass is off)",
            northText(mapOf(OrientationEstimator.NORTH_REFERENCE to OrientationEstimator.NORTH_OFF)),
        )
        // A run from before the diagnostic existed is not called relative: it may well be magnetic.
        assertEquals("Not recorded", northText(emptyMap()))
    }
}
