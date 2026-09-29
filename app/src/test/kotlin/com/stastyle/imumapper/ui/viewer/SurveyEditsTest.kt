package com.stastyle.imumapper.ui.viewer

import com.stastyle.imumapper.SurveyFixtures
import com.stastyle.imumapper.data.SurveyLoad
import com.stastyle.imumapper.pipeline.survey.Detail
import com.stastyle.imumapper.pipeline.survey.Station
import com.stastyle.imumapper.pipeline.survey.StationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Station edits and undo on the L walk: Start (id 1, 0 m), Junction 1 (id 2, 5 m), C1 (id 4, 10 m,
 * the only corner at every Detail) and End (id 3, 15 m); one point every 0.5 m and 0.5 s.
 */
class SurveyEditsTest {

    private val geo = SurveyGeometry.of(SurveyFixtures.lWalk(), runId = 1, raw = false)
    private val seeded = SurveyController.open(SurveyLoad.Missing, geo).state

    private fun t(index: Int): Long = SurveyFixtures.tNs(index)

    private fun SurveyState.station(id: Int): Station = doc.stations.single { it.id == id }

    private fun SurveyState.cornerTimes(): List<Long> =
        doc.stations.filter { it.kind == StationKind.CORNER }.map { it.tNs }

    private fun tap(state: SurveyState, vararg ids: Int): SurveyState =
        ids.fold(state) { s, id -> SurveyController.tapStation(s, id) }

    @Test
    fun addedStationIsS1InTimeOrderWithOneUndoEntry() {
        val state = SurveyController.addStation(seeded, t(15))
        assertEquals(listOf(1, 2, 5, 4, 3), state.doc.stations.map { it.id })
        assertEquals(Station(id = 5, kind = StationKind.USER, name = "S1", tNs = t(15)), state.station(5))
        assertEquals(listOf(seeded.doc), state.undo)
    }

    @Test
    fun undoRestoresTheDocButNotTheCursor() {
        val added = SurveyController.addStation(seeded, t(15))
        val selected = SurveyController.setCursor(tap(added, 5), geo, 12.5)
        val undone = SurveyController.undo(selected)
        assertEquals(seeded.doc, undone.doc)
        assertTrue(undone.undo.isEmpty())
        assertEquals(t(25), undone.cursorNs)
        // S1 is gone, so the chain that held only S1 is too.
        assertEquals(SurveySelection.None, undone.selection)
        assertSame(undone, SurveyController.undo(undone))
    }

    @Test
    fun movedCornerBecomesAUserStationThatADetailChangeKeeps() {
        val moved = SurveyController.moveStation(seeded, 4, t(25))
        val expected = Station(id = 4, kind = StationKind.USER, name = "C1", tNs = t(25))
        assertEquals(expected, moved.station(4))
        assertEquals(listOf(1, 2, 4, 3), moved.doc.stations.map { it.id })
        assertTrue(moved.cornerTimes().isEmpty())

        // FINE finds the corner at 10 m again (2.5 m from the moved station) and keeps the moved one.
        val fine = SurveyController.setDetail(moved, geo, Detail.FINE)
        assertEquals(expected, fine.station(4))
        assertEquals(listOf(t(20)), fine.cornerTimes())
        // The moved station kept the name C1, so the new corner takes the next free one.
        assertEquals(listOf("C2"), fine.doc.stations.filter { it.kind == StationKind.CORNER }.map { it.name })
    }

    @Test
    fun startEndAndMarksDoNotMove() {
        assertSame(seeded, SurveyController.moveStation(seeded, 1, t(5)))
        assertSame(seeded, SurveyController.moveStation(seeded, 2, t(5)))
        assertSame(seeded, SurveyController.moveStation(seeded, 3, t(5)))
        assertSame(seeded, SurveyController.moveStation(seeded, 99, t(5)))
    }

    @Test
    fun renameTrimsAndRefusesABlankName() {
        val renamed = SurveyController.renameStation(seeded, 2, "  Big room  ")
        assertEquals("Big room", renamed.station(2).name)
        assertEquals(1, renamed.undo.size)
        assertSame(renamed, SurveyController.renameStation(renamed, 2, "   "))
        assertSame(renamed, SurveyController.renameStation(renamed, 2, "Big room"))
        assertSame(renamed, SurveyController.renameStation(renamed, 99, "Nowhere"))
    }

    @Test
    fun deletePrunesTheChainAndDropsAStretchThatEndedThere() {
        val chain = SurveyController.deleteStation(tap(seeded, 1, 4, 2), 4)
        assertEquals(SurveySelection.Chain(listOf(1, 2)), chain.selection)
        // Out and back through C1: without it Start would follow itself, so the two collapse.
        val outAndBack = SurveyController.deleteStation(tap(seeded, 1, 4, 1), 4)
        assertEquals(SurveySelection.Chain(listOf(1)), outAndBack.selection)

        val stretch = SurveyController.selectLeg(seeded, 2, 4)
        val deleted = SurveyController.deleteStation(stretch, 4)
        assertEquals(SurveySelection.None, deleted.selection)
        assertTrue(deleted.doc.stations.none { it.id == 4 })
        assertEquals(SurveySelection.Stretch(2, 4), SurveyController.deleteStation(stretch, 1).selection)
    }

    @Test
    fun detailChangeRegeneratesCornersClearsTheSelectionAndCanBeUndone() {
        val fine = SurveyController.setDetail(tap(seeded, 4), geo, Detail.FINE)
        assertEquals(Detail.FINE, fine.doc.detail)
        assertEquals(listOf("C1"), fine.doc.stations.filter { it.kind == StationKind.CORNER }.map { it.name })
        assertEquals(listOf(t(20)), fine.cornerTimes())
        assertEquals(SurveySelection.None, fine.selection)
        assertEquals(listOf(seeded.doc), fine.undo)
        assertEquals(seeded.doc, SurveyController.undo(fine).doc)
    }

    @Test
    fun choosingTheCurrentDetailChangesNothing() {
        assertSame(seeded, SurveyController.setDetail(seeded, geo, Detail.NORMAL))
    }

    @Test
    fun cornerCountsCoverEveryDetailLevel() {
        val one = mapOf(Detail.COARSE to 1, Detail.NORMAL to 1, Detail.FINE to 1)
        assertEquals(one, SurveyController.cornerCounts(seeded, geo))
        val noCorner = SurveyController.deleteStation(seeded, 4)
        assertEquals(one, SurveyController.cornerCounts(noCorner, geo))
        // A station 0.5 m past the corner keeps any corner from being placed within 1.5 m of it.
        val crowded = SurveyController.addStation(noCorner, t(21))
        val none = mapOf(Detail.COARSE to 0, Detail.NORMAL to 0, Detail.FINE to 0)
        assertEquals(none, SurveyController.cornerCounts(crowded, geo))
    }

    @Test
    fun undoKeepsTheLatestFiftyDocs() {
        val state = (0 until 60).fold(seeded) { s, i -> SurveyController.renameStation(s, 1, "A$i") }
        assertEquals(SurveyController.MAX_UNDO, state.undo.size)
        // Edit i pushed the doc from before it, so the ten oldest (Start, A0..A8) were dropped.
        assertEquals("A9", state.undo.first().stations.single { it.id == 1 }.name)
        assertEquals("A58", state.undo.last().stations.single { it.id == 1 }.name)
    }

    @Test
    fun readOnlyRefusesEveryEditButStillSelects() {
        val readOnly = SurveyController.open(SurveyLoad.Malformed("bad"), geo).state
        val selected = tap(readOnly, 4)
        assertEquals(SurveySelection.Chain(listOf(4)), selected.selection)
        assertSame(selected, SurveyController.addStation(selected, t(15)))
        assertSame(selected, SurveyController.moveStation(selected, 4, t(25)))
        assertSame(selected, SurveyController.renameStation(selected, 4, "X"))
        assertSame(selected, SurveyController.deleteStation(selected, 4))
        assertSame(selected, SurveyController.setDetail(selected, geo, Detail.FINE))
        assertSame(selected, SurveyController.undo(selected))
    }
}
