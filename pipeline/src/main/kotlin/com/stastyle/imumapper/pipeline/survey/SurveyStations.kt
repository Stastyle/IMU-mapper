package com.stastyle.imumapper.pipeline.survey

import com.stastyle.imumapper.pipeline.core.AnnotationKind
import com.stastyle.imumapper.pipeline.core.PathAnnotation

/** Builds, names and orders the stations of a survey. */
object SurveyStations {

    /** Annotation kinds that become MARK stations; LOOP_CLOSED and REORIENT do not. */
    val MARK_KINDS: Set<AnnotationKind> =
        setOf(AnnotationKind.WAYPOINT, AnnotationKind.JUNCTION, AnnotationKind.CHAMBER, AnnotationKind.NOTE)

    private val USER_NAME = Regex("S(\\d+)")

    /** The traverse order: by tNs, then by id. */
    fun ordered(stations: List<Station>): List<Station> = stations.sortedWith(compareBy({ it.tNs }, { it.id }))

    /**
     * First-open stations: START "Start" (id 1) at startNs, one MARK per MARK_KINDS annotation in time
     * order (ids 2, 3, ...) at its time clamped to the path, END "End" at endNs (next id), then the CORNER
     * stations of [detail] with every other station kept (next ids, named C1, C2, ... in time order).
     * Returned in traverse order. A mark can fall outside the path: PDR ends at the last step, so a mark
     * made while standing before STOP is later, and VIO starts at the first tracking frame. The walker
     * stood at that end, and by the id order a clamped mark stays after START and before END.
     */
    fun seed(
        timeline: PathTimeline,
        annotations: List<PathAnnotation>,
        detail: Detail = Detail.NORMAL,
    ): List<Station> {
        val out = ArrayList<Station>()
        out.add(Station(1, StationKind.START, "Start", timeline.startNs))
        val ordinals = HashMap<AnnotationKind, Int>()
        for (a in annotations.filter { it.kind in MARK_KINDS }.sortedBy { it.tNs }) {
            val ordinal = (ordinals[a.kind] ?: 0) + 1
            ordinals[a.kind] = ordinal
            val tNs = a.tNs.coerceIn(timeline.startNs, timeline.endNs)
            out.add(Station(out.size + 1, StationKind.MARK, markName(a.kind, a.note, ordinal), tNs))
        }
        out.add(Station(out.size + 1, StationKind.END, "End", timeline.endNs))
        return regenerateCorners(out, timeline, detail)
    }

    /** The trimmed note, or the kind in title case and its 1-based [ordinal] among that kind ("Junction 2"). */
    fun markName(kind: AnnotationKind, note: String, ordinal: Int): String =
        note.trim().ifEmpty { kind.name.lowercase().replaceFirstChar { it.uppercase() } + " " + ordinal }

    /**
     * Removes every CORNER, detects corners for [detail] keeping all other stations as spacing anchors,
     * names them C1.. in time order, skipping names already in use, ids from nextId of the kept stations.
     * A moved corner is a USER station that kept its name, and the legs, the CSV and the copied line name
     * stations only by name, so a new corner must not take it again.
     */
    fun regenerateCorners(stations: List<Station>, timeline: PathTimeline, detail: Detail): List<Station> {
        val kept = stations.filter { it.kind != StationKind.CORNER }
        val firstId = nextId(kept)
        val taken = kept.mapTo(HashSet()) { it.name }
        var n = 0
        val corners = CornerDetector.corners(timeline, detail, kept.map { it.tNs }).mapIndexed { k, tNs ->
            do {
                n++
            } while ("C$n" in taken)
            Station(firstId + k, StationKind.CORNER, "C$n", tNs)
        }
        return ordered(kept + corners)
    }

    /** max(id) + 1, or 1. */
    fun nextId(stations: List<Station>): Int = (stations.maxOfOrNull { it.id } ?: 0) + 1

    /** "S" + (1 + the largest n of any name "S<n>"), "S1" first. */
    fun nextUserName(stations: List<Station>): String {
        val largest = stations.mapNotNull { USER_NAME.matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 0
        return "S${largest + 1}"
    }

    /** A new USER station at [tNs] with nextId and nextUserName. */
    fun user(stations: List<Station>, tNs: Long): Station =
        Station(nextId(stations), StationKind.USER, nextUserName(stations), tNs)
}
