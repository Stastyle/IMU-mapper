package com.stastyle.imumapper.pipeline.survey

import com.stastyle.imumapper.pipeline.core.AnnotationKind
import com.stastyle.imumapper.pipeline.core.PathAnnotation
import com.stastyle.imumapper.pipeline.survey.SurveyPaths.T0
import com.stastyle.imumapper.pipeline.survey.SurveyPaths.tNs
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals

class SurveyStationsTest {

    /** 10 m north (points 0-20), then 5 m east (points 20-30): one corner, at point 20. */
    private val lWalk = PathTimeline(SurveyPaths.steps(20 to 0.0, 10 to PI / 2))

    /** 10 m N, 6 m E, 10 m S, 6 m W: corners at points 20, 32 and 52 (10, 16 and 26 m along). */
    private val rectangle = PathTimeline(SurveyPaths.steps(20 to 0.0, 12 to PI / 2, 20 to PI, 12 to -PI / 2))

    private fun note(index: Int, kind: AnnotationKind, text: String = "") =
        PathAnnotation(tNs(index), kind, text, lWalk.positionAt(tNs(index)))

    @Test
    fun seedNamesMarksAndAddsCorners() {
        // Given out of time order; REORIENT and LOOP_CLOSED are not stations.
        val annotations = listOf(
            note(12, AnnotationKind.JUNCTION),
            note(5, AnnotationKind.REORIENT),
            note(2, AnnotationKind.WAYPOINT, " Entrance "),
            note(26, AnnotationKind.JUNCTION, "  "),
            note(30, AnnotationKind.LOOP_CLOSED),
        )
        val expected = listOf(
            Station(1, StationKind.START, "Start", T0),
            Station(2, StationKind.MARK, "Entrance", tNs(2)),
            Station(3, StationKind.MARK, "Junction 1", tNs(12)),
            Station(6, StationKind.CORNER, "C1", tNs(20)),
            Station(4, StationKind.MARK, "Junction 2", tNs(26)),
            Station(5, StationKind.END, "End", tNs(30)),
        )
        assertEquals(expected, SurveyStations.seed(lWalk, annotations))
    }

    @Test
    fun markOrdinalsCountNotedMarksToo() {
        val annotations = listOf(
            note(4, AnnotationKind.JUNCTION, "Fork"),
            note(12, AnnotationKind.JUNCTION),
            note(26, AnnotationKind.CHAMBER),
            note(28, AnnotationKind.NOTE),
        )
        val marks = SurveyStations.seed(lWalk, annotations).filter { it.kind == StationKind.MARK }
        assertEquals(listOf("Fork", "Junction 2", "Chamber 1", "Note 1"), marks.map { it.name })
        assertEquals("Waypoint 3", SurveyStations.markName(AnnotationKind.WAYPOINT, "", 3))
        assertEquals("Big room", SurveyStations.markName(AnnotationKind.CHAMBER, "  Big room ", 1))
    }

    @Test
    fun markNameJoinsTheLinesOfAMultiLineNote() {
        // The recorder's Note field takes several lines; one line keeps the chain row and each hop line whole.
        assertEquals("Squeeze left side", SurveyStations.markName(AnnotationKind.NOTE, "Squeeze\nleft side", 1))
        assertEquals("Squeeze left side", SurveyStations.oneLineName(" Squeeze \r\n\r\n  left side "))
        assertEquals("Note 2", SurveyStations.markName(AnnotationKind.NOTE, " \n\r\n ", 2))
        assertEquals("Squeeze left side", SurveyStations.oneLineName("Squeeze\u2028left side"))
    }

    @Test
    fun seedClampsMarksToThePathSoStartAndEndStayOutermost() {
        // A mark tapped while standing after the last step (PDR ends there), and one before the path starts
        // (VIO starts at the first tracking frame): both sit at the nearest end, inside Start and End.
        val annotations = listOf(
            PathAnnotation(tNs(30) + 1_000_000_000L, AnnotationKind.CHAMBER, "Sump", lWalk.positionAt(tNs(30))),
            PathAnnotation(T0 - 500_000_000L, AnnotationKind.WAYPOINT, "Entrance", lWalk.positionAt(T0)),
        )
        val expected = listOf(
            Station(1, StationKind.START, "Start", T0),
            Station(2, StationKind.MARK, "Entrance", T0),
            Station(5, StationKind.CORNER, "C1", tNs(20)),
            Station(3, StationKind.MARK, "Sump", tNs(30)),
            Station(4, StationKind.END, "End", tNs(30)),
        )
        assertEquals(expected, SurveyStations.seed(lWalk, annotations))
    }

    @Test
    fun seedWithoutMarksIsStartEndAndCorners() {
        val stations = SurveyStations.seed(lWalk, emptyList(), Detail.COARSE)
        assertEquals(
            listOf(
                Station(1, StationKind.START, "Start", T0),
                Station(3, StationKind.CORNER, "C1", tNs(20)),
                Station(2, StationKind.END, "End", tNs(30)),
            ),
            stations,
        )
    }

    @Test
    fun regenerateCornersKeepsEveryOtherStation() {
        val stations = listOf(
            Station(1, StationKind.START, "Start", T0),
            Station(2, StationKind.END, "End", tNs(64)),
            Station(3, StationKind.CORNER, "Big bend", tNs(20)),
            Station(4, StationKind.USER, "S1", tNs(40)),
            // A corner the user moved: now USER, 0.5 m past the second corner, so that corner stays away.
            Station(5, StationKind.USER, "Moved", tNs(33)),
        )
        val expected = listOf(
            Station(1, StationKind.START, "Start", T0),
            Station(6, StationKind.CORNER, "C1", tNs(20)),
            Station(5, StationKind.USER, "Moved", tNs(33)),
            Station(4, StationKind.USER, "S1", tNs(40)),
            Station(7, StationKind.CORNER, "C2", tNs(52)),
            Station(2, StationKind.END, "End", tNs(64)),
        )
        assertEquals(expected, SurveyStations.regenerateCorners(stations, rectangle, Detail.FINE))
    }

    @Test
    fun regeneratedCornersSkipNamesStillInUse() {
        // A corner the user moved is a USER station that kept its name, here "C1", 0.5 m past the second corner.
        val stations = listOf(
            Station(1, StationKind.START, "Start", T0),
            Station(2, StationKind.END, "End", tNs(64)),
            Station(5, StationKind.USER, "C1", tNs(33)),
        )
        val expected = listOf(
            Station(1, StationKind.START, "Start", T0),
            Station(6, StationKind.CORNER, "C2", tNs(20)),
            Station(5, StationKind.USER, "C1", tNs(33)),
            Station(7, StationKind.CORNER, "C3", tNs(52)),
            Station(2, StationKind.END, "End", tNs(64)),
        )
        assertEquals(expected, SurveyStations.regenerateCorners(stations, rectangle, Detail.FINE))
    }

    @Test
    fun orderedIsByTimeThenId() {
        val stations = listOf(
            Station(5, StationKind.USER, "b", 100L),
            Station(2, StationKind.MARK, "a", 100L),
            Station(9, StationKind.START, "s", 50L),
        )
        assertEquals(listOf(9, 2, 5), SurveyStations.ordered(stations).map { it.id })
    }

    @Test
    fun idsAndUserNames() {
        assertEquals(1, SurveyStations.nextId(emptyList()))
        assertEquals("S1", SurveyStations.nextUserName(emptyList()))
        val stations = listOf(
            Station(3, StationKind.USER, "S1", 10L),
            Station(9, StationKind.USER, "S7", 20L),
            Station(4, StationKind.USER, "Sx", 30L),
            Station(1, StationKind.START, "Start", 0L),
            Station(6, StationKind.USER, "S03", 40L),
            Station(7, StationKind.USER, "s9", 50L),
        )
        assertEquals(10, SurveyStations.nextId(stations))
        assertEquals("S8", SurveyStations.nextUserName(stations))
        assertEquals(Station(10, StationKind.USER, "S8", tNs(5)), SurveyStations.user(stations, tNs(5)))
    }
}
