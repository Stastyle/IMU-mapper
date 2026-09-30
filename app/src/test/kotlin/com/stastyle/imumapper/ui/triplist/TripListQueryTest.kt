package com.stastyle.imumapper.ui.triplist

import com.stastyle.imumapper.data.db.TripEntity
import com.stastyle.imumapper.data.db.TripStatus
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.TripMode
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/** The trip list's search, filter chips and sort orders. */
class TripListQueryTest {

    private fun item(
        id: Long,
        name: String = "Trip $id",
        mode: TripMode = TripMode.POCKET,
        status: TripStatus = TripStatus.PROCESSED,
        latestRunId: Int? = 1,
        startedAt: Long = id * 1_000L,
        durationS: Double? = null,
        distanceM: Double? = null,
    ) = TripListItem(
        trip = TripEntity(
            id = id, name = name, mode = mode, carryPosition = CarryPosition.HAND, startedAtEpochMs = startedAt,
            status = status, latestRunId = latestRunId,
        ),
        durationS = durationS,
        distanceM = distanceM,
        steps = null,
    )

    private fun TripListQuery.ids(items: List<TripListItem>): List<Long> = apply(items, Locale.US).map { it.id }

    @Test
    fun defaultShowsEveryTripNewestFirst() {
        val items = listOf(item(1), item(3), item(2))
        assertEquals(listOf(3L, 2L, 1L), TripListQuery().ids(items))
    }

    @Test
    fun searchMatchesPartOfTheNameIgnoringCaseAndSpaces() {
        val items = listOf(item(1, "Cave loop"), item(2, "Office walk"), item(3, "cave SIDE passage"))
        assertEquals(listOf(3L, 1L), TripListQuery(text = "  CAVE ").ids(items))
        assertEquals(listOf(2L), TripListQuery(text = "walk").ids(items))
        assertEquals(emptyList(), TripListQuery(text = "garden").ids(items))
        assertEquals(listOf(3L, 2L, 1L), TripListQuery(text = "   ").ids(items), "a blank search hides nothing")
    }

    @Test
    fun modeFiltersKeepTheirMode() {
        val items = listOf(
            item(1, mode = TripMode.POCKET),
            item(2, mode = TripMode.FLASHLIGHT),
            item(3, mode = TripMode.ILLUMINATED),
            item(4, mode = TripMode.FLASHLIGHT),
        )
        assertEquals(listOf(1L), TripListQuery(filter = TripFilter.POCKET).ids(items))
        assertEquals(listOf(4L, 2L), TripListQuery(filter = TripFilter.FLASHLIGHT).ids(items))
        assertEquals(listOf(3L), TripListQuery(filter = TripFilter.ILLUMINATED).ids(items))
        assertEquals(listOf(4L, 3L, 2L, 1L), TripListQuery(filter = TripFilter.ALL).ids(items))
    }

    @Test
    fun processedFollowsTheRunNotTheStatus() {
        val items = listOf(
            item(1, status = TripStatus.PROCESSED, latestRunId = 1),
            item(2, status = TripStatus.FAILED, latestRunId = 3),
            item(3, status = TripStatus.FAILED, latestRunId = null),
            item(4, status = TripStatus.RECORDED, latestRunId = null),
            item(5, status = TripStatus.RECORDING, latestRunId = null),
        )
        assertEquals(listOf(2L, 1L), TripListQuery(filter = TripFilter.PROCESSED).ids(items), "FAILED with a run")
        assertEquals(
            listOf(4L, 3L),
            TripListQuery(filter = TripFilter.NOT_PROCESSED).ids(items),
            "FAILED without a run is not processed; a trip still recording is in neither",
        )
    }

    @Test
    fun datesSortBothWaysWithTheIdBreakingTies() {
        val items = listOf(item(1, startedAt = 50), item(2, startedAt = 10), item(3, startedAt = 50))
        assertEquals(listOf(3L, 1L, 2L), TripListQuery(sort = TripSort.NEWEST).ids(items))
        assertEquals(listOf(2L, 1L, 3L), TripListQuery(sort = TripSort.OLDEST).ids(items))
    }

    @Test
    fun namesSortByCollationThenNewest() {
        val items = listOf(
            item(1, "delta"),
            item(2, "Écluse"),
            item(3, "Alpha"),
            item(4, "Zulu"),
            item(5, "beta"),
            item(6, "Cave"),
            item(7, "Cave"),
        )
        // A plain string sort would put "Zulu" before "beta" and "Écluse" last.
        assertEquals(listOf(3L, 5L, 7L, 6L, 1L, 2L, 4L), TripListQuery(sort = TripSort.NAME).ids(items))
    }

    @Test
    fun distanceSortsLongestFirstWithUnknownLast() {
        val items = listOf(
            item(1, distanceM = 12.0),
            item(2, distanceM = null),
            item(3, distanceM = 250.0),
            item(4, distanceM = null),
            item(5, distanceM = 12.0),
        )
        assertEquals(listOf(3L, 5L, 1L, 4L, 2L), TripListQuery(sort = TripSort.DISTANCE).ids(items))
    }

    @Test
    fun durationSortsLongestFirstWithUnknownLast() {
        val items = listOf(item(1, durationS = 60.0), item(2, durationS = null), item(3, durationS = 3_600.0))
        assertEquals(listOf(3L, 1L, 2L), TripListQuery(sort = TripSort.DURATION).ids(items))
    }

    @Test
    fun searchFilterAndSortCombine() {
        val items = listOf(
            item(1, "Cave A", mode = TripMode.FLASHLIGHT, distanceM = 40.0),
            item(2, "Cave B", mode = TripMode.POCKET, distanceM = 90.0),
            item(3, "Cave C", mode = TripMode.FLASHLIGHT, distanceM = 70.0),
            item(4, "Office", mode = TripMode.FLASHLIGHT, distanceM = 500.0),
            item(5, "Cave D", mode = TripMode.FLASHLIGHT, latestRunId = null, status = TripStatus.RECORDED),
        )
        val query = TripListQuery(text = "cave", filter = TripFilter.FLASHLIGHT, sort = TripSort.DISTANCE)
        assertEquals(listOf(3L, 1L, 5L), query.ids(items))
        assertEquals(listOf(2L, 3L, 1L), query.copy(filter = TripFilter.PROCESSED).ids(items))
    }
}
