package com.stastyle.imumapper.render

import com.stastyle.imumapper.SurveyFixtures
import com.stastyle.imumapper.SurveyFixtures.tNs
import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.PositionSource
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.survey.PathTimeline
import com.stastyle.imumapper.pipeline.survey.Station
import com.stastyle.imumapper.pipeline.survey.StationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SurveyLayerTest {

    private val timeline = PathTimeline(SurveyFixtures.lWalk().points)

    /**
     * The L walk's seeded stations in traverse order, plus a user station half-way through the sixth
     * step: every step takes 0.5 s, so placement is linear and S1 sits at (0, 2.75).
     */
    private val stations = listOf(
        Station(id = 1, kind = StationKind.START, name = "Start", tNs = tNs(0)),
        Station(id = 5, kind = StationKind.USER, name = "S1", tNs = tNs(5) + SurveyFixtures.STEP_NS / 2),
        Station(id = 2, kind = StationKind.MARK, name = "Junction 1", tNs = tNs(10)),
        Station(id = 4, kind = StationKind.CORNER, name = "C1", tNs = tNs(20)),
        Station(id = 3, kind = StationKind.END, name = "End", tNs = tNs(30)),
    )

    private fun build(selection: LayerSelection = LayerSelection(), cursorNs: Long? = null): SurveyLayer =
        SurveyLayer.build(timeline, stations, selection, cursorNs)

    private fun stationOf(layer: SurveyLayer, id: Int): LayerStation = layer.stations.single { it.id == id }

    /** Vertex [i] of a flat array with 3 doubles per vertex (a chord is two vertices). */
    private fun vertex(coords: DoubleArray, i: Int): Vec3 = Vec3(coords[i * 3], coords[i * 3 + 1], coords[i * 3 + 2])

    private fun assertNear(expected: Vec3, actual: Vec3?, message: String = "") {
        val v = assertNotNull(actual, message)
        assertEquals(expected.x, v.x, 1e-9, "$message x")
        assertEquals(expected.y, v.y, 1e-9, "$message y")
        assertEquals(expected.z, v.z, 1e-9, "$message z")
    }

    @Test
    fun stationsSitOnThePathAtTheirTimesInTheirKindsColours() {
        val layer = build()
        assertEquals(listOf(1, 5, 2, 4, 3), layer.stations.map { it.id })
        assertEquals(listOf("Start", "S1", "Junction 1", "C1", "End"), layer.stations.map { it.name })
        val expected = listOf(
            Vec3(0.0, 0.0, 0.0),
            Vec3(0.0, 2.75, 0.0),
            Vec3(0.0, 5.0, 0.0),
            Vec3(0.0, 10.0, 0.0),
            Vec3(5.0, 10.0, 0.0),
        )
        for (i in expected.indices) {
            assertNear(expected[i], layer.stations[i].position, "station ${layer.stations[i].id}")
        }
        assertEquals(
            listOf(SurveyColors.START, SurveyColors.USER, SurveyColors.MARK, SurveyColors.CORNER, SurveyColors.END),
            layer.stations.map { it.color },
        )
        assertTrue(layer.stations.none { it.selected || it.order != 0 })
        assertEquals(0, layer.chordCount)
        assertEquals(0, layer.stretchCount)
        assertNull(layer.cursor)
    }

    @Test
    fun chainNumbersItsStationsAndJoinsThemWithChords() {
        val layer = build(LayerSelection(chainIds = listOf(1, 4)))
        assertEquals(1, stationOf(layer, 1).order)
        assertEquals(2, stationOf(layer, 4).order)
        assertEquals(setOf(1, 4), layer.stations.filter { it.selected }.map { it.id }.toSet())
        assertTrue(layer.stations.filter { it.id != 1 && it.id != 4 }.all { it.order == 0 })
        assertEquals(1, layer.chordCount)
        assertEquals(6, layer.chordCoords.size)
        assertNear(Vec3(0.0, 0.0, 0.0), vertex(layer.chordCoords, 0))
        assertNear(Vec3(0.0, 10.0, 0.0), vertex(layer.chordCoords, 1))
        assertEquals(0, layer.stretchCount)
    }

    @Test
    fun aStationTappedAgainShowsItsLastPlaceInTheChain() {
        val layer = build(LayerSelection(chainIds = listOf(1, 4, 1)))
        assertEquals(3, stationOf(layer, 1).order)
        assertEquals(2, stationOf(layer, 4).order)
        assertEquals(2, layer.chordCount)
        assertNear(Vec3(0.0, 10.0, 0.0), vertex(layer.chordCoords, 2))
        assertNear(Vec3(0.0, 0.0, 0.0), vertex(layer.chordCoords, 3))
    }

    @Test
    fun unknownChainIdsAreSkipped() {
        val layer = build(LayerSelection(chainIds = listOf(1, 99, 4)))
        assertEquals(1, stationOf(layer, 1).order)
        assertEquals(2, stationOf(layer, 4).order)
        assertEquals(1, layer.chordCount)
        assertNear(Vec3(0.0, 0.0, 0.0), vertex(layer.chordCoords, 0))
        assertNear(Vec3(0.0, 10.0, 0.0), vertex(layer.chordCoords, 1))
    }

    @Test
    fun stretchHighlightsThePathBetweenItsEndsAndRingsThem() {
        val layer = build(LayerSelection(stretchNs = tNs(10) to tNs(20), stretchIds = listOf(2, 4)))
        // The two ends plus the nine step points strictly between them, every 0.5 m north.
        assertEquals(11, layer.stretchCount)
        assertEquals(33, layer.stretchCoords.size)
        for (k in 0 until 11) assertNear(Vec3(0.0, 5.0 + 0.5 * k, 0.0), vertex(layer.stretchCoords, k), "vertex $k")
        assertEquals(1, layer.chordCount)
        assertNear(Vec3(0.0, 5.0, 0.0), vertex(layer.chordCoords, 0))
        assertNear(Vec3(0.0, 10.0, 0.0), vertex(layer.chordCoords, 1))
        assertEquals(setOf(2, 4), layer.stations.filter { it.selected }.map { it.id }.toSet())
        assertTrue(layer.stations.all { it.order == 0 })
    }

    @Test
    fun cursorIsPlacedAtItsMoment() {
        assertNear(Vec3(2.5, 10.0, 0.0), build(cursorNs = tNs(25)).cursor)
        assertNull(build(cursorNs = null).cursor)
    }

    @Test
    fun hitTestPathKeepsEveryPointOfAShortWalkWithItsDistance() {
        val layer = build()
        assertEquals(31, layer.pathCount)
        assertEquals(93, layer.pathCoords.size)
        assertEquals(31, layer.pathDistances.size)
        for (i in 0..30) assertEquals(0.5 * i, layer.pathDistances[i], 1e-9, "distance of point $i")
        assertNear(Vec3(0.0, 10.0, 0.0), vertex(layer.pathCoords, 20))
        assertNear(Vec3(5.0, 10.0, 0.0), vertex(layer.pathCoords, 30))
    }

    @Test
    fun longPathIsDecimatedKeepingBothEnds() {
        val points = (0 until 10_000).map { i ->
            PathPoint(tNs(i), Vec3(0.0, 0.5 * i, 0.0), PositionSource.PDR, 0.0, i - 1)
        }
        val whole = LayerSelection(stretchNs = points.first().tNs to points.last().tNs)
        val layer = SurveyLayer.build(PathTimeline(points), emptyList(), whole, null)

        assertEquals(SurveyLayer.MAX_PATH_VERTICES, layer.pathCount)
        assertEquals(0.0, layer.pathDistances.first(), 1e-9)
        assertEquals(4999.5, layer.pathDistances.last(), 1e-9)
        assertNear(Vec3(0.0, 4999.5, 0.0), vertex(layer.pathCoords, layer.pathCount - 1))
        for (k in 1 until layer.pathCount) assertTrue(layer.pathDistances[k] > layer.pathDistances[k - 1])

        assertEquals(SurveyLayer.MAX_STRETCH_VERTICES, layer.stretchCount)
        assertNear(Vec3.ZERO, vertex(layer.stretchCoords, 0))
        assertNear(Vec3(0.0, 4999.5, 0.0), vertex(layer.stretchCoords, layer.stretchCount - 1))
    }
}
