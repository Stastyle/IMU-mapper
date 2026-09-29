package com.stastyle.imumapper.render

/** What a survey tap or long press landed on. Stations lie on the path, so a station always wins. */
sealed interface SurveyHit {
    data class OnStation(val stationId: Int) : SurveyHit

    /**
     * Two or more stations drawn on one spot, in traverse order: a loop closed with "Back at start"
     * puts End on Start, and a mark made before the first step or after the last sits on Start or End.
     * Nearest-wins would always pick the first of them, so each gets its own way in.
     */
    data class OnStations(val stationIds: List<Int>) : SurveyHit

    data class OnPath(val distanceM: Double) : SurveyHit
    data object Miss : SurveyHit
}

/**
 * Screen-space copy of a SurveyLayer, refilled on each camera or viewport change (like ProjectedScene).
 * Buffers are allocated once per layer; a survey edit builds a new layer and so a new projection, while
 * a camera move only refills these arrays.
 */
class ProjectedSurvey(val layer: SurveyLayer) {
    /** 2 floats per station. */
    val stationScreen = FloatArray(layer.stations.size * 2)
    val stationVisible = BooleanArray(layer.stations.size)

    /** 4 floats per chord: ax, ay, bx, by. */
    val chordScreen = FloatArray(layer.chordCount * 4)

    /** Both ends in front of the near plane. Chords span a few stations, so they are dropped rather than clipped. */
    val chordVisible = BooleanArray(layer.chordCount)

    /** 2 floats per stretch vertex. */
    val stretchScreen = FloatArray(layer.stretchCount * 2)
    val stretchVisible = BooleanArray(layer.stretchCount)

    val cursorScreen = FloatArray(2)
    var cursorVisible: Boolean = false
        private set

    /** 2 floats per hit-test vertex. */
    val pathScreen = FloatArray(layer.pathCount * 2)
    val pathVisible = BooleanArray(layer.pathCount)

    private var lastCamera: OrbitCamera? = null
    private var lastWidth = -1f
    private var lastHeight = -1f
    private val tmp = FloatArray(3)

    /** Re-projects when camera or viewport changed; true when work was done. */
    fun update(camera: OrbitCamera, widthPx: Float, heightPx: Float): Boolean {
        if (camera == lastCamera && widthPx == lastWidth && heightPx == lastHeight) return false
        lastCamera = camera
        lastWidth = widthPx
        lastHeight = heightPx
        val projector = Projector(camera, widthPx, heightPx)

        for (i in layer.stations.indices) {
            val p = layer.stations[i].position
            stationVisible[i] = project(projector, p.x, p.y, p.z, stationScreen, i * 2)
        }
        val cc = layer.chordCoords
        for (i in 0 until layer.chordCount) {
            val o = i * 6
            chordVisible[i] = project(projector, cc[o], cc[o + 1], cc[o + 2], chordScreen, i * 4) &&
                project(projector, cc[o + 3], cc[o + 4], cc[o + 5], chordScreen, i * 4 + 2)
        }
        projectVertices(projector, layer.stretchCoords, layer.stretchCount, stretchScreen, stretchVisible)
        projectVertices(projector, layer.pathCoords, layer.pathCount, pathScreen, pathVisible)
        val c = layer.cursor
        cursorVisible = c != null && project(projector, c.x, c.y, c.z, cursorScreen, 0)
        return true
    }

    /** Index into layer.stations nearest on screen within [radiusPx], or -1. */
    fun hitTestStation(xPx: Float, yPx: Float, radiusPx: Float): Int {
        val r2 = radiusPx * radiusPx
        var best = -1
        var bestD2 = Float.POSITIVE_INFINITY
        for (i in stationVisible.indices) {
            if (!stationVisible[i]) continue
            val dx = stationScreen[i * 2] - xPx
            val dy = stationScreen[i * 2 + 1] - yPx
            val d2 = dx * dx + dy * dy
            if (d2 <= r2 && d2 < bestD2) {
                bestD2 = d2
                best = i
            }
        }
        return best
    }

    /**
     * Indices into layer.stations of the nearest station on screen within [radiusPx] and every other
     * visible station within [sameSpotPx] of it, in traverse order; empty when none is in reach.
     */
    fun hitTestStations(xPx: Float, yPx: Float, radiusPx: Float, sameSpotPx: Float): List<Int> {
        val nearest = hitTestStation(xPx, yPx, radiusPx)
        if (nearest < 0) return emptyList()
        val nx = stationScreen[nearest * 2]
        val ny = stationScreen[nearest * 2 + 1]
        val s2 = sameSpotPx * sameSpotPx
        return stationVisible.indices.filter { i ->
            if (!stationVisible[i]) return@filter false
            val dx = stationScreen[i * 2] - nx
            val dy = stationScreen[i * 2 + 1] - ny
            dx * dx + dy * dy <= s2
        }
    }

    /** Distance along the path of the nearest point on a visible screen segment within [radiusPx], or -1.0. */
    fun hitTestPath(xPx: Float, yPx: Float, radiusPx: Float): Double {
        val r2 = radiusPx * radiusPx
        var best = -1.0
        var bestD2 = Float.POSITIVE_INFINITY
        for (k in 0 until layer.pathCount - 1) {
            if (!pathVisible[k] || !pathVisible[k + 1]) continue
            val ax = pathScreen[k * 2]
            val ay = pathScreen[k * 2 + 1]
            val abx = pathScreen[k * 2 + 2] - ax
            val aby = pathScreen[k * 2 + 3] - ay
            val len2 = abx * abx + aby * aby
            // A segment that is a dot on screen (standing still, or seen end-on) is hit at its start.
            val f = if (len2 > 0f) (((xPx - ax) * abx + (yPx - ay) * aby) / len2).coerceIn(0f, 1f) else 0f
            val dx = ax + abx * f - xPx
            val dy = ay + aby * f - yPx
            val d2 = dx * dx + dy * dy
            if (d2 <= r2 && d2 < bestD2) {
                bestD2 = d2
                val from = layer.pathDistances[k]
                best = from + (layer.pathDistances[k + 1] - from) * f
            }
        }
        return best
    }

    /**
     * Station within STATION_HIT_DP (OnStations when others are drawn on the same spot), else path
     * within PATH_HIT_DP, else Miss; radii scaled by [density].
     */
    fun hitTest(xPx: Float, yPx: Float, density: Float): SurveyHit {
        val stations = hitTestStations(xPx, yPx, STATION_HIT_DP * density, SAME_SPOT_DP * density)
        if (stations.size == 1) return SurveyHit.OnStation(layer.stations[stations[0]].id)
        if (stations.size > 1) return SurveyHit.OnStations(stations.map { layer.stations[it].id })
        val distance = hitTestPath(xPx, yPx, PATH_HIT_DP * density)
        return if (distance >= 0.0) SurveyHit.OnPath(distance) else SurveyHit.Miss
    }

    /** Projector writes x, y and depth; the buffers here keep only x and y, so it goes through [tmp]. */
    private fun project(projector: Projector, x: Double, y: Double, z: Double, out: FloatArray, offset: Int): Boolean {
        if (!projector.project(x, y, z, tmp, 0)) return false
        out[offset] = tmp[0]
        out[offset + 1] = tmp[1]
        return true
    }

    private fun projectVertices(
        projector: Projector,
        coords: DoubleArray,
        count: Int,
        screen: FloatArray,
        visible: BooleanArray,
    ) {
        for (i in 0 until count) {
            val o = i * 3
            visible[i] = project(projector, coords[o], coords[o + 1], coords[o + 2], screen, i * 2)
        }
    }

    companion object {
        /** Same as PathRenderer.HIT_RADIUS_DP, repeated because this file stays free of Compose. */
        const val STATION_HIT_DP: Float = 24f

        /** Wider than a station's so a tap need not land on a thin line. */
        const val PATH_HIT_DP: Float = 32f

        /**
         * Stations this close on screen are one spot to the finger. Coinciding stations are exactly on
         * top of each other at every zoom; ones merely close come apart when the user zooms in.
         */
        const val SAME_SPOT_DP: Float = 1f
    }
}
