package com.stastyle.imumapper.ui.viewer

import com.stastyle.imumapper.data.SurveyLoad
import com.stastyle.imumapper.pipeline.core.PathResult
import com.stastyle.imumapper.pipeline.survey.ChainMeasure
import com.stastyle.imumapper.pipeline.survey.CompassReference
import com.stastyle.imumapper.pipeline.survey.Detail
import com.stastyle.imumapper.pipeline.survey.Measure
import com.stastyle.imumapper.pipeline.survey.NorthFrame
import com.stastyle.imumapper.pipeline.survey.NorthSolver
import com.stastyle.imumapper.pipeline.survey.PathTimeline
import com.stastyle.imumapper.pipeline.survey.ReferenceLine
import com.stastyle.imumapper.pipeline.survey.StationKind
import com.stastyle.imumapper.pipeline.survey.StretchMeasure
import com.stastyle.imumapper.pipeline.survey.SurveyAngles
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
    const val MAX_UNDO: Int = 50
    const val MIN_REFERENCE_HORIZONTAL_M: Double = 10.0
    const val MIN_STRAIGHTNESS: Double = 0.9
    const val MAX_FIT_GAP_DEG: Double = 3.0
    const val LARGE_CHANGE_DEG: Double = 15.0
    const val BACK_BEARING_CHANGE_DEG: Double = 45.0

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

    // --- station edits and undo: each pushes one undo entry and is refused read-only ---

    /** A USER station at [tNs], named after the largest S number in use. */
    fun addStation(state: SurveyState, tNs: Long): SurveyState {
        val stations = state.doc.stations
        val added = SurveyStations.ordered(stations + SurveyStations.user(stations, tNs))
        return edit(state, state.doc.copy(stations = added))
    }

    /** Only corners and user stations move; a moved corner becomes USER so a Detail change keeps it. */
    fun moveStation(state: SurveyState, stationId: Int, tNs: Long): SurveyState {
        val station = state.doc.stations.firstOrNull { it.id == stationId } ?: return state
        if (station.kind != StationKind.CORNER && station.kind != StationKind.USER) return state
        val moved = station.copy(kind = StationKind.USER, tNs = tNs)
        val stations = state.doc.stations.map { if (it.id == stationId) moved else it }
        return edit(state, state.doc.copy(stations = SurveyStations.ordered(stations)))
    }

    /** Trimmed; a blank name is refused, since the table and the CSV name every leg by its stations. */
    fun renameStation(state: SurveyState, stationId: Int, name: String): SurveyState {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return state
        val stations = state.doc.stations.map { if (it.id == stationId) it.copy(name = trimmed) else it }
        return edit(state, state.doc.copy(stations = stations))
    }

    /** No tombstone: a deleted corner comes back only when Detail changes. */
    fun deleteStation(state: SurveyState, stationId: Int): SurveyState =
        edit(state, state.doc.copy(stations = state.doc.stations.filter { it.id != stationId }))

    /** Regenerates the corners on the shown path; new corners may reuse ids, so the selection is cleared. */
    fun setDetail(state: SurveyState, geo: SurveyGeometry, detail: Detail): SurveyState {
        if (detail == state.doc.detail) return state
        val stations = SurveyStations.regenerateCorners(state.doc.stations, geo.timeline, detail)
        val next = edit(state, state.doc.copy(detail = detail, stations = stations))
        return if (next === state) state else next.copy(selection = SurveySelection.None)
    }

    /** How many corners each Detail would give now, so the dialog shows the effect before the choice. */
    fun cornerCounts(state: SurveyState, geo: SurveyGeometry): Map<Detail, Int> =
        Detail.entries.associateWith { detail ->
            SurveyStations.regenerateCorners(state.doc.stations, geo.timeline, detail)
                .count { it.kind == StationKind.CORNER }
        }

    /** Back to the doc before the last edit; the selection is pruned against it, the cursor stays. */
    fun undo(state: SurveyState): SurveyState {
        if (state.readOnly) return state
        val previous = state.undo.lastOrNull() ?: return state
        return state.copy(doc = previous, undo = state.undo.dropLast(1), selection = prune(state.selection, previous))
    }

    // --- north edits: the facts the rotation is solved from; each is one undo entry, refused read-only ---

    /**
     * A hand-compass bearing for the selected pair or stretch. It needs a chord azimuth on the
     * uncorrected path, since a reference without one could never enter the solve.
     */
    fun addReference(
        state: SurveyState,
        geo: SurveyGeometry,
        bearingDeg: Double,
        backBearing: Boolean,
        line: ReferenceLine,
    ): SurveyState {
        val reference = newReference(state, geo, bearingDeg, backBearing, line) ?: return state
        if (Measure.leg(geo.plain, reference.fromNs, reference.toNs).azimuthDeg == null) return state
        return edit(state, state.doc.copy(references = state.doc.references + reference))
    }

    fun deleteReference(state: SurveyState, referenceId: Int): SurveyState =
        edit(state, state.doc.copy(references = state.doc.references.filter { it.id != referenceId }))

    /** Refused while references exist, because they set north; tagged with the run it was set on. */
    fun setManualRotation(state: SurveyState, rotationDeg: Double, runId: Int): SurveyState {
        if (state.doc.references.isNotEmpty() || !rotationDeg.isFinite()) return state
        val doc = state.doc.copy(manualRotationDeg = SurveyAngles.wrapDeg(rotationDeg), manualRotationRunId = runId)
        return edit(state, doc)
    }

    /** The user checked that the manual rotation fits [runId] too (a re-process can change the heading). */
    fun confirmManualRotation(state: SurveyState, runId: Int): SurveyState =
        edit(state, state.doc.copy(manualRotationRunId = runId))

    fun resetNorth(state: SurveyState): SurveyState =
        edit(state, state.doc.copy(references = emptyList(), manualRotationDeg = 0.0, manualRotationRunId = null))

    /**
     * What adding the reference would do, shown before it is added: the selection's readings now, the
     * rotation after, and a warning for each way a reading usually goes wrong. Null without a pair or
     * stretch, or for a bearing that is not a number.
     */
    fun azimuthPreview(
        state: SurveyState,
        geo: SurveyGeometry,
        bearingDeg: Double,
        backBearing: Boolean,
        line: ReferenceLine,
    ): AzimuthPreview? {
        val reference = newReference(state, geo, bearingDeg, backBearing, line) ?: return null
        val measure = Measure.stretch(geo.timeline, reference.fromNs, reference.toNs)
        val now = NorthSolver.solve(state.doc, geo.plain, geo.runId).rotationDeg
        val withReference = state.doc.copy(references = state.doc.references + reference)
        val after = NorthSolver.solve(withReference, geo.plain, geo.runId).rotationDeg
        val change = SurveyAngles.wrapDeg(after - now)
        val leg = measure.leg
        val chord = leg.azimuthDeg
        val fitted = measure.fittedAzimuthDeg
        val bent = leg.straightness?.let { it < MIN_STRAIGHTNESS } == true
        val fitGap = chord != null && fitted != null && abs(SurveyAngles.wrapDeg(chord - fitted)) > MAX_FIT_GAP_DEG
        val warnings = buildSet {
            if (leg.horizontalM < MIN_REFERENCE_HORIZONTAL_M) add(AzimuthWarning.SHORT)
            if (bent || fitGap) add(AzimuthWarning.CROOKED)
            if (abs(change) > LARGE_CHANGE_DEG) add(AzimuthWarning.LARGE_CHANGE)
            if (abs(change) > BACK_BEARING_CHANGE_DEG) add(AzimuthWarning.BACK_BEARING)
        }
        return AzimuthPreview(chord, fitted, after, change, warnings)
    }

    // --- helpers ---

    private fun seededDoc(geo: SurveyGeometry): SurveyDoc =
        SurveyDoc(stations = SurveyStations.seed(geo.timeline, geo.framed.annotations))

    private fun stationTime(state: SurveyState, stationId: Int): Long? =
        state.doc.stations.firstOrNull { it.id == stationId }?.tNs

    /**
     * The one way a doc changes: refused read-only or when nothing changed; otherwise the old doc goes
     * on the undo stack (the oldest dropped past MAX_UNDO) and the selection loses deleted stations.
     */
    private fun edit(state: SurveyState, doc: SurveyDoc): SurveyState {
        if (state.readOnly || doc == state.doc) return state
        return state.copy(
            doc = doc,
            undo = (state.undo + state.doc).takeLast(MAX_UNDO),
            selection = prune(state.selection, doc),
        )
    }

    /** Drops stations [doc] no longer has; a chain keeps its order without the same station twice in a row. */
    private fun prune(selection: SurveySelection, doc: SurveyDoc): SurveySelection {
        val ids = doc.stations.mapTo(HashSet()) { it.id }
        return when (selection) {
            SurveySelection.None -> selection
            is SurveySelection.Chain -> {
                val kept = selection.stationIds.filter { it in ids }
                val chain = kept.filterIndexed { i, id -> i == 0 || kept[i - 1] != id }
                when {
                    chain.isEmpty() -> SurveySelection.None
                    chain == selection.stationIds -> selection
                    else -> SurveySelection.Chain(chain)
                }
            }
            is SurveySelection.Stretch -> {
                val ends = listOfNotNull(selection.fromId, selection.toId)
                if (ends.all { it in ids }) selection else SurveySelection.None
            }
        }
    }

    /** The reference a bearing on the current selection would add; null without a pair or a finite bearing. */
    private fun newReference(
        state: SurveyState,
        geo: SurveyGeometry,
        bearingDeg: Double,
        backBearing: Boolean,
        line: ReferenceLine,
    ): CompassReference? {
        if (!bearingDeg.isFinite()) return null
        val (fromNs, toNs) = selectionEnds(state, geo) ?: return null
        return CompassReference(
            id = (state.doc.references.maxOfOrNull { it.id } ?: 0) + 1,
            fromNs = fromNs,
            toNs = toNs,
            bearingDeg = SurveyAngles.to360(bearingDeg),
            backBearing = backBearing,
            line = line,
        )
    }
}
