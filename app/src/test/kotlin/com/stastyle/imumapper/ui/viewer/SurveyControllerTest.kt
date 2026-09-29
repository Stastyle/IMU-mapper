package com.stastyle.imumapper.ui.viewer

import com.stastyle.imumapper.SurveyFixtures
import com.stastyle.imumapper.data.SurveyLoad
import com.stastyle.imumapper.pipeline.survey.Station
import com.stastyle.imumapper.pipeline.survey.StationKind
import com.stastyle.imumapper.pipeline.survey.SurveyAngles
import com.stastyle.imumapper.pipeline.survey.SurveyDoc
import com.stastyle.imumapper.render.LayerSelection
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Opening, selection, cursor and readout on the L walk: Start (id 1, 0 m), Junction 1 (id 2, 5 m),
 * C1 (id 4, 10 m, the corner at (0, 10)) and End (id 3, 15 m, at (5, 10)); one point every 0.5 m.
 */
class SurveyControllerTest {

    private val geo = SurveyGeometry.of(SurveyFixtures.lWalk(), runId = 1, raw = false)

    private fun t(index: Int): Long = SurveyFixtures.tNs(index)

    private fun seeded(): SurveyState = SurveyController.open(SurveyLoad.Missing, geo).state

    private fun tap(state: SurveyState, vararg ids: Int): SurveyState =
        ids.fold(state) { s, id -> SurveyController.tapStation(s, id) }

    private fun loaded(stations: List<Station>): SurveyState =
        SurveyController.open(SurveyLoad.Loaded(SurveyDoc(stations = stations)), geo).state

    /** Azimuths near north may come out as 0 or just under 360; both are north. */
    private fun assertNorth(deg: Double?) = assertEquals(0.0, SurveyAngles.wrapDeg(deg!!), 1e-9)

    @Test
    fun missingFileSeedsTheStationsWithTheCursorAtTheStart() {
        val opened = SurveyController.open(SurveyLoad.Missing, geo)
        assertTrue(opened.seeded)
        assertNull(opened.error)
        val state = opened.state
        assertEquals(listOf(1, 2, 4, 3), state.doc.stations.map { it.id })
        assertEquals(listOf("Start", "Junction 1", "C1", "End"), state.doc.stations.map { it.name })
        assertEquals(t(0), state.cursorNs)
        assertEquals(SurveySelection.None, state.selection)
        assertTrue(state.undo.isEmpty())
        assertFalse(state.readOnly)
    }

    @Test
    fun loadedDocIsKeptAsItIsEvenWhenEmpty() {
        val empty = SurveyDoc()
        val opened = SurveyController.open(SurveyLoad.Loaded(empty), geo)
        assertFalse(opened.seeded)
        assertNull(opened.error)
        assertSame(empty, opened.state.doc)
        assertEquals(t(0), opened.state.cursorNs)
    }

    @Test
    fun malformedFileIsReadOnlyWithSeededStationsAndAnError() {
        val opened = SurveyController.open(SurveyLoad.Malformed("Unexpected JSON token"), geo)
        assertFalse(opened.seeded)
        assertTrue(opened.state.readOnly)
        assertEquals(4, opened.state.doc.stations.size)
        assertEquals(
            "survey.json could not be read (Unexpected JSON token). " +
                "Survey mode is read-only and the file is left as it is.",
            opened.error,
        )
    }

    @Test
    fun stationTapsBuildAChainAndTappingTheLastAgainTakesItBack() {
        var state = tap(seeded(), 1)
        assertEquals(SurveySelection.Chain(listOf(1)), state.selection)
        // A station may come back later in the chain (out and back).
        state = tap(state, 4, 1)
        assertEquals(SurveySelection.Chain(listOf(1, 4, 1)), state.selection)
        state = tap(state, 1)
        assertEquals(SurveySelection.Chain(listOf(1, 4)), state.selection)
        assertSame(state, SurveyController.tapStation(state, 99))
        assertEquals(SurveySelection.None, tap(state, 4, 1).selection)
        assertEquals(SurveySelection.None, SurveyController.clearSelection(state).selection)
    }

    @Test
    fun pathTapSelectsTheStretchAroundTheMomentAndMovesTheCursor() {
        // 7.5 m along the path is point 15, between Junction 1 (5 m) and C1 (10 m); it replaces the chain.
        val state = SurveyController.tapPath(tap(seeded(), 1), geo, 7.5)
        assertEquals(SurveySelection.Stretch(2, 4), state.selection)
        assertEquals(t(15), state.cursorNs)
    }

    @Test
    fun stretchBeyondTheFirstOrLastStationEndsAtThePathsOwnEnd() {
        val stations = seeded().doc.stations
        val noStart = SurveyController.tapPath(loaded(stations.filter { it.kind != StationKind.START }), geo, 2.5)
        assertEquals(SurveySelection.Stretch(null, 2), noStart.selection)
        assertEquals(t(0) to t(10), SurveyController.selectionEnds(noStart, geo))
        val start = assertIs<SurveyReadout.Stretch>(SurveyController.readout(noStart, geo))
        assertEquals(listOf(SurveyController.PATH_START_NAME, "Junction 1"), start.names)

        val noEnd = SurveyController.tapPath(loaded(stations.filter { it.kind != StationKind.END }), geo, 12.5)
        assertEquals(SurveySelection.Stretch(4, null), noEnd.selection)
        assertEquals(t(20) to t(30), SurveyController.selectionEnds(noEnd, geo))
        val end = assertIs<SurveyReadout.Stretch>(SurveyController.readout(noEnd, geo))
        assertEquals(listOf("C1", SurveyController.PATH_END_NAME), end.names)
    }

    @Test
    fun oneStationWaitsForTheNextAndNothingSelectedReadsNothing() {
        assertEquals(SurveyReadout.First(listOf("Start")), SurveyController.readout(tap(seeded(), 1), geo))
        assertNull(SurveyController.readout(seeded(), geo))
    }

    @Test
    fun chainReadoutHasEachHopAndTheStraightLine() {
        val pair = assertIs<SurveyReadout.Chain>(SurveyController.readout(tap(seeded(), 1, 4), geo))
        assertEquals(listOf("Start", "C1"), pair.names)
        assertEquals(1, pair.measure.hops.size)
        assertEquals(10.0, pair.measure.straight.lengthM, 1e-9)
        assertNorth(pair.measure.straight.azimuthDeg)
        assertEquals(10.0, pair.measure.hopPathSumM, 1e-9)

        // Start (0, 0) to C1 (0, 10) to End (5, 10): hops of 10 m and 5 m, straight line sqrt(125).
        val three = assertIs<SurveyReadout.Chain>(SurveyController.readout(tap(seeded(), 1, 4, 3), geo))
        assertEquals(listOf("Start", "C1", "End"), three.names)
        assertEquals(2, three.measure.hops.size)
        assertEquals(15.0, three.measure.hopLengthSumM, 1e-9)
        assertEquals(15.0, three.measure.hopPathSumM, 1e-9)
        assertEquals(sqrt(125.0), three.measure.straight.lengthM, 1e-9)
    }

    @Test
    fun stretchReadoutHasTheChordAndThePassageDirection() {
        val state = SurveyController.selectLeg(seeded(), 2, 4)
        assertEquals(SurveySelection.Stretch(2, 4), state.selection)
        val readout = assertIs<SurveyReadout.Stretch>(SurveyController.readout(state, geo))
        assertEquals(listOf("Junction 1", "C1"), readout.names)
        assertEquals(5.0, readout.measure.leg.lengthM, 1e-9)
        assertNorth(readout.measure.leg.azimuthDeg)
        assertNorth(readout.measure.fittedAzimuthDeg)
    }

    @Test
    fun cursorIsSetByDistanceAndSteppedByPathPointWithinThePath() {
        val start = seeded()
        val mid = SurveyController.setCursor(start, geo, 7.5)
        assertEquals(t(15), mid.cursorNs)
        assertEquals(t(0), SurveyController.setCursor(start, geo, -3.0).cursorNs)
        assertEquals(t(30), SurveyController.setCursor(start, geo, 99.0).cursorNs)
        assertEquals(t(16), SurveyController.stepCursor(mid, geo, 1).cursorNs)
        assertEquals(t(13), SurveyController.stepCursor(mid, geo, -2).cursorNs)
        assertEquals(t(15), SurveyController.stepCursor(mid, geo, 0).cursorNs)
        assertEquals(t(0), SurveyController.stepCursor(start, geo, -1).cursorNs)
        assertEquals(t(30), SurveyController.stepCursor(SurveyController.setCursor(start, geo, 15.0), geo, 1).cursorNs)
    }

    @Test
    fun selectionEndsArePairsInTapOrderOrStretches() {
        val start = seeded()
        assertNull(SurveyController.selectionEnds(start, geo))
        assertNull(SurveyController.selectionEnds(tap(start, 4), geo))
        assertEquals(t(20) to t(0), SurveyController.selectionEnds(tap(start, 4, 1), geo))
        assertNull(SurveyController.selectionEnds(tap(start, 4, 1, 3), geo))
        assertEquals(t(10) to t(20), SurveyController.selectionEnds(SurveyController.selectLeg(start, 2, 4), geo))
    }

    @Test
    fun onlyOneSelectedCornerOrUserStationCanBeMoved() {
        val start = seeded()
        assertEquals(4, SurveyController.movableStationId(tap(start, 4)))
        assertNull(SurveyController.movableStationId(tap(start, 1)))
        assertNull(SurveyController.movableStationId(tap(start, 2)))
        assertNull(SurveyController.movableStationId(tap(start, 4, 2)))
        assertNull(SurveyController.movableStationId(SurveyController.selectLeg(start, 2, 4)))
        val user = loaded(listOf(Station(id = 9, kind = StationKind.USER, name = "S1", tNs = t(5))))
        assertEquals(9, SurveyController.movableStationId(tap(user, 9)))
    }

    @Test
    fun layerSelectionCarriesTheChainOrTheStretchTimes() {
        val start = seeded()
        assertEquals(LayerSelection(), SurveyController.layerSelection(start, geo))
        assertEquals(LayerSelection(chainIds = listOf(1, 4)), SurveyController.layerSelection(tap(start, 1, 4), geo))
        assertEquals(
            LayerSelection(stretchNs = t(10) to t(20), stretchIds = listOf(2, 4)),
            SurveyController.layerSelection(SurveyController.selectLeg(start, 2, 4), geo),
        )
    }

    @Test
    fun geometryIsReusedForAnUnchangedAngleAndTurnsAboutTheStart() {
        assertSame(geo.shown, geo.framed)
        assertSame(geo.plain, geo.timeline)
        assertSame(geo, geo.rotated(0.0))

        val turned = geo.rotated(10.0)
        assertNotSame(geo, turned)
        assertEquals(10.0, turned.rotationDeg)
        assertSame(geo.shown, turned.shown)
        assertSame(geo.plain, turned.plain)
        assertEquals(1, turned.runId)
        assertFalse(turned.raw)
        assertSame(turned, turned.rotated(10.0))
        assertSame(geo.shown, turned.rotated(0.0).framed)

        // x' = x cos + y sin, y' = -x sin + y cos about the origin: the corner (0, 10) moves east of north.
        val corner = turned.framed.points[20].p
        assertEquals(10.0 * sin(Math.toRadians(10.0)), corner.x, 1e-9)
        assertEquals(10.0 * cos(Math.toRadians(10.0)), corner.y, 1e-9)
        assertEquals(geo.plain.lengthM, turned.timeline.lengthM, 1e-9)
    }
}
