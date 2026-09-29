package com.stastyle.imumapper.pipeline.survey

import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.survey.SurveyPaths.T0
import com.stastyle.imumapper.pipeline.survey.SurveyPaths.tNs
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SurveyCsvTest {

    /** 10 m north then 5 m east, exact to the last bit: every cell of the file is known. */
    private val lWalk = PathTimeline(SurveyPaths.steps(20 to 0.0, 10 to PI / 2))

    private fun station(id: Int, kind: StationKind, name: String, index: Int) = Station(id, kind, name, tNs(index))

    private fun rows(text: String): List<String> =
        text.removePrefix(SurveyCsv.BOM).removeSuffix(SurveyCsv.EOL).split(SurveyCsv.EOL)

    @Test
    fun wholeFileForAnLTraverse() {
        val stations = listOf(
            station(1, StationKind.START, "Start", 0),
            station(2, StationKind.MARK, "Junction 1", 10),
            station(4, StationKind.CORNER, "C1", 20),
            station(3, StationKind.END, "End", 30),
        )
        val text = SurveyCsv.text(Traverse.legs(stations, lWalk), T0, Vec3.ZERO, magnetic = true)
        val expected = "\uFEFF" +
            "from,to,from_s,to_s,length_m,horizontal_m,height_change_m,azimuth_deg,north,slope_deg,grade_pct," +
            "path_m,curved,to_east_m,to_north_m,to_up_m\r\n" +
            "Start,Junction 1,0.0,5.0,5.00,5.00,0.00,0.0,M,0.0,0.0,5.00,no,0.00,5.00,0.00\r\n" +
            "Junction 1,C1,5.0,10.0,5.00,5.00,0.00,0.0,M,0.0,0.0,5.00,no,0.00,10.00,0.00\r\n" +
            "C1,End,10.0,15.0,5.00,5.00,0.00,90.0,M,0.0,0.0,5.00,no,5.00,10.00,0.00\r\n"
        assertEquals(expected, text)
    }

    @Test
    fun emptyTraverseIsTheHeaderAlone() {
        assertEquals(SurveyCsv.BOM + SurveyCsv.HEADER + SurveyCsv.EOL, SurveyCsv.text(emptyList(), T0, Vec3.ZERO, true))
    }

    @Test
    fun namesWithCommasOrQuotesAreQuoted() {
        assertEquals("\"Big room, \"\"north\"\"\"", SurveyCsv.field("Big room, \"north\""))
        assertEquals("\"two\nlines\"", SurveyCsv.field("two\nlines"))
        assertEquals("\"a\rb\"", SurveyCsv.field("a\rb"))
        assertEquals("מערה", SurveyCsv.field("מערה"))
        val stations = listOf(station(1, StationKind.START, "Start", 0), station(2, StationKind.MARK, "Fork, left", 10))
        val row = rows(SurveyCsv.text(Traverse.legs(stations, lWalk), T0, Vec3.ZERO, true))[1]
        assertTrue(row.startsWith("Start,\"Fork, left\",0.0,5.0,"), row)
    }

    @Test
    fun zeroLengthLegHasEmptyAzimuthAndGrade() {
        val stations = listOf(station(1, StationKind.USER, "S1", 10), station(2, StationKind.USER, "S2", 10))
        val row = rows(SurveyCsv.text(Traverse.legs(stations, lWalk), T0, Vec3.ZERO, true))[1]
        assertEquals("S1,S2,5.0,5.0,0.00,0.00,0.00,,M,0.0,,0.00,no,0.00,5.00,0.00", row)
    }

    @Test
    fun relativeNorthWritesR() {
        val stations = listOf(station(1, StationKind.START, "Start", 0), station(2, StationKind.END, "End", 30))
        val row = rows(SurveyCsv.text(Traverse.legs(stations, lWalk), T0, Vec3.ZERO, magnetic = false))[1]
        assertEquals("R", row.split(",")[8])
    }

    @Test
    fun coordinatesAreRelativeToTheOrigin() {
        val stations = listOf(station(1, StationKind.START, "Start", 0), station(2, StationKind.END, "End", 30))
        val row = rows(SurveyCsv.text(Traverse.legs(stations, lWalk), T0, Vec3(1.0, 2.0, -0.5), true))[1]
        assertTrue(row.endsWith(",4.00,8.00,0.50"), row)
    }

    @Test
    fun azimuthJustWestOfNorthReadsZeroNotThreeSixty() {
        // 1 cm west over 20 m north: 359.97 degrees, which one decimal would round to 360.0.
        val tl = PathTimeline(SurveyPaths.linear(Vec3.ZERO, Vec3(-0.01, 20.0, 0.0)))
        val stations = listOf(Station(1, StationKind.START, "A", T0), Station(2, StationKind.END, "B", tNs(1)))
        val row = rows(SurveyCsv.text(Traverse.legs(stations, tl), T0, Vec3.ZERO, true))[1]
        assertEquals("A,B,0.0,0.5,20.00,20.00,0.00,0.0,M,0.0,0.0,20.00,no,-0.01,20.00,0.00", row)
    }

    @Test
    fun fixedNeverPrintsNegativeZero() {
        assertEquals("0.00", SurveyCsv.fixed(-0.001, 2))
        assertEquals("0.0", SurveyCsv.fixed(-0.0, 1))
        assertEquals("0.0", SurveyCsv.fixed(-0.04, 1))
        assertEquals("-0.1", SurveyCsv.fixed(-0.06, 1))
        assertEquals("1234.57", SurveyCsv.fixed(1234.5678, 2))
        assertEquals("3.0", SurveyCsv.fixed(3.0, 1))
        assertEquals("-12.50", SurveyCsv.fixed(-12.5, 2))
    }

    @Test
    fun correctionTextForEverySource() {
        fun fit(id: Int, used: Boolean) = ReferenceFit(id, if (used) 10.0 else null, 12.0, if (used) 0.1 else null)
        val two = NorthSolution(4.04, NorthSource.REFERENCES, listOf(fit(1, true), fit(2, true), fit(3, false)))
        assertEquals("north +4.0° from 2 compass readings", SurveyCsv.correctionText(two))
        val one = NorthSolution(3.96, NorthSource.REFERENCES, listOf(fit(1, true)))
        assertEquals("north +4.0° from 1 compass reading", SurveyCsv.correctionText(one))
        fun alone(rotationDeg: Double, source: NorthSource) = NorthSolution(rotationDeg, source, emptyList())
        assertEquals("north -1.5° set by hand", SurveyCsv.correctionText(alone(-1.5, NorthSource.MANUAL)))
        // Rounded before formatting, so a hair west of zero is not "-0.0".
        assertEquals("north +0.0° set by hand", SurveyCsv.correctionText(alone(-0.04, NorthSource.MANUAL)))
        assertEquals("north as recorded", SurveyCsv.correctionText(alone(0.0, NorthSource.NONE)))
    }
}
