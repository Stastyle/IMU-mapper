## Section D: Viewer integration and UI

Tasks 19 to 30 put Survey mode into the trip viewer: the display strings, the pure `SurveyController`
(selection, cursor, readout, station edits, north edits, undo), the view model that owns it and saves
`survey.json`, the Compose panel, sheets and dialogs, the screen wiring, and the `CLAUDE.md` layout note.

**Depends on:** Section A (the whole `pipeline.survey` package), Section B (`SurveyLoad`, `SurveyStore`,
`TripFiles.surveyFile`, `SurveyCsvFile`, `SurveyShare`, `AppContainer.surveyStore`) and Section C
(`SurveyLayer`, `LayerSelection`, `SurveyHit`, the new `ViewerCanvas` parameters, and the app fixture
`app/src/test/kotlin/com/stastyle/imumapper/SurveyFixtures.kt`). Check before starting:

```bash
ls pipeline/src/main/kotlin/com/stastyle/imumapper/pipeline/survey/NorthSolver.kt \
   app/src/main/kotlin/com/stastyle/imumapper/data/SurveyStore.kt \
   app/src/main/kotlin/com/stastyle/imumapper/render/SurveyLayer.kt \
   app/src/test/kotlin/com/stastyle/imumapper/SurveyFixtures.kt
```

**How this section was checked:** every file and replacement below was applied, in task order, to a
scratch export of the worktree with the code of Sections A to C, and each step behaved as written: the
failing test failed with the quoted first error, the test then passed, and `:app:assembleDebug` and the
full `:app:testDebugUnitTest` passed at the end.

**Conventions used below:**

- Gradle commands are shown without the `JAVA_HOME` / `ANDROID_HOME` prefix from the plan header; add it.
- Only failing tests print. With Kotlin 2.1 a missing symbol is reported as
  `e: file:///X:/IMU-mapper-survey/...Test.kt:<line>:<col> Unresolved reference 'Name'.`, then
  `> Task :app:compileDebugUnitTestKotlin FAILED` and `BUILD FAILED`. More errors follow the first; only
  the first is quoted.
- A modification is a list of exact replacements, applied in order: the old text, then the new text.
  "At line N" is where the old text starts in the file as the previous step left it. If an old snippet
  does not match, stop: the file has changed since this plan was written. The worktree checks files out
  with CRLF line ends; match the text, not the line ends.
- App tests use kotlin.test on JUnit 4 (`@BeforeTest` / `@AfterTest`). A JUnit 4 test method must return
  nothing, so a test body written as `= runBlocking { ... }` whose last expression has a value fails with
  `InvalidTestClassError: Method ... should be void`; the view-model tests use `runBlocking<Unit>`.

**Interface notes (all of these are in architecture section 2.4 too; they are easy to get wrong):**

- `SurveyFormat` has four members used only by this section: `DISAGREE` (the North sheet's text),
  `detailLabel(Detail)`, `corners(Int)` and `reference(CompassReference, ReferenceFit?)`. They are tested
  in `SurveyFormatTest`.
- `ViewerViewModel.READ_ONLY_MESSAGE` is a public constant so the test can compare the message.
- `SurveyController.azimuthPreview` also returns null for a bearing that is not finite (the architecture
  only says "null without selectionEnds"); the dialog never passes one.
- `publishSurvey()` is called from `applyResult` and `toggleRaw` unconditionally: outside Survey mode it
  sets `survey = null` (already null) and updates the bounds, which is what those calls did before.
- The cached geometry is reused only when the shown instance **and** the run id and raw flag match:
  `MutableStateFlow` drops an update whose value is equal, so after switching to a run whose result
  equals the previous one `shownResult` stays the old instance. Keying on the instance alone would keep
  the old run id. `manualRotationFromAnotherRunIsOfferedAndApplyUsesIt` covers this.
- The snapshot is split so the scrubber stays smooth on a long path: the private `SurveyBase` (north,
  framed geometry, legs, totals, north warning) is rebuilt only when the doc, the shown result, the run
  or the raw flag changes; the readout is kept until the selection changes; a cursor move rebuilds only
  the layer. `publishSurvey()` recomputes the bounds only when `sceneResult` is a different instance.
  `cursorMovesReuseWhatWalksTheWholePath` covers this.
- A doc edit replaces the pending snackbar message (with its own, or with none), so the snackbar's Undo
  never undoes a later edit than the one it names. Leaving Survey mode clears the message, because
  `surveyUndo()` does nothing outside the mode (`leavingSurveyModeDropsTheUndoMessage`).

---

### Task 19: Survey display strings

**Files:**
- Create: `app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/SurveyFormat.kt`
- Test: `app/src/test/kotlin/com/stastyle/imumapper/ui/viewer/SurveyFormatTest.kt`

`SurveyFormat` turns measurements into the strings of the panel, the Legs table, the copied line, the
north chip and the share text (architecture 2.4 and 4.2). Metres and rotations are rounded to 0.1 before
formatting and `+ 0.0` clears a negative zero; degrees round with `Math.round`; every number uses
`Locale.US`. `formatDuration` is the existing top-level function in `ViewerPanels.kt` (same package);
`SurveyCsv.correctionText` comes from Task 11.

**Step 1: Write the failing test**

Create `app/src/test/kotlin/com/stastyle/imumapper/ui/viewer/SurveyFormatTest.kt`:

```kotlin
package com.stastyle.imumapper.ui.viewer

import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.survey.CompassReference
import com.stastyle.imumapper.pipeline.survey.Detail
import com.stastyle.imumapper.pipeline.survey.LegMeasure
import com.stastyle.imumapper.pipeline.survey.LegTotals
import com.stastyle.imumapper.pipeline.survey.NorthSolution
import com.stastyle.imumapper.pipeline.survey.NorthSource
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
    fun cursorLabelShowsTheClockWhenTheStartIsKnownElseTheElapsedTime() {
        // 1_700_000_000_000 ms is 2023-11-14 22:13:20 UTC; 125 s later is 22:15:25.
        assertEquals(
            "22:15:25 · 41.2 m",
            SurveyFormat.cursorLabel(1_700_000_000_000L, 125_000_000_000L, 41.23, ZoneOffset.UTC),
        )
        assertEquals("3:05 · 41.2 m", SurveyFormat.cursorLabel(null, 185_000_000_000L, 41.23, ZoneOffset.UTC))
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
```

How the expected strings follow from the inputs: 34.21 m rounds to 34.2; 4.08 to +4.1; 38.46 to 38.5;
47.3° to 047; 6.86° to +7; 359.6° rounds to 360, which is 000; straightness is 34.21 / 38.46 = 0.8895,
shown 0.89; -0.04 and -0.0 round to +0.0; 12.345 × 10 rounds to 123, so +12.3°; 1 700 000 000 000 ms is
2023-11-14 22:13:20 UTC and 125 s later is 22:15:25; 185 s is 3:05 by `formatDuration`; the totals
136.44, 1.96 and 150.08 round to 136.4, +2.0 and 150.1; a residual of 0.26° rounds to +0.3°.

**Step 2: Run the test and watch it fail**

```bash
./gradlew :app:testDebugUnitTest --tests 'com.stastyle.imumapper.ui.viewer.SurveyFormatTest'
```

Expected: `BUILD FAILED` in `:app:compileDebugUnitTestKotlin`. The first error is
`e: file:///X:/IMU-mapper-survey/app/src/test/kotlin/com/stastyle/imumapper/ui/viewer/SurveyFormatTest.kt:63:27 Unresolved reference 'SurveyFormat'.`.

**Step 3: Write the implementation**

Create `app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/SurveyFormat.kt`:

```kotlin
package com.stastyle.imumapper.ui.viewer

import com.stastyle.imumapper.pipeline.survey.CompassReference
import com.stastyle.imumapper.pipeline.survey.Detail
import com.stastyle.imumapper.pipeline.survey.LegMeasure
import com.stastyle.imumapper.pipeline.survey.LegTotals
import com.stastyle.imumapper.pipeline.survey.NorthSolution
import com.stastyle.imumapper.pipeline.survey.ReferenceFit
import com.stastyle.imumapper.pipeline.survey.ReferenceLine
import com.stastyle.imumapper.pipeline.survey.StretchMeasure
import com.stastyle.imumapper.pipeline.survey.SurveyCsv
import com.stastyle.imumapper.pipeline.survey.TraverseLeg
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Display strings for Survey mode. Numbers use Locale.US, so a copied line pastes into a spreadsheet
 * the same on every phone; a value that cannot be measured shows [DASH]. Metres and rotations are
 * rounded before formatting, so "-0.0" never appears.
 */
object SurveyFormat {
    const val DASH: String = "—"

    /** Shown by the North sheet when NorthSolution.disagree. */
    const val DISAGREE: String = "References disagree: drift during the walk, or a misread bearing"

    private const val SEPARATOR = " · "
    private const val CURVED_MARK = "⌒"
    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)

    /** M only when north is known to be magnetic; an unknown north is never shown as magnetic. */
    fun suffix(magnetic: Boolean): String = if (magnetic) "M" else "R"

    fun metres(m: Double): String = String.format(Locale.US, "%.1f m", tenths(m))

    /** Height changes carry their sign even at zero, so up and down read alike. */
    fun signedMetres(m: Double): String = String.format(Locale.US, "%+.1f", tenths(m))

    /** Three digits like a compass card; 359.6 rounds to 000, not 360. */
    fun azimuth(deg: Double?, magnetic: Boolean): String {
        val whole = wholeAzimuth(deg) ?: return DASH
        return String.format(Locale.US, "%03d° %s", whole, suffix(magnetic))
    }

    /** The arrow reads at a glance; a level leg gets none. */
    fun slope(deg: Double): String {
        val whole = Math.round(deg)
        return when {
            whole > 0 -> "▲ +$whole°"
            whole < 0 -> "▼ $whole°"
            else -> "0°"
        }
    }

    fun signedDegrees(deg: Double): String = String.format(Locale.US, "%+d°", Math.round(deg))

    fun grade(pct: Double?): String = if (pct == null || !pct.isFinite()) DASH else "${Math.round(pct)} %"

    /** North rotations in tenths, the steppers' finest step being half a degree. */
    fun rotation(deg: Double): String = String.format(Locale.US, "%+.1f°", tenths(deg))

    /** The readout's second line; the curve mark says the path strays from the chord. */
    fun details(leg: LegMeasure): String = listOf(
        "horiz ${number(leg.horizontalM)}",
        "Δh ${signedMetres(leg.heightChangeM)}",
        grade(leg.gradePct),
        "path ${pathNumber(leg)}",
    ).joinToString(SEPARATOR)

    /** Stretch selections only: the passage direction and how straight the path is. */
    fun stretchLine(measure: StretchMeasure, magnetic: Boolean): String {
        val straight = measure.leg.straightness?.let { String.format(Locale.US, "%.2f", it) } ?: DASH
        return "fitted ${azimuth(measure.fittedAzimuthDeg, magnetic)}${SEPARATOR}straight $straight"
    }

    /** The line a long press on the readout copies, readable on its own in a note or a message. */
    fun copyLine(from: String, to: String, leg: LegMeasure, magnetic: Boolean): String =
        "$from → $to: ${metres(leg.lengthM)}, horiz ${metres(leg.horizontalM)}, " +
            "${azimuth(leg.azimuthDeg, magnetic)}, ${signedDegrees(leg.slopeDeg)} " +
            "(Δh ${signedMetres(leg.heightChangeM)} m), path ${metres(leg.pathM)}"

    /** The top bar's north chip: the rotation in use and how many compass readings set it. */
    fun northChip(north: NorthSolution, magnetic: Boolean): String {
        val chip = "N ${rotation(north.rotationDeg)} ${suffix(magnetic)}"
        return when (val used = north.usedCount) {
            0 -> chip
            1 -> "${chip}${SEPARATOR}1 ref"
            else -> "${chip}${SEPARATOR}$used refs"
        }
    }

    /**
     * The scrubber's label: the clock time at the cursor when the trip's start is known (it matches
     * a note taken underground), else the time since the path's start.
     */
    fun cursorLabel(startedAtEpochMs: Long?, elapsedNs: Long, distanceM: Double, zone: ZoneId): String {
        val time = if (startedAtEpochMs == null) {
            formatDuration(elapsedNs / 1e9)
        } else {
            CLOCK.withZone(zone).format(Instant.ofEpochMilli(startedAtEpochMs + elapsedNs / 1_000_000L))
        }
        return "$time$SEPARATOR${metres(distanceM)}"
    }

    /** The share sheet's text: which trip, run and north a CSV was measured on. */
    fun shareText(tripName: String, runId: Int, raw: Boolean, north: NorthSolution): String =
        "$tripName · Run $runId${if (raw) " (raw path)" else ""}, ${SurveyCsv.correctionText(north)}"

    /** The header carries the suffix so every azimuth cell stays short. */
    fun tableHeader(magnetic: Boolean): List<String> =
        listOf("From", "To", "Length", "Azimuth ${suffix(magnetic)}", "Slope", "Δh", "Path")

    fun tableRow(leg: TraverseLeg): List<String> {
        val m = leg.measure
        val azimuth = wholeAzimuth(m.azimuthDeg)?.let { String.format(Locale.US, "%03d°", it) } ?: DASH
        return listOf(
            leg.from.name,
            leg.to.name,
            number(m.lengthM),
            azimuth,
            signedDegrees(m.slopeDeg),
            signedMetres(m.heightChangeM),
            pathNumber(m),
        )
    }

    /** Height changes sum to the net climb; azimuth and slope have no meaningful sum. */
    fun totalsRow(totals: LegTotals): List<String> =
        listOf("Total", "", number(totals.lengthM), "", "", signedMetres(totals.heightChangeM), number(totals.pathM))

    /** A typed angle: a comma works as the decimal point (the keypad offers one), and NaN is refused. */
    fun parseDegrees(text: String): Double? =
        text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

    /** The Detail dialog's names, with the RDP tolerance so the choice is concrete. */
    fun detailLabel(detail: Detail): String = when (detail) {
        Detail.COARSE -> "Coarse (1 m)"
        Detail.NORMAL -> "Normal (0.5 m)"
        Detail.FINE -> "Fine (0.25 m)"
    }

    fun corners(count: Int): String = if (count == 1) "1 corner" else "$count corners"

    /**
     * One compass reading in the North sheet. Bearings come from a hand compass, so they are always
     * magnetic; a reading whose chord is too short on this run stays in the file but is not used.
     */
    fun reference(reference: CompassReference, fit: ReferenceFit?): String {
        val back = if (reference.backBearing) " back-bearing" else ""
        val bearing = azimuth(reference.bearingDeg, magnetic = true) + back
        val line = when (reference.line) {
            ReferenceLine.CHORD -> "point to point"
            ReferenceLine.FITTED -> "passage"
        }
        val residual = fit?.residualDeg?.let { "residual ${rotation(it)}" } ?: "not used: too short"
        return listOf(bearing, line, residual).joinToString(SEPARATOR)
    }

    /** Rounded to 0.1 first, and + 0.0 turns a negative zero positive. */
    private fun tenths(value: Double): Double = Math.round(value * 10.0) / 10.0 + 0.0

    private fun number(m: Double): String = String.format(Locale.US, "%.1f", tenths(m))

    private fun pathNumber(leg: LegMeasure): String = number(leg.pathM) + if (leg.curved) CURVED_MARK else ""

    private fun wholeAzimuth(deg: Double?): Long? =
        if (deg == null || !deg.isFinite()) null else Math.floorMod(Math.round(deg), 360L)
}
```

**Step 4: Run the test and watch it pass**

```bash
./gradlew :app:testDebugUnitTest --tests 'com.stastyle.imumapper.ui.viewer.SurveyFormatTest'
```

Expected: `BUILD SUCCESSFUL`. Only failing tests print; the report is in `app/build/reports/tests/testDebugUnitTest/`.

**Step 5: Commit**

```bash
git add app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/SurveyFormat.kt \
  app/src/test/kotlin/com/stastyle/imumapper/ui/viewer/SurveyFormatTest.kt
git commit -F - <<'EOF'
Format survey distances, azimuths and slopes for display

Survey mode shows the same numbers in the panel, the legs table, the copied line and the share
text, so one pure object formats them all: Locale.US, one decimal for metres and rotations with
no negative zero, whole degrees, and a dash for a value that cannot be measured.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

### Task 20: Survey controller: geometry, selection, cursor and readout

**Files:**
- Create: `app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/SurveyController.kt`
- Test: `app/src/test/kotlin/com/stastyle/imumapper/ui/viewer/SurveyControllerTest.kt`

This task creates the file with every type of architecture section 2.4 (`SurveyGeometry`,
`SurveySelection`, `SurveyState`, `SurveyOpen`, `SurveyReadout`, `AzimuthWarning`, `AzimuthPreview`) and
the `SurveyController` functions that are not edits. Tasks 21 and 22 add the edits. Nothing here touches
a file or Android: the view model (Task 23) owns the state and saves it.

The fixture is `SurveyFixtures.lWalk()`: points 0..30 every 0.5 s and 0.5 m, north to (0, 10) at point
20, then east to (5, 10). Every gap is one step (0.5 s, shorter than 1.5 × the median step), so time
placement is linear and point i is `SurveyFixtures.tNs(i)` at i × 0.5 m. Seeding gives Start (id 1, point
0), Junction 1 (id 2, point 10), C1 (id 4, point 20) and End (id 3, point 30), in that traverse order.
Worked numbers used below: a tap 7.5 m along lands on point 15, after Junction 1 and before C1, so the
stretch is (2, 4); Start to C1 is 10 m due north; Start, C1, End has hops of 10 m and 5 m and a straight
line of √125 m; turning by +10° about the start moves the corner (0, 10) to (10 sin 10°, 10 cos 10°),
from x' = x cos θ + y sin θ and y' = −x sin θ + y cos θ with the origin at (0, 0).

**Step 1: Write the failing test**

Create `app/src/test/kotlin/com/stastyle/imumapper/ui/viewer/SurveyControllerTest.kt`:

```kotlin
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
```

**Step 2: Run the test and watch it fail**

```bash
./gradlew :app:testDebugUnitTest --tests 'com.stastyle.imumapper.ui.viewer.SurveyControllerTest'
```

Expected: `BUILD FAILED` in `:app:compileDebugUnitTestKotlin`. The first error is
`e: file:///X:/IMU-mapper-survey/app/src/test/kotlin/com/stastyle/imumapper/ui/viewer/SurveyControllerTest.kt:28:23 Unresolved reference 'SurveyGeometry'.`.

**Step 3: Write the implementation**

Create `app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/SurveyController.kt`:

```kotlin
package com.stastyle.imumapper.ui.viewer

import com.stastyle.imumapper.data.SurveyLoad
import com.stastyle.imumapper.pipeline.core.PathResult
import com.stastyle.imumapper.pipeline.survey.ChainMeasure
import com.stastyle.imumapper.pipeline.survey.Measure
import com.stastyle.imumapper.pipeline.survey.NorthFrame
import com.stastyle.imumapper.pipeline.survey.PathTimeline
import com.stastyle.imumapper.pipeline.survey.StationKind
import com.stastyle.imumapper.pipeline.survey.StretchMeasure
import com.stastyle.imumapper.pipeline.survey.SurveyDoc
import com.stastyle.imumapper.pipeline.survey.SurveyStations
import com.stastyle.imumapper.pipeline.survey.Traverse
import com.stastyle.imumapper.render.LayerSelection
import kotlin.math.abs

/** The shown run's path before and after the north rotation; cached by ViewerViewModel per shown result and angle. */
class SurveyGeometry private constructor(
    /** The shown result (raw view already applied). */
    val shown: PathResult,
    val runId: Int,
    val raw: Boolean,
    /** Timeline of [shown]: compass references are solved against it. */
    val plain: PathTimeline,
    val rotationDeg: Double,
    /** [shown] turned by [rotationDeg]: the scene and every number use it. */
    val framed: PathResult,
    val timeline: PathTimeline,
) {
    /** This geometry turned by [rotationDeg]; this instance when the angle is unchanged. */
    fun rotated(rotationDeg: Double): SurveyGeometry =
        if (rotationDeg == this.rotationDeg) this else build(shown, runId, raw, plain, rotationDeg)

    companion object {
        /** [shown] must have at least one point. With 0 the framed result and timeline are the plain ones. */
        fun of(shown: PathResult, runId: Int, raw: Boolean, rotationDeg: Double = 0.0): SurveyGeometry =
            build(shown, runId, raw, PathTimeline(shown.points), rotationDeg)

        /** Rotating a result and building a timeline each walk every point, so an angle of 0 reuses the plain ones. */
        private fun build(
            shown: PathResult,
            runId: Int,
            raw: Boolean,
            plain: PathTimeline,
            rotationDeg: Double,
        ): SurveyGeometry {
            if (rotationDeg == 0.0) return SurveyGeometry(shown, runId, raw, plain, 0.0, shown, plain)
            val framed = NorthFrame.rotate(shown, rotationDeg)
            return SurveyGeometry(shown, runId, raw, plain, rotationDeg, framed, PathTimeline(framed.points))
        }
    }
}

sealed interface SurveySelection {
    data object None : SurveySelection

    /** Stations tapped one after another, in tap order; a station may repeat but not twice in a row. */
    data class Chain(val stationIds: List<Int>) : SurveySelection

    /** The path between two stations; a null end is the path's own start or end. */
    data class Stretch(val fromId: Int?, val toId: Int?) : SurveySelection
}

data class SurveyState(
    val doc: SurveyDoc,
    val selection: SurveySelection = SurveySelection.None,
    /** The scrubber's moment, drawn as the cursor. */
    val cursorNs: Long = 0L,
    /** Earlier docs, oldest first, at most SurveyController.MAX_UNDO. */
    val undo: List<SurveyDoc> = emptyList(),
    /** survey.json could not be read: nothing is edited or saved. */
    val readOnly: Boolean = false,
)

/** Result of opening Survey mode on a trip. */
class SurveyOpen(
    val state: SurveyState,
    /** A new doc was seeded and must be saved now. */
    val seeded: Boolean,
    /** Why the survey is read-only; null otherwise. */
    val error: String?,
)

/** What the panel shows for the selection. [names] feed the selection row. */
sealed interface SurveyReadout {
    val names: List<String>

    /** One station chosen, waiting for the next. */
    data class First(override val names: List<String>) : SurveyReadout

    data class Chain(override val names: List<String>, val measure: ChainMeasure) : SurveyReadout

    data class Stretch(override val names: List<String>, val measure: StretchMeasure) : SurveyReadout
}

enum class AzimuthWarning { SHORT, CROOKED, LARGE_CHANGE, BACK_BEARING }

/** What the Set azimuth dialog shows before the reference is added. */
data class AzimuthPreview(
    /** Current (corrected) chord and fitted azimuths of the selection. */
    val chordDeg: Double?,
    val fittedDeg: Double?,
    /** The rotation with the new reference added, and the change from now, both in (-180, 180]. */
    val rotationDeg: Double,
    val changeDeg: Double,
    val warnings: Set<AzimuthWarning>,
)

/**
 * Survey mode's rules as pure functions of a [SurveyState]: selection, cursor, readout, edits and
 * undo. The view model owns the state, saves the doc and publishes the result; nothing here touches
 * a file or Android, so every rule runs in a JVM test.
 */
object SurveyController {
    const val PATH_START_NAME: String = "Path start"
    const val PATH_END_NAME: String = "Path end"

    // --- selection, cursor and readout: none of these is an edit, so all work read-only ---

    /**
     * Only a missing file seeds (and must be saved). A loaded doc is kept as it is, even when empty,
     * so a trip is seeded once. A malformed file is seeded in memory only and the survey is read-only,
     * so the file is never overwritten.
     */
    fun open(load: SurveyLoad, geo: SurveyGeometry): SurveyOpen {
        val start = geo.timeline.startNs
        return when (load) {
            SurveyLoad.Missing -> SurveyOpen(SurveyState(seededDoc(geo), cursorNs = start), seeded = true, error = null)
            is SurveyLoad.Loaded -> SurveyOpen(SurveyState(load.doc, cursorNs = start), seeded = false, error = null)
            is SurveyLoad.Malformed -> SurveyOpen(
                SurveyState(seededDoc(geo), cursorNs = start, readOnly = true),
                seeded = false,
                error = "survey.json could not be read (${load.message}). " +
                    "Survey mode is read-only and the file is left as it is.",
            )
        }
    }

    /** Taps build a chain; tapping its last station again takes it back, so a mis-tap costs one tap. */
    fun tapStation(state: SurveyState, stationId: Int): SurveyState {
        if (state.doc.stations.none { it.id == stationId }) return state
        val chain = (state.selection as? SurveySelection.Chain)?.stationIds.orEmpty()
        val next = when {
            chain.isEmpty() -> listOf(stationId)
            chain.last() == stationId -> chain.dropLast(1)
            else -> chain + stationId
        }
        return state.copy(selection = if (next.isEmpty()) SurveySelection.None else SurveySelection.Chain(next))
    }

    /** A path tap selects the stretch between the stations either side of that moment and moves the cursor there. */
    fun tapPath(state: SurveyState, geo: SurveyGeometry, distanceM: Double): SurveyState {
        val tNs = geo.timeline.timeAtDistance(distanceM)
        val ordered = SurveyStations.ordered(state.doc.stations)
        val from = ordered.lastOrNull { it.tNs <= tNs }
        val to = ordered.firstOrNull { it.tNs > tNs }
        return state.copy(selection = SurveySelection.Stretch(from?.id, to?.id), cursorNs = tNs)
    }

    /** A row of the Legs table: the stretch of that leg. */
    fun selectLeg(state: SurveyState, fromId: Int, toId: Int): SurveyState =
        state.copy(selection = SurveySelection.Stretch(fromId, toId))

    /** Clearing is an explicit button, so a near miss on the map never loses a selection. */
    fun clearSelection(state: SurveyState): SurveyState = state.copy(selection = SurveySelection.None)

    /** The scrubber works in distance, so standing still takes no room on it. */
    fun setCursor(state: SurveyState, geo: SurveyGeometry, distanceM: Double): SurveyState =
        state.copy(cursorNs = geo.timeline.timeAtDistance(distanceM))

    /** The scrubber's arrows move [delta] path points, the finest step the path has. */
    fun stepCursor(state: SurveyState, geo: SurveyGeometry, delta: Int): SurveyState {
        var tNs = state.cursorNs
        repeat(abs(delta)) {
            tNs = if (delta < 0) geo.timeline.previousPointNs(tNs) else geo.timeline.nextPointNs(tNs)
        }
        return state.copy(cursorNs = tNs)
    }

    /** The two moments a pair (chain of exactly two) or a stretch spans; null otherwise. */
    fun selectionEnds(state: SurveyState, geo: SurveyGeometry): Pair<Long, Long>? =
        when (val selection = state.selection) {
            SurveySelection.None -> null
            is SurveySelection.Chain -> {
                val ids = selection.stationIds
                if (ids.size != 2) {
                    null
                } else {
                    val from = stationTime(state, ids[0]) ?: return null
                    val to = stationTime(state, ids[1]) ?: return null
                    from to to
                }
            }
            is SurveySelection.Stretch -> {
                val from = selection.fromId?.let { stationTime(state, it) ?: return null } ?: geo.timeline.startNs
                val to = selection.toId?.let { stationTime(state, it) ?: return null } ?: geo.timeline.endNs
                from to to
            }
        }

    /** The id of the one selected CORNER or USER station ("Move here"); null otherwise. */
    fun movableStationId(state: SurveyState): Int? {
        val id = (state.selection as? SurveySelection.Chain)?.stationIds?.singleOrNull() ?: return null
        val kind = state.doc.stations.firstOrNull { it.id == id }?.kind ?: return null
        return if (kind == StationKind.CORNER || kind == StationKind.USER) id else null
    }

    /** The selection in render terms, so render does not depend on ui.viewer. */
    fun layerSelection(state: SurveyState, geo: SurveyGeometry): LayerSelection =
        when (val selection = state.selection) {
            SurveySelection.None -> LayerSelection()
            is SurveySelection.Chain -> LayerSelection(chainIds = selection.stationIds)
            is SurveySelection.Stretch -> LayerSelection(
                stretchNs = selectionEnds(state, geo),
                stretchIds = listOfNotNull(selection.fromId, selection.toId),
            )
        }

    /** Numbers for the panel, measured on the shown, north-corrected path. */
    fun readout(state: SurveyState, geo: SurveyGeometry): SurveyReadout? {
        val byId = state.doc.stations.associateBy { it.id }
        return when (val selection = state.selection) {
            SurveySelection.None -> null
            is SurveySelection.Chain -> {
                val stations = selection.stationIds.mapNotNull { byId[it] }
                when (stations.size) {
                    0 -> null
                    1 -> SurveyReadout.First(listOf(stations[0].name))
                    else -> SurveyReadout.Chain(
                        stations.map { it.name },
                        Traverse.chain(stations.map { it.tNs }, geo.timeline),
                    )
                }
            }
            is SurveySelection.Stretch -> {
                val (fromNs, toNs) = selectionEnds(state, geo) ?: return null
                val from = selection.fromId?.let { byId[it]?.name } ?: PATH_START_NAME
                val to = selection.toId?.let { byId[it]?.name } ?: PATH_END_NAME
                SurveyReadout.Stretch(listOf(from, to), Measure.stretch(geo.timeline, fromNs, toNs))
            }
        }
    }

    // --- helpers ---

    private fun seededDoc(geo: SurveyGeometry): SurveyDoc =
        SurveyDoc(stations = SurveyStations.seed(geo.timeline, geo.framed.annotations))

    private fun stationTime(state: SurveyState, stationId: Int): Long? =
        state.doc.stations.firstOrNull { it.id == stationId }?.tNs
}
```

**Step 4: Run the test and watch it pass**

```bash
./gradlew :app:testDebugUnitTest --tests 'com.stastyle.imumapper.ui.viewer.SurveyControllerTest'
```

Expected: `BUILD SUCCESSFUL`. Only failing tests print; the report is in `app/build/reports/tests/testDebugUnitTest/`.

**Step 5: Commit**

```bash
git add app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/SurveyController.kt \
  app/src/test/kotlin/com/stastyle/imumapper/ui/viewer/SurveyControllerTest.kt
git commit -F - <<'EOF'
Select stations and stretches of a trip and read their distance, azimuth and slope

The survey rules live in a pure controller so they run in JVM tests: station taps build a chain,
a path tap selects the stretch between the stations either side and moves the cursor there, and
the readout measures on the shown, north-corrected path. The geometry caches the rotated result so
a survey edit does not rotate the path again.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

### Task 21: Survey controller: station edits, detail and undo

**Files:**
- Modify: `app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/SurveyController.kt` (the imports, the constants, and two blocks added before
  `// --- helpers ---` and at the end of the object)
- Test: `app/src/test/kotlin/com/stastyle/imumapper/ui/viewer/SurveyEditsTest.kt`

Every edit goes through one private `edit()`: it refuses when read-only or when the doc did not change,
otherwise pushes the old doc on the undo stack (keeping the newest `MAX_UNDO`) and prunes the selection
against the new stations. On the L walk every Detail level finds exactly one corner, at point 20 (10 m):
the path is two straight legs. A corner is dropped within 1.5 m of path distance of a kept station, so a
station at point 21 (10.5 m) leaves no corner, while one at point 25 (12.5 m) does not stop it. With
undo capped at 50, 60 renames of Start to A0..A59 keep the docs from before edits 10..59, so the oldest
kept name is A9 and the newest A58.

**Step 1: Write the failing test**

Create `app/src/test/kotlin/com/stastyle/imumapper/ui/viewer/SurveyEditsTest.kt`:

```kotlin
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
```

**Step 2: Run the test and watch it fail**

```bash
./gradlew :app:testDebugUnitTest --tests 'com.stastyle.imumapper.ui.viewer.SurveyEditsTest'
```

Expected: `BUILD FAILED` in `:app:compileDebugUnitTestKotlin`. The first error is
`e: file:///X:/IMU-mapper-survey/app/src/test/kotlin/com/stastyle/imumapper/ui/viewer/SurveyEditsTest.kt:34:38 Unresolved reference 'addStation'.`.

**Step 3: Write the implementation**

Apply these replacements to `app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/SurveyController.kt`, in order:

a. Import `Detail`. Old (at line 5):

```kotlin
import com.stastyle.imumapper.pipeline.survey.ChainMeasure
import com.stastyle.imumapper.pipeline.survey.Measure
```

New:

```kotlin
import com.stastyle.imumapper.pipeline.survey.ChainMeasure
import com.stastyle.imumapper.pipeline.survey.Detail
import com.stastyle.imumapper.pipeline.survey.Measure
```

b. The undo cap. Old (at line 117):

```kotlin
    const val PATH_END_NAME: String = "Path end"
```

New:

```kotlin
    const val PATH_END_NAME: String = "Path end"
    const val MAX_UNDO: Int = 50
```

c. The station edits and undo, above `// --- helpers ---`. Old (at line 246):

```kotlin
    // --- helpers ---
```

New:

```kotlin
    // --- station edits and undo: each pushes one undo entry and is refused read-only ---

    /** A USER station at [tNs], named after the largest S number in use. */
    fun addStation(state: SurveyState, tNs: Long): SurveyState {
        val stations = state.doc.stations
        val added = SurveyStations.ordered(stations + SurveyStations.user(stations, tNs))
        return edit(state, state.doc.copy(stations = added))
    }

    /** Only corners and user stations move; a moved corner becomes USER so a Detail change keeps it. */
    fun moveStation(state: SurveyState, stationId: Int, tNs: Long): SurveyState {
        val station = state.doc.stations.firstOrNull { it.id == stationId } ?: return state
        if (station.kind != StationKind.CORNER && station.kind != StationKind.USER) return state
        val moved = station.copy(kind = StationKind.USER, tNs = tNs)
        val stations = state.doc.stations.map { if (it.id == stationId) moved else it }
        return edit(state, state.doc.copy(stations = SurveyStations.ordered(stations)))
    }

    /** Trimmed; a blank name is refused, since the table and the CSV name every leg by its stations. */
    fun renameStation(state: SurveyState, stationId: Int, name: String): SurveyState {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return state
        val stations = state.doc.stations.map { if (it.id == stationId) it.copy(name = trimmed) else it }
        return edit(state, state.doc.copy(stations = stations))
    }

    /** No tombstone: a deleted corner comes back only when Detail changes. */
    fun deleteStation(state: SurveyState, stationId: Int): SurveyState =
        edit(state, state.doc.copy(stations = state.doc.stations.filter { it.id != stationId }))

    /** Regenerates the corners on the shown path; new corners may reuse ids, so the selection is cleared. */
    fun setDetail(state: SurveyState, geo: SurveyGeometry, detail: Detail): SurveyState {
        if (detail == state.doc.detail) return state
        val stations = SurveyStations.regenerateCorners(state.doc.stations, geo.timeline, detail)
        val next = edit(state, state.doc.copy(detail = detail, stations = stations))
        return if (next === state) state else next.copy(selection = SurveySelection.None)
    }

    /** How many corners each Detail would give now, so the dialog shows the effect before the choice. */
    fun cornerCounts(state: SurveyState, geo: SurveyGeometry): Map<Detail, Int> =
        Detail.entries.associateWith { detail ->
            SurveyStations.regenerateCorners(state.doc.stations, geo.timeline, detail)
                .count { it.kind == StationKind.CORNER }
        }

    /** Back to the doc before the last edit; the selection is pruned against it, the cursor stays. */
    fun undo(state: SurveyState): SurveyState {
        if (state.readOnly) return state
        val previous = state.undo.lastOrNull() ?: return state
        return state.copy(doc = previous, undo = state.undo.dropLast(1), selection = prune(state.selection, previous))
    }

    // --- helpers ---
```

d. `edit()` and `prune()`, at the end of the object after `stationTime`. Old (at line 304):

```kotlin
        state.doc.stations.firstOrNull { it.id == stationId }?.tNs
}
```

New:

```kotlin
        state.doc.stations.firstOrNull { it.id == stationId }?.tNs

    /**
     * The one way a doc changes: refused read-only or when nothing changed; otherwise the old doc goes
     * on the undo stack (the oldest dropped past MAX_UNDO) and the selection loses deleted stations.
     */
    private fun edit(state: SurveyState, doc: SurveyDoc): SurveyState {
        if (state.readOnly || doc == state.doc) return state
        return state.copy(
            doc = doc,
            undo = (state.undo + state.doc).takeLast(MAX_UNDO),
            selection = prune(state.selection, doc),
        )
    }

    /** Drops stations [doc] no longer has; a chain keeps its order without the same station twice in a row. */
    private fun prune(selection: SurveySelection, doc: SurveyDoc): SurveySelection {
        val ids = doc.stations.mapTo(HashSet()) { it.id }
        return when (selection) {
            SurveySelection.None -> selection
            is SurveySelection.Chain -> {
                val kept = selection.stationIds.filter { it in ids }
                val chain = kept.filterIndexed { i, id -> i == 0 || kept[i - 1] != id }
                when {
                    chain.isEmpty() -> SurveySelection.None
                    chain == selection.stationIds -> selection
                    else -> SurveySelection.Chain(chain)
                }
            }
            is SurveySelection.Stretch -> {
                val ends = listOfNotNull(selection.fromId, selection.toId)
                if (ends.all { it in ids }) selection else SurveySelection.None
            }
        }
    }
}
```

**Step 4: Run the tests and watch them pass**

```bash
./gradlew :app:testDebugUnitTest --tests 'com.stastyle.imumapper.ui.viewer.Survey*'
```

Expected: `BUILD SUCCESSFUL`. Only failing tests print; the report is in `app/build/reports/tests/testDebugUnitTest/`.

**Step 5: Commit**

```bash
git add app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/SurveyController.kt \
  app/src/test/kotlin/com/stastyle/imumapper/ui/viewer/SurveyEditsTest.kt
git commit -F - <<'EOF'
Add, move, rename and delete survey stations with undo

Every edit goes through one function that refuses a read-only survey, keeps the last fifty docs
for undo and drops deleted stations from the selection. A moved corner becomes a user station so
a later Detail change keeps it, and a Detail change places the automatic corners again.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

### Task 22: Survey controller: north edits and the azimuth preview

**Files:**
- Modify: `app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/SurveyController.kt` (imports, constants, a block before `// --- helpers ---` and one at
  the end of the object)
- Test: `app/src/test/kotlin/com/stastyle/imumapper/ui/viewer/SurveyNorthEditsTest.kt`

The rotation is solved by `NorthSolver` (Task 7) from the facts in the doc; this task only edits the
facts and previews a new reading. Worked numbers: Start to C1 measures 0° over 10 m, so a 10° bearing
gives θ = atan2(10 sin 10°, 10 cos 10°) = 10°, a change of +10° and no warning (10 m is not under 10 m,
the path is straight, and 10° is under 15°). 50° is over both 15° and 45°. Start to Junction 1 is 5 m,
under 10 m. Start to End cuts the corner: its chord reads atan2(5, 10) = 26.6° over √125 = 11.2 m, but
the path walks 15 m, straightness 0.75 < 0.9; a bearing equal to the chord changes nothing, so CROOKED is
the only warning. A manual 190° wraps to −170°.

**Step 1: Write the failing test**

Create `app/src/test/kotlin/com/stastyle/imumapper/ui/viewer/SurveyNorthEditsTest.kt`:

```kotlin
package com.stastyle.imumapper.ui.viewer

import com.stastyle.imumapper.SurveyFixtures
import com.stastyle.imumapper.data.SurveyLoad
import com.stastyle.imumapper.pipeline.survey.CompassReference
import com.stastyle.imumapper.pipeline.survey.NorthSolver
import com.stastyle.imumapper.pipeline.survey.ReferenceLine
import com.stastyle.imumapper.pipeline.survey.SurveyAngles
import kotlin.math.atan2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Compass references, the manual rotation and the Set azimuth preview on the L walk. Start (id 1) to
 * C1 (id 4) is 10 m due north, Start to Junction 1 (id 2) 5 m north, and Start to End (id 3) cuts the
 * corner to (5, 10) over 15 m of path.
 */
class SurveyNorthEditsTest {

    private val geo = SurveyGeometry.of(SurveyFixtures.lWalk(), runId = 1, raw = false)
    private val seeded = SurveyController.open(SurveyLoad.Missing, geo).state

    private fun t(index: Int): Long = SurveyFixtures.tNs(index)

    private fun chain(vararg ids: Int): SurveyState = ids.fold(seeded) { s, id -> SurveyController.tapStation(s, id) }

    private fun north(state: SurveyState): Double = NorthSolver.solve(state.doc, geo.plain, geo.runId).rotationDeg

    @Test
    fun bearingOnAPairAddsAChordReferenceThatTurnsNorth() {
        val state = SurveyController.addReference(chain(1, 4), geo, 10.0, false, ReferenceLine.CHORD)
        assertEquals(
            listOf(CompassReference(id = 1, fromNs = t(0), toNs = t(20), bearingDeg = 10.0)),
            state.doc.references,
        )
        assertEquals(1, state.undo.size)
        assertEquals(10.0, north(state), 1e-9)

        // Bearings are stored in [0, 360) and ids keep counting.
        val second = SurveyController.addReference(state, geo, 370.0, backBearing = true, line = ReferenceLine.FITTED)
        val added = second.doc.references.last()
        assertEquals(2, added.id)
        assertEquals(10.0, added.bearingDeg, 1e-9)
        assertTrue(added.backBearing)
        assertEquals(ReferenceLine.FITTED, added.line)
    }

    @Test
    fun referenceNeedsAPairOrStretchWithAChordAndAFiniteBearing() {
        assertSame(seeded, SurveyController.addReference(seeded, geo, 10.0, false, ReferenceLine.CHORD))
        val one = chain(1)
        assertSame(one, SurveyController.addReference(one, geo, 10.0, false, ReferenceLine.CHORD))
        val pair = chain(1, 4)
        assertSame(pair, SurveyController.addReference(pair, geo, Double.NaN, false, ReferenceLine.CHORD))
        assertSame(pair, SurveyController.addReference(pair, geo, Double.POSITIVE_INFINITY, false, ReferenceLine.CHORD))
        // A stretch from a station to itself has no chord, so its bearing could never be used.
        val nowhere = SurveyController.selectLeg(seeded, 2, 2)
        assertSame(nowhere, SurveyController.addReference(nowhere, geo, 10.0, false, ReferenceLine.CHORD))
        val stretch = SurveyController.selectLeg(seeded, 2, 4)
        val fitted = SurveyController.addReference(stretch, geo, 0.0, false, ReferenceLine.FITTED)
        assertEquals(1, fitted.doc.references.size)
    }

    @Test
    fun manualRotationIsWrappedTaggedAndRefusedWhileReferencesExist() {
        val manual = SurveyController.setManualRotation(seeded, 190.0, runId = 1)
        assertEquals(-170.0, manual.doc.manualRotationDeg, 1e-9)
        assertEquals(1, manual.doc.manualRotationRunId)
        assertSame(manual, SurveyController.setManualRotation(manual, Double.NaN, runId = 1))

        val referenced = SurveyController.addReference(chain(1, 4), geo, 10.0, false, ReferenceLine.CHORD)
        assertSame(referenced, SurveyController.setManualRotation(referenced, 5.0, runId = 1))
    }

    @Test
    fun confirmingTagsTheManualRotationWithTheShownRun() {
        val manual = SurveyController.setManualRotation(seeded, 5.0, runId = 1)
        val confirmed = SurveyController.confirmManualRotation(manual, runId = 2)
        assertEquals(2, confirmed.doc.manualRotationRunId)
        assertEquals(5.0, confirmed.doc.manualRotationDeg)
        assertEquals(2, confirmed.undo.size)
    }

    @Test
    fun resetClearsReferencesAndTheManualRotationAndDeleteRemovesOne() {
        val manual = SurveyController.setManualRotation(chain(1, 4), 5.0, runId = 1)
        val both = SurveyController.addReference(manual, geo, 10.0, false, ReferenceLine.CHORD)
        assertEquals(1, both.doc.references.size)

        val reset = SurveyController.resetNorth(both)
        assertTrue(reset.doc.references.isEmpty())
        assertEquals(0.0, reset.doc.manualRotationDeg)
        assertNull(reset.doc.manualRotationRunId)
        assertEquals(both.doc.stations, reset.doc.stations)

        val referenceId = both.doc.references.single().id
        assertTrue(SurveyController.deleteReference(both, referenceId).doc.references.isEmpty())
        assertSame(both, SurveyController.deleteReference(both, 99))
    }

    @Test
    fun previewOnAStraightTenMetrePairWarnsOfNothing() {
        val preview = assertNotNull(SurveyController.azimuthPreview(chain(1, 4), geo, 10.0, false, ReferenceLine.CHORD))
        assertEquals(0.0, preview.chordDeg!!, 1e-9)
        assertEquals(0.0, SurveyAngles.wrapDeg(preview.fittedDeg!!), 1e-9)
        assertEquals(10.0, preview.rotationDeg, 1e-9)
        assertEquals(10.0, preview.changeDeg, 1e-9)
        assertEquals(emptySet(), preview.warnings)
        assertNull(SurveyController.azimuthPreview(seeded, geo, 10.0, false, ReferenceLine.CHORD))
        assertNull(SurveyController.azimuthPreview(chain(1, 4), geo, Double.NaN, false, ReferenceLine.CHORD))
    }

    @Test
    fun previewOfFiftyDegreesAsksAboutABackBearing() {
        val preview = assertNotNull(SurveyController.azimuthPreview(chain(1, 4), geo, 50.0, false, ReferenceLine.CHORD))
        assertEquals(setOf(AzimuthWarning.LARGE_CHANGE, AzimuthWarning.BACK_BEARING), preview.warnings)
    }

    @Test
    fun previewOnAFiveMetrePairSaysItIsShort() {
        val preview = assertNotNull(SurveyController.azimuthPreview(chain(1, 2), geo, 0.0, false, ReferenceLine.CHORD))
        assertEquals(setOf(AzimuthWarning.SHORT), preview.warnings)
    }

    @Test
    fun previewAcrossTheCornerSaysItIsCrooked() {
        // Start to End reads atan2(5, 10) = 26.6 degrees over 11.2 m, but the path walks 15 m: straightness 0.75.
        val chordDeg = Math.toDegrees(atan2(5.0, 10.0))
        val preview = assertNotNull(
            SurveyController.azimuthPreview(chain(1, 3), geo, chordDeg, false, ReferenceLine.CHORD),
        )
        assertEquals(chordDeg, preview.chordDeg!!, 1e-9)
        assertEquals(0.0, preview.changeDeg, 1e-9)
        assertEquals(setOf(AzimuthWarning.CROOKED), preview.warnings)
    }

    @Test
    fun previewChangeIsMeasuredFromTheRotationInUse() {
        val first = SurveyController.addReference(chain(1, 4), geo, 10.0, false, ReferenceLine.CHORD)
        val preview = assertNotNull(
            SurveyController.azimuthPreview(first, geo.rotated(10.0), 10.0, false, ReferenceLine.CHORD),
        )
        // The pair already reads 010 on the corrected path, and a second identical reading changes nothing.
        assertEquals(10.0, preview.chordDeg!!, 1e-9)
        assertEquals(10.0, preview.rotationDeg, 1e-9)
        assertEquals(0.0, preview.changeDeg, 1e-9)
    }

    @Test
    fun northEditsAreRefusedReadOnlyButThePreviewStillReads() {
        val readOnly = listOf(1, 4).fold(SurveyController.open(SurveyLoad.Malformed("bad"), geo).state) { s, id ->
            SurveyController.tapStation(s, id)
        }
        assertSame(readOnly, SurveyController.addReference(readOnly, geo, 10.0, false, ReferenceLine.CHORD))
        assertSame(readOnly, SurveyController.setManualRotation(readOnly, 5.0, runId = 1))
        assertSame(readOnly, SurveyController.confirmManualRotation(readOnly, runId = 2))
        assertSame(readOnly, SurveyController.resetNorth(readOnly))
        assertNotNull(SurveyController.azimuthPreview(readOnly, geo, 10.0, false, ReferenceLine.CHORD))
    }
}
```

**Step 2: Run the test and watch it fail**

```bash
./gradlew :app:testDebugUnitTest --tests 'com.stastyle.imumapper.ui.viewer.SurveyNorthEditsTest'
```

Expected: `BUILD FAILED` in `:app:compileDebugUnitTestKotlin`. The first error is
`e: file:///X:/IMU-mapper-survey/app/src/test/kotlin/com/stastyle/imumapper/ui/viewer/SurveyNorthEditsTest.kt:35:38 Unresolved reference 'addReference'.`.

**Step 3: Write the implementation**

Apply these replacements to `app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/SurveyController.kt`, in order:

a. Import `CompassReference`. Old (at line 5):

```kotlin
import com.stastyle.imumapper.pipeline.survey.ChainMeasure
import com.stastyle.imumapper.pipeline.survey.Detail
```

New:

```kotlin
import com.stastyle.imumapper.pipeline.survey.ChainMeasure
import com.stastyle.imumapper.pipeline.survey.CompassReference
import com.stastyle.imumapper.pipeline.survey.Detail
```

b. Import `NorthSolver` and `ReferenceLine`. Old (at line 9):

```kotlin
import com.stastyle.imumapper.pipeline.survey.NorthFrame
import com.stastyle.imumapper.pipeline.survey.PathTimeline
import com.stastyle.imumapper.pipeline.survey.StationKind
```

New:

```kotlin
import com.stastyle.imumapper.pipeline.survey.NorthFrame
import com.stastyle.imumapper.pipeline.survey.NorthSolver
import com.stastyle.imumapper.pipeline.survey.PathTimeline
import com.stastyle.imumapper.pipeline.survey.ReferenceLine
import com.stastyle.imumapper.pipeline.survey.StationKind
```

c. Import `SurveyAngles`. Old (at line 14):

```kotlin
import com.stastyle.imumapper.pipeline.survey.StretchMeasure
import com.stastyle.imumapper.pipeline.survey.SurveyDoc
```

New:

```kotlin
import com.stastyle.imumapper.pipeline.survey.StretchMeasure
import com.stastyle.imumapper.pipeline.survey.SurveyAngles
import com.stastyle.imumapper.pipeline.survey.SurveyDoc
```

d. The Set azimuth limits. Old (at line 122):

```kotlin
    const val MAX_UNDO: Int = 50
```

New:

```kotlin
    const val MAX_UNDO: Int = 50
    const val MIN_REFERENCE_HORIZONTAL_M: Double = 10.0
    const val MIN_STRAIGHTNESS: Double = 0.9
    const val MAX_FIT_GAP_DEG: Double = 3.0
    const val LARGE_CHANGE_DEG: Double = 15.0
    const val BACK_BEARING_CHANGE_DEG: Double = 45.0
```

e. The north edits and the preview, above `// --- helpers ---`. Old (at line 307):

```kotlin
    // --- helpers ---
```

New:

```kotlin
    // --- north edits: the facts the rotation is solved from; each is one undo entry, refused read-only ---

    /**
     * A hand-compass bearing for the selected pair or stretch. It needs a chord azimuth on the
     * uncorrected path, since a reference without one could never enter the solve.
     */
    fun addReference(
        state: SurveyState,
        geo: SurveyGeometry,
        bearingDeg: Double,
        backBearing: Boolean,
        line: ReferenceLine,
    ): SurveyState {
        val reference = newReference(state, geo, bearingDeg, backBearing, line) ?: return state
        if (Measure.leg(geo.plain, reference.fromNs, reference.toNs).azimuthDeg == null) return state
        return edit(state, state.doc.copy(references = state.doc.references + reference))
    }

    fun deleteReference(state: SurveyState, referenceId: Int): SurveyState =
        edit(state, state.doc.copy(references = state.doc.references.filter { it.id != referenceId }))

    /** Refused while references exist, because they set north; tagged with the run it was set on. */
    fun setManualRotation(state: SurveyState, rotationDeg: Double, runId: Int): SurveyState {
        if (state.doc.references.isNotEmpty() || !rotationDeg.isFinite()) return state
        val doc = state.doc.copy(manualRotationDeg = SurveyAngles.wrapDeg(rotationDeg), manualRotationRunId = runId)
        return edit(state, doc)
    }

    /** The user checked that the manual rotation fits [runId] too (a re-process can change the heading). */
    fun confirmManualRotation(state: SurveyState, runId: Int): SurveyState =
        edit(state, state.doc.copy(manualRotationRunId = runId))

    fun resetNorth(state: SurveyState): SurveyState =
        edit(state, state.doc.copy(references = emptyList(), manualRotationDeg = 0.0, manualRotationRunId = null))

    /**
     * What adding the reference would do, shown before it is added: the selection's readings now, the
     * rotation after, and a warning for each way a reading usually goes wrong. Null without a pair or
     * stretch, or for a bearing that is not a number.
     */
    fun azimuthPreview(
        state: SurveyState,
        geo: SurveyGeometry,
        bearingDeg: Double,
        backBearing: Boolean,
        line: ReferenceLine,
    ): AzimuthPreview? {
        val reference = newReference(state, geo, bearingDeg, backBearing, line) ?: return null
        val measure = Measure.stretch(geo.timeline, reference.fromNs, reference.toNs)
        val now = NorthSolver.solve(state.doc, geo.plain, geo.runId).rotationDeg
        val withReference = state.doc.copy(references = state.doc.references + reference)
        val after = NorthSolver.solve(withReference, geo.plain, geo.runId).rotationDeg
        val change = SurveyAngles.wrapDeg(after - now)
        val leg = measure.leg
        val chord = leg.azimuthDeg
        val fitted = measure.fittedAzimuthDeg
        val bent = leg.straightness?.let { it < MIN_STRAIGHTNESS } == true
        val fitGap = chord != null && fitted != null && abs(SurveyAngles.wrapDeg(chord - fitted)) > MAX_FIT_GAP_DEG
        val warnings = buildSet {
            if (leg.horizontalM < MIN_REFERENCE_HORIZONTAL_M) add(AzimuthWarning.SHORT)
            if (bent || fitGap) add(AzimuthWarning.CROOKED)
            if (abs(change) > LARGE_CHANGE_DEG) add(AzimuthWarning.LARGE_CHANGE)
            if (abs(change) > BACK_BEARING_CHANGE_DEG) add(AzimuthWarning.BACK_BEARING)
        }
        return AzimuthPreview(chord, fitted, after, change, warnings)
    }

    // --- helpers ---
```

f. `newReference()`, at the end of the object after `prune`. Old (at line 411):

```kotlin
                if (ends.all { it in ids }) selection else SurveySelection.None
            }
        }
    }
}
```

New:

```kotlin
                if (ends.all { it in ids }) selection else SurveySelection.None
            }
        }
    }

    /** The reference a bearing on the current selection would add; null without a pair or a finite bearing. */
    private fun newReference(
        state: SurveyState,
        geo: SurveyGeometry,
        bearingDeg: Double,
        backBearing: Boolean,
        line: ReferenceLine,
    ): CompassReference? {
        if (!bearingDeg.isFinite()) return null
        val (fromNs, toNs) = selectionEnds(state, geo) ?: return null
        return CompassReference(
            id = (state.doc.references.maxOfOrNull { it.id } ?: 0) + 1,
            fromNs = fromNs,
            toNs = toNs,
            bearingDeg = SurveyAngles.to360(bearingDeg),
            backBearing = backBearing,
            line = line,
        )
    }
}
```

**Step 4: Run the tests and watch them pass**

```bash
./gradlew :app:testDebugUnitTest --tests 'com.stastyle.imumapper.ui.viewer.Survey*'
```

Expected: `BUILD SUCCESSFUL`. Only failing tests print; the report is in `app/build/reports/tests/testDebugUnitTest/`.

**Step 5: Commit**

```bash
git add app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/SurveyController.kt \
  app/src/test/kotlin/com/stastyle/imumapper/ui/viewer/SurveyNorthEditsTest.kt
git commit -F - <<'EOF'
Correct a trip's north from compass bearings or by hand, with a preview

The survey stores facts, compass bearings and a manual rotation tagged with its run, and the
rotation is solved from them. Before a bearing is added, the preview shows the turn it causes and
warns about a short or crooked stretch and about a change large enough to be a back-bearing.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

### Task 23: ViewModel: Survey mode, loading, seeding and geometry

**Files:**
- Modify: `app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/ViewerViewModel.kt` (imports lines 3-31; `ViewerUiState` 33-73; constructor 80-85;
  fields after line 103; `loadRun` 190; `applyResult` 210-211; `updateBounds` 222-226; `toggleRaw` 240-252;
  a new section before `// --- photos ---` at 295; `openPhoto` 307)
- Test: `app/src/test/kotlin/com/stastyle/imumapper/ui/viewer/ViewerSurveyTest.kt`

The view model owns the `SurveyState`, loads and saves the doc through `SurveyStore` on `ioDispatcher`, and
publishes a `SurveyUi` snapshot. Saves are serialized by a `Mutex` and write the newest doc (latest wins).
Selection and cursor changes need no file, so they work read-only.

The snapshot is built in parts, because the scrubber calls `setSurveyCursor` on every drag frame and a VIO
path has about 100k points. What walks the whole path (north solved on the unrotated path, the shown result
turned with `SurveyGeometry.rotated`, the legs and their totals) is a private `SurveyBase`, rebuilt only when
the doc, the shown result, the run or the raw view changes. The readout (a stretch refits the path every
0.25 m) is kept until the selection changes too. A cursor move rebuilds only the `SurveyLayer`, whose
arrays are decimated. The bounds copy every point, so `publishSurvey()` recomputes them only when a
different result is drawn.

The test builds a processed trip in a temp dir with `FakeTripRepository`, writes each run's JSON where
`TripFiles.resultFile` expects it, and passes `ioDispatcher = Dispatchers.Unconfined` with
`Dispatchers.setMain(UnconfinedTestDispatcher())`, so loading, seeding and saving finish inside each call.
`setViewport(1080f, 1920f)` gives the presets a viewport. The TOP preset is `yawRad = 0`,
`pitchRad = OrbitCamera.MAX_PITCH_RAD`.

**Step 1: Write the failing test**

Create `app/src/test/kotlin/com/stastyle/imumapper/ui/viewer/ViewerSurveyTest.kt`:

```kotlin
package com.stastyle.imumapper.ui.viewer

import com.stastyle.imumapper.SurveyFixtures
import com.stastyle.imumapper.data.FakeTripRepository
import com.stastyle.imumapper.data.SurveyStore
import com.stastyle.imumapper.data.TripFiles
import com.stastyle.imumapper.data.db.PathResultEntity
import com.stastyle.imumapper.data.db.TripEntity
import com.stastyle.imumapper.data.db.TripStatus
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.PathResult
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.TripMode
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.survey.Station
import com.stastyle.imumapper.pipeline.survey.StationKind
import com.stastyle.imumapper.pipeline.survey.SurveyDoc
import com.stastyle.imumapper.process.TripProcessor
import com.stastyle.imumapper.render.LayerSelection
import com.stastyle.imumapper.render.OrbitCamera
import com.stastyle.imumapper.render.SurveyHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Survey mode in the viewer, on the L walk: Start (id 1, 0 m), Junction 1 (id 2, 5 m), C1 (id 4,
 * 10 m, the corner at (0, 10)) and End (id 3, 15 m). File work runs on Dispatchers.Unconfined, so
 * every call, saves included, has finished when it returns. Tests use runBlocking<Unit> because JUnit 4
 * rejects a test method that returns a value.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewerSurveyTest {

    private lateinit var tmp: File
    private lateinit var files: TripFiles
    private lateinit var trips: FakeTripRepository

    /** These trips already have runs, so the viewer must never process one. */
    private class NoProcessing : TripProcessor {
        override suspend fun process(tripId: Long, config: PipelineConfig?, label: String): PathResultEntity =
            throw AssertionError("the viewer processed a trip that has runs")
    }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        tmp = Files.createTempDirectory("imu-survey-viewer").toFile()
        files = TripFiles(File(tmp, "files"), File(tmp, "cache"))
        trips = FakeTripRepository(files)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        tmp.deleteRecursively()
    }

    /** A processed trip named "Cave loop" whose runs 1, 2, ... are [runs]. */
    private suspend fun processedTrip(vararg runs: PathResult): Long {
        val id = trips.createTrip(
            TripEntity(
                name = "Cave loop",
                mode = TripMode.POCKET,
                carryPosition = CarryPosition.HAND,
                startedAtEpochMs = 1L,
                status = TripStatus.PROCESSED,
            ),
        )
        runs.forEachIndexed { index, result ->
            val runId = index + 1
            files.resultFile(id, runId).writeText(result.toJson())
            trips.addResult(
                PathResultEntity(
                    tripId = id,
                    runId = runId,
                    pipelineVersion = result.pipelineVersion,
                    createdAtEpochMs = 0L,
                    fileName = files.resultFileName(runId),
                    statsJson = "{}",
                    configJson = "{}",
                ),
            )
        }
        return id
    }

    /** The viewer with the latest run loaded and a portrait viewport, so presets can frame. */
    private fun viewer(tripId: Long): ViewerViewModel = ViewerViewModel(
        tripId,
        trips,
        files,
        NoProcessing(),
        surveys = SurveyStore(files),
        ioDispatcher = Dispatchers.Unconfined,
    ).also { it.setViewport(1080f, 1920f) }

    private fun survey(vm: ViewerViewModel): SurveyUi = assertNotNull(vm.ui.value.survey)

    private fun savedDoc(tripId: Long): SurveyDoc = SurveyDoc.fromJson(files.surveyFile(tripId).readText())

    private fun t(index: Int): Long = SurveyFixtures.tNs(index)

    private fun assertNear(expected: Vec3, actual: Vec3) {
        assertEquals(expected.x, actual.x, 1e-9)
        assertEquals(expected.y, actual.y, 1e-9)
        assertEquals(expected.z, actual.z, 1e-9)
    }

    // --- entering, loading, selecting ---

    @Test
    fun enteringSeedsTheStationsSavesThemAndLooksFromTheTop() = runBlocking<Unit> {
        val id = processedTrip(SurveyFixtures.lWalk())
        val vm = viewer(id)
        vm.toggleSurvey()

        val ui = vm.ui.value
        assertTrue(ui.surveyMode)
        val survey = survey(vm)
        assertEquals(listOf("Start", "Junction 1", "C1", "End"), survey.state.doc.stations.map { it.name })
        assertEquals(survey.state.doc, savedDoc(id))
        assertEquals(t(0), survey.state.cursorNs)
        assertEquals(3, survey.legs.size)
        assertEquals(15.0, survey.totals.pathM, 1e-9)
        assertTrue(survey.magnetic)
        assertNull(survey.northWarning)
        assertNull(survey.error)
        assertEquals(OrbitCamera.MAX_PITCH_RAD, vm.camera.value.pitchRad)
        assertEquals(0.0, vm.camera.value.yawRad)
        assertSame(survey.geometry.framed, ui.sceneResult)
        assertNull(ui.sceneOverlay)
    }

    @Test
    fun leavingKeepsTheCameraAndTheSurveyInMemory() = runBlocking<Unit> {
        val id = processedTrip(SurveyFixtures.lWalk())
        val vm = viewer(id)
        vm.toggleSurvey()
        val doc = survey(vm).state.doc
        vm.zoom(2f)
        val camera = vm.camera.value

        vm.toggleSurvey()
        assertFalse(vm.ui.value.surveyMode)
        assertNull(vm.ui.value.survey)
        assertEquals(camera, vm.camera.value)
        assertSame(vm.ui.value.shownResult, vm.ui.value.sceneResult)

        // Re-entering uses the survey in memory: the file is not read again.
        files.surveyFile(id).delete()
        vm.toggleSurvey()
        assertSame(doc, survey(vm).state.doc)
        assertFalse(files.surveyFile(id).exists())
    }

    @Test
    fun existingFileIsLoadedNotReseeded() = runBlocking<Unit> {
        val id = processedTrip(SurveyFixtures.lWalk())
        val doc = SurveyDoc(stations = listOf(Station(id = 7, kind = StationKind.USER, name = "Pillar", tNs = t(12))))
        files.surveyFile(id).writeText(doc.toJson())
        val before = files.surveyFile(id).readText()
        val vm = viewer(id)
        vm.toggleSurvey()
        assertEquals(doc, survey(vm).state.doc)
        assertEquals(before, files.surveyFile(id).readText())
    }

    @Test
    fun malformedFileIsReadOnlyAndLeftAsItIs() = runBlocking<Unit> {
        val id = processedTrip(SurveyFixtures.lWalk())
        files.surveyFile(id).writeText("{ not json")
        val vm = viewer(id)
        vm.toggleSurvey()

        val survey = survey(vm)
        assertTrue(survey.state.readOnly)
        assertEquals(4, survey.state.doc.stations.size)
        assertTrue(assertNotNull(survey.error).startsWith("survey.json could not be read ("))
        assertEquals("{ not json", files.surveyFile(id).readText())
        // Selecting still works read-only.
        vm.surveyTap(SurveyHit.OnPath(7.5))
        assertEquals(SurveySelection.Stretch(2, 4), survey(vm).state.selection)
    }

    @Test
    fun pathTapSelectsTheStretchStationTapsBuildAChainAndAMissDoesNothing() = runBlocking<Unit> {
        val vm = viewer(processedTrip(SurveyFixtures.lWalk()))
        vm.toggleSurvey()

        vm.surveyTap(SurveyHit.OnPath(7.5))
        val stretch = survey(vm)
        assertEquals(SurveySelection.Stretch(2, 4), stretch.state.selection)
        assertEquals(t(15), stretch.state.cursorNs)
        assertEquals(listOf("Junction 1", "C1"), assertIs<SurveyReadout.Stretch>(stretch.readout).names)
        assertEquals(
            LayerSelection(stretchNs = t(10) to t(20), stretchIds = listOf(2, 4)),
            SurveyController.layerSelection(stretch.state, stretch.geometry),
        )

        vm.surveyTap(SurveyHit.Miss)
        assertSame(stretch, vm.ui.value.survey)

        vm.surveyTap(SurveyHit.OnStation(1))
        vm.surveyTap(SurveyHit.OnStation(4))
        assertEquals(SurveySelection.Chain(listOf(1, 4)), survey(vm).state.selection)
        assertIs<SurveyReadout.Chain>(survey(vm).readout)
    }

    @Test
    fun cursorAndLegCallsReachTheController() = runBlocking<Unit> {
        val vm = viewer(processedTrip(SurveyFixtures.lWalk()))
        vm.toggleSurvey()
        vm.setSurveyCursor(7.5)
        assertEquals(t(15), survey(vm).state.cursorNs)
        vm.stepSurveyCursor(1)
        assertEquals(t(16), survey(vm).state.cursorNs)
        vm.selectLeg(1, 2)
        assertEquals(SurveySelection.Stretch(1, 2), survey(vm).state.selection)
        vm.clearSurveySelection()
        assertEquals(SurveySelection.None, survey(vm).state.selection)
    }

    @Test
    fun cursorMovesReuseWhatWalksTheWholePath() = runBlocking<Unit> {
        val vm = viewer(processedTrip(SurveyFixtures.lWalk()))
        vm.toggleSurvey()
        vm.surveyTap(SurveyHit.OnPath(7.5))
        val before = survey(vm)

        // The scrubber sends this on every drag frame: only the layer may be rebuilt.
        vm.setSurveyCursor(12.5)
        val after = survey(vm)
        assertEquals(t(25), after.state.cursorNs)
        assertNear(Vec3(2.5, 10.0, 0.0), assertNotNull(after.layer.cursor))
        assertSame(before.geometry, after.geometry)
        assertSame(before.north, after.north)
        assertSame(before.legs, after.legs)
        assertSame(before.totals, after.totals)
        assertSame(before.readout, after.readout)
    }

    @Test
    fun rawToggleRepublishesTheStationsOnTheRawPath() = runBlocking<Unit> {
        val walk = SurveyFixtures.lWalk()
        // The raw path lies 1 m east of the corrected one.
        val shifted = walk.copy(rawPoints = walk.points.map { it.copy(p = it.p + Vec3(1.0, 0.0, 0.0)) })
        val vm = viewer(processedTrip(shifted))
        vm.toggleSurvey()
        assertNear(Vec3.ZERO, survey(vm).layer.stations.first { it.id == 1 }.position)

        vm.toggleRaw()
        val survey = survey(vm)
        assertTrue(survey.geometry.raw)
        assertNear(Vec3(1.0, 0.0, 0.0), survey.layer.stations.first { it.id == 1 }.position)
        assertSame(survey.geometry.framed, vm.ui.value.sceneResult)
    }

    @Test
    fun nothingShownMeansNothingToMeasure() = runBlocking<Unit> {
        val id = trips.createTrip(
            TripEntity(
                name = "walk",
                mode = TripMode.POCKET,
                carryPosition = CarryPosition.HAND,
                startedAtEpochMs = 1L,
                status = TripStatus.RECORDING,
            ),
        )
        val vm = viewer(id)
        vm.toggleSurvey()
        assertFalse(vm.ui.value.surveyMode)
        assertEquals(SurveyMessage("Nothing to measure yet", undoable = false), vm.ui.value.surveyMessage)
    }
}
```

**Step 2: Run the test and watch it fail**

```bash
./gradlew :app:testDebugUnitTest --tests 'com.stastyle.imumapper.ui.viewer.ViewerSurveyTest'
```

Expected: `BUILD FAILED` in `:app:compileDebugUnitTestKotlin`. The first error is
`e: file:///X:/IMU-mapper-survey/app/src/test/kotlin/com/stastyle/imumapper/ui/viewer/ViewerSurveyTest.kt:109:9 No parameter with name 'surveys' found.`.

**Step 3: Write the implementation**

Apply these replacements to `app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/ViewerViewModel.kt`, in order:

a. The import block, replaced whole. Old (at line 3):

```kotlin
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stastyle.imumapper.data.TripFiles
import com.stastyle.imumapper.data.TripRepository
import com.stastyle.imumapper.data.db.PathResultEntity
import com.stastyle.imumapper.data.db.TripEntity
import com.stastyle.imumapper.data.db.TripStatus
import com.stastyle.imumapper.pipeline.core.PathResult
import com.stastyle.imumapper.pipeline.post.RawPath
import com.stastyle.imumapper.process.TripProcessor
import com.stastyle.imumapper.render.Bounds
import com.stastyle.imumapper.render.CameraPreset
import com.stastyle.imumapper.render.ColorMode
import com.stastyle.imumapper.render.OrbitCamera
import com.stastyle.imumapper.render.SceneMarker
import com.stastyle.imumapper.render.SceneOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
```

New:

```kotlin
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stastyle.imumapper.data.SurveyStore
import com.stastyle.imumapper.data.TripFiles
import com.stastyle.imumapper.data.TripRepository
import com.stastyle.imumapper.data.db.PathResultEntity
import com.stastyle.imumapper.data.db.TripEntity
import com.stastyle.imumapper.data.db.TripStatus
import com.stastyle.imumapper.pipeline.core.PathResult
import com.stastyle.imumapper.pipeline.post.RawPath
import com.stastyle.imumapper.pipeline.survey.LegTotals
import com.stastyle.imumapper.pipeline.survey.NorthSolution
import com.stastyle.imumapper.pipeline.survey.NorthSolver
import com.stastyle.imumapper.pipeline.survey.SurveyDoc
import com.stastyle.imumapper.pipeline.survey.Traverse
import com.stastyle.imumapper.pipeline.survey.TraverseLeg
import com.stastyle.imumapper.process.TripProcessor
import com.stastyle.imumapper.render.Bounds
import com.stastyle.imumapper.render.CameraPreset
import com.stastyle.imumapper.render.ColorMode
import com.stastyle.imumapper.render.OrbitCamera
import com.stastyle.imumapper.render.SceneMarker
import com.stastyle.imumapper.render.SceneOptions
import com.stastyle.imumapper.render.SurveyHit
import com.stastyle.imumapper.render.SurveyLayer
import com.stastyle.imumapper.ui.calibration.CalibrationMath
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
```

b. The survey types, above `ViewerUiState`. Old (at line 46):

```kotlin
data class ViewerUiState(
```

New:

```kotlin
/** Snackbar text from a survey edit; Undo is offered when [undoable]. */
data class SurveyMessage(val text: String, val undoable: Boolean)

/** A written CSV waiting for the share sheet; the screen builds the Intent (SurveyShare), keeping this JVM-testable. */
data class SurveyCsvShare(val file: File, val subject: String, val text: String)

/** Everything the survey panel, sheets and layer need, rebuilt after each change. */
data class SurveyUi(
    val state: SurveyState,
    val geometry: SurveyGeometry,
    val north: NorthSolution,
    /** Suffix M when true, R when false (NorthSolver.isMagnetic). */
    val magnetic: Boolean,
    val readout: SurveyReadout?,
    val legs: List<TraverseLeg>,
    val totals: LegTotals,
    val layer: SurveyLayer,
    /** CalibrationMath.northProblem's reason, set on an R run without references, for the banner. */
    val northWarning: String?,
    /** The manual rotation was set on another run: ask before using it (ManualRotationDialog). */
    val askManualRotation: Boolean,
    /** SurveyOpen.error while read-only. */
    val error: String?,
)

data class ViewerUiState(
```

c. `ViewerUiState` gains the Survey mode fields. Old (at line 93):

```kotlin
    val photoError: String? = null,
) {
```

New:

```kotlin
    val photoError: String? = null,
    /** Plan view, orbit locked, the survey panel instead of the stats panel. */
    val surveyMode: Boolean = false,
    /** Null while Survey mode is off or its file is loading. */
    val survey: SurveyUi? = null,
    val surveyMessage: SurveyMessage? = null,
    /** One-shot: the screen hands it to the share sheet, then calls consumeCsvShare. */
    val pendingCsv: SurveyCsvShare? = null,
) {
```

d. Two getters for what the canvas draws. Old (at line 109):

```kotlin
    val shownOverlay: PathResult? get() = overlayResult ?: if (showRaw && rawResult != null) result else null
```

New:

```kotlin
    val shownOverlay: PathResult? get() = overlayResult ?: if (showRaw && rawResult != null) result else null

    /** What the canvas draws: in Survey mode the north-corrected path. */
    val sceneResult: PathResult? get() = if (surveyMode) survey?.geometry?.framed ?: shownResult else shownResult

    /** Survey mode hides the overlay run. */
    val sceneOverlay: PathResult? get() = if (surveyMode) null else shownOverlay
```

e. The constructor gains the store and the IO dispatcher, both defaulted, so `ViewerViewModelTest` and `ViewerScreen` compile unchanged. Old (at line 135):

```kotlin
    private val tripProcessor: TripProcessor,
) : ViewModel() {
```

New:

```kotlin
    private val tripProcessor: TripProcessor,
    private val surveys: SurveyStore = SurveyStore(files),
    /** Tests pass Dispatchers.Unconfined so file work finishes inside the call. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
```

f. The survey state, declared before `init` because `init` can already publish a loaded run. Old (at line 157):

```kotlin
    private var currentBounds: Bounds = Bounds.EMPTY
```

New:

```kotlin
    private var currentBounds: Bounds = Bounds.EMPTY

    // Survey mode. Declared before init, which may already publish a loaded run.
    /** Kept after leaving Survey mode, so undo survives a re-entry; null until the mode first opens. */
    private var surveyState: SurveyState? = null
    /** The geometry last published (already rotated); reused while the shown result and the angle stay. */
    private var surveyGeometry: SurveyGeometry? = null
    /** What the snapshot computed over the whole path; reused until the doc, run, result or raw view changes. */
    private var surveyBase: SurveyBase? = null
    /** The last readout; a stretch's refits the path, so it is reused until the selection changes. */
    private var readoutCache: ReadoutCache? = null
    /** The drawn result the bounds were last taken from; they copy every point, so only a new one updates them. */
    private var boundsSource: PathResult? = null
    /** Why survey.json could not be read; shown while the survey is read-only. */
    private var surveyError: String? = null
    /** "Not now" on the manual-rotation prompt, for this run and this screen only. */
    private var manualPromptDismissedRunId: Int? = null
    /** The newest doc not yet written; saves run one at a time and skip docs a later edit overtook. */
    private var docToSave: SurveyDoc? = null
    private val saveLock = Mutex()
```

g. `loadRun` reads on `ioDispatcher`. Old (at line 263):

```kotlin
            val loaded = withContext(Dispatchers.IO) {
```

New:

```kotlin
            val loaded = withContext(ioDispatcher) {
```

h. `applyResult` republishes the survey, which also updates the bounds. Old (at line 283):

```kotlin
            _ui.update { it.copy(result = result, rawResult = raw, error = null) }
            updateBounds()
```

New:

```kotlin
            _ui.update { it.copy(result = result, rawResult = raw, error = null) }
            // In Survey mode the stations are placed again on the new run; either way the bounds follow.
            publishSurvey()
```

i. `updateBounds` frames what the canvas draws. Old (at line 296):

```kotlin
    /** Fit and presets frame the path on screen, so they follow the raw toggle. */
    private fun updateBounds() {
        val shown = _ui.value.shownResult ?: return
```

New:

```kotlin
    /** Fit and presets frame the path on screen, so they follow the raw toggle and Survey mode's north. */
    private fun updateBounds() {
        val shown = _ui.value.sceneResult ?: return
```

j. `toggleRaw`'s KDoc. Old (at line 316):

```kotlin
     * selected marker is dropped because its position belongs to the other path.
     */
```

New:

```kotlin
     * selected marker is dropped because its position belongs to the other path; survey stations
     * are stored by time, so they follow.
     */
```

k. `toggleRaw` republishes. Old (at line 325):

```kotlin
        _ui.update { it.copy(showRaw = show, rawResult = raw, selectedMarker = null) }
        updateBounds()
```

New:

```kotlin
        _ui.update { it.copy(showRaw = show, rawResult = raw, selectedMarker = null) }
        publishSurvey()
```

l. The Survey mode section, above `// --- photos ---`. Old (at line 370):

```kotlin
    // --- photos ---
```

New:

```kotlin
    // --- survey mode ---

    /**
     * Enters or leaves Survey mode. Leaving keeps the camera where it is and the survey in memory, so
     * undo survives a re-entry. The first entry reads survey.json (seeding and saving it when the trip
     * has none yet), then shows the north-corrected plan from the top.
     */
    fun toggleSurvey() {
        if (_ui.value.surveyMode) {
            // The snackbar's Undo works only in Survey mode, so a pending message leaves with it.
            _ui.update { it.copy(surveyMode = false, surveyMessage = null) }
            publishSurvey()
            return
        }
        val shown = _ui.value.shownResult
        if (shown == null || shown.points.size < 2) {
            _ui.update { it.copy(surveyMessage = SurveyMessage("Nothing to measure yet", undoable = false)) }
            return
        }
        _ui.update { it.copy(surveyMode = true, selectedMarker = null) }
        if (surveyState != null) {
            showSurvey()
            return
        }
        viewModelScope.launch {
            val load = withContext(ioDispatcher) { surveys.load(tripId) }
            // Leaving while the file was read, or a second entry that got there first, makes this load stale.
            if (!_ui.value.surveyMode || surveyState != null) return@launch
            val ui = _ui.value
            val current = ui.shownResult?.takeIf { it.points.isNotEmpty() } ?: return@launch
            val runId = ui.selectedRunId ?: return@launch
            // A new doc has no correction yet, so it is seeded on the uncorrected path.
            val geometry = SurveyGeometry.of(current, runId, raw = ui.showRaw && ui.rawResult != null)
            val opened = SurveyController.open(load, geometry)
            surveyGeometry = geometry
            surveyState = opened.state
            surveyError = opened.error
            if (opened.seeded) persist(opened.state.doc)
            showSurvey()
        }
    }

    /** A tap in Survey mode: a station extends the chain, the path selects a stretch, a miss does nothing. */
    fun surveyTap(hit: SurveyHit) = changeSurvey { state, geometry ->
        when (hit) {
            is SurveyHit.OnStation -> SurveyController.tapStation(state, hit.stationId)
            is SurveyHit.OnPath -> SurveyController.tapPath(state, geometry, hit.distanceM)
            SurveyHit.Miss -> state
        }
    }

    fun clearSurveySelection() = changeSurvey { state, _ -> SurveyController.clearSelection(state) }

    /** The scrubber: [distanceM] along the shown path. */
    fun setSurveyCursor(distanceM: Double) =
        changeSurvey { state, geometry -> SurveyController.setCursor(state, geometry, distanceM) }

    /** The scrubber's arrows: [delta] path points back or forward. */
    fun stepSurveyCursor(delta: Int) =
        changeSurvey { state, geometry -> SurveyController.stepCursor(state, geometry, delta) }

    /** A row of the Legs table. */
    fun selectLeg(fromId: Int, toId: Int) = changeSurvey { state, _ -> SurveyController.selectLeg(state, fromId, toId) }

    private fun showSurvey() {
        publishSurvey()
        applyPreset(CameraPreset.TOP)
    }

    /** Runs a selection or cursor change; these need no file and work read-only too. */
    private fun changeSurvey(change: (SurveyState, SurveyGeometry) -> SurveyState) {
        if (!_ui.value.surveyMode) return
        val state = surveyState ?: return
        val geometry = surveyGeometry ?: return
        val next = change(state, geometry)
        if (next !== state) applySurvey(next)
    }

    /** Stores [next], saves its doc when an edit changed it, and republishes. */
    private fun applySurvey(next: SurveyState, message: SurveyMessage? = null) {
        val previous = surveyState
        surveyState = next
        val docChanged = previous != null && next.doc !== previous.doc
        if (docChanged && !next.readOnly) persist(next.doc)
        publishSurvey()
        // A doc edit replaces the last message, so the snackbar's Undo always undoes the edit it names.
        if (docChanged) _ui.update { it.copy(surveyMessage = message) }
    }

    /**
     * Saves the whole doc after every edit. Saves run one at a time, and one that starts after a newer
     * edit writes the newest doc, so the file never goes back to an older state.
     */
    private fun persist(doc: SurveyDoc) {
        docToSave = doc
        viewModelScope.launch {
            saveLock.withLock {
                val latest = docToSave ?: return@withLock
                docToSave = null
                runCatching { withContext(ioDispatcher) { surveys.save(tripId, latest) } }.onFailure { e ->
                    val message = SurveyMessage("Could not save the survey: ${describe(e)}", undoable = false)
                    _ui.update { it.copy(surveyMessage = message) }
                }
            }
        }
    }

    /**
     * Rebuilds the survey snapshot for what is shown now (null outside Survey mode). The bounds copy every
     * point, so they are recomputed only when a different result is drawn, not on each cursor move.
     */
    private fun publishSurvey() {
        _ui.update { it.copy(survey = surveySnapshot()) }
        val drawn = _ui.value.sceneResult
        if (drawn !== boundsSource) {
            boundsSource = drawn
            updateBounds()
        }
    }

    /**
     * The survey as the panel and the layer need it, on the shown run turned onto the solved north. What
     * walks the whole path comes from [surveyBase] and the readout from [readoutCache], so a cursor move
     * rebuilds only the layer.
     */
    private fun surveySnapshot(): SurveyUi? {
        val ui = _ui.value
        val state = surveyState
        val shown = ui.shownResult
        val runId = ui.selectedRunId
        if (!ui.surveyMode || state == null || shown == null || runId == null || shown.points.isEmpty()) return null
        val raw = ui.showRaw && ui.rawResult != null
        val base = surveyBase?.takeIf { it.isFor(state.doc, shown, runId, raw) }
            ?: buildSurveyBase(state.doc, shown, runId, raw).also { surveyBase = it }
        val geometry = base.geometry
        val readout = readoutCache?.takeIf { it.base === base && it.selection == state.selection }
            ?: ReadoutCache(base, state.selection, SurveyController.readout(state, geometry)).also { readoutCache = it }
        val selection = SurveyController.layerSelection(state, geometry)
        return SurveyUi(
            state = state,
            geometry = geometry,
            north = base.north,
            magnetic = base.magnetic,
            readout = readout.value,
            legs = base.legs,
            totals = base.totals,
            layer = SurveyLayer.build(geometry.timeline, state.doc.stations, selection, state.cursorNs),
            northWarning = base.northWarning,
            askManualRotation = NorthSolver.manualNeedsConfirmation(state.doc, runId) &&
                manualPromptDismissedRunId != runId,
            error = if (state.readOnly) surveyError else null,
        )
    }

    /** The north solve, the framed path, the legs and their totals for [doc] on the shown run. */
    private fun buildSurveyBase(doc: SurveyDoc, shown: PathResult, runId: Int, raw: Boolean): SurveyBase {
        // The run id is checked too: the state flow keeps the old instance when a new run's result is equal.
        val unturned = surveyGeometry?.takeIf { it.shown === shown && it.runId == runId && it.raw == raw }
            ?: SurveyGeometry.of(shown, runId, raw)
        val north = NorthSolver.solve(doc, unturned.plain, runId)
        val geometry = unturned.rotated(north.rotationDeg)
        surveyGeometry = geometry
        val magnetic = NorthSolver.isMagnetic(shown.diagnostics, north)
        val legs = Traverse.legs(doc.stations, geometry.timeline)
        return SurveyBase(
            doc = doc,
            shown = shown,
            runId = runId,
            raw = raw,
            geometry = geometry,
            north = north,
            magnetic = magnetic,
            legs = legs,
            totals = Traverse.totals(legs),
            northWarning = if (!magnetic && doc.references.isEmpty()) {
                CalibrationMath.northProblem(shown.diagnostics)
            } else {
                null
            },
        )
    }

    /**
     * The parts of the survey snapshot that walk the whole path. The doc and the shown result are compared
     * as instances: an edit or a new run always brings a new one, and an equal copy costs one rebuild at most.
     */
    private class SurveyBase(
        val doc: SurveyDoc,
        val shown: PathResult,
        val runId: Int,
        val raw: Boolean,
        val geometry: SurveyGeometry,
        val north: NorthSolution,
        val magnetic: Boolean,
        val legs: List<TraverseLeg>,
        val totals: LegTotals,
        val northWarning: String?,
    ) {
        fun isFor(doc: SurveyDoc, shown: PathResult, runId: Int, raw: Boolean): Boolean =
            doc === this.doc && shown === this.shown && runId == this.runId && raw == this.raw
    }

    /** A readout and what it was computed from. */
    private class ReadoutCache(val base: SurveyBase, val selection: SurveySelection, val value: SurveyReadout?)

    // --- photos ---
```

m. `openPhoto` decodes on `ioDispatcher`. Old (at line 587):

```kotlin
            val decoded = withContext(Dispatchers.IO) {
```

New:

```kotlin
            val decoded = withContext(ioDispatcher) {
```

**Step 4: Run the tests and watch them pass**

The package filter also runs the existing `ViewerViewModelTest`, which must still pass unchanged.

```bash
./gradlew :app:testDebugUnitTest --tests 'com.stastyle.imumapper.ui.viewer.*'
```

Expected: `BUILD SUCCESSFUL`. Only failing tests print; the report is in `app/build/reports/tests/testDebugUnitTest/`.

**Step 5: Commit**

```bash
git add app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/ViewerViewModel.kt \
  app/src/test/kotlin/com/stastyle/imumapper/ui/viewer/ViewerSurveyTest.kt
git commit -F - <<'EOF'
Open a trip in Survey mode: seed its stations once, save them, and view the plan from the top

The viewer's view model owns the survey: the first entry seeds and saves survey.json, a loaded
file is never re-seeded, and an unreadable one leaves Survey mode read-only without touching it.
The canvas draws the north-corrected path, and taps select stations and stretches. What walks the
whole path is cached until the doc or the shown run changes, so moving the cursor stays smooth on a
long walk.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

### Task 24: ViewModel: station edits, detail, undo and messages

**Files:**
- Modify: `app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/ViewerViewModel.kt` (imports; public functions above `showSurvey`; `editSurvey` above
  `applySurvey`; the companion)
- Test: `app/src/test/kotlin/com/stastyle/imumapper/ui/viewer/ViewerSurveyTest.kt` (one import and six tests)

Every doc edit goes through the private `editSurvey`: read-only refuses it with
`SurveyMessage(READ_ONLY_MESSAGE, undoable = false)`, an edit the controller refused changes nothing, and a
real one is saved and announced with an undoable message where the design asks for a snackbar (add, move,
delete, Detail). Rename has no message: the sheet already shows the new name.

**Step 1: Write the failing test**

Apply these replacements to `app/src/test/kotlin/com/stastyle/imumapper/ui/viewer/ViewerSurveyTest.kt`, in order:

a. Import `Detail`. Old (at line 14):

```kotlin
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.survey.Station
```

New:

```kotlin
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.survey.Detail
import com.stastyle.imumapper.pipeline.survey.Station
```

b. The station-edit tests, after the last test. Old (at line 289):

```kotlin
        assertEquals(SurveyMessage("Nothing to measure yet", undoable = false), vm.ui.value.surveyMessage)
    }
}
```

New:

```kotlin
        assertEquals(SurveyMessage("Nothing to measure yet", undoable = false), vm.ui.value.surveyMessage)
    }

    // --- station edits, Detail and undo ---

    @Test
    fun addedStationIsSavedWithAnUndoableMessageAndUndoFollowsToTheFile() = runBlocking<Unit> {
        val id = processedTrip(SurveyFixtures.lWalk())
        val vm = viewer(id)
        vm.toggleSurvey()

        vm.addStationAt(7.5)
        val added = survey(vm).state.doc.stations.single { it.name == "S1" }
        assertEquals(Station(id = 5, kind = StationKind.USER, name = "S1", tNs = t(15)), added)
        assertEquals(SurveyMessage("Added S1", undoable = true), vm.ui.value.surveyMessage)
        assertEquals(survey(vm).state.doc, savedDoc(id))

        vm.surveyUndo()
        assertEquals(4, survey(vm).state.doc.stations.size)
        assertNull(vm.ui.value.surveyMessage)
        assertTrue(savedDoc(id).stations.none { it.name == "S1" })
    }

    @Test
    fun leavingSurveyModeDropsTheUndoMessage() = runBlocking<Unit> {
        val id = processedTrip(SurveyFixtures.lWalk())
        val vm = viewer(id)
        vm.toggleSurvey()
        vm.addStationAt(7.5)
        assertEquals(SurveyMessage("Added S1", undoable = true), vm.ui.value.surveyMessage)

        // Undo works only in Survey mode, so the snackbar must not stay up offering it.
        vm.toggleSurvey()
        assertNull(vm.ui.value.surveyMessage)
        vm.surveyUndo()
        assertTrue(savedDoc(id).stations.any { it.name == "S1" })
    }

    @Test
    fun selectedCornerMovesToTheCursorAndAStationCanBeAddedThere() = runBlocking<Unit> {
        val id = processedTrip(SurveyFixtures.lWalk())
        val vm = viewer(id)
        vm.toggleSurvey()
        vm.surveyTap(SurveyHit.OnStation(4))
        vm.setSurveyCursor(12.5)

        vm.moveSelectedStationToCursor()
        val moved = Station(id = 4, kind = StationKind.USER, name = "C1", tNs = t(25))
        assertEquals(moved, survey(vm).state.doc.stations.single { it.id == 4 })
        assertEquals(SurveyMessage("Moved C1 to the cursor", undoable = true), vm.ui.value.surveyMessage)
        assertEquals(moved, savedDoc(id).stations.single { it.id == 4 })

        vm.addStationAtCursor()
        assertEquals(t(25), savedDoc(id).stations.single { it.name == "S1" }.tNs)
    }

    @Test
    fun renameAndDeleteReachTheFile() = runBlocking<Unit> {
        val id = processedTrip(SurveyFixtures.lWalk())
        val vm = viewer(id)
        vm.toggleSurvey()

        vm.renameStation(2, "  Big room ")
        assertEquals("Big room", savedDoc(id).stations.single { it.id == 2 }.name)
        assertNull(vm.ui.value.surveyMessage)

        vm.deleteStation(4)
        assertTrue(savedDoc(id).stations.none { it.id == 4 })
        assertEquals(SurveyMessage("Deleted C1", undoable = true), vm.ui.value.surveyMessage)
    }

    @Test
    fun detailBringsBackADeletedCornerAndCanBeUndone() = runBlocking<Unit> {
        val id = processedTrip(SurveyFixtures.lWalk())
        val vm = viewer(id)
        vm.toggleSurvey()
        vm.deleteStation(4)
        assertEquals(mapOf(Detail.COARSE to 1, Detail.NORMAL to 1, Detail.FINE to 1), vm.cornerCounts())

        vm.setDetail(Detail.FINE)
        val fine = survey(vm).state.doc
        assertEquals(Detail.FINE, fine.detail)
        assertEquals(listOf(t(20)), fine.stations.filter { it.kind == StationKind.CORNER }.map { it.tNs })
        assertEquals(SurveyMessage("Fine (0.25 m): 1 corner", undoable = true), vm.ui.value.surveyMessage)
        assertEquals(fine, savedDoc(id))

        vm.surveyUndo()
        assertEquals(Detail.NORMAL, savedDoc(id).detail)
        assertTrue(savedDoc(id).stations.none { it.kind == StationKind.CORNER })
    }

    @Test
    fun readOnlyRefusesEditsWithAMessageAndNeverWrites() = runBlocking<Unit> {
        val id = processedTrip(SurveyFixtures.lWalk())
        files.surveyFile(id).writeText("{ not json")
        val vm = viewer(id)
        vm.toggleSurvey()

        vm.addStationAt(7.5)
        vm.renameStation(1, "Entrance")
        vm.surveyUndo()
        val doc = survey(vm).state.doc
        assertEquals(4, doc.stations.size)
        assertEquals("Start", doc.stations.single { it.id == 1 }.name)
        vm.renameStation(1, "Entrance")
        assertEquals(SurveyMessage(ViewerViewModel.READ_ONLY_MESSAGE, undoable = false), vm.ui.value.surveyMessage)
        assertEquals("{ not json", files.surveyFile(id).readText())
    }
}
```

**Step 2: Run the test and watch it fail**

```bash
./gradlew :app:testDebugUnitTest --tests 'com.stastyle.imumapper.ui.viewer.ViewerSurveyTest'
```

Expected: `BUILD FAILED` in `:app:compileDebugUnitTestKotlin`. The first error is
`e: file:///X:/IMU-mapper-survey/app/src/test/kotlin/com/stastyle/imumapper/ui/viewer/ViewerSurveyTest.kt:300:12 Unresolved reference 'addStationAt'.`.

**Step 3: Write the implementation**

Apply these replacements to `app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/ViewerViewModel.kt`, in order:

a. Import `Detail`. Old (at line 16):

```kotlin
import com.stastyle.imumapper.pipeline.post.RawPath
import com.stastyle.imumapper.pipeline.survey.LegTotals
```

New:

```kotlin
import com.stastyle.imumapper.pipeline.post.RawPath
import com.stastyle.imumapper.pipeline.survey.Detail
import com.stastyle.imumapper.pipeline.survey.LegTotals
```

b. Import `StationKind` and `SurveyStations`. Old (at line 20):

```kotlin
import com.stastyle.imumapper.pipeline.survey.NorthSolver
import com.stastyle.imumapper.pipeline.survey.SurveyDoc
import com.stastyle.imumapper.pipeline.survey.Traverse
```

New:

```kotlin
import com.stastyle.imumapper.pipeline.survey.NorthSolver
import com.stastyle.imumapper.pipeline.survey.StationKind
import com.stastyle.imumapper.pipeline.survey.SurveyDoc
import com.stastyle.imumapper.pipeline.survey.SurveyStations
import com.stastyle.imumapper.pipeline.survey.Traverse
```

c. The station edits, above `showSurvey`. Old (at line 437):

```kotlin
    private fun showSurvey() {
```

New:

```kotlin
    /** A long press on the path adds a station there. */
    fun addStationAt(distanceM: Double) {
        val geometry = surveyGeometry ?: return
        addStationAtTime(geometry.timeline.timeAtDistance(distanceM))
    }

    /** "+ Station" adds one at the scrubber's cursor, which can pick either pass of an out-and-back. */
    fun addStationAtCursor() {
        val state = surveyState ?: return
        addStationAtTime(state.cursorNs)
    }

    /** "Move here": the one selected corner or user station goes to the cursor. */
    fun moveSelectedStationToCursor() {
        val state = surveyState ?: return
        val id = SurveyController.movableStationId(state) ?: return
        val name = state.doc.stations.firstOrNull { it.id == id }?.name ?: return
        editSurvey({ "Moved $name to the cursor" }) { s, _ -> SurveyController.moveStation(s, id, s.cursorNs) }
    }

    fun renameStation(stationId: Int, name: String) =
        editSurvey { state, _ -> SurveyController.renameStation(state, stationId, name) }

    fun deleteStation(stationId: Int) {
        val name = surveyState?.doc?.stations?.firstOrNull { it.id == stationId }?.name ?: return
        editSurvey({ "Deleted $name" }) { state, _ -> SurveyController.deleteStation(state, stationId) }
    }

    /** Regenerates the automatic corners; the message says how many the new level gave. */
    fun setDetail(detail: Detail) = editSurvey({ next ->
        val corners = next.doc.stations.count { it.kind == StationKind.CORNER }
        "${SurveyFormat.detailLabel(detail)}: ${SurveyFormat.corners(corners)}"
    }) { state, geometry -> SurveyController.setDetail(state, geometry, detail) }

    /** For the Detail dialog; empty outside Survey mode. */
    fun cornerCounts(): Map<Detail, Int> {
        val state = surveyState ?: return emptyMap()
        val geometry = surveyGeometry ?: return emptyMap()
        return SurveyController.cornerCounts(state, geometry)
    }

    /** The top bar's and the snackbar's Undo. */
    fun surveyUndo() {
        if (!_ui.value.surveyMode) return
        val state = surveyState ?: return
        val next = SurveyController.undo(state)
        _ui.update { it.copy(surveyMessage = null) }
        if (next !== state) applySurvey(next)
    }

    fun dismissSurveyMessage() = _ui.update { it.copy(surveyMessage = null) }

    private fun addStationAtTime(tNs: Long) {
        val name = SurveyStations.nextUserName(surveyState?.doc?.stations ?: return)
        editSurvey({ "Added $name" }) { state, _ -> SurveyController.addStation(state, tNs) }
    }

    private fun showSurvey() {
```

d. `editSurvey`, above `applySurvey`. Old (at line 508):

```kotlin
    /** Stores [next], saves its doc when an edit changed it, and republishes. */
```

New:

```kotlin
    /**
     * Runs one doc edit. Read-only refuses it with a message; an edit the controller refused leaves
     * everything as it was; a real one is saved, and [message] (given the new state) offers Undo.
     */
    private fun editSurvey(
        message: (SurveyState) -> String? = { null },
        edit: (SurveyState, SurveyGeometry) -> SurveyState,
    ) {
        if (!_ui.value.surveyMode) return
        val state = surveyState ?: return
        val geometry = surveyGeometry ?: return
        if (state.readOnly) {
            _ui.update { it.copy(surveyMessage = SurveyMessage(READ_ONLY_MESSAGE, undoable = false)) }
            return
        }
        val next = edit(state, geometry)
        if (next === state) return
        applySurvey(next, message(next)?.let { SurveyMessage(it, undoable = true) })
    }

    /** Stores [next], saves its doc when an edit changed it, and republishes. */
```

e. The read-only message. Old (at line 696):

```kotlin
        const val MAX_PHOTO_PX = 1600
```

New:

```kotlin
        const val MAX_PHOTO_PX = 1600

        /** Shown when an edit is tried on a survey whose file could not be read. */
        const val READ_ONLY_MESSAGE = "Survey mode is read-only: survey.json could not be read"
```

**Step 4: Run the tests and watch them pass**

```bash
./gradlew :app:testDebugUnitTest --tests 'com.stastyle.imumapper.ui.viewer.*'
```

Expected: `BUILD SUCCESSFUL`. Only failing tests print; the report is in `app/build/reports/tests/testDebugUnitTest/`.

**Step 5: Commit**

```bash
git add app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/ViewerViewModel.kt \
  app/src/test/kotlin/com/stastyle/imumapper/ui/viewer/ViewerSurveyTest.kt
git commit -F - <<'EOF'
Edit survey stations from the viewer, saved after each change and undoable

Adding, moving, renaming and deleting stations and changing the corner detail each save the whole
survey file and, except for a rename, offer Undo in a snackbar. A read-only survey refuses every
edit with a message and is never written.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

### Task 25: ViewModel: north edits, manual-rotation prompt and CSV export

**Files:**
- Modify: `app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/ViewerViewModel.kt` (imports; public functions above `addStationAtTime`)
- Test: `app/src/test/kotlin/com/stastyle/imumapper/ui/viewer/ViewerSurveyTest.kt` (imports and four tests)

The steppers nudge from the rotation in use (`survey.north.rotationDeg`), which is what the user sees.
"Not now" on the manual-rotation prompt is remembered for that run and this screen only. The CSV is built
with `SurveyCsv.text(legs, geometry.timeline.startNs, geometry.framed.points.first().p, magnetic)`, written
by `SurveyCsvFile.write(files.exportDir(), SurveyCsvFile.fileName(...), text)` on `ioDispatcher`, and handed
to the screen as `pendingCsv`; the screen builds the Intent (Task 29), so the view model stays JVM-tested.

Worked numbers: on `lWalk(magnetic = false)` the run is R and `northWarning` is
`CalibrationMath.northProblem(...)` of `NORTH_OFF`. A 10° bearing on Start to C1 solves to +10°, makes the
run M (references applied) and clears the warning; the corner (0, 10) is framed at (10 sin 10°, 10 cos 10°).
A manual 5° then −0.5° on run 1 is 4.5°; on run 2 it is not applied (0°) and the prompt is asked.

**Step 1: Write the failing test**

Apply these replacements to `app/src/test/kotlin/com/stastyle/imumapper/ui/viewer/ViewerSurveyTest.kt`, in order:

a. Import `SurveyCsvFile`. Old (at line 5):

```kotlin
import com.stastyle.imumapper.data.SurveyStore
```

New:

```kotlin
import com.stastyle.imumapper.data.SurveyCsvFile
import com.stastyle.imumapper.data.SurveyStore
```

b. Import `NorthSource` and `ReferenceLine`. Old (at line 16):

```kotlin
import com.stastyle.imumapper.pipeline.survey.Detail
import com.stastyle.imumapper.pipeline.survey.Station
```

New:

```kotlin
import com.stastyle.imumapper.pipeline.survey.Detail
import com.stastyle.imumapper.pipeline.survey.NorthSource
import com.stastyle.imumapper.pipeline.survey.ReferenceLine
import com.stastyle.imumapper.pipeline.survey.Station
```

c. Import `SurveyCsv`. Old (at line 20):

```kotlin
import com.stastyle.imumapper.pipeline.survey.StationKind
import com.stastyle.imumapper.pipeline.survey.SurveyDoc
```

New:

```kotlin
import com.stastyle.imumapper.pipeline.survey.StationKind
import com.stastyle.imumapper.pipeline.survey.SurveyCsv
import com.stastyle.imumapper.pipeline.survey.SurveyDoc
```

d. Import `cos` and `sin`. Old (at line 34):

```kotlin
import java.nio.file.Files
```

New:

```kotlin
import java.nio.file.Files
import kotlin.math.cos
import kotlin.math.sin
```

e. The north and CSV tests, after the last test. Old (at line 401):

```kotlin
        assertEquals("{ not json", files.surveyFile(id).readText())
    }
}
```

New:

```kotlin
        assertEquals("{ not json", files.surveyFile(id).readText())
    }

    // --- north, the manual-rotation prompt and the CSV ---

    @Test
    fun compassReadingTurnsTheMapAndMakesAnArbitraryNorthMagnetic() = runBlocking<Unit> {
        val id = processedTrip(SurveyFixtures.lWalk(magnetic = false))
        val vm = viewer(id)
        vm.toggleSurvey()
        val before = survey(vm)
        assertFalse(before.magnetic)
        assertNotNull(before.northWarning)

        vm.surveyTap(SurveyHit.OnStation(1))
        vm.surveyTap(SurveyHit.OnStation(4))
        vm.addReference(10.0, backBearing = false, line = ReferenceLine.CHORD)

        val after = survey(vm)
        assertEquals(NorthSource.REFERENCES, after.north.source)
        assertEquals(10.0, after.north.rotationDeg, 1e-9)
        assertTrue(after.magnetic)
        assertNull(after.northWarning)
        assertEquals(SurveyMessage("Compass reading added", undoable = true), vm.ui.value.surveyMessage)
        // Turned about the start: the corner (0, 10) is now 10 degrees east of north.
        val theta = Math.toRadians(10.0)
        assertNear(Vec3(10.0 * sin(theta), 10.0 * cos(theta), 0.0), after.geometry.framed.points[20].p)
        assertSame(after.geometry.framed, vm.ui.value.sceneResult)
        val chain = assertIs<SurveyReadout.Chain>(after.readout)
        assertEquals(10.0, assertNotNull(chain.measure.straight.azimuthDeg), 1e-9)
        assertEquals(1, savedDoc(id).references.size)

        // References set north, so a manual rotation is refused; reset clears them and can be undone.
        vm.setRotation(3.0)
        assertEquals(10.0, survey(vm).north.rotationDeg, 1e-9)
        vm.resetNorth()
        assertEquals(0.0, survey(vm).north.rotationDeg)
        assertEquals(SurveyMessage("North reset", undoable = true), vm.ui.value.surveyMessage)
        vm.surveyUndo()
        assertEquals(10.0, survey(vm).north.rotationDeg, 1e-9)
        vm.deleteReference(1)
        assertTrue(savedDoc(id).references.isEmpty())
    }

    @Test
    fun manualRotationFromAnotherRunIsOfferedAndApplyUsesIt() = runBlocking<Unit> {
        // Two runs with equal results: the state flow then keeps run 1's instance on screen, so the
        // survey must follow the selected run id, not only the result instance.
        val id = processedTrip(SurveyFixtures.lWalk(), SurveyFixtures.lWalk())
        val vm = viewer(id)
        vm.selectRun(1)
        vm.toggleSurvey()
        vm.setRotation(5.0)
        vm.nudgeRotation(-0.5)
        assertEquals(4.5, survey(vm).north.rotationDeg, 1e-9)
        assertEquals(1, savedDoc(id).manualRotationRunId)

        vm.selectRun(2)
        val asked = survey(vm)
        assertTrue(asked.askManualRotation)
        assertEquals(0.0, asked.north.rotationDeg)

        vm.answerManualRotation(apply = true)
        val applied = survey(vm)
        assertFalse(applied.askManualRotation)
        assertEquals(4.5, applied.north.rotationDeg, 1e-9)
        assertEquals(2, savedDoc(id).manualRotationRunId)
    }

    @Test
    fun notNowHidesTheManualRotationPromptForThatRun() = runBlocking<Unit> {
        val id = processedTrip(SurveyFixtures.lWalk(), SurveyFixtures.lWalk())
        val vm = viewer(id)
        vm.selectRun(1)
        vm.toggleSurvey()
        vm.setRotation(5.0)
        vm.selectRun(2)
        assertTrue(survey(vm).askManualRotation)

        vm.answerManualRotation(apply = false)
        val dismissed = survey(vm)
        assertFalse(dismissed.askManualRotation)
        assertEquals(0.0, dismissed.north.rotationDeg)
        assertEquals(1, savedDoc(id).manualRotationRunId)
    }

    @Test
    fun csvIsWrittenForTheShareSheetOnce() = runBlocking<Unit> {
        val id = processedTrip(SurveyFixtures.lWalk())
        val vm = viewer(id)
        vm.toggleSurvey()

        vm.exportSurveyCsv()
        val share = assertNotNull(vm.ui.value.pendingCsv)
        assertEquals(File(files.exportDir(), SurveyCsvFile.fileName("Cave loop", id, 1, raw = false)), share.file)
        val bytes = share.file.readBytes()
        assertEquals(listOf(0xEF, 0xBB, 0xBF), bytes.take(3).map { it.toInt() and 0xFF })
        val text = bytes.toString(Charsets.UTF_8)
        assertTrue(text.startsWith(SurveyCsv.BOM + SurveyCsv.HEADER + SurveyCsv.EOL))
        // The header, three legs, and nothing after the last line end.
        assertEquals(5, text.split(SurveyCsv.EOL).size)
        assertEquals("IMU Mapper survey: Cave loop", share.subject)
        assertEquals(SurveyFormat.shareText("Cave loop", 1, raw = false, north = survey(vm).north), share.text)

        vm.consumeCsvShare()
        assertNull(vm.ui.value.pendingCsv)
    }
}
```

**Step 2: Run the test and watch it fail**

```bash
./gradlew :app:testDebugUnitTest --tests 'com.stastyle.imumapper.ui.viewer.ViewerSurveyTest'
```

Expected: `BUILD FAILED` in `:app:compileDebugUnitTestKotlin`. The first error is
`e: file:///X:/IMU-mapper-survey/app/src/test/kotlin/com/stastyle/imumapper/ui/viewer/ViewerSurveyTest.kt:417:12 Unresolved reference 'addReference'.`.

**Step 3: Write the implementation**

Apply these replacements to `app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/ViewerViewModel.kt`, in order:

a. Import `SurveyCsvFile`. Old (at line 9):

```kotlin
import com.stastyle.imumapper.data.SurveyStore
```

New:

```kotlin
import com.stastyle.imumapper.data.SurveyCsvFile
import com.stastyle.imumapper.data.SurveyStore
```

b. Import `ReferenceLine` and `SurveyCsv`. Old (at line 21):

```kotlin
import com.stastyle.imumapper.pipeline.survey.NorthSolver
import com.stastyle.imumapper.pipeline.survey.StationKind
import com.stastyle.imumapper.pipeline.survey.SurveyDoc
```

New:

```kotlin
import com.stastyle.imumapper.pipeline.survey.NorthSolver
import com.stastyle.imumapper.pipeline.survey.ReferenceLine
import com.stastyle.imumapper.pipeline.survey.StationKind
import com.stastyle.imumapper.pipeline.survey.SurveyCsv
import com.stastyle.imumapper.pipeline.survey.SurveyDoc
```

c. The north edits, the prompt's answer and the CSV export, above `addStationAtTime`. Old (at line 492):

```kotlin
    private fun addStationAtTime(tNs: Long) {
```

New:

```kotlin
    /** What the Set azimuth dialog shows for a bearing on the current pair or stretch. */
    fun azimuthPreview(bearingDeg: Double, backBearing: Boolean, line: ReferenceLine): AzimuthPreview? {
        val state = surveyState ?: return null
        val geometry = surveyGeometry ?: return null
        return SurveyController.azimuthPreview(state, geometry, bearingDeg, backBearing, line)
    }

    fun addReference(bearingDeg: Double, backBearing: Boolean, line: ReferenceLine) =
        editSurvey({ "Compass reading added" }) { state, geometry ->
            SurveyController.addReference(state, geometry, bearingDeg, backBearing, line)
        }

    fun deleteReference(referenceId: Int) =
        editSurvey({ "Compass reading deleted" }) { state, _ -> SurveyController.deleteReference(state, referenceId) }

    /** The North sheet's steppers start from the rotation in use, which is what the user sees. */
    fun nudgeRotation(deltaDeg: Double) {
        val current = _ui.value.survey?.north?.rotationDeg ?: return
        setRotation(current + deltaDeg)
    }

    /** A manual rotation, tagged with the shown run. */
    fun setRotation(rotationDeg: Double) =
        editSurvey { state, geometry -> SurveyController.setManualRotation(state, rotationDeg, geometry.runId) }

    fun resetNorth() = editSurvey({ "North reset" }) { state, _ -> SurveyController.resetNorth(state) }

    /** The manual-rotation prompt: Apply tags the rotation with this run, Not now hides it for this run. */
    fun answerManualRotation(apply: Boolean) {
        if (apply) {
            editSurvey { state, geometry -> SurveyController.confirmManualRotation(state, geometry.runId) }
        } else {
            manualPromptDismissedRunId = surveyGeometry?.runId
            publishSurvey()
        }
    }

    /**
     * Writes the traverse as CSV into the export cache and hands it to the screen for the share sheet.
     * The numbers are the ones on screen: the shown run, raw or not, north-corrected.
     */
    fun exportSurveyCsv() {
        val survey = _ui.value.survey ?: return
        val geometry = survey.geometry
        val tripName = _ui.value.trip?.name ?: "Trip $tripId"
        val origin = geometry.framed.points.first().p
        val text = SurveyCsv.text(survey.legs, geometry.timeline.startNs, origin, survey.magnetic)
        val fileName = SurveyCsvFile.fileName(tripName, tripId, geometry.runId, geometry.raw)
        val shareText = SurveyFormat.shareText(tripName, geometry.runId, geometry.raw, survey.north)
        viewModelScope.launch {
            runCatching { withContext(ioDispatcher) { SurveyCsvFile.write(files.exportDir(), fileName, text) } }
                .onSuccess { file ->
                    _ui.update { it.copy(pendingCsv = SurveyCsvShare(file, "IMU Mapper survey: $tripName", shareText)) }
                }
                .onFailure { e ->
                    val message = SurveyMessage("Could not export the CSV: ${describe(e)}", undoable = false)
                    _ui.update { it.copy(surveyMessage = message) }
                }
        }
    }

    fun consumeCsvShare() = _ui.update { it.copy(pendingCsv = null) }

    private fun addStationAtTime(tNs: Long) {
```

**Step 4: Run the tests and watch them pass**

```bash
./gradlew :app:testDebugUnitTest --tests 'com.stastyle.imumapper.ui.viewer.*'
```

Expected: `BUILD SUCCESSFUL`. Only failing tests print; the report is in `app/build/reports/tests/testDebugUnitTest/`.

**Step 5: Commit**

```bash
git add app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/ViewerViewModel.kt \
  app/src/test/kotlin/com/stastyle/imumapper/ui/viewer/ViewerSurveyTest.kt
git commit -F - <<'EOF'
Correct north and export the survey legs as CSV from the viewer

A compass bearing on two stations turns the map about the start and makes an arbitrary north
magnetic. A manual rotation set on another run is only used after the user confirms it, since a
re-process can change the heading. The CSV of the legs is written to the export cache for the
share sheet.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

### Task 26: Survey panel

**Files:**
- Create: `app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/SurveyPanel.kt`

The panel replaces the stats panel in Survey mode (design section "The survey panel"): the selection
row (names joined by ` › `, Clear), the headline (length, azimuth with its M or R, slope greyed when
`slopeUncertain`), the details line, the fitted and straightness line for a stretch, each hop and the
sums for a longer chain, the scrubber (a `Slider` over distance with ◀ ▶ steps and
`SurveyFormat.cursorLabel`), Move here and + Station, and Legs and Set azimuth. A long press on the
numbers copies `SurveyFormat.copyLine` through `LocalClipboardManager`, detected with
`pointerInput` + `detectTapGestures(onLongPress)` (no experimental `combinedClickable`).

This is Compose only, with no logic of its own to test: every rule it shows lives in `SurveyController`
or `SurveyFormat`, already tested. It is proven by compiling; Task 29 wires it into the screen, where the
manual checks below are made.

**Step 1: Write the code**

Create `app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/SurveyPanel.kt`:

```kotlin
package com.stastyle.imumapper.ui.viewer

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stastyle.imumapper.pipeline.survey.LegMeasure
import java.time.ZoneId
import kotlin.math.max

/**
 * Survey mode's panel under the plan: what is selected and its numbers, the scrubber that moves the
 * cursor along the path, and the actions. It only renders [survey]; every change goes back through
 * the callbacks, so the rules stay in SurveyController where they are tested.
 */
@Composable
fun SurveyPanel(
    survey: SurveyUi,
    startedAtEpochMs: Long?,
    onClear: () -> Unit,
    onCursor: (distanceM: Double) -> Unit,
    onStep: (delta: Int) -> Unit,
    onAddStation: () -> Unit,
    onMoveHere: () -> Unit,
    onShowLegs: () -> Unit,
    onSetAzimuth: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = survey.state
    val readOnly = state.readOnly
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 3.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            SelectionRow(survey.readout, canClear = state.selection != SurveySelection.None, onClear = onClear)
            SelectionReadout(survey.readout, survey.magnetic)
            if (readOnly) {
                Text(
                    "Read-only: nothing is saved",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Scrubber(survey, onCursor, onStep)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val timeline = survey.geometry.timeline
                Text(
                    SurveyFormat.cursorLabel(
                        startedAtEpochMs,
                        state.cursorNs - timeline.startNs,
                        timeline.distanceAt(state.cursorNs),
                        ZoneId.systemDefault(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onMoveHere,
                    enabled = !readOnly && SurveyController.movableStationId(state) != null,
                ) { Text("Move here") }
                OutlinedButton(onClick = onAddStation, enabled = !readOnly) { Text("+ Station") }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onShowLegs, enabled = survey.legs.isNotEmpty(), modifier = Modifier.weight(1f)) {
                    Text("Legs")
                }
                Button(
                    onClick = onSetAzimuth,
                    enabled = !readOnly && SurveyController.selectionEnds(state, survey.geometry) != null,
                    modifier = Modifier.weight(1f),
                ) { Text("Set azimuth") }
            }
        }
    }
}

@Composable
private fun SelectionRow(readout: SurveyReadout?, canClear: Boolean, onClear: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            readout?.names?.joinToString(" › ") ?: "Tap stations, or the path between them",
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onClear, enabled = canClear) { Text("Clear") }
    }
}

/** The numbers; a long press copies them as one line (SurveyFormat.copyLine) for a note or a message. */
@Composable
private fun SelectionReadout(readout: SurveyReadout?, magnetic: Boolean) {
    val copy = copyLineOf(readout, magnetic)
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(copy) {
                if (copy != null) {
                    detectTapGestures(
                        onLongPress = {
                            clipboard.setText(AnnotatedString(copy))
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                    )
                }
            },
    ) {
        when (readout) {
            null -> Unit
            is SurveyReadout.First -> Text(
                "Tap another station, or the path",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is SurveyReadout.Chain -> {
                val measure = readout.measure
                LegNumbers(measure.straight, magnetic)
                if (measure.hops.size > 1) {
                    Text(
                        "Straight line above · Σ hops ${SurveyFormat.metres(measure.hopLengthSumM)} · " +
                            "Σ path ${SurveyFormat.metres(measure.hopPathSumM)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Column(modifier = Modifier.heightIn(max = HOPS_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
                        measure.hops.forEachIndexed { i, hop ->
                            val from = readout.names.getOrElse(i) { "" }
                            val to = readout.names.getOrElse(i + 1) { "" }
                            Text(
                                "$from › $to: ${SurveyFormat.metres(hop.lengthM)} · " +
                                    "${SurveyFormat.azimuth(hop.azimuthDeg, magnetic)} · " +
                                    SurveyFormat.signedDegrees(hop.slopeDeg),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            is SurveyReadout.Stretch -> {
                LegNumbers(readout.measure.leg, magnetic)
                Text(SurveyFormat.stretchLine(readout.measure, magnetic), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Headline (length, azimuth, slope) and the details line; a slope over a short run is greyed. */
@Composable
private fun LegNumbers(leg: LegMeasure, magnetic: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            SurveyFormat.metres(leg.lengthM),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            SurveyFormat.azimuth(leg.azimuthDeg, magnetic),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            SurveyFormat.slope(leg.slopeDeg),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (leg.slopeUncertain) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = UNCERTAIN_ALPHA)
            } else {
                Color.Unspecified
            },
        )
    }
    Text(
        SurveyFormat.details(leg),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Slider over distance along the path, so standing still takes no room; the arrows step one point. */
@Composable
private fun Scrubber(survey: SurveyUi, onCursor: (Double) -> Unit, onStep: (Int) -> Unit) {
    val timeline = survey.geometry.timeline
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onStep(-1) }) {
            Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous point")
        }
        Slider(
            value = timeline.distanceAt(survey.state.cursorNs).toFloat(),
            onValueChange = { onCursor(it.toDouble()) },
            // A path that never moved still needs a range the slider can draw.
            valueRange = 0f..max(timeline.lengthM, MIN_SCRUB_RANGE_M).toFloat(),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onStep(1) }) {
            Icon(Icons.Filled.ChevronRight, contentDescription = "Next point")
        }
    }
}

private fun copyLineOf(readout: SurveyReadout?, magnetic: Boolean): String? = when (readout) {
    is SurveyReadout.Chain ->
        SurveyFormat.copyLine(readout.names.first(), readout.names.last(), readout.measure.straight, magnetic)
    is SurveyReadout.Stretch ->
        SurveyFormat.copyLine(readout.names.first(), readout.names.last(), readout.measure.leg, magnetic)
    is SurveyReadout.First, null -> null
}

private const val MIN_SCRUB_RANGE_M = 0.01
private const val UNCERTAIN_ALPHA = 0.6f
private val HOPS_MAX_HEIGHT = 96.dp
```

**Step 2: Compile**

```bash
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL` with no warning from `ui/viewer/`.

Manual check once Task 29 is in (debug build on a phone):

- The panel shows "Tap stations, or the path between them" until something is selected.
- Tapping Start then C1 shows "Start › C1", the length, a three-digit azimuth with M or R, and the slope.
- A slope over less than 5 m of horizontal run is grey.
- A long press on the numbers copies one line in the form `Junction 2 → Chamber: 34.2 m, horiz 33.9 m, 047° M,
  +7° (Δh +4.1 m), path 38.5 m`.
- Dragging the slider moves the pink cursor along the path; ◀ ▶ step one path point; the label shows the clock
  time.
- Move here is enabled only with one corner or user station selected; Set azimuth only for a pair or stretch.

**Step 3: Commit**

```bash
git add app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/SurveyPanel.kt
git commit -F - <<'EOF'
Show the survey panel: selection, readout, scrubber and actions

The panel only renders the survey snapshot and calls back to the view model, so every rule it
shows stays in the tested controller and formatter.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

### Task 27: Legs table, station sheet and Detail dialog

**Files:**
- Create: `app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/SurveySheets.kt`

`LegsSheet` is the traverse as a table (`SurveyFormat.tableHeader`, `tableRow`, `totalsRow`) in a
`ModalBottomSheet`; a row tap selects that leg. `StationSheet` (long press on a station) renames or
deletes it, both disabled read-only. `DetailDialog` lists the three levels as radio rows with their
corner counts (`Coarse (1 m) · 1 corner`). `ModalBottomSheet` needs
`@OptIn(ExperimentalMaterial3Api::class)`, as in `TripListDialogs.kt`.

This is Compose only, with no logic of its own to test: every rule it shows lives in `SurveyController`
or `SurveyFormat`, already tested. It is proven by compiling; Task 29 wires it into the screen, where the
manual checks below are made.

**Step 1: Write the code**

Create `app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/SurveySheets.kt`:

```kotlin
package com.stastyle.imumapper.ui.viewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stastyle.imumapper.pipeline.survey.Detail
import com.stastyle.imumapper.pipeline.survey.LegTotals
import com.stastyle.imumapper.pipeline.survey.Station
import com.stastyle.imumapper.pipeline.survey.StationKind
import com.stastyle.imumapper.pipeline.survey.TraverseLeg

/** The traverse as a table, one row per leg; a tap on a row selects that leg on the map. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegsSheet(
    legs: List<TraverseLeg>,
    totals: LegTotals,
    magnetic: Boolean,
    onSelect: (fromId: Int, toId: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Text(
            "Legs",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        TableRow(SurveyFormat.tableHeader(magnetic), bold = true)
        HorizontalDivider()
        // Not filling: the totals row stays on screen under a long table.
        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
            items(legs) { leg ->
                TableRow(
                    SurveyFormat.tableRow(leg),
                    modifier = Modifier.clickable { onSelect(leg.from.id, leg.to.id) },
                )
            }
        }
        HorizontalDivider()
        TableRow(SurveyFormat.totalsRow(totals), bold = true)
        Spacer(Modifier.height(24.dp))
    }
}

/** A long press on a station: rename or delete it. Both are refused while the survey is read-only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationSheet(
    station: Station,
    readOnly: Boolean,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(station.id) { mutableStateOf(station.name) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            Text(station.name, style = MaterialTheme.typography.titleMedium)
            Text(
                kindLabel(station.kind),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                enabled = !readOnly,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDelete, enabled = !readOnly) {
                    Text("Delete", color = MaterialTheme.colorScheme.error.copy(alpha = if (readOnly) 0.38f else 1f))
                }
                TextButton(
                    onClick = { onRename(name) },
                    enabled = !readOnly && name.isNotBlank() && name.trim() != station.name,
                ) { Text("Rename") }
            }
            if (station.kind == StationKind.CORNER) {
                Text(
                    "A deleted corner comes back only when the corner detail changes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (readOnly) {
                Text(
                    "survey.json could not be read, so nothing can be changed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** How finely corners are found; each choice shows how many corners it would give now. */
@Composable
fun DetailDialog(current: Detail, counts: Map<Detail, Int>, onSelect: (Detail) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Corner detail") },
        text = {
            Column {
                Text(
                    "A change places the automatic corners again. Stations you added or moved stay.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                for (detail in Detail.entries) {
                    val selected = detail == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(detail) })
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text("${SurveyFormat.detailLabel(detail)} · ${SurveyFormat.corners(counts[detail] ?: 0)}")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** One row of the legs table; the two name columns get more room than the numbers. */
@Composable
private fun TableRow(cells: List<String>, modifier: Modifier = Modifier, bold: Boolean = false) {
    Row(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        cells.forEachIndexed { i, cell ->
            Text(
                cell,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (bold) FontWeight.SemiBold else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (i < NAME_COLUMNS) TextAlign.Start else TextAlign.End,
                modifier = Modifier.weight(COLUMN_WEIGHTS.getOrElse(i) { 1f }).padding(horizontal = 2.dp),
            )
        }
    }
}

private fun kindLabel(kind: StationKind): String = when (kind) {
    StationKind.START -> "Start of the path"
    StationKind.END -> "End of the path"
    StationKind.MARK -> "Marked while walking"
    StationKind.CORNER -> "Automatic corner"
    StationKind.USER -> "Added in Survey mode"
}

private const val NAME_COLUMNS = 2

/** From, To, Length, Azimuth, Slope, Δh, Path. */
private val COLUMN_WEIGHTS = floatArrayOf(1.5f, 1.5f, 1f, 1f, 0.8f, 0.9f, 1.1f)
```

**Step 2: Compile**

```bash
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL` with no warning from `ui/viewer/`.

Manual check once Task 29 is in (debug build on a phone):

- Legs shows one row per leg with a ⌒ after the path of a curved leg and a Total row; tapping a row selects
  that stretch.
- A long press on a station opens its sheet; Rename trims the name; Delete removes it with an Undo snackbar.
- On a read-only survey the name field and both buttons are disabled.
- Overflow > Corner detail… shows the three levels with their corner counts; picking one places the corners
  again.

**Step 3: Commit**

```bash
git add app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/SurveySheets.kt
git commit -F - <<'EOF'
Add the survey legs table, the station sheet and the corner detail dialog

The legs table is the traverse the CSV exports, and a row selects that leg on the map. Stations are
renamed or deleted from a sheet, and the corner detail dialog shows how many corners each level
would give before the choice.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

### Task 28: Set azimuth dialog, North sheet and manual-rotation prompt

**Files:**
- Create: `app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/NorthDialogs.kt`

`SetAzimuthDialog` shows the current point-to-point and passage readings, takes the compass bearing
(`SurveyFormat.parseDegrees`, so a comma works), a back-bearing box and the line choice, previews
"The map turns +x° about the start" and prints a warning per `AzimuthWarning`; Apply is enabled only
when the bearing parses and the selection has a chord. `NorthSheet` has the −5, −0.5, +0.5, +5
steppers and a typed value (disabled while references exist), each reference with its bearing, line
and residual and a delete button, the disagreement text, and Reset north. `ManualRotationDialog` asks
before a manual rotation set on another run is used.

This is Compose only, with no logic of its own to test: every rule it shows lives in `SurveyController`
or `SurveyFormat`, already tested. It is proven by compiling; Task 29 wires it into the screen, where the
manual checks below are made.

**Step 1: Write the code**

Create `app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/NorthDialogs.kt`:

```kotlin
package com.stastyle.imumapper.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.stastyle.imumapper.pipeline.survey.ReferenceLine
import com.stastyle.imumapper.pipeline.survey.SurveyCsv
import java.util.Locale
import kotlin.math.abs

/**
 * A hand-compass bearing for the selected pair or stretch. It previews the turn before anything is
 * saved, with a warning for each way a reading usually goes wrong, so a slip is caught here and not
 * in the map.
 */
@Composable
fun SetAzimuthDialog(
    fromName: String,
    toName: String,
    magnetic: Boolean,
    preview: (bearingDeg: Double, backBearing: Boolean, line: ReferenceLine) -> AzimuthPreview?,
    onConfirm: (bearingDeg: Double, backBearing: Boolean, line: ReferenceLine) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var backBearing by rememberSaveable { mutableStateOf(false) }
    var line by rememberSaveable { mutableStateOf(ReferenceLine.CHORD) }
    val bearing = SurveyFormat.parseDegrees(text)
    // The current readings do not depend on the bearing, so 0 stands in until one is typed.
    val shown = remember(bearing, backBearing, line) { preview(bearing ?: 0.0, backBearing, line) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set azimuth") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("$fromName → $toName", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Now: point to point ${SurveyFormat.azimuth(shown?.chordDeg, magnetic)} · " +
                        "passage ${SurveyFormat.azimuth(shown?.fittedDeg, magnetic)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Compass bearing (°)") },
                    singleLine = true,
                    isError = text.isNotBlank() && bearing == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(value = backBearing, role = Role.Checkbox, onValueChange = { backBearing = it })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = backBearing, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Back-bearing: read at $toName looking back to $fromName")
                }
                LineOption(ReferenceLine.CHORD, line, "Point to point (the straight line)") { line = it }
                LineOption(ReferenceLine.FITTED, line, "Passage direction (the fitted line)") { line = it }
                if (bearing != null && shown != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        turnText(shown),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    for (warning in AzimuthWarning.entries) {
                        if (warning in shown.warnings) {
                            Text(
                                warningText(warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            // Without a chord on the path the reading could never be used, so it is not offered.
            TextButton(
                onClick = { if (bearing != null) onConfirm(bearing, backBearing, line) },
                enabled = bearing != null && shown?.chordDeg != null,
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The north chip's sheet: the manual rotation (steppers and a typed value, off while compass readings
 * set north), each reading with its residual, and Reset north.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NorthSheet(
    survey: SurveyUi,
    onNudge: (Double) -> Unit,
    onSet: (Double) -> Unit,
    onDeleteReference: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val doc = survey.state.doc
    val readOnly = survey.state.readOnly
    val manualEnabled = !readOnly && doc.references.isEmpty()
    var typed by rememberSaveable { mutableStateOf("") }
    val typedDeg = SurveyFormat.parseDegrees(typed)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp).verticalScroll(rememberScrollState())) {
            Text("North", style = MaterialTheme.typography.titleMedium)
            Text(
                "${SurveyFormat.northChip(survey.north, survey.magnetic)}: ${SurveyCsv.correctionText(survey.north)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Text("Turn by hand", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((step, label) in STEPS) {
                    OutlinedButton(
                        onClick = { onNudge(step) },
                        enabled = manualEnabled,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text(label) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("Rotation (°)") },
                    singleLine = true,
                    enabled = manualEnabled,
                    isError = typed.isNotBlank() && typedDeg == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        if (typedDeg != null) {
                            onSet(typedDeg)
                            typed = ""
                        }
                    },
                    enabled = manualEnabled && typedDeg != null,
                ) { Text("Set") }
            }
            if (doc.references.isNotEmpty()) {
                Text(
                    "Compass readings set north. Delete them to turn by hand.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text("Compass readings", style = MaterialTheme.typography.titleSmall)
            if (doc.references.isEmpty()) {
                Text(
                    "None yet. Select two stations or a straight stretch and use Set azimuth.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            for (reference in doc.references) {
                val fit = survey.north.fits.firstOrNull { it.referenceId == reference.id }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        SurveyFormat.reference(reference, fit),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onDeleteReference(reference.id) }, enabled = !readOnly) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete compass reading")
                    }
                }
            }
            if (survey.north.disagree) {
                Text(
                    SurveyFormat.DISAGREE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(8.dp))
            val corrected =
                doc.references.isNotEmpty() || doc.manualRotationDeg != 0.0 || doc.manualRotationRunId != null
            TextButton(onClick = onReset, enabled = !readOnly && corrected) { Text("Reset north") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Asked when the manual rotation was set on another run: a re-process can change the heading offset,
 * so the same angle may not fit this run.
 */
@Composable
fun ManualRotationDialog(rotationDeg: Double, fromRunId: Int?, toRunId: Int, onAnswer: (apply: Boolean) -> Unit) {
    val from = fromRunId?.let { "run $it" } ?: "another run"
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text("Turn this run too?") },
        text = {
            Text(
                "North was turned ${SurveyFormat.rotation(rotationDeg)} by hand on $from. A re-process can " +
                    "change the heading, so check that the same turn fits run $toRunId.",
            )
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text("Apply on run $toRunId") } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("Not now") } },
    )
}

@Composable
private fun LineOption(value: ReferenceLine, current: ReferenceLine, label: String, onSelect: (ReferenceLine) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = value == current, role = Role.RadioButton, onClick = { onSelect(value) })
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = value == current, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

/** The change is what the user sees happen; the total is added when earlier corrections exist. */
private fun turnText(preview: AzimuthPreview): String {
    val turn = "The map turns ${SurveyFormat.rotation(preview.changeDeg)} about the start"
    val earlier = abs(preview.rotationDeg - preview.changeDeg) >= SAME_TURN_DEG
    return if (earlier) "$turn (north ${SurveyFormat.rotation(preview.rotationDeg)} in all)" else turn
}

private fun warningText(warning: AzimuthWarning): String = when (warning) {
    AzimuthWarning.SHORT -> String.format(
        Locale.US,
        "Under %.0f m across: a small error in the path is a large angle here.",
        SurveyController.MIN_REFERENCE_HORIZONTAL_M,
    )
    AzimuthWarning.CROOKED -> "The path bends here: point to point and the passage direction differ."
    AzimuthWarning.LARGE_CHANGE ->
        String.format(Locale.US, "This turns the map by more than %.0f°.", SurveyController.LARGE_CHANGE_DEG)
    AzimuthWarning.BACK_BEARING ->
        String.format(Locale.US, "Over %.0f°: was it a back-bearing?", SurveyController.BACK_BEARING_CHANGE_DEG)
}

/** Below a twentieth of a degree the total and the change print the same. */
private const val SAME_TURN_DEG = 0.05

private val STEPS = listOf(-5.0 to "-5°", -0.5 to "-0.5°", 0.5 to "+0.5°", 5.0 to "+5°")
```

**Step 2: Compile**

```bash
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL` with no warning from `ui/viewer/`.

Manual check once Task 29 is in (debug build on a phone):

- Set azimuth on Start › C1 with 10 shows "The map turns +10.0° about the start"; Apply turns the map and the
  chip reads `N +10.0° M · 1 ref`.
- A bearing 50° off the reading shows the 15° and back-bearing warnings; a 5 m pair shows the short warning.
- The North sheet's steppers are disabled while a compass reading exists, and its row shows the residual.
- After a manual rotation on one run, showing another run asks "Turn this run too?"; Not now keeps it off for
  that run.

**Step 3: Commit**

```bash
git add app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/NorthDialogs.kt
git commit -F - <<'EOF'
Add the Set azimuth dialog, the North sheet and the manual-rotation prompt

North is corrected from hand-compass bearings or by hand. The dialog previews the turn and warns
about the usual mistakes before anything is saved, and the sheet lists each reading with its
residual so a bad one stands out.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

### Task 29: Viewer screen wiring

**Files:**
- Modify: `app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/ViewerScreen.kt` (nearly every part changes, so the whole file is given; the private
  `StatusOverlay`, `RunMenu`, `PresetMenu`, `ViewMenu`, `ColorModeItem`, `ToggleItem`, `CheckIcon` and
  `runLabel` are unchanged)

What changes:

- The view model is built with `surveys = container.surveyStore`.
- The scene is built from `ui.sceneResult` / `ui.sceneOverlay`, with `showMarkers = false` in Survey mode
  (stations replace the markers, so a tap is never ambiguous).
- `CanvasGestures` gets `onSurveyTap = vm::surveyTap` and `onSurveyLongPress` (a station opens
  `StationSheet`, the path adds a station); `ViewerCanvas` gets `survey = survey?.layer` and
  `orbitLocked = ui.surveyMode`.
- Top bar: the title's subtitle starts with "Survey (beta)" in Survey mode; the ruler
  (`Icons.Filled.SquareFoot`) toggles the mode in both modes (tinted while on); in Survey mode the actions
  are the north chip (`SurveyFormat.northChip`, opens `NorthSheet`), Undo (`Icons.AutoMirrored.Filled.Undo`),
  the ruler and an overflow (`Icons.Filled.MoreVert`) with Export CSV, Corner detail…, Raw path and the
  run list. The back arrow and the system back (`BackHandler`) leave Survey mode first.
- Banners at the top for a read-only survey and an arbitrary north (`northWarning`).
- `SurveyPanel` replaces `StatsPanel`; `MarkerCard` is not shown in Survey mode.
- The sheets and dialogs of Tasks 27 and 28, and `ManualRotationDialog` when `askManualRotation`.
- A `SnackbarHost`: an undoable message gets an Undo action and `SnackbarDuration.Long` (with an action
  the default would be `Indefinite`), then `dismissSurveyMessage()`.
- `LaunchedEffect(ui.pendingCsv)` starts `SurveyShare.intent(...)` and calls `consumeCsvShare()`, like the
  trip list's ZIP export.

**Step 0: Check that the screen is still the one this plan replaces**

Step 1 overwrites the whole file, so any change made to it since this plan was written would be lost
without a compile error or a failing test to show it. Tasks 1 to 28 do not touch `ViewerScreen.kt`, so it
must still equal the version in commit `6023cc0` (the design-doc commit this plan was written against):

```bash
git diff --exit-code 6023cc0 -- app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/ViewerScreen.kt
```

Expected: no output and exit status 0. If it prints a diff (for example after a rebase onto a `main` that
changed the viewer), stop: do not overwrite the file. Apply the Survey mode changes listed above by hand to
the current file instead, keeping the other changes, then continue with Step 2.

**Step 1: Write the code**

Replace the whole contents of `app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/ViewerScreen.kt` with:

```kotlin
package com.stastyle.imumapper.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SquareFoot
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stastyle.imumapper.data.SurveyShare
import com.stastyle.imumapper.data.db.PathResultEntity
import com.stastyle.imumapper.data.db.TripStatus
import com.stastyle.imumapper.render.CameraPreset
import com.stastyle.imumapper.render.ColorMode
import com.stastyle.imumapper.render.PathScene
import com.stastyle.imumapper.render.SceneModel
import com.stastyle.imumapper.render.SurveyHit
import com.stastyle.imumapper.ui.common.appContainer

/** 3D path viewer for one trip, with Survey mode for measuring between points of the walk. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    tripId: Long,
    onBack: () -> Unit,
    onOpenDebug: (tripId: Long) -> Unit,
) {
    val context = LocalContext.current
    val container = appContainer()
    val vm: ViewerViewModel = viewModel(key = "viewer-$tripId") {
        ViewerViewModel(
            tripId,
            container.tripRepository,
            container.tripFiles,
            container.tripProcessor,
            surveys = container.surveyStore,
        )
    }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val camera by vm.camera.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val survey = ui.survey?.takeIf { ui.surveyMode }

    var showLegs by remember { mutableStateOf(false) }
    var showDetail by remember { mutableStateOf(false) }
    var showSetAzimuth by remember { mutableStateOf(false) }
    var showNorth by remember { mutableStateOf(false) }
    var stationSheetId by remember { mutableStateOf<Int?>(null) }

    // Survey mode draws the north-corrected path without the overlay run or the scene's markers:
    // stations take the markers' place, so a tap can only mean one thing.
    val result = ui.sceneResult
    val overlay = ui.sceneOverlay
    val options = if (ui.surveyMode) ui.options.copy(showMarkers = false) else ui.options
    // Building the scene walks every point once; toggles, run changes and a new north rotation
    // invalidate it. Survey edits do not: they rebuild only the survey layer.
    val scene: SceneModel? = remember(result, overlay, options) {
        result?.let { PathScene.build(it, options, overlay) }
    }
    val selectedMarker = ui.selectedMarker
    val selectedIndex = if (scene == null || selectedMarker == null) -1 else scene.markers.indexOf(selectedMarker)
    // The gesture callbacks are created once; the tap handler reads the scene through a state holder
    // so a rebuilt scene (toggle, run change) is used without recreating the pointerInput.
    val latestScene = rememberUpdatedState(scene)
    val gestures = remember(vm) {
        CanvasGestures(
            onViewport = vm::setViewport,
            onOrbit = vm::orbit,
            onZoom = vm::zoom,
            onPan = vm::pan,
            onDoubleTap = vm::fitToPath,
            onTap = { index ->
                val markers = latestScene.value?.markers
                vm.selectMarker(if (markers != null && index in markers.indices) markers[index] else null)
            },
            onSurveyTap = vm::surveyTap,
            onSurveyLongPress = { hit ->
                if (hit is SurveyHit.OnStation) {
                    stationSheetId = hit.stationId
                } else if (hit is SurveyHit.OnPath) {
                    vm.addStationAt(hit.distanceM)
                }
            },
        )
    }

    BackHandler(enabled = ui.surveyMode) { vm.toggleSurvey() }
    LaunchedEffect(ui.surveyMessage) {
        val message = ui.surveyMessage ?: return@LaunchedEffect
        val answer = snackbar.showSnackbar(
            message = message.text,
            actionLabel = if (message.undoable) "Undo" else null,
            // With an action the default would be Indefinite; a long snackbar still goes away.
            duration = if (message.undoable) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        if (answer == SnackbarResult.ActionPerformed) vm.surveyUndo()
        vm.dismissSurveyMessage()
    }
    LaunchedEffect(ui.pendingCsv) {
        val csv = ui.pendingCsv ?: return@LaunchedEffect
        runCatching { context.startActivity(SurveyShare.intent(context, csv.file, csv.subject, csv.text)) }
        vm.consumeCsvShare()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { ViewerTitle(ui, tripId) },
                navigationIcon = {
                    IconButton(onClick = { if (ui.surveyMode) vm.toggleSurvey() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (ui.surveyMode) {
                        if (survey != null) {
                            TextButton(onClick = { showNorth = true }) {
                                Text(SurveyFormat.northChip(survey.north, survey.magnetic), maxLines = 1)
                            }
                            IconButton(onClick = vm::surveyUndo, enabled = survey.state.undo.isNotEmpty()) {
                                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                            }
                        }
                        SurveyToggle(surveyMode = true, onToggle = vm::toggleSurvey)
                        SurveyMenu(ui, vm, onDetail = { showDetail = true })
                    } else {
                        RunMenu(ui, vm)
                        PresetMenu(vm)
                        ViewMenu(ui, vm)
                        SurveyToggle(surveyMode = false, onToggle = vm::toggleSurvey)
                        IconButton(onClick = { onOpenDebug(tripId) }) {
                            Icon(Icons.Filled.BugReport, contentDescription = "Debug")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            ViewerCanvas(
                scene = scene,
                camera = camera,
                selectedMarker = selectedIndex,
                gestures = gestures,
                survey = survey?.layer,
                orbitLocked = ui.surveyMode,
            )
            if (scene == null) {
                StatusOverlay(ui, vm, modifier = Modifier.align(Alignment.Center))
            }
            if (survey != null) {
                SurveyBanners(survey, modifier = Modifier.align(Alignment.TopCenter))
            }
            Column(modifier = Modifier.align(Alignment.BottomCenter)) {
                val marker = ui.selectedMarker
                if (!ui.surveyMode && marker != null && selectedIndex >= 0) {
                    MarkerCard(
                        marker = marker,
                        onOpenPhoto = { vm.openPhoto(marker) },
                        onClose = { vm.selectMarker(null) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (ui.error != null && scene != null) {
                    Text(
                        ui.error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                val shown = ui.shownResult
                if (survey != null) {
                    SurveyPanel(
                        survey = survey,
                        startedAtEpochMs = ui.trip?.startedAtEpochMs,
                        onClear = vm::clearSurveySelection,
                        onCursor = vm::setSurveyCursor,
                        onStep = vm::stepSurveyCursor,
                        onAddStation = vm::addStationAtCursor,
                        onMoveHere = vm::moveSelectedStationToCursor,
                        onShowLegs = { showLegs = true },
                        onSetAzimuth = { showSetAzimuth = true },
                    )
                } else if (!ui.surveyMode && shown != null) {
                    StatsPanel(shown.stats)
                }
            }
        }
    }

    if (ui.photo != null || ui.photoLoading || ui.photoError != null) {
        PhotoDialog(
            title = ui.photoTitle,
            bitmap = ui.photo,
            loading = ui.photoLoading,
            error = ui.photoError,
            onDismiss = vm::closePhoto,
        )
    }

    if (survey != null) {
        if (showLegs) {
            LegsSheet(
                legs = survey.legs,
                totals = survey.totals,
                magnetic = survey.magnetic,
                onSelect = { fromId, toId ->
                    vm.selectLeg(fromId, toId)
                    showLegs = false
                },
                onDismiss = { showLegs = false },
            )
        }
        val sheetStation = stationSheetId?.let { id -> survey.state.doc.stations.firstOrNull { it.id == id } }
        if (sheetStation != null) {
            StationSheet(
                station = sheetStation,
                readOnly = survey.state.readOnly,
                onRename = { name ->
                    vm.renameStation(sheetStation.id, name)
                    stationSheetId = null
                },
                onDelete = {
                    vm.deleteStation(sheetStation.id)
                    stationSheetId = null
                },
                onDismiss = { stationSheetId = null },
            )
        }
        if (showDetail) {
            // Corner detection runs three times; only a doc or path change makes the counts stale.
            val counts = remember(survey.state.doc, survey.geometry) { vm.cornerCounts() }
            DetailDialog(
                current = survey.state.doc.detail,
                counts = counts,
                onSelect = { detail ->
                    vm.setDetail(detail)
                    showDetail = false
                },
                onDismiss = { showDetail = false },
            )
        }
        val names = survey.readout?.names
        if (showSetAzimuth && names != null && names.size >= 2) {
            SetAzimuthDialog(
                fromName = names.first(),
                toName = names.last(),
                magnetic = survey.magnetic,
                preview = vm::azimuthPreview,
                onConfirm = { bearingDeg, backBearing, line ->
                    vm.addReference(bearingDeg, backBearing, line)
                    showSetAzimuth = false
                },
                onDismiss = { showSetAzimuth = false },
            )
        }
        if (showNorth) {
            NorthSheet(
                survey = survey,
                onNudge = vm::nudgeRotation,
                onSet = vm::setRotation,
                onDeleteReference = vm::deleteReference,
                onReset = vm::resetNorth,
                onDismiss = { showNorth = false },
            )
        }
        if (survey.askManualRotation) {
            ManualRotationDialog(
                rotationDeg = survey.state.doc.manualRotationDeg,
                fromRunId = survey.state.doc.manualRotationRunId,
                toRunId = survey.geometry.runId,
                onAnswer = vm::answerManualRotation,
            )
        }
    }
}

@Composable
private fun ViewerTitle(ui: ViewerUiState, tripId: Long) {
    Column {
        Text(ui.trip?.name ?: "Trip $tripId", maxLines = 1, overflow = TextOverflow.Ellipsis)
        val run = ui.runs.firstOrNull { it.runId == ui.selectedRunId }
        val parts = buildList {
            if (ui.surveyMode) add("Survey (beta)")
            if (run != null) add(runLabel(run) + if (ui.showRaw && ui.rawResult != null) " · raw" else "")
        }
        if (parts.isNotEmpty()) {
            Text(
                parts.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The ruler: enters and leaves Survey mode, tinted while it is on. */
@Composable
private fun SurveyToggle(surveyMode: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            Icons.Filled.SquareFoot,
            contentDescription = if (surveyMode) "Leave Survey mode" else "Survey mode",
            tint = if (surveyMode) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        )
    }
}

/** Survey mode's overflow: export, corner detail, the raw toggle and the run list (the other menus hide). */
@Composable
private fun SurveyMenu(ui: ViewerUiState, vm: ViewerViewModel, onDetail: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = "More")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        val survey = ui.survey
        DropdownMenuItem(
            text = { Text("Export CSV") },
            onClick = {
                open = false
                vm.exportSurveyCsv()
            },
            enabled = survey != null,
        )
        DropdownMenuItem(
            text = { Text("Corner detail…") },
            onClick = {
                open = false
                onDetail()
            },
            enabled = survey != null && !survey.state.readOnly,
        )
        ToggleItem("Raw path", ui.showRaw, vm::toggleRaw)
        if (ui.runs.isNotEmpty()) {
            HorizontalDivider()
            Text(
                "Show run",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            for (run in ui.runs) {
                DropdownMenuItem(
                    text = { Text(runLabel(run)) },
                    onClick = {
                        open = false
                        vm.selectRun(run.runId)
                    },
                    trailingIcon = { if (run.runId == ui.selectedRunId) CheckIcon() },
                )
            }
        }
    }
}

/** A read-only survey and an arbitrary north are said at the top, where the plan is not covered by the panel. */
@Composable
private fun SurveyBanners(survey: SurveyUi, modifier: Modifier = Modifier) {
    val error = survey.error
    val north = survey.northWarning
    if (error == null && north == null) return
    Column(
        modifier = modifier.fillMaxWidth().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (error != null) {
            Banner(error, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        }
        if (north != null) {
            Banner(
                "North is arbitrary on this run ($north). Select two stations or a straight stretch and " +
                    "use Set azimuth with a compass bearing.",
                MaterialTheme.colorScheme.secondaryContainer,
                MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun Banner(text: String, container: Color, content: Color) {
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp, 8.dp))
    }
}

@Composable
private fun StatusOverlay(ui: ViewerUiState, vm: ViewerViewModel, modifier: Modifier = Modifier) {
    val trip = ui.trip
    Column(modifier = modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            ui.processing -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Processing trip…", style = MaterialTheme.typography.bodyLarge)
            }
            ui.error != null -> {
                Text(ui.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Button(onClick = vm::retryProcessing) { Text("Retry") }
            }
            trip?.status == TripStatus.RECORDING -> {
                Text("This trip is still being recorded.", style = MaterialTheme.typography.bodyLarge)
            }
            ui.loading || ui.runs.isNotEmpty() -> CircularProgressIndicator()
            trip != null && trip.status == TripStatus.FAILED -> {
                // The run the recording screen started failed; the view model does not repeat it on
                // its own (see ViewerViewModel.maybeProcess), so the stored reason is shown here.
                Text(
                    "Processing failed: " + (trip.lastError ?: "no error message was recorded"),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = vm::retryProcessing) { Text("Retry") }
            }
            else -> {
                Text("No processed path yet.", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(12.dp))
                Button(onClick = vm::retryProcessing) { Text("Process now") }
            }
        }
    }
}

@Composable
private fun RunMenu(ui: ViewerUiState, vm: ViewerViewModel) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, enabled = ui.runs.isNotEmpty()) {
        Icon(Icons.Filled.Layers, contentDescription = "Runs")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        Text(
            "Show run",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        for (run in ui.runs) {
            DropdownMenuItem(
                text = { Text(runLabel(run)) },
                onClick = {
                    open = false
                    vm.selectRun(run.runId)
                },
                trailingIcon = { if (run.runId == ui.selectedRunId) CheckIcon() },
            )
        }
        if (ui.runs.size > 1) {
            HorizontalDivider()
            Text(
                "Overlay (dimmed)",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            DropdownMenuItem(
                text = { Text("None") },
                onClick = {
                    open = false
                    vm.selectOverlay(null)
                },
                trailingIcon = { if (ui.overlayRunId == null) CheckIcon() },
            )
            for (run in ui.runs) {
                if (run.runId == ui.selectedRunId) continue
                DropdownMenuItem(
                    text = { Text(runLabel(run)) },
                    onClick = {
                        open = false
                        vm.selectOverlay(run.runId)
                    },
                    trailingIcon = { if (run.runId == ui.overlayRunId) CheckIcon() },
                )
            }
        }
    }
}

@Composable
private fun PresetMenu(vm: ViewerViewModel) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Filled.ViewInAr, contentDescription = "View presets")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(text = { Text("3D") }, onClick = { open = false; vm.applyPreset(CameraPreset.THREE_D) })
        DropdownMenuItem(text = { Text("Top") }, onClick = { open = false; vm.applyPreset(CameraPreset.TOP) })
        DropdownMenuItem(text = { Text("Side") }, onClick = { open = false; vm.applyPreset(CameraPreset.SIDE) })
        HorizontalDivider()
        DropdownMenuItem(text = { Text("Fit to path") }, onClick = { open = false; vm.fitToPath() })
    }
}

@Composable
private fun ViewMenu(ui: ViewerUiState, vm: ViewerViewModel) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Filled.Tune, contentDescription = "View options")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        Text(
            "Colour by",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        ColorModeItem("Elapsed time", ColorMode.TIME, ui.options.colorMode) { vm.setColorMode(it) }
        ColorModeItem("Altitude", ColorMode.ALTITUDE, ui.options.colorMode) { vm.setColorMode(it) }
        ColorModeItem("Source (PDR vs VIO)", ColorMode.SOURCE, ui.options.colorMode) { vm.setColorMode(it) }
        HorizontalDivider()
        ToggleItem("Floor grid", ui.options.showGrid, vm::toggleGrid)
        ToggleItem("Point cloud", ui.options.showPointCloud, vm::togglePointCloud)
        ToggleItem("Markers", ui.options.showMarkers, vm::toggleMarkers)
        HorizontalDivider()
        ToggleItem("Raw path", ui.showRaw, vm::toggleRaw)
        val hint = when {
            ui.rawUnavailable -> "Re-process this run to store its raw path"
            ui.showRaw && ui.rawResult == null && ui.result != null -> "Nothing was corrected in this run"
            else -> "Before loop closure and smoothing; the corrected path is dimmed"
        }
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).widthIn(max = 260.dp),
        )
    }
}

@Composable
private fun ColorModeItem(label: String, mode: ColorMode, current: ColorMode, onSelect: (ColorMode) -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = { onSelect(mode) },
        trailingIcon = { if (mode == current) CheckIcon() },
    )
}

@Composable
private fun ToggleItem(label: String, checked: Boolean, onToggle: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onToggle,
        trailingIcon = { Switch(checked = checked, onCheckedChange = { onToggle() }) },
    )
}

@Composable
private fun CheckIcon() {
    Icon(Icons.Filled.Check, contentDescription = null)
}

private fun runLabel(run: PathResultEntity): String {
    val base = "Run ${run.runId} · v${run.pipelineVersion}"
    return if (run.label.isBlank()) base else "$base · ${run.label}"
}
```

**Step 2: Compile and run every test**

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
(cd pipeline && ../gradlew test)
```

Expected: `BUILD SUCCESSFUL` for both.

**Step 3: Manual check on a phone**

Install the debug APK (`app/build/outputs/apk/debug/app-debug.apk`) and open a processed trip:

- The ruler is in the top bar between the view options and Debug. Tapping it shows the plan from the top,
  north up, with Start, End, marks and C1.. stations, and the survey panel instead of the stats.
- One-finger drag pans (no orbit), pinch zooms, a double tap fits.
- Tap two stations: chords and the numbers appear; tap the path: its stretch is highlighted and the cursor
  jumps there; tap empty space: nothing changes.
- Long press on the path adds S1 with an Undo snackbar; Undo removes it. Long press on a station opens its
  sheet.
- The north chip opens the North sheet; Undo in the top bar undoes the last edit.
- Overflow > Export CSV opens the share sheet with `<trip>-<id>-run<n>-survey.csv`; the file opens in a
  spreadsheet with Hebrew names intact.
- Overflow > Raw path moves the stations with the raw path; the run list switches runs.
- On a trip whose north is not magnetic, the banner explains why and the azimuths end in R.
- Back (arrow or system) leaves Survey mode with the camera where it was; entering again keeps the stations
  and the undo.
- Kill and reopen the app: the stations and the correction are still there (survey.json).

**Step 4: Commit**

```bash
git add app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/ViewerScreen.kt
git commit -F - <<'EOF'
Add Survey mode to the trip viewer

The ruler in the viewer's top bar opens Survey mode: a north-up plan with named stations, the
survey panel, the legs table, compass and manual north correction, undo, and a CSV export through
the share sheet. Back leaves Survey mode first.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

### Task 30: Document the survey package

**Files:**
- Modify: `CLAUDE.md` (the Layout list, lines 23 and 28)

**Step 1: Edit the layout**

Apply these replacements to `CLAUDE.md`, in order:

a. The pipeline package. Old (at line 23):

```markdown
  - `tuning`: the assisted-tuning prompt, parser and score.
```

New:

```markdown
  - `tuning`: the assisted-tuning prompt, parser and score.
  - `survey`: Survey mode's pure math (time placement on a path, measurements, corners, the north
    solve and frame, the traverse, the CSV text). The processor never runs it, so a change there
    needs no `PIPELINE_VERSION` bump and no `replay.py` port.
```

b. The survey file in `data/`. Old (at line 31):

```markdown
  - `data/`: Room, trip files, ZIP export/import.
```

New:

```markdown
  - `data/`: Room, trip files, ZIP export/import, and `SurveyStore` for
    `files/trips/<id>/survey.json`: the per-trip Survey mode facts (stations, compass readings, the
    manual rotation). It is user state, not a run, and travels in the ZIP export.
```

**Step 2: Final verification**

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
(cd pipeline && ../gradlew test)
git status --short
```

Expected: `BUILD SUCCESSFUL` for both Gradle runs, and `git status` shows only `CLAUDE.md` modified (no
`*.jks`, no build output).

**Step 3: Commit**

```bash
git add CLAUDE.md
git commit -F - <<'EOF'
Document the survey package and survey.json in CLAUDE.md

The layout now names the pipeline's survey package, which the processor never runs (so it needs no
pipeline version bump or replay.py port), and the per-trip survey.json that travels in the ZIP.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

Phase 1 ships later as one PR with a user-facing title (for example "Measure distance, azimuth and slope
between points of a recorded trip, and correct its north (beta)"). This plan does not push, open a PR or
merge to `main`.
