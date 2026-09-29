package com.stastyle.imumapper.ui.viewer

import com.stastyle.imumapper.data.SurveyLoad
import com.stastyle.imumapper.pipeline.core.PathResult
import com.stastyle.imumapper.pipeline.survey.ChainMeasure
import com.stastyle.imumapper.pipeline.survey.Measure
import com.stastyle.imumapper.pipeline.survey.NorthFrame
import com.stastyle.imumapper.pipeline.survey.PathTimeline
import com.stastyle.imumapper.pipeline.survey.StationKind
import com.stastyle.imumapper.pipeline.survey.StretchMeasure
import com.stastyle.imumapper.pipeline.survey.SurveyDoc
import com.stastyle.imumapper.pipeline.survey.SurveyStations
import com.stastyle.imumapper.pipeline.survey.Traverse
import com.stastyle.imumapper.render.LayerSelection
import kotlin.math.abs

/** The shown run's path before and after the north rotation; cached by ViewerViewModel per shown result and angle. */
class SurveyGeometry private constructor(
    /** The shown result (raw view already applied). */
    val shown: PathResult,
    val runId: Int,
    val raw: Boolean,
    /** Timeline of [shown]: compass references are solved against it. */
    val plain: PathTimeline,
    val rotationDeg: Double,
    /** [shown] turned by [rotationDeg]: the scene and every number use it. */
    val framed: PathResult,
    val timeline: PathTimeline,
) {
    /** This geometry turned by [rotationDeg]; this instance when the angle is unchanged. */
    fun rotated(rotationDeg: Double): SurveyGeometry =
        if (rotationDeg == this.rotationDeg) this else build(shown, runId, raw, plain, rotationDeg)

    companion object {
        /** [shown] must have at least one point. With 0 the framed result and timeline are the plain ones. */
        fun of(shown: PathResult, runId: Int, raw: Boolean, rotationDeg: Double = 0.0): SurveyGeometry =
            build(shown, runId, raw, PathTimeline(shown.points), rotationDeg)

        /** Rotating a result and building a timeline each walk every point, so an angle of 0 reuses the plain ones. */
        private fun build(
            shown: PathResult,
            runId: Int,
            raw: Boolean,
            plain: PathTimeline,
            rotationDeg: Double,
        ): SurveyGeometry {
            if (rotationDeg == 0.0) return SurveyGeometry(shown, runId, raw, plain, 0.0, shown, plain)
            val framed = NorthFrame.rotate(shown, rotationDeg)
            return SurveyGeometry(shown, runId, raw, plain, rotationDeg, framed, PathTimeline(framed.points))
        }
    }
}

sealed interface SurveySelection {
    data object None : SurveySelection

    /** Stations tapped one after another, in tap order; a station may repeat but not twice in a row. */
    data class Chain(val stationIds: List<Int>) : SurveySelection

    /** The path between two stations; a null end is the path's own start or end. */
    data class Stretch(val fromId: Int?, val toId: Int?) : SurveySelection
}

data class SurveyState(
    val doc: SurveyDoc,
    val selection: SurveySelection = SurveySelection.None,
    /** The scrubber's moment, drawn as the cursor. */
    val cursorNs: Long = 0L,
    /** Earlier docs, oldest first, at most SurveyController.MAX_UNDO. */
    val undo: List<SurveyDoc> = emptyList(),
    /** survey.json could not be read: nothing is edited or saved. */
    val readOnly: Boolean = false,
)

/** Result of opening Survey mode on a trip. */
class SurveyOpen(
    val state: SurveyState,
    /** A new doc was seeded and must be saved now. */
    val seeded: Boolean,
    /** Why the survey is read-only; null otherwise. */
    val error: String?,
)

/** What the panel shows for the selection. [names] feed the selection row. */
sealed interface SurveyReadout {
    val names: List<String>

    /** One station chosen, waiting for the next. */
    data class First(override val names: List<String>) : SurveyReadout

    data class Chain(override val names: List<String>, val measure: ChainMeasure) : SurveyReadout

    data class Stretch(override val names: List<String>, val measure: StretchMeasure) : SurveyReadout
}

enum class AzimuthWarning { SHORT, CROOKED, LARGE_CHANGE, BACK_BEARING }

/** What the Set azimuth dialog shows before the reference is added. */
data class AzimuthPreview(
    /** Current (corrected) chord and fitted azimuths of the selection. */
    val chordDeg: Double?,
    val fittedDeg: Double?,
    /** The rotation with the new reference added, and the change from now, both in (-180, 180]. */
    val rotationDeg: Double,
    val changeDeg: Double,
    val warnings: Set<AzimuthWarning>,
)

/**
 * Survey mode's rules as pure functions of a [SurveyState]: selection, cursor, readout, edits and
 * undo. The view model owns the state, saves the doc and publishes the result; nothing here touches
 * a file or Android, so every rule runs in a JVM test.
 */
object SurveyController {
    const val PATH_START_NAME: String = "Path start"
    const val PATH_END_NAME: String = "Path end"

    // --- selection, cursor and readout: none of these is an edit, so all work read-only ---

    /**
     * Only a missing file seeds (and must be saved). A loaded doc is kept as it is, even when empty,
     * so a trip is seeded once. A malformed file is seeded in memory only and the survey is read-only,
     * so the file is never overwritten.
     */
    fun open(load: SurveyLoad, geo: SurveyGeometry): SurveyOpen {
        val start = geo.timeline.startNs
        return when (load) {
            SurveyLoad.Missing -> SurveyOpen(SurveyState(seededDoc(geo), cursorNs = start), seeded = true, error = null)
            is SurveyLoad.Loaded -> SurveyOpen(SurveyState(load.doc, cursorNs = start), seeded = false, error = null)
            is SurveyLoad.Malformed -> SurveyOpen(
                SurveyState(seededDoc(geo), cursorNs = start, readOnly = true),
                seeded = false,
                error = "survey.json could not be read (${load.message}). " +
                    "Survey mode is read-only and the file is left as it is.",
            )
        }
    }

    /** Taps build a chain; tapping its last station again takes it back, so a mis-tap costs one tap. */
    fun tapStation(state: SurveyState, stationId: Int): SurveyState {
        if (state.doc.stations.none { it.id == stationId }) return state
        val chain = (state.selection as? SurveySelection.Chain)?.stationIds.orEmpty()
        val next = when {
            chain.isEmpty() -> listOf(stationId)
            chain.last() == stationId -> chain.dropLast(1)
            else -> chain + stationId
        }
        return state.copy(selection = if (next.isEmpty()) SurveySelection.None else SurveySelection.Chain(next))
    }

    /** A path tap selects the stretch between the stations either side of that moment and moves the cursor there. */
    fun tapPath(state: SurveyState, geo: SurveyGeometry, distanceM: Double): SurveyState {
        val tNs = geo.timeline.timeAtDistance(distanceM)
        val ordered = SurveyStations.ordered(state.doc.stations)
        val from = ordered.lastOrNull { it.tNs <= tNs }
        val to = ordered.firstOrNull { it.tNs > tNs }
        return state.copy(selection = SurveySelection.Stretch(from?.id, to?.id), cursorNs = tNs)
    }

    /** A row of the Legs table: the stretch of that leg. */
    fun selectLeg(state: SurveyState, fromId: Int, toId: Int): SurveyState =
        state.copy(selection = SurveySelection.Stretch(fromId, toId))

    /** Clearing is an explicit button, so a near miss on the map never loses a selection. */
    fun clearSelection(state: SurveyState): SurveyState = state.copy(selection = SurveySelection.None)

    /** The scrubber works in distance, so standing still takes no room on it. */
    fun setCursor(state: SurveyState, geo: SurveyGeometry, distanceM: Double): SurveyState =
        state.copy(cursorNs = geo.timeline.timeAtDistance(distanceM))

    /** The scrubber's arrows move [delta] path points, the finest step the path has. */
    fun stepCursor(state: SurveyState, geo: SurveyGeometry, delta: Int): SurveyState {
        var tNs = state.cursorNs
        repeat(abs(delta)) {
            tNs = if (delta < 0) geo.timeline.previousPointNs(tNs) else geo.timeline.nextPointNs(tNs)
        }
        return state.copy(cursorNs = tNs)
    }

    /** The two moments a pair (chain of exactly two) or a stretch spans; null otherwise. */
    fun selectionEnds(state: SurveyState, geo: SurveyGeometry): Pair<Long, Long>? =
        when (val selection = state.selection) {
            SurveySelection.None -> null
            is SurveySelection.Chain -> {
                val ids = selection.stationIds
                if (ids.size != 2) {
                    null
                } else {
                    val from = stationTime(state, ids[0]) ?: return null
                    val to = stationTime(state, ids[1]) ?: return null
                    from to to
                }
            }
            is SurveySelection.Stretch -> {
                val from = selection.fromId?.let { stationTime(state, it) ?: return null } ?: geo.timeline.startNs
                val to = selection.toId?.let { stationTime(state, it) ?: return null } ?: geo.timeline.endNs
                from to to
            }
        }

    /** The id of the one selected CORNER or USER station ("Move here"); null otherwise. */
    fun movableStationId(state: SurveyState): Int? {
        val id = (state.selection as? SurveySelection.Chain)?.stationIds?.singleOrNull() ?: return null
        val kind = state.doc.stations.firstOrNull { it.id == id }?.kind ?: return null
        return if (kind == StationKind.CORNER || kind == StationKind.USER) id else null
    }

    /** The selection in render terms, so render does not depend on ui.viewer. */
    fun layerSelection(state: SurveyState, geo: SurveyGeometry): LayerSelection =
        when (val selection = state.selection) {
            SurveySelection.None -> LayerSelection()
            is SurveySelection.Chain -> LayerSelection(chainIds = selection.stationIds)
            is SurveySelection.Stretch -> LayerSelection(
                stretchNs = selectionEnds(state, geo),
                stretchIds = listOfNotNull(selection.fromId, selection.toId),
            )
        }

    /** Numbers for the panel, measured on the shown, north-corrected path. */
    fun readout(state: SurveyState, geo: SurveyGeometry): SurveyReadout? {
        val byId = state.doc.stations.associateBy { it.id }
        return when (val selection = state.selection) {
            SurveySelection.None -> null
            is SurveySelection.Chain -> {
                val stations = selection.stationIds.mapNotNull { byId[it] }
                when (stations.size) {
                    0 -> null
                    1 -> SurveyReadout.First(listOf(stations[0].name))
                    else -> SurveyReadout.Chain(
                        stations.map { it.name },
                        Traverse.chain(stations.map { it.tNs }, geo.timeline),
                    )
                }
            }
            is SurveySelection.Stretch -> {
                val (fromNs, toNs) = selectionEnds(state, geo) ?: return null
                val from = selection.fromId?.let { byId[it]?.name } ?: PATH_START_NAME
                val to = selection.toId?.let { byId[it]?.name } ?: PATH_END_NAME
                SurveyReadout.Stretch(listOf(from, to), Measure.stretch(geo.timeline, fromNs, toNs))
            }
        }
    }

    // --- helpers ---

    private fun seededDoc(geo: SurveyGeometry): SurveyDoc =
        SurveyDoc(stations = SurveyStations.seed(geo.timeline, geo.framed.annotations))

    private fun stationTime(state: SurveyState, stationId: Int): Long? =
        state.doc.stations.firstOrNull { it.id == stationId }?.tNs
}
