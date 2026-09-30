package com.stastyle.imumapper.ui.triplist

import com.stastyle.imumapper.data.db.TripStatus
import com.stastyle.imumapper.pipeline.core.TripMode
import java.text.Collator
import java.util.Locale

/** The trip list's filter chips, in the order they are shown. */
enum class TripFilter(val label: String) {
    ALL("All"),
    POCKET("Pocket"),
    FLASHLIGHT("Flashlight"),
    ILLUMINATED("Illuminated"),

    /** Trips with a run, whatever their status: a FAILED re-process keeps its earlier run and its Failed pill. */
    PROCESSED("Processed"),

    /** Trips without a run that could be processed; one still recording has nothing to process yet. */
    NOT_PROCESSED("Not processed"),
    ;

    fun matches(item: TripListItem): Boolean = when (this) {
        ALL -> true
        POCKET -> item.trip.mode == TripMode.POCKET
        FLASHLIGHT -> item.trip.mode == TripMode.FLASHLIGHT
        ILLUMINATED -> item.trip.mode == TripMode.ILLUMINATED
        PROCESSED -> item.trip.latestRunId != null
        NOT_PROCESSED -> item.trip.latestRunId == null && item.trip.status != TripStatus.RECORDING
    }
}

/** The trip list's sort orders. Distance and duration put the longest first and the unknown last. */
enum class TripSort(val label: String) {
    NEWEST("Date (newest)"),
    OLDEST("Date (oldest)"),
    NAME("Name"),
    DISTANCE("Distance (longest)"),
    DURATION("Duration (longest)"),
}

/**
 * What the trip list shows: the trips whose name contains [text] (ignoring case and surrounding spaces) and that
 * match [filter], in [sort] order. Ties are broken by the start time and then the id, so the order never depends on
 * the database's.
 */
data class TripListQuery(
    val text: String = "",
    val filter: TripFilter = TripFilter.ALL,
    val sort: TripSort = TripSort.NEWEST,
) {
    /** [items] searched, filtered and sorted. Names sort by [locale]'s collation, as a person reads them. */
    fun apply(items: List<TripListItem>, locale: Locale = Locale.getDefault()): List<TripListItem> {
        val needle = text.trim()
        return items
            .filter { filter.matches(it) && (needle.isEmpty() || it.trip.name.contains(needle, ignoreCase = true)) }
            .sortedWith(comparator(locale))
    }

    private fun comparator(locale: Locale): Comparator<TripListItem> {
        val newestFirst = compareByDescending<TripListItem> { it.trip.startedAtEpochMs }.thenByDescending { it.id }
        return when (sort) {
            TripSort.NEWEST -> newestFirst
            TripSort.OLDEST -> compareBy<TripListItem> { it.trip.startedAtEpochMs }.thenBy { it.id }
            TripSort.NAME -> {
                val collator = Collator.getInstance(locale)
                Comparator<TripListItem> { a, b -> collator.compare(a.trip.name, b.trip.name) }.then(newestFirst)
            }
            TripSort.DISTANCE -> longestFirst { it.distanceM }.then(newestFirst)
            TripSort.DURATION -> longestFirst { it.durationS }.then(newestFirst)
        }
    }

    private fun longestFirst(value: (TripListItem) -> Double?): Comparator<TripListItem> =
        compareBy(nullsLast(reverseOrder<Double>()), value)

    companion object {
        /**
         * The query saved as its text and the [TripFilter] and [TripSort] names; a null is a value never saved. A name
         * this version does not know (renamed or removed since it was saved) falls back to the default, instead of
         * failing the screen.
         */
        fun restore(text: String?, filterName: String?, sortName: String?): TripListQuery {
            val default = TripListQuery()
            return TripListQuery(
                text = text ?: default.text,
                filter = TripFilter.entries.firstOrNull { it.name == filterName } ?: default.filter,
                sort = TripSort.entries.firstOrNull { it.name == sortName } ?: default.sort,
            )
        }
    }
}
