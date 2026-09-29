package com.stastyle.imumapper.render

import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.survey.PathTimeline
import com.stastyle.imumapper.pipeline.survey.Station
import com.stastyle.imumapper.pipeline.survey.StationKind
import kotlin.math.abs

/**
 * ARGB colours of the survey layer. START and END are the scene's own, so a station reads as the
 * marker it replaces in Survey mode.
 */
object SurveyColors {
    val START: Int = SceneColors.START
    val END: Int = SceneColors.END
    val MARK: Int = SceneColors.argb(255, 255, 202, 40)
    val CORNER: Int = SceneColors.argb(255, 207, 216, 220)
    val USER: Int = SceneColors.argb(255, 38, 198, 218)
    val CHORD: Int = SceneColors.argb(255, 255, 255, 255)
    val STRETCH: Int = SceneColors.argb(200, 255, 235, 59)
    val CURSOR: Int = SceneColors.argb(255, 255, 64, 129)
    val LABEL: Int = SceneColors.argb(230, 255, 255, 255)

    fun forKind(kind: StationKind): Int = when (kind) {
        StationKind.START -> START
        StationKind.END -> END
        StationKind.MARK -> MARK
        StationKind.CORNER -> CORNER
        StationKind.USER -> USER
    }
}

/** A station as drawn. [order] is its 1-based place in the chain (last occurrence), 0 when not in it. */
data class LayerStation(
    val id: Int,
    val position: Vec3,
    val name: String,
    val color: Int,
    val selected: Boolean,
    val order: Int,
    /** What is written by and on its spot; null when an earlier station on the same spot carries it. */
    val text: SpotText?,
)

/**
 * The text of the stations drawn on one spot. Usually that is one station, but End sits on Start after
 * a loop closed with "Back at start", and a mark made before the first step or after the last sits on
 * Start or End; their names and numbers would print over each other, so the first of them carries
 * everything.
 */
data class SpotText(
    /** Their names in traverse order, joined with " / ". */
    val names: String,
    /** Their places in the chain, ascending and joined with "/"; null when none is in the chain. */
    val order: String?,
    /** Some station on the spot is ringed, so drawn larger, and the names start past that ring. */
    val selected: Boolean,
)

/** The selection in render terms, so render does not depend on ui.viewer. */
data class LayerSelection(
    /** Chain stations in tap order; chords join consecutive ones. */
    val chainIds: List<Int> = emptyList(),
    /** Stretch ends as times: the highlighted path and one chord; null without a stretch. */
    val stretchNs: Pair<Long, Long>? = null,
    /** Stations ringed as the stretch's ends. */
    val stretchIds: List<Int> = emptyList(),
)

/**
 * Survey mode's overlay in world coordinates, drawn over the path scene with the same camera. Survey
 * edits rebuild only this, never the scene. Flat arrays like SceneModel.
 */
class SurveyLayer(
    /** In the order given to [build], which is the traverse order the doc keeps. */
    val stations: List<LayerStation>,
    val chordCount: Int,
    /** 6 doubles per chord. */
    val chordCoords: DoubleArray,
    val stretchCount: Int,
    /** 3 doubles per vertex of the highlighted stretch. */
    val stretchCoords: DoubleArray,
    val cursor: Vec3?,
    val pathCount: Int,
    /** 3 doubles per vertex of the decimated path used for hit tests. */
    val pathCoords: DoubleArray,
    /** Distance along the path of each hit-test vertex; a hit maps to a time through PathTimeline.timeAtDistance. */
    val pathDistances: DoubleArray,
) {
    companion object {
        /** Enough for a smooth hit anywhere on a long trip while a re-projection stays cheap. */
        const val MAX_PATH_VERTICES: Int = 4000
        const val MAX_STRETCH_VERTICES: Int = 2000

        /**
         * Stations this close are drawn on one spot at any zoom and share one [SpotText]. It covers the
         * rounding that leaves End a hair off Start after a loop closure.
         */
        const val SAME_SPOT_M: Double = 0.01

        fun build(
            timeline: PathTimeline,
            stations: List<Station>,
            selection: LayerSelection,
            cursorNs: Long?,
        ): SurveyLayer {
            val known = stations.mapTo(HashSet()) { it.id }
            // The controller prunes the selection after every edit; this only guards a stale id.
            val chain = selection.chainIds.filter { it in known }
            // Later entries overwrite earlier ones, so a station tapped twice shows its last place.
            val order = HashMap<Int, Int>()
            chain.forEachIndexed { k, id -> order[id] = k + 1 }
            val ringed = order.keys + selection.stretchIds

            val positions = stations.map { timeline.positionAt(it.tNs) }
            // Keyed by the first station on each spot, with every station on it in traverse order.
            val lead = spotLeads(positions)
            val spots = stations.indices.groupBy({ lead[it] }, { stations[it] })
            val layerStations = stations.mapIndexed { i, s ->
                LayerStation(
                    id = s.id,
                    position = positions[i],
                    name = s.name,
                    color = SurveyColors.forKind(s.kind),
                    selected = s.id in ringed,
                    order = order[s.id] ?: 0,
                    text = spots[i]?.let { spot ->
                        val places = spot.mapNotNull { order[it.id] }.sorted()
                        SpotText(
                            names = spot.joinToString(" / ") { it.name },
                            order = if (places.isEmpty()) null else places.joinToString("/"),
                            selected = spot.any { it.id in ringed },
                        )
                    },
                )
            }
            val positionOf = layerStations.associate { it.id to it.position }

            val chordEnds = ArrayList<Vec3>()
            for (k in 0 until chain.size - 1) {
                chordEnds += positionOf.getValue(chain[k])
                chordEnds += positionOf.getValue(chain[k + 1])
            }
            var stretch: List<Vec3> = emptyList()
            val span = selection.stretchNs
            if (span != null) {
                val (fromNs, toNs) = span
                chordEnds += timeline.positionAt(fromNs)
                chordEnds += timeline.positionAt(toNs)
                val samples = timeline.samplesBetween(fromNs, toNs)
                stretch = PathScene.decimate(samples.size, MAX_STRETCH_VERTICES).map { samples[it] }
            }

            val points = timeline.points
            val keep = PathScene.decimate(points.size, MAX_PATH_VERTICES)
            val pathDistances = DoubleArray(keep.size) { k -> timeline.distanceOfPoint(keep[k]) }

            return SurveyLayer(
                stations = layerStations,
                chordCount = chordEnds.size / 2,
                chordCoords = flatten(chordEnds),
                stretchCount = stretch.size,
                stretchCoords = flatten(stretch),
                cursor = cursorNs?.let { timeline.positionAt(it) },
                pathCount = keep.size,
                pathCoords = flatten(keep.map { points[it].p }),
                pathDistances = pathDistances,
            )
        }

        /**
         * For each station, the index of the first station within [SAME_SPOT_M] of it on every axis, or
         * its own. Quadratic, but a layer holds hundreds of stations at most and the test is a few
         * subtractions.
         */
        private fun spotLeads(positions: List<Vec3>): IntArray {
            val lead = IntArray(positions.size) { it }
            for (i in positions.indices) {
                if (lead[i] != i) continue
                val p = positions[i]
                for (j in i + 1 until positions.size) {
                    if (lead[j] != j) continue
                    val q = positions[j]
                    if (abs(q.x - p.x) < SAME_SPOT_M && abs(q.y - p.y) < SAME_SPOT_M && abs(q.z - p.z) < SAME_SPOT_M) {
                        lead[j] = i
                    }
                }
            }
            return lead
        }

        private fun flatten(vertices: List<Vec3>): DoubleArray {
            val out = DoubleArray(vertices.size * 3)
            for (i in vertices.indices) {
                val v = vertices[i]
                out[i * 3] = v.x
                out[i * 3 + 1] = v.y
                out[i * 3 + 2] = v.z
            }
            return out
        }
    }
}
