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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProjectedSurveyTest {
    private val width = 1080f
    private val height = 1920f

    private val walk = SurveyFixtures.lWalk()
    private val timeline = PathTimeline(walk.points)
    private val stations = listOf(
        Station(id = 1, kind = StationKind.START, name = "Start", tNs = tNs(0)),
        Station(id = 2, kind = StationKind.MARK, name = "Junction 1", tNs = tNs(10)),
        Station(id = 4, kind = StationKind.CORNER, name = "C1", tNs = tNs(20)),
        Station(id = 3, kind = StationKind.END, name = "End", tNs = tNs(30)),
    )

    /**
     * The plan view Survey mode opens with: north up, about 86.5 px per metre, so the 5 x 10 m L spans
     * roughly 433 x 865 px around the centre and stations are at least 216 px apart.
     */
    private val camera = OrbitCamera().withPreset(CameraPreset.TOP, Bounds.of(walk.points.map { it.p }), width, height)
    private val projector = Projector(camera, width, height)

    private fun projected(selection: LayerSelection = LayerSelection(), cursorNs: Long? = null): ProjectedSurvey {
        val p = ProjectedSurvey(SurveyLayer.build(timeline, stations, selection, cursorNs))
        assertTrue(p.update(camera, width, height))
        return p
    }

    private fun screenOf(v: Vec3): ProjectedPoint = assertNotNull(projector.project(v))

    @Test
    fun stationsProjectWhereTheProjectorPutsThem() {
        val p = projected()
        for (i in stations.indices) {
            val s = screenOf(p.layer.stations[i].position)
            assertTrue(p.stationVisible[i])
            assertEquals(s.x, p.stationScreen[i * 2], 1e-3f)
            assertEquals(s.y, p.stationScreen[i * 2 + 1], 1e-3f)
        }
        // North up, east right: C1 is above the start and the end is right of C1.
        assertTrue(p.stationScreen[2 * 2 + 1] < p.stationScreen[0 * 2 + 1])
        assertTrue(p.stationScreen[3 * 2] > p.stationScreen[2 * 2])
    }

    @Test
    fun chordsStretchAndCursorAreProjected() {
        val chain = projected(LayerSelection(chainIds = listOf(1, 4)), cursorNs = tNs(25))
        val start = screenOf(Vec3.ZERO)
        val corner = screenOf(Vec3(0.0, 10.0, 0.0))
        assertTrue(chain.chordVisible[0])
        assertEquals(start.x, chain.chordScreen[0], 1e-3f)
        assertEquals(start.y, chain.chordScreen[1], 1e-3f)
        assertEquals(corner.x, chain.chordScreen[2], 1e-3f)
        assertEquals(corner.y, chain.chordScreen[3], 1e-3f)
        val cursor = screenOf(Vec3(2.5, 10.0, 0.0))
        assertTrue(chain.cursorVisible)
        assertEquals(cursor.x, chain.cursorScreen[0], 1e-3f)
        assertEquals(cursor.y, chain.cursorScreen[1], 1e-3f)

        val stretch = projected(LayerSelection(stretchNs = tNs(10) to tNs(20), stretchIds = listOf(2, 4)))
        assertEquals(11, stretch.stretchVisible.count { it })
        val middle = screenOf(Vec3(0.0, 7.5, 0.0))
        assertEquals(middle.x, stretch.stretchScreen[5 * 2], 1e-3f)
        assertEquals(middle.y, stretch.stretchScreen[5 * 2 + 1], 1e-3f)
        assertFalse(stretch.cursorVisible)
    }

    @Test
    fun tapOnAStationWinsOverThePathUnderIt() {
        val p = projected()
        val corner = screenOf(Vec3(0.0, 10.0, 0.0))
        // The path passes under C1 at 10 m, yet the station is what the tap selects.
        assertEquals(10.0, p.hitTestPath(corner.x, corner.y, ProjectedSurvey.PATH_HIT_DP), 1e-3)
        assertEquals(SurveyHit.OnStation(4), p.hitTest(corner.x, corner.y, density = 1f))
        // 20 px east of C1 is on the east leg itself, but still within the 24 px station radius.
        assertEquals(SurveyHit.OnStation(4), p.hitTest(corner.x + 20f, corner.y, density = 1f))
    }

    @Test
    fun tapBesideThePathReturnsTheDistanceAlongIt() {
        val p = projected()
        val north = screenOf(Vec3(0.0, 2.5, 0.0))
        val onNorthLeg = assertIs<SurveyHit.OnPath>(p.hitTest(north.x - 10f, north.y, density = 1f))
        assertEquals(2.5, onNorthLeg.distanceM, 0.1)
        // Vertices are 0.5 m apart, so only interpolating along the 2.0..2.5 m segment (f = 0.6) gives 2.3 m;
        // snapping to a vertex, or an unnormalised f clamped to 1, would give 2.5 m.
        val between = screenOf(Vec3(0.0, 2.3, 0.0))
        val midSegment = assertIs<SurveyHit.OnPath>(p.hitTest(between.x - 10f, between.y, density = 1f))
        assertEquals(2.3, midSegment.distanceM, 1e-3)
        // Half-way along the east leg: 10 m north, then 2.5 m east.
        val east = screenOf(Vec3(2.5, 10.0, 0.0))
        val onEastLeg = assertIs<SurveyHit.OnPath>(p.hitTest(east.x, east.y + 10f, density = 1f))
        assertEquals(12.5, onEastLeg.distanceM, 0.1)
    }

    @Test
    fun farTapMisses() {
        val start = screenOf(Vec3.ZERO)
        assertEquals(SurveyHit.Miss, projected().hitTest(start.x + 500f, start.y, density = 1f))
    }

    @Test
    fun hitRadiiScaleWithDensity() {
        val p = projected()
        val corner = screenOf(Vec3(0.0, 10.0, 0.0))
        // 30 px west of C1: past the 24 px station radius at density 1, inside the 32 px path radius.
        val onPath = assertIs<SurveyHit.OnPath>(p.hitTest(corner.x - 30f, corner.y, density = 1f))
        assertEquals(10.0, onPath.distanceM, 0.1)
        assertEquals(SurveyHit.OnStation(4), p.hitTest(corner.x - 30f, corner.y, density = 2f))
    }

    @Test
    fun stationsOnOneSpotAreAllHitInTraverseOrder() {
        // A loop closed with "Back at start" ends exactly on the origin, so End is drawn on Start. A mark
        // made just before Stop is clamped onto the end and sorts before End by its lower id.
        val loop = listOf(Vec3.ZERO, Vec3(0.0, 10.0, 0.0), Vec3(10.0, 10.0, 0.0), Vec3(10.0, 0.0, 0.0), Vec3.ZERO)
            .mapIndexed { i, p -> PathPoint(tNs(i), p, PositionSource.PDR, 0.0, i - 1) }
        val onLoop = listOf(
            Station(id = 1, kind = StationKind.START, name = "Start", tNs = tNs(0)),
            Station(id = 4, kind = StationKind.CORNER, name = "C1", tNs = tNs(1)),
            Station(id = 2, kind = StationKind.MARK, name = "Exit", tNs = tNs(4)),
            Station(id = 3, kind = StationKind.END, name = "End", tNs = tNs(4)),
        )
        val loopCamera = OrbitCamera().withPreset(CameraPreset.TOP, Bounds.of(loop.map { it.p }), width, height)
        val loopProjector = Projector(loopCamera, width, height)
        val p = ProjectedSurvey(SurveyLayer.build(PathTimeline(loop), onLoop, LayerSelection(), null))
        assertTrue(p.update(loopCamera, width, height))
        val origin = assertNotNull(loopProjector.project(Vec3.ZERO))

        assertEquals(listOf(0, 2, 3), p.hitTestStations(origin.x + 5f, origin.y, 24f, sameSpotPx = 1f))
        assertEquals(SurveyHit.OnStations(listOf(1, 2, 3)), p.hitTest(origin.x + 5f, origin.y, density = 1f))
        // A station alone on its spot is still a plain hit.
        val corner = assertNotNull(loopProjector.project(Vec3(0.0, 10.0, 0.0)))
        assertEquals(SurveyHit.OnStation(4), p.hitTest(corner.x, corner.y, density = 1f))
        assertEquals(emptyList<Int>(), p.hitTestStations(origin.x + 500f, origin.y, 24f, sameSpotPx = 1f))
    }

    @Test
    fun stationsCloseButApartOnScreenAreNotOneSpot() {
        // A radius wide enough to reach every station still hits only the nearest when none share its spot.
        val p = projected()
        val corner = screenOf(Vec3(0.0, 10.0, 0.0))
        assertEquals(listOf(2), p.hitTestStations(corner.x, corner.y, 1000f, sameSpotPx = 1f))
    }

    @Test
    fun updateReprojectsOnlyWhenTheCameraOrViewportChanges() {
        val p = ProjectedSurvey(SurveyLayer.build(timeline, stations, LayerSelection(), null))
        val start = screenOf(Vec3.ZERO)
        // Nothing is on screen before the first projection.
        assertEquals(SurveyHit.Miss, p.hitTest(start.x, start.y, density = 1f))
        assertTrue(p.update(camera, width, height))
        assertFalse(p.update(camera, width, height))
        val before = p.stationScreen[0]
        val panned = camera.panned(50f, 0f, height)
        assertTrue(p.update(panned, width, height))
        // The scene follows the finger: 50 px right.
        assertEquals(before + 50f, p.stationScreen[0], 0.01f)
        assertTrue(p.update(panned, width, 1000f))
    }
}
