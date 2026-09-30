package com.stastyle.imumapper.ui.triplist

import com.stastyle.imumapper.capture.RecordingController
import com.stastyle.imumapper.data.db.TripEntity
import com.stastyle.imumapper.data.db.TripRow
import com.stastyle.imumapper.pipeline.core.PathStats
import kotlinx.serialization.json.Json

/**
 * One trip card's data: the trip and the three numbers its card shows. Pure Kotlin, so the one-run rule in [of] is
 * unit-tested on the JVM.
 *
 * Each number is null when it is unknown, and never negative or non-finite, so what the card shows (a dash) and what
 * the sort orders by agree.
 */
data class TripListItem(
    val trip: TripEntity,
    val durationS: Double?,
    val distanceM: Double?,
    val steps: Int?,
) {
    val id: Long get() = trip.id

    /** The run the card's thumbnail and numbers come from; null before the first run. */
    val runId: Int? get() = trip.latestRunId

    /**
     * True for a trip whose recorder died, which `finalizeOrphanedTrip` marks with this note. The note is shown as a
     * warning rather than as the user's own words.
     */
    val endedUnexpectedly: Boolean get() = trip.notes == RecordingController.ENDED_UNEXPECTEDLY_NOTE

    companion object {
        /**
         * The card for [row], with its numbers from one run so they describe the same walk:
         *
         * - A latest run whose stats decode gives all three from those stats.
         * - A latest run whose stats do not decode (a missing or damaged row) falls back to the trip's own duration and
         *   distance, which `markProcessed` wrote from that run, and has no step count.
         * - Without a run there is no distance or step count: a ZIP imported without results keeps its manifest's
         *   distance, which describes no run here. The duration is the trip's own, the active time of a recording.
         *
         * [json] is the app's, which ignores unknown keys, so stats written by a newer build still decode.
         */
        fun of(row: TripRow, json: Json): TripListItem {
            val trip = row.trip
            if (trip.latestRunId == null) {
                return TripListItem(trip, durationS = shown(trip.durationS), distanceM = null, steps = null)
            }
            val stats = row.statsJson?.let { text ->
                runCatching { json.decodeFromString(PathStats.serializer(), text) }.getOrNull()
            }
            return if (stats != null) {
                TripListItem(
                    trip,
                    durationS = shown(stats.durationS),
                    distanceM = shown(stats.distanceM),
                    steps = stats.stepCount.takeIf { it >= 0 },
                )
            } else {
                TripListItem(trip, durationS = shown(trip.durationS), distanceM = shown(trip.distanceM), steps = null)
            }
        }

        private fun shown(value: Double?): Double? = value?.takeIf { it.isFinite() && it >= 0.0 }
    }
}
