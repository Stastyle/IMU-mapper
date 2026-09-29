package com.stastyle.imumapper.ui.viewer

import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.PositionSource
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.survey.CompassReference
import com.stastyle.imumapper.pipeline.survey.Detail
import com.stastyle.imumapper.pipeline.survey.LegMeasure
import com.stastyle.imumapper.pipeline.survey.LegTotals
import com.stastyle.imumapper.pipeline.survey.NorthSolution
import com.stastyle.imumapper.pipeline.survey.NorthSource
import com.stastyle.imumapper.pipeline.survey.PathTimeline
import com.stastyle.imumapper.pipeline.survey.ReferenceFit
import com.stastyle.imumapper.pipeline.survey.ReferenceLine
import com.stastyle.imumapper.pipeline.survey.Station
import com.stastyle.imumapper.pipeline.survey.StationKind
import com.stastyle.imumapper.pipeline.survey.StretchMeasure
import com.stastyle.imumapper.pipeline.survey.TraverseLeg
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Survey mode's strings, checked against the examples in docs/plans/2026-09-29-survey-mode-design.md. */
class SurveyFormatTest {

    /** The design's example leg: Junction 2 to Chamber, 34.2 m at 047°, up 7°, along a curved 38.5 m. */
    private val leg = LegMeasure(
        fromNs = 0L,
        toNs = 1L,
        a = Vec3.ZERO,
        b = Vec3.ZERO,
        lengthM = 34.21,
        horizontalM = 33.94,
        heightChangeM = 4.08,
        azimuthDeg = 47.3,
        slopeDeg = 6.86,
        gradePct = 12.02,
        pathM = 38.46,
        curved = true,
    )

    /** Nearly vertical and straight: no azimuth and no grade. */
    private val shaft = leg.copy(
        lengthM = 3.0,
        horizontalM = 0.1,
        heightChangeM = -2.96,
        azimuthDeg = null,
        slopeDeg = -88.1,
        gradePct = null,
        pathM = 3.04,
        curved = false,
    )

    /** A solution whose first [used] references entered the solve. */
    private fun solution(rotationDeg: Double, source: NorthSource, used: Int) = NorthSolution(
        rotationDeg = rotationDeg,
        source = source,
        fits = (1..used).map { id ->
            ReferenceFit(referenceId = id, measuredDeg = 10.0, horizontalM = 20.0, residualDeg = 0.0)
        },
    )

    @Test
    fun suffixIsMOnlyForMagneticNorth() {
        assertEquals("M", SurveyFormat.suffix(magnetic = true))
        assertEquals("R", SurveyFormat.suffix(magnetic = false))
    }

    @Test
    fun metresRoundToOneDecimalWithoutANegativeZero() {
        assertEquals("34.2 m", SurveyFormat.metres(34.21))
        assertEquals("1234.6 m", SurveyFormat.metres(1234.56))
        assertEquals("0.0 m", SurveyFormat.metres(-0.04))
        assertEquals("+4.1", SurveyFormat.signedMetres(4.08))
        assertEquals("-0.3", SurveyFormat.signedMetres(-0.26))
        assertEquals("+0.0", SurveyFormat.signedMetres(-0.04))
        assertEquals("+0.0", SurveyFormat.signedMetres(-0.0))
    }

    @Test
    fun azimuthHasThreeDigitsAndItsNorth() {
        assertEquals("047° M", SurveyFormat.azimuth(47.3, magnetic = true))
        assertEquals("005° R", SurveyFormat.azimuth(5.0, magnetic = false))
        assertEquals("180° R", SurveyFormat.azimuth(180.0, magnetic = false))
        assertEquals("359° M", SurveyFormat.azimuth(359.4, magnetic = true))
        assertEquals("000° M", SurveyFormat.azimuth(359.6, magnetic = true))
        assertEquals(SurveyFormat.DASH, SurveyFormat.azimuth(null, magnetic = true))
    }

    @Test
    fun slopeHasAnArrowAndALevelLegHasNone() {
        assertEquals("▲ +7°", SurveyFormat.slope(6.86))
        assertEquals("▼ -3°", SurveyFormat.slope(-3.2))
        assertEquals("0°", SurveyFormat.slope(0.4))
        assertEquals("0°", SurveyFormat.slope(-0.4))
        assertEquals("+7°", SurveyFormat.signedDegrees(6.86))
        assertEquals("-3°", SurveyFormat.signedDegrees(-3.2))
        assertEquals("+0°", SurveyFormat.signedDegrees(-0.4))
    }

    @Test
    fun gradeIsWholePercentAndDashWithoutAHorizontalRun() {
        assertEquals("12 %", SurveyFormat.grade(12.02))
        assertEquals("-5 %", SurveyFormat.grade(-5.4))
        assertEquals(SurveyFormat.DASH, SurveyFormat.grade(null))
    }

    @Test
    fun rotationIsSignedTenthsOfADegree() {
        assertEquals("+4.0°", SurveyFormat.rotation(4.0))
        assertEquals("-0.5°", SurveyFormat.rotation(-0.5))
        assertEquals("+0.0°", SurveyFormat.rotation(-0.04))
        assertEquals("+12.3°", SurveyFormat.rotation(12.345))
    }

    @Test
    fun detailsMarkACurvedPathAndDashAMissingGrade() {
        assertEquals("horiz 33.9 · Δh +4.1 · 12 % · path 38.5⌒", SurveyFormat.details(leg))
        assertEquals("horiz 0.1 · Δh -3.0 · — · path 3.0", SurveyFormat.details(shaft))
    }

    @Test
    fun stretchLineShowsTheFitAndHowStraightThePathIs() {
        // 34.21 / 38.46 = 0.8895
        val stretch = StretchMeasure(leg, fittedAzimuthDeg = 46.2)
        assertEquals("fitted 046° M · straight 0.89", SurveyFormat.stretchLine(stretch, magnetic = true))
        val standing = StretchMeasure(leg.copy(pathM = 0.0), fittedAzimuthDeg = null)
        assertEquals("fitted — · straight —", SurveyFormat.stretchLine(standing, magnetic = false))
    }

    @Test
    fun copyLineIsTheDesignExample() {
        assertEquals(
            "Junction 2 → Chamber: 34.2 m, horiz 33.9 m, 047° M, +7° (Δh +4.1 m), path 38.5 m",
            SurveyFormat.copyLine("Junction 2", "Chamber", leg, magnetic = true),
        )
    }

    @Test
    fun northChipCountsTheReferencesThatEnteredTheSolve() {
        assertEquals("N +0.0° R", SurveyFormat.northChip(solution(0.0, NorthSource.NONE, used = 0), magnetic = false))
        val manual = solution(-1.5, NorthSource.MANUAL, used = 0)
        assertEquals("N -1.5° R", SurveyFormat.northChip(manual, magnetic = false))
        assertEquals(
            "N +4.0° M · 1 ref",
            SurveyFormat.northChip(solution(4.0, NorthSource.REFERENCES, used = 1), magnetic = true),
        )
        assertEquals(
            "N +4.0° M · 2 refs",
            SurveyFormat.northChip(solution(4.0, NorthSource.REFERENCES, used = 2), magnetic = true),
        )
    }

    @Test
    fun theTopBarChipLeavesOutTheReferenceCountSoTheTitleKeepsRoom() {
        // At 384 dp the full "N +4.0° M · 2 refs" left the trip name and "Survey (beta)" as "S…".
        assertEquals("N +0.0° R", SurveyFormat.northChipShort(solution(0.0, NorthSource.NONE, used = 0), magnetic = false))
        assertEquals("N -1.5° R", SurveyFormat.northChipShort(solution(-1.5, NorthSource.MANUAL, used = 0), magnetic = false))
        assertEquals(
            "N +4.0° M",
            SurveyFormat.northChipShort(solution(4.0, NorthSource.REFERENCES, used = 2), magnetic = true),
        )
    }

    @Test
    fun cursorLabelShowsTheClockWhenTheStartIsKnownElseTheElapsedTime() {
        // 1_700_000_000_000 ms is 2023-11-14 22:13:20 UTC; 125 s later is 22:15:25.
        assertEquals(
            "22:15:25 · 41.2 m",
            SurveyFormat.cursorLabel(1_700_000_000_000L, 125_000_000_000L, 41.23, ZoneOffset.UTC),
        )
        assertEquals("3:05 · 41.2 m", SurveyFormat.cursorLabel(null, 185_000_000_000L, 41.23, ZoneOffset.UTC))
    }

    @Test
    fun scrubberClockCountsFromTheTripStartNotFromAVioPathsFirstTrackingPose() {
        // START at 1 s; ARCore first tracked at 9 s, where the VIO path begins, and 10 m north at 19 s.
        val tripStartNs = 1_000_000_000L
        val timeline = PathTimeline(
            listOf(
                PathPoint(9_000_000_000L, Vec3.ZERO, PositionSource.VIO, 0.0),
                PathPoint(19_000_000_000L, Vec3(0.0, 10.0, 0.0), PositionSource.VIO, 0.0),
            ),
        )
        // 22:13:20 at START, so the cursor 18 s later reads 22:13:38, not 22:13:30.
        assertEquals(
            "22:13:38 · 10.0 m",
            SurveyFormat.scrubberLabel(1_700_000_000_000L, tripStartNs, timeline, 19_000_000_000L, ZoneOffset.UTC),
        )
        // Without the log's START no clock is shown, only the time since the path's start.
        assertEquals(
            "0:10 · 10.0 m",
            SurveyFormat.scrubberLabel(1_700_000_000_000L, null, timeline, 19_000_000_000L, ZoneOffset.UTC),
        )
        assertEquals("0:10 · 10.0 m", SurveyFormat.scrubberLabel(null, tripStartNs, timeline, 19_000_000_000L, ZoneOffset.UTC))
    }

    @Test
    fun shareTextNamesTheTripTheRunAndTheCorrection() {
        assertEquals(
            "Cave loop · Run 3, north +4.0° from 2 compass readings",
            SurveyFormat.shareText("Cave loop", 3, raw = false, solution(4.0, NorthSource.REFERENCES, used = 2)),
        )
        assertEquals(
            "Cave loop · Run 3 (raw path), north as recorded",
            SurveyFormat.shareText("Cave loop", 3, raw = true, north = solution(0.0, NorthSource.NONE, used = 0)),
        )
    }

    @Test
    fun legsTableHasAHeaderRowsAndTotals() {
        assertEquals(
            listOf("From", "To", "Length", "Azimuth M", "Slope", "Δh", "Path"),
            SurveyFormat.tableHeader(magnetic = true),
        )
        assertEquals("Azimuth R", SurveyFormat.tableHeader(magnetic = false)[3])
        val from = Station(id = 2, kind = StationKind.MARK, name = "Junction 2", tNs = 0L)
        val to = Station(id = 5, kind = StationKind.MARK, name = "Chamber", tNs = 1L)
        assertEquals(
            listOf("Junction 2", "Chamber", "34.2", "047°", "+7°", "+4.1", "38.5⌒"),
            SurveyFormat.tableRow(TraverseLeg(from, to, leg)),
        )
        assertEquals(
            listOf("Junction 2", "Chamber", "3.0", SurveyFormat.DASH, "-88°", "-3.0", "3.0"),
            SurveyFormat.tableRow(TraverseLeg(from, to, shaft)),
        )
        val totals = LegTotals(lengthM = 136.44, horizontalM = 120.0, heightChangeM = 1.96, pathM = 150.08)
        assertEquals(listOf("Total", "", "136.4", "", "", "+2.0", "150.1"), SurveyFormat.totalsRow(totals))
    }

    /** The azimuth cells carry no suffix and a narrow header can wrap, so the title always says which north. */
    @Test
    fun legsTitleSaysWhichNorthTheAzimuthsAreFrom() {
        assertEquals("Legs · azimuths from magnetic north (M)", SurveyFormat.legsTitle(magnetic = true))
        assertEquals("Legs · azimuths from relative north (R)", SurveyFormat.legsTitle(magnetic = false))
    }

    @Test
    fun typedDegreesTakeACommaAndRefuseAnythingNotFinite() {
        assertEquals(45.0, SurveyFormat.parseDegrees("45"))
        assertEquals(4.5, SurveyFormat.parseDegrees(" 4,5 "))
        assertEquals(-0.5, SurveyFormat.parseDegrees("-0.5"))
        assertNull(SurveyFormat.parseDegrees(""))
        assertNull(SurveyFormat.parseDegrees("   "))
        assertNull(SurveyFormat.parseDegrees("abc"))
        assertNull(SurveyFormat.parseDegrees("NaN"))
        assertNull(SurveyFormat.parseDegrees("Infinity"))
    }

    @Test
    fun detailLevelsAndCornerCountsReadAsWords() {
        assertEquals("Coarse (1 m)", SurveyFormat.detailLabel(Detail.COARSE))
        assertEquals("Normal (0.5 m)", SurveyFormat.detailLabel(Detail.NORMAL))
        assertEquals("Fine (0.25 m)", SurveyFormat.detailLabel(Detail.FINE))
        assertEquals("0 corners", SurveyFormat.corners(0))
        assertEquals("1 corner", SurveyFormat.corners(1))
        assertEquals("3 corners", SurveyFormat.corners(3))
    }

    @Test
    fun compassReadingShowsBearingLineAndResidual() {
        val chord = CompassReference(id = 1, fromNs = 0L, toNs = 1L, bearingDeg = 45.0)
        assertEquals(
            "045° M · point to point · residual +0.3°",
            SurveyFormat.reference(chord, ReferenceFit(1, measuredDeg = 44.0, horizontalM = 12.0, residualDeg = 0.26)),
        )
        val back = CompassReference(
            id = 2,
            fromNs = 0L,
            toNs = 1L,
            bearingDeg = 225.0,
            backBearing = true,
            line = ReferenceLine.FITTED,
        )
        val unused = "225° M back-bearing · passage · not used: too short"
        val tooShort = ReferenceFit(2, measuredDeg = null, horizontalM = 0.1, residualDeg = null)
        assertEquals(unused, SurveyFormat.reference(back, tooShort))
        assertEquals(unused, SurveyFormat.reference(back, null))
    }
}
