# Survey Mode Phase 1 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add Survey mode to the trip viewer: named stations placed by time on the shown path (start, end,
marks and automatic corners), distance / azimuth / slope readouts between stations or along a tapped
stretch, north correction from hand-compass bearings or a manual rotation, a per-trip `survey.json` that
travels in the ZIP export, and a CSV export of the traverse legs.

**Architecture:** All geometry lives in a new pure package `com.stastyle.imumapper.pipeline.survey`
(document model, time placement, measurements, line fit, corners, north solve and frame, traverse, CSV
text). The app adds a file store and ZIP/CSV plumbing in `data/`, a pure survey layer with projection and
hit tests plus a Compose drawing pass in `render/`, and in `ui/viewer/` a pure `SurveyController`
(selection, cursor, edits, undo) owned by `ViewerViewModel`, which persists the document through
`SurveyStore` and publishes a `SurveyUi` snapshot that the panel, sheets and dialogs render. Nothing is
re-processed, no run is written, and Room is not touched.

**Tech Stack:** Kotlin 2.1.0, kotlinx.serialization 1.7.3, JUnit 5 + kotlin.test (pipeline), JUnit 4 +
kotlin.test + kotlinx-coroutines-test 1.9.0 (app), Jetpack Compose BOM 2024.12.01 (ui/foundation 1.7.6,
material3 1.3.1, material-icons-extended 1.7.6), Android minSdk 30. No new dependency.

## Before you start

- **Worktree:** work only in `X:\IMU-mapper-survey` (Git Bash: `/x/IMU-mapper-survey`), branch
  `claude/survey-mode`. Check with `git -C /x/IMU-mapper-survey branch --show-current`. Never read or
  modify anything under `X:\IMU-mapper`: it is another session's checkout on another branch. Every
  command below runs from the worktree root `/x/IMU-mapper-survey` unless it says `(cd pipeline && ...)`.
- **Read first:** `CLAUDE.md`, `docs/CONVENTIONS.md`, `.claude/rules/app.md`, `.claude/rules/pipeline.md`,
  and the spec `docs/plans/2026-09-29-survey-mode-design.md`. Follow the spec exactly.
- **Gradle needs JDK 21 (JBR) and the SDK on every call.** Prefix every Gradle command in this plan with
  `JAVA_HOME="/c/Program Files/Android/Android Studio1/jbr" ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"`.
  Tasks show the commands without the prefix to stay readable. Examples with the prefix:

  ```bash
  (cd pipeline && JAVA_HOME="/c/Program Files/Android/Android Studio1/jbr" ../gradlew test --tests '*PathTimelineTest')
  JAVA_HOME="/c/Program Files/Android/Android Studio1/jbr" ANDROID_HOME="$LOCALAPPDATA/Android/Sdk" ./gradlew :app:testDebugUnitTest --tests 'com.stastyle.imumapper.data.SurveyStoreTest'
  JAVA_HOME="/c/Program Files/Android/Android Studio1/jbr" ANDROID_HOME="$LOCALAPPDATA/Android/Sdk" ./gradlew :app:assembleDebug
  ```

- **Pipeline tests run from `pipeline/`**; a root `./gradlew test` does not run them. Only failing tests
  print; reports are in `pipeline/build/reports/tests/test/` and `app/build/reports/tests/`.
- **Never read, print, stage or commit `*.jks` or `*.jks.base64`.** Stage files by explicit path, never
  `git add -A` or `git add .`.
- **Out of bounds:** no new dependency, no edit to `gradle/libs.versions.toml`, `*.gradle.kts` or the
  workflows, no Room entity or database change, no `PIPELINE_VERSION` bump, no `tools/replay.py` port
  (the survey package is code the processor never runs, like `tuning/`).
- **Commits:** one commit per task, message through a heredoc, subject a plain-English sentence about the
  effect, a short prose body saying why, and the trailer:

  ```bash
  git commit -F - <<'EOF'
  Place survey stations at any moment of a recorded walk

  A PDR path has one point per step, so a moment between two steps is placed where the walker stood
  until the last stride of the gap; VIO paths are interpolated linearly.

  Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
  EOF
  ```

---

## 1. Shape of the change

```
pipeline/.../pipeline/survey/          pure math, JUnit 5 tests next door
    SurveyDoc.kt          StationKind, Station, ReferenceLine, CompassReference, Detail, SurveyDoc (+ JSON)
    SurveyAngles.kt       azimuth / wrap helpers in degrees
    PathTimeline.kt       time placement, distance along the path, samples between two moments
    Measure.kt            LegMeasure, StretchMeasure, Measure (chord numbers, curved, line fit)
    NorthFrame.kt         rotation of a PathResult about its first point
    NorthSolver.kt        NorthSource, ReferenceFit, NorthSolution, NorthSolver
    CornerDetector.kt     Corner, CornerDetector (RDP + turn filter + spacing)
    SurveyStations.kt     seeding, names, corner regeneration, ordering
    Traverse.kt           TraverseLeg, LegTotals, ChainMeasure, Traverse
    SurveyCsv.kt          CSV text and the correction sentence

app/.../data/
    AtomicFiles.kt        NEW  temp-file-and-rename write (moved out of TripProcessor)
    SurveyStore.kt        NEW  SurveyLoad + SurveyStore (files/trips/<id>/survey.json)
    ExportNames.kt        NEW  file-system-safe stem shared by the ZIP and CSV names
    SurveyCsvFile.kt      NEW  CSV file name and write into cache/export
    SurveyShare.kt        NEW  Android share intent for a CSV (FileProvider)
    TripFiles.kt          MOD  surveyFile(), SURVEY_NAME, layout comment
    TripArchive.kt        MOD  survey.json in the ZIP, ExtractedTrip.survey
    TripImporter.kt       MOD  moves survey.json into the new trip
    TripExporter.kt       MOD  exportFileName uses ExportNames.safeStem
app/.../process/TripProcessor.kt       MOD  uses AtomicFiles.writeText
app/.../AppContainer.kt                MOD  surveyStore
app/.../render/
    SurveyLayer.kt        NEW  SurveyColors, LayerStation, LayerSelection, SurveyLayer (pure)
    ProjectedSurvey.kt    NEW  SurveyHit, ProjectedSurvey (pure projection + hit tests)
    SurveyRenderer.kt     NEW  Compose drawing of a ProjectedSurvey
app/.../ui/viewer/
    ViewerCanvas.kt       MOD  CanvasGestures survey callbacks, long-press, orbit lock, survey layer
    SurveyFormat.kt       NEW  display strings (pure)
    SurveyController.kt   NEW  SurveyGeometry, SurveySelection, SurveyState, SurveyOpen, SurveyReadout,
                               AzimuthWarning, AzimuthPreview, SurveyController (pure)
    ViewerViewModel.kt    MOD  SurveyUi, SurveyMessage, SurveyCsvShare, survey state and actions
    SurveyPanel.kt        NEW  the panel: selection row, readout, scrubber, buttons
    SurveySheets.kt       NEW  LegsSheet, StationSheet, DetailDialog
    NorthDialogs.kt       NEW  SetAzimuthDialog, NorthSheet, ManualRotationDialog
    ViewerScreen.kt       MOD  top bar, banner, snackbar, share, wiring
CLAUDE.md                                  MOD  layout lists the survey package and survey.json
```

Tests (new unless marked):

```
pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/
    SurveyPaths.kt (fixtures), SurveyDocTest, SurveyAnglesTest, PathTimelineTest, MeasureTest,
    NorthFrameTest, NorthSolverTest, CornerDetectorTest, SurveyStationsTest, TraverseTest, SurveyCsvTest
app/src/test/kotlin/com/stastyle/imumapper/
    SurveyFixtures.kt (fixtures, package com.stastyle.imumapper)
    data/SurveyStoreTest, data/SurveyCsvFileTest, data/TripArchiveTest (MOD), data/TripImporterTest (MOD)
    render/SurveyLayerTest, render/ProjectedSurveyTest
    ui/viewer/SurveyFormatTest, ui/viewer/SurveyControllerTest, ui/viewer/SurveyEditsTest,
    ui/viewer/SurveyNorthEditsTest, ui/viewer/ViewerSurveyTest
```

---

## 2. Shared interfaces

Every signature below is binding: tasks implement exactly these names, parameter orders and defaults so
later tasks compile against earlier ones. Bodies are the task's job; where an algorithm is subtle, it is
spelled out here so all tasks agree. KDoc on each public declaration says why, not what (one line is
enough unless the algorithm needs more). The owning task is in brackets.

### 2.1 Pipeline: `com.stastyle.imumapper.pipeline.survey`

Existing symbols used: `PathPoint`, `PathResult`, `PathAnnotation`, `PathKeyframe`, `PositionSource`,
`AnnotationKind`, `Vec3` (`core`); `Angles.wrap` (`pdr/Angles.kt`); `OrientationEstimator.NORTH_REFERENCE`,
`OrientationEstimator.MAGNETIC` (`pdr/OrientationEstimator.kt`); `PathBuilder.nearestIndex` is not needed.

#### `SurveyDoc.kt` [Task 1]

```kotlin
/** Where a station came from: it sets the default name, and only CORNER stations regenerate on a Detail change. */
@Serializable
enum class StationKind { START, END, MARK, CORNER, USER }

/** A named moment of the walk. It is stored by time and placed on whichever path is shown. */
@Serializable
data class Station(
    val id: Int = 0,
    val kind: StationKind = StationKind.USER,
    val name: String = "",
    /** elapsedRealtime nanoseconds, the clock of every PathPoint.tNs. */
    val tNs: Long = 0L,
)

/** Which line of the stretch a compass bearing describes. */
@Serializable
enum class ReferenceLine {
    /** The straight line from the first moment to the second (default). */
    CHORD,
    /** The direction of the passage: the total-least-squares line through the stretch. */
    FITTED,
}

/** A hand-compass bearing (magnetic) between two moments of the walk, stored as read. */
@Serializable
data class CompassReference(
    val id: Int = 0,
    val fromNs: Long = 0L,
    val toNs: Long = 0L,
    /** Degrees in [0, 360) as read on the compass, before any back-bearing flip. */
    val bearingDeg: Double = 0.0,
    /** Taken from B back to A: 180 degrees are added before use. */
    val backBearing: Boolean = false,
    val line: ReferenceLine = ReferenceLine.CHORD,
)

/** RDP tolerance for automatic corners. */
@Serializable
enum class Detail(val toleranceM: Double) { COARSE(1.0), NORMAL(0.5), FINE(0.25) }

/**
 * The per-trip survey file (`files/trips/<id>/survey.json`). Facts only: no derived value and no NaN,
 * every field defaulted so older and newer files keep decoding.
 */
@Serializable
data class SurveyDoc(
    val formatVersion: Int = FORMAT_VERSION,
    val stations: List<Station> = emptyList(),
    val references: List<CompassReference> = emptyList(),
    /** Degrees, positive turns the map clockwise; used only while no reference enters the solve. */
    val manualRotationDeg: Double = 0.0,
    /** The run the manual rotation was set on; null when it was never set. */
    val manualRotationRunId: Int? = null,
    val detail: Detail = Detail.NORMAL,
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        const val FORMAT_VERSION: Int = 1

        /** coerceInputValues turns an enum value from a newer build into the field's default instead of failing. */
        val json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            coerceInputValues = true
            prettyPrint = true
        }

        fun fromJson(text: String): SurveyDoc = json.decodeFromString(serializer(), text)
    }
}
```

`allowSpecialFloatingPointValues` stays off, so `toJson()` throws on a NaN or infinite value; callers
validate input before it reaches the doc.

#### `SurveyAngles.kt` [Task 2]

```kotlin
/** Degree helpers for survey numbers (the pipeline's Angles works in radians). */
object SurveyAngles {
    /** Plan direction (dE, dN) as degrees clockwise from north in [0, 360): N 0, E 90, S 180, W 270. */
    fun azimuthDeg(dE: Double, dN: Double): Double
    /** Wraps into (-180, 180]; never returns -0.0. */
    fun wrapDeg(deg: Double): Double
    /** Wraps into [0, 360); never returns 360.0 or -0.0. */
    fun to360(deg: Double): Double
}
```

`azimuthDeg = to360(Math.toDegrees(atan2(dE, dN)))`. `to360`: `r = deg % 360`, add 360 when negative,
map a result `>= 360.0` to `0.0`, add `0.0` to clear a negative zero. `wrapDeg`: `r = deg % 360`,
subtract 360 when `> 180`, add 360 when `<= -180`, add `0.0`.

#### `PathTimeline.kt` [Task 3]

```kotlin
/**
 * Places moments of the walk on a path and measures distance along it. A PDR path has one point per
 * step, and between two steps separated by a long gap (standing, or a pause) the walker stayed at the
 * earlier step: in the segment from point i to i+1, when point i+1 is a PDR point, the position holds at
 * p(i) and moves to p(i+1) over the last min(gap, HOLD_STEP_FACTOR x medianStepNs) of the gap. Other
 * segments (VIO, INTERPOLATED) are linear in time. Distances are 3D and cumulative from the first point.
 */
class PathTimeline(val points: List<PathPoint>) {
    init { require(points.isNotEmpty()) { "a timeline needs at least one point" } }

    val startNs: Long
    val endNs: Long
    /** 3D length of the whole path, metres. */
    val lengthM: Double
    /** Median positive gap between consecutive step points (both stepIndex >= 0, later one PDR); DEFAULT_STEP_NS when there is none. */
    val medianStepNs: Long

    /** Distance along the path at point [index]. */
    fun distanceOfPoint(index: Int): Double
    /** Where the walker was at [tNs]; clamped to the path's time range. */
    fun positionAt(tNs: Long): Vec3
    /** Metres along the path at [tNs]; clamped to [0, lengthM]. */
    fun distanceAt(tNs: Long): Double
    /** The point [distanceM] along the path (linear by distance inside a segment); clamped. */
    fun positionAtDistance(distanceM: Double): Vec3
    /** The earliest moment the walker reached [distanceM]; clamped. Standing still takes no distance. */
    fun timeAtDistance(distanceM: Double): Long
    /** positionAt(min), every point strictly between the two times, positionAt(max), in time order; the ends may be given in either order. */
    fun samplesBetween(fromNs: Long, toNs: Long): List<Vec3>
    /** Time of the last point strictly before [tNs], or startNs. Used by the scrubber's back button. */
    fun previousPointNs(tNs: Long): Long
    /** Time of the first point strictly after [tNs], or endNs. */
    fun nextPointNs(tNs: Long): Long

    companion object {
        const val HOLD_STEP_FACTOR: Double = 1.5
        /** A typical walking step period, used when a path has too few steps to measure one. */
        const val DEFAULT_STEP_NS: Long = 550_000_000L
    }
}
```

Internals (all arrays built once in the constructor): `t[i]`, `cum[i]` (cumulative 3D distance),
`moveStart[i]` for each segment i (0..n-2): `if (points[i+1].source == PositionSource.PDR)
t[i+1] - min(t[i+1]-t[i], round(HOLD_STEP_FACTOR * medianStepNs)) else t[i]`. Fraction in segment i at
time t: `1.0` when `t >= t[i+1]`, `0.0` when `t <= moveStart[i]`, else
`(t - moveStart[i]) / (t[i+1] - moveStart[i])`. `positionAt` lerps `p[i]` to `p[i+1]` by that fraction;
`distanceAt` lerps `cum[i]` to `cum[i+1]`. `timeAtDistance(s)`: clamp s, `j` = first index with
`cum[j] >= s` (binary search); `j == 0` gives `startNs`; `cum[j] == s` gives `t[j]`; otherwise segment
`j-1` with `f = (s - cum[j-1]) / (cum[j] - cum[j-1])` gives `moveStart[j-1] + round(f * (t[j] -
moveStart[j-1]))`. A one-point path has length 0 and returns that point everywhere.

#### `Measure.kt` [Tasks 4 and 5]

```kotlin
/** Numbers for the straight line (chord) from A, the path at [fromNs], to B, the path at [toNs]. */
data class LegMeasure(
    val fromNs: Long,
    val toNs: Long,
    val a: Vec3,
    val b: Vec3,
    val lengthM: Double,
    val horizontalM: Double,
    /** b.z - a.z, signed. */
    val heightChangeM: Double,
    /** Degrees clockwise from north in [0, 360); null when horizontalM < Measure.MIN_HORIZONTAL_M. */
    val azimuthDeg: Double?,
    /** atan2(dz, H) in degrees, up positive; +90 / -90 for a vertical leg, 0 for no movement. */
    val slopeDeg: Double,
    /** 100 * dz / H; null when horizontalM < Measure.MIN_HORIZONTAL_M. */
    val gradePct: Double?,
    /** 3D length along the path between the two moments. */
    val pathM: Double,
    /** The path strays from the chord by more than max(CURVE_MIN_M, CURVE_FRACTION x lengthM). */
    val curved: Boolean,
) {
    /** Over a short horizontal run barometer noise dominates the slope, so it is shown greyed. */
    val slopeUncertain: Boolean get() = horizontalM < Measure.MIN_SLOPE_HORIZONTAL_M
    /** lengthM / pathM, 1 for a straight stretch; null when pathM is 0. */
    val straightness: Double? get() = if (pathM > 0.0) lengthM / pathM else null
}

/** A stretch selection: the chord plus the direction of the passage fitted through it. */
data class StretchMeasure(
    val leg: LegMeasure,
    /** Degrees in [0, 360), oriented from A to B; null when the chord has no azimuth or the fit is degenerate. */
    val fittedAzimuthDeg: Double?,
)

object Measure {
    const val MIN_HORIZONTAL_M: Double = 0.3
    const val MIN_SLOPE_HORIZONTAL_M: Double = 5.0
    const val CURVE_MIN_M: Double = 0.3
    const val CURVE_FRACTION: Double = 0.02
    const val FIT_SPACING_M: Double = 0.25

    /** [Task 4] The chord numbers for two moments of [timeline], in the order given (A may be later than B). */
    fun leg(timeline: PathTimeline, fromNs: Long, toNs: Long): LegMeasure
    /** [Task 4] Largest 3D distance from [samples] to the segment [a]-[b] (to [a] when they coincide). */
    fun maxDeviationM(samples: List<Vec3>, a: Vec3, b: Vec3): Double
    /** [Task 5] Total-least-squares direction of the stretch's plan points, resampled every FIT_SPACING_M of path. */
    fun fittedAzimuthDeg(timeline: PathTimeline, fromNs: Long, toNs: Long): Double?
    /** [Task 5] leg() plus fittedAzimuthDeg(). */
    fun stretch(timeline: PathTimeline, fromNs: Long, toNs: Long): StretchMeasure
}
```

`leg`: `a = positionAt(fromNs)`, `b = positionAt(toNs)`, `d = b - a`, `H = hypot(d.x, d.y)`,
`pathM = abs(distanceAt(toNs) - distanceAt(fromNs))`, `curved` from
`maxDeviationM(samplesBetween(fromNs, toNs), a, b)`. Slope uses `Math.toDegrees(atan2(dz, H))`.
`fittedAzimuthDeg`: null when `H < MIN_HORIZONTAL_M`; sample `positionAtDistance(s)` for `s` from the
smaller distance to the larger in steps of `FIT_SPACING_M`, always including both ends; use (x, y) only
(height left out so barometer noise cannot tilt it); means, then `cEE`, `cNN`, `cEN`; null when
`cEE + cNN < 1e-12`; `phi = 0.5 * atan2(2 * cEN, cEE - cNN)` is the axis angle counter-clockwise from
east, so the unit vector is `(cos phi, sin phi)` in (E, N); flip it when its dot product with `(d.x, d.y)`
is negative; return `SurveyAngles.azimuthDeg(uE, uN)`.

#### `NorthFrame.kt` [Task 6]

```kotlin
/**
 * Turns a result onto corrected north about its first point O, clockwise on the map for positive
 * angles so every azimuth grows by the angle: x' = Ox + (x-Ox)cos + (y-Oy)sin, y' = Oy - (x-Ox)sin +
 * (y-Oy)cos, z' = z, heading' = wrap(heading + angle). Rotation commutes with LoopClosure.apply and
 * Smoothing.movingAverage, so rotating the output equals rotating inside the pipeline.
 */
object NorthFrame {
    /** Rotates points, rawPoints, annotations, keyframes (position and heading) and pointCloud; stats are kept. Same instance for 0 or an empty path. */
    fun rotate(result: PathResult, rotationDeg: Double): PathResult
    /** The points turned about [origin]; tNs, source, stepIndex kept, headingRad turned. */
    fun rotatePoints(points: List<PathPoint>, origin: Vec3, rotationDeg: Double): List<PathPoint>
}
```

#### `NorthSolver.kt` [Task 7]

```kotlin
enum class NorthSource { NONE, MANUAL, REFERENCES }

/** One compass reference after the solve. */
data class ReferenceFit(
    val referenceId: Int,
    /** Azimuth of the reference's line on the uncorrected path; null when its chord is under 0.3 m. */
    val measuredDeg: Double?,
    val horizontalM: Double,
    /** wrapDeg(R - M - rotation); null when measuredDeg is. */
    val residualDeg: Double?,
)

data class NorthSolution(
    /** Degrees in (-180, 180]; positive turns the map clockwise. */
    val rotationDeg: Double,
    val source: NorthSource,
    val fits: List<ReferenceFit>,
) {
    /** References that entered the solve. */
    val usedCount: Int get() = fits.count { it.residualDeg != null }
    /** Two or more references and one misses by more than NorthSolver.DISAGREE_DEG. */
    val disagree: Boolean
}

/** Solves the north rotation from the survey's facts for whichever run is shown. */
object NorthSolver {
    const val DISAGREE_DEG: Double = 3.0

    /**
     * [plain] is the shown path before any rotation. With references: rotation = atan2(sum h sin(R-M),
     * sum h cos(R-M)) over references with a measured azimuth (source REFERENCES); when none can be
     * measured on [plain] (every chord under 0.3 m) the sums are empty, giving 0 with source NONE, and the
     * manual rotation is not used. Without references, the manual rotation when manualApplies (source
     * MANUAL, or NONE when it is 0), else 0 with source NONE.
     */
    fun solve(doc: SurveyDoc, plain: PathTimeline, runId: Int): NorthSolution
    /** The bearing from A to B: bearingDeg, plus 180 for a back-bearing, in [0, 360). */
    fun forwardBearingDeg(reference: CompassReference): Double
    /** The reference's azimuth on [plain]: the chord's, or the fitted line's for ReferenceLine.FITTED. */
    fun measuredDeg(reference: CompassReference, plain: PathTimeline): Double?
    /** The manual rotation was set on [runId], or was never tagged. */
    fun manualApplies(doc: SurveyDoc, runId: Int): Boolean
    /** A manual rotation exists, no reference does, and it was set on another run: the app asks first. */
    fun manualNeedsConfirmation(doc: SurveyDoc, runId: Int): Boolean
    /** Azimuths are magnetic (suffix M): the run's northReference is MAGNETIC, or compass references were applied. */
    fun isMagnetic(diagnostics: Map<String, String>, solution: NorthSolution): Boolean
}
```

`isMagnetic` reads `diagnostics[OrientationEstimator.NORTH_REFERENCE]?.trim() == OrientationEstimator.MAGNETIC`
(constants, never literals) or `solution.source == NorthSource.REFERENCES`. A manual rotation alone
never makes a run magnetic; a run without the key is R.

#### `CornerDetector.kt` [Task 8]

```kotlin
/** A turning vertex of the simplified plan. The vertex is always an existing path point: a place the walker stood. */
data class Corner(val pointIndex: Int, val tNs: Long, val turnDeg: Double)

object CornerDetector {
    const val MIN_TURN_DEG: Double = 20.0
    const val MIN_SPACING_M: Double = 1.5

    /** Ramer-Douglas-Peucker on the plan (x, y) of [points], iterative; sorted indices, first and last always kept. */
    fun simplify(points: List<PathPoint>, toleranceM: Double): IntArray
    /**
     * The interior vertices whose plan direction turns by at least [minTurnDeg], in path order. The
     * weakest vertex below the limit is removed and its neighbours re-checked until every one passes.
     * A vertex next to a zero-length plan segment turns by 0.
     */
    fun turning(points: List<PathPoint>, vertices: IntArray, minTurnDeg: Double = MIN_TURN_DEG): List<Corner>
    /**
     * Corner times for [detail] on [timeline]: turning vertices taken strongest first (ties: earlier
     * first), each dropped when it lies within MIN_SPACING_M of path distance of a kept time or of a
     * corner already taken; returned in time order.
     */
    fun corners(timeline: PathTimeline, detail: Detail, keptTimesNs: List<Long>): List<Long>
}
```

Turn at vertex k = `abs(Angles.diff(h2, h1))` in degrees, where h1 / h2 are the plan headings
`atan2(dx, dy)` of the segments into and out of the vertex. Spacing uses `timeline.distanceAt`.

#### `SurveyStations.kt` [Task 9]

```kotlin
object SurveyStations {
    /** Annotation kinds that become MARK stations; LOOP_CLOSED and REORIENT do not. */
    val MARK_KINDS: Set<AnnotationKind> = setOf(AnnotationKind.WAYPOINT, AnnotationKind.JUNCTION, AnnotationKind.CHAMBER, AnnotationKind.NOTE)

    /** The traverse order: by tNs, then by id. */
    fun ordered(stations: List<Station>): List<Station>
    /**
     * First-open stations: START "Start" (id 1) at startNs, one MARK per MARK_KINDS annotation in time
     * order (ids 2, 3, ...) at its time clamped to the path, END "End" at endNs (next id), then the CORNER
     * stations of [detail] with every other station kept (next ids, named C1, C2, ... in time order).
     * Returned in traverse order. A mark can fall outside the path: PDR ends at the last step, so a mark
     * made while standing before STOP is later, and VIO starts at the first tracking frame. The walker
     * stood at that end, and by the id order a clamped mark stays after START and before END.
     */
    fun seed(timeline: PathTimeline, annotations: List<PathAnnotation>, detail: Detail = Detail.NORMAL): List<Station>
    /** The note as oneLineName, or the kind in title case and its 1-based [ordinal] among that kind ("Junction 2"). */
    fun markName(kind: AnnotationKind, note: String, ordinal: Int): String
    /** Trimmed, each line break and the spaces around it made one space (the Note field takes several lines). */
    fun oneLineName(raw: String): String
    /**
     * Removes every CORNER, detects corners for [detail] keeping all other stations, names them C1.. in
     * time order skipping names already in use (a moved corner is a USER station that kept its "C<n>"),
     * ids from nextId.
     */
    fun regenerateCorners(stations: List<Station>, timeline: PathTimeline, detail: Detail): List<Station>
    /** max(id) + 1, or 1. */
    fun nextId(stations: List<Station>): Int
    /** "S" + (1 + the largest n of any name "S<n>"), "S1" first. */
    fun nextUserName(stations: List<Station>): String
    /** A new USER station at [tNs] with nextId and nextUserName. */
    fun user(stations: List<Station>, tNs: Long): Station
}
```

MARK ordinals count every annotation of that kind in time order, including ones that have a note.

#### `Traverse.kt` [Task 10]

```kotlin
/** Consecutive stations of the traverse and the chord between them. */
data class TraverseLeg(val from: Station, val to: Station, val measure: LegMeasure)

data class LegTotals(val lengthM: Double, val horizontalM: Double, val heightChangeM: Double, val pathM: Double)

/** A chain selection: each hop, the straight line from the first to the last, and the sums. */
data class ChainMeasure(
    val hops: List<LegMeasure>,
    val straight: LegMeasure,
    val hopLengthSumM: Double,
    val hopPathSumM: Double,
)

object Traverse {
    /** Legs between consecutive stations in SurveyStations.ordered order; empty for fewer than two. */
    fun legs(stations: List<Station>, timeline: PathTimeline): List<TraverseLeg>
    /** Sums of the legs (heightChange sums to the net climb). */
    fun totals(legs: List<TraverseLeg>): LegTotals
    /** [timesNs] in tap order, at least two. */
    fun chain(timesNs: List<Long>, timeline: PathTimeline): ChainMeasure
}
```

#### `SurveyCsv.kt` [Task 11]

```kotlin
object SurveyCsv {
    /** Excel needs the byte-order mark to read UTF-8 (Hebrew station names). */
    const val BOM: String = "\uFEFF"
    const val HEADER: String = "from,to,from_s,to_s,length_m,horizontal_m,height_change_m,azimuth_deg,north," +
        "slope_deg,grade_pct,path_m,curved,to_east_m,to_north_m,to_up_m"
    const val EOL: String = "\r\n"

    /** The whole file: BOM + HEADER + EOL, then one row + EOL per leg. See section 4.1 for the cells. */
    fun text(legs: List<TraverseLeg>, startNs: Long, origin: Vec3, magnetic: Boolean): String
    /** RFC 4180 quoting: wrapped in quotes, quotes doubled, when the value holds , " CR or LF. */
    fun field(value: String): String
    /** Locale.US fixed-point with [decimals] places; "-0.00" becomes "0.00". */
    fun fixed(value: Double, decimals: Int): String
    /** "north +4.0° from 2 compass readings", "north +4.0° from 1 compass reading", "north -1.5° set by hand", "north as recorded". */
    fun correctionText(solution: NorthSolution): String
}
```

### 2.2 App data: `com.stastyle.imumapper.data`

```kotlin
// AtomicFiles.kt [Task 12]
/** Writes through a temp file and a rename so a reader never sees half a file (results, survey.json, CSV). */
object AtomicFiles {
    /** Writes [text] (UTF-8) to "<target>.tmp" then renames it over [target]; falls back to a direct write when the rename fails. */
    fun writeText(target: File, text: String)
}
```

`TripProcessor`: delete its private `writeAtomically` and call `AtomicFiles.writeText(resultFile,
result.toJson())` at the one call site. Behaviour is unchanged (same `.tmp` suffix).

```kotlin
// TripFiles.kt [Task 12]
fun surveyFile(tripId: Long): File = File(tripDir(tripId), SURVEY_NAME)
// companion:
const val SURVEY_NAME = "survey.json"
// KDoc layout block gains: files/trips/<tripId>/survey.json  Survey mode facts (SurveyStore)
```

```kotlin
// SurveyStore.kt [Task 12]
/** What reading a trip's survey.json found. */
sealed interface SurveyLoad {
    /** No file yet: Survey mode has never been opened on this trip, so it seeds. */
    data object Missing : SurveyLoad
    data class Loaded(val doc: SurveyDoc) : SurveyLoad
    /** formatVersion above SurveyDoc.FORMAT_VERSION: read-only, never overwritten, no Start over. */
    data class Newer(val doc: SurveyDoc) : SurveyLoad
    /** Unreadable or undecodable: Survey mode goes read-only and never overwrites it. */
    data class Malformed(val message: String) : SurveyLoad
}

/** Blocking file access for survey.json; call it off the main thread. */
class SurveyStore(private val files: TripFiles) {
    /**
     * Missing when the file does not exist; Malformed on any read or decode exception, with the first
     * non-blank line of the exception message, or the class name (kotlinx.serialization appends the JSON
     * input on later lines, and the controller puts the message in a one-line error).
     */
    fun load(tripId: Long): SurveyLoad
    /** Atomic write of doc.toJson() through AtomicFiles. */
    fun save(tripId: Long, doc: SurveyDoc)
}
```

`AppContainer` [Task 12]: `val surveyStore: SurveyStore by lazy { SurveyStore(tripFiles) }`.

```kotlin
// TripArchive.kt [Task 13]
data class ExtractedTrip(
    val manifest: TripManifest?,
    val rawLog: File?,
    val results: List<File>,
    val photos: List<File>,
    /** survey.json when the archive carried one (exports from builds with Survey mode). */
    val survey: File? = null,
)
```

ZIP layout gains `survey.json` at the root. `write` adds it after the results and before the photos when
`File(tripDir, TripFiles.SURVEY_NAME)` is a file; `extractZip` copies an entry named exactly
`TripFiles.SURVEY_NAME` to `File(stagingDir, TripFiles.SURVEY_NAME)`. `MANIFEST_VERSION` stays 1 (older
builds ignore the entry). The KDoc layout block lists the entry. `TripImporter.moveIntoTrip` adds
`extracted.survey?.let { move(it, files.surveyFile(tripId)) }`. A `.tmp` left in the trip root is never
exported (only the exact name is).

```kotlin
// ExportNames.kt [Task 14]
/** File names other apps see: readable, and safe on every file system the share sheet may hand them to. */
object ExportNames {
    /** Letters and digits kept, everything else '_', '_' trimmed at the ends, at most 40 chars, "trip" when empty. */
    fun safeStem(name: String): String
}

// SurveyCsvFile.kt [Task 14]
object SurveyCsvFile {
    const val MIME_CSV: String = "text/csv"
    /** "<safeStem>-<tripId>-run<runId>[-raw]-survey.csv". */
    fun fileName(tripName: String, tripId: Long, runId: Int, raw: Boolean): String
    /** Writes [text] into [dir] atomically and returns the file. */
    fun write(dir: File, fileName: String, text: String): File
}

// SurveyShare.kt [Task 14], Android, not unit tested
object SurveyShare {
    /** ACTION_SEND chooser for a CSV in cache/export, through the <packageName>.fileprovider authority (TripExporter.authority). */
    fun intent(context: Context, file: File, subject: String, text: String): Intent
}
```

`TripExporter.exportFileName` switches its inline sanitising to `ExportNames.safeStem(tripName)`; its
output is unchanged. `SurveyShare.intent` mirrors `TripExporter.shareIntent`: `type = MIME_CSV`,
`EXTRA_STREAM`, `EXTRA_SUBJECT`, `EXTRA_TEXT`, `clipData = ClipData.newRawUri(subject, uri)`,
`FLAG_GRANT_READ_URI_PERMISSION` on both the send and the chooser (`"Export survey CSV"`). The existing
`<cache-path name="cache" path="/" />` already covers `cache/export/`.

### 2.3 App render: `com.stastyle.imumapper.render`

```kotlin
// SurveyLayer.kt [Task 15], pure (no Compose)
/** ARGB colours of the survey layer. */
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
    fun forKind(kind: StationKind): Int
}

/** A station as drawn. [order] is its 1-based place in the chain (last occurrence), 0 when not in it. */
data class LayerStation(
    val id: Int,
    val position: Vec3,
    val name: String,
    val color: Int,
    val selected: Boolean,
    val order: Int,
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
        const val MAX_PATH_VERTICES: Int = 4000
        const val MAX_STRETCH_VERTICES: Int = 2000
        fun build(timeline: PathTimeline, stations: List<Station>, selection: LayerSelection, cursorNs: Long?): SurveyLayer
    }
}
```

`build`: station positions `timeline.positionAt(tNs)`, colour `SurveyColors.forKind`, `selected` when in
`chainIds` or `stretchIds`; chords for consecutive `chainIds` (unknown ids skipped) plus one chord between
the stretch ends; stretch vertices from `timeline.samplesBetween`, decimated with
`PathScene.decimate(n, MAX_STRETCH_VERTICES)`; hit-test vertices `PathScene.decimate(points.size,
MAX_PATH_VERTICES)` with `timeline.distanceOfPoint(i)`; cursor `positionAt(cursorNs)` or null.

```kotlin
// ProjectedSurvey.kt [Task 16], pure
/** What a survey tap or long press landed on. Stations lie on the path, so a station always wins. */
sealed interface SurveyHit {
    data class OnStation(val stationId: Int) : SurveyHit
    data class OnPath(val distanceM: Double) : SurveyHit
    data object Miss : SurveyHit
}

/** Screen-space copy of a SurveyLayer, refilled on each camera or viewport change (like ProjectedScene). */
class ProjectedSurvey(val layer: SurveyLayer) {
    val stationScreen: FloatArray      // 2 per station
    val stationVisible: BooleanArray
    val chordScreen: FloatArray        // 4 per chord
    val chordVisible: BooleanArray     // both ends in front of the near plane
    val stretchScreen: FloatArray      // 2 per vertex
    val stretchVisible: BooleanArray
    val cursorScreen: FloatArray       // 2
    var cursorVisible: Boolean
        private set
    val pathScreen: FloatArray         // 2 per hit-test vertex
    val pathVisible: BooleanArray

    /** Re-projects when camera or viewport changed; true when work was done. */
    fun update(camera: OrbitCamera, widthPx: Float, heightPx: Float): Boolean
    /** Index into layer.stations nearest on screen within [radiusPx], or -1. */
    fun hitTestStation(xPx: Float, yPx: Float, radiusPx: Float): Int
    /** Distance along the path of the nearest point on a visible screen segment within [radiusPx], or -1.0. */
    fun hitTestPath(xPx: Float, yPx: Float, radiusPx: Float): Double
    /** Station within STATION_HIT_DP, else path within PATH_HIT_DP, else Miss; radii scaled by [density]. */
    fun hitTest(xPx: Float, yPx: Float, density: Float): SurveyHit

    companion object {
        /** Same as PathRenderer.HIT_RADIUS_DP, repeated because this file stays free of Compose. */
        const val STATION_HIT_DP: Float = 24f
        const val PATH_HIT_DP: Float = 32f
    }
}
```

`hitTestPath` interpolates `pathDistances` by the fraction along the nearest screen segment.

```kotlin
// SurveyRenderer.kt [Task 17], Compose
object SurveyRenderer {
    /** Draws stretch, chords, cursor, stations and names over the scene; call after update(). */
    fun DrawScope.drawSurvey(projected: ProjectedSurvey, textMeasurer: TextMeasurer?)
}
```

Labels go through `PathRenderer.labelOrigin` (never call `drawText` with an unchecked origin).

### 2.4 App viewer: `com.stastyle.imumapper.ui.viewer`

#### `ViewerCanvas.kt` changes [Task 18]

```kotlin
class CanvasGestures(
    val onViewport: (widthPx: Float, heightPx: Float) -> Unit,
    val onOrbit: (dxPx: Float, dyPx: Float) -> Unit,
    val onZoom: (factor: Float) -> Unit,
    val onPan: (dxPx: Float, dyPx: Float) -> Unit,
    val onDoubleTap: () -> Unit,
    /** Marker index in the scene, or -1 when the tap hit nothing. Outside Survey mode only. */
    val onTap: (markerIndex: Int) -> Unit,
    /** Survey mode: what a tap hit. */
    val onSurveyTap: (SurveyHit) -> Unit = {},
    /** Survey mode: the station or path a long press hit; a pause on empty map is not one, so never Miss. */
    val onSurveyLongPress: (SurveyHit) -> Unit = {},
)

@Composable
fun ViewerCanvas(
    scene: SceneModel?,
    camera: OrbitCamera,
    selectedMarker: Int,
    gestures: CanvasGestures,
    modifier: Modifier = Modifier,
    /** Survey mode's layer, drawn over the scene; null outside Survey mode or while it loads. */
    survey: SurveyLayer? = null,
    /** One-finger drag pans instead of orbiting (Survey mode's plan view). */
    orbitLocked: Boolean = false,
)
```

#### `SurveyFormat.kt` [Task 19], pure

```kotlin
/** Display strings for Survey mode. Numbers use Locale.US; a value that cannot be measured shows DASH. */
object SurveyFormat {
    const val DASH: String = "—"
    fun suffix(magnetic: Boolean): String                          // "M" / "R"
    fun metres(m: Double): String                                  // "34.2 m"
    fun signedMetres(m: Double): String                            // "+4.1", "-0.3", "+0.0" (no unit)
    fun azimuth(deg: Double?, magnetic: Boolean): String           // "047° M", "000° R" (359.6 rounds to 000), DASH
    fun slope(deg: Double): String                                 // "▲ +7°", "▼ -3°", "0°"
    fun signedDegrees(deg: Double): String                         // "+7°", "-3°", "+0°"
    fun grade(pct: Double?): String                                // "12 %", "-5 %", DASH
    fun rotation(deg: Double): String                              // "+4.0°", "-0.5°", "+0.0°"
    fun details(leg: LegMeasure): String                           // "horiz 33.9 · Δh +4.1 · 12 % · path 38.5⌒"
    fun stretchLine(measure: StretchMeasure, magnetic: Boolean): String  // "fitted 046° M · straight 0.89"
    fun copyLine(from: String, to: String, leg: LegMeasure, magnetic: Boolean): String
        // "Junction 2 → Chamber: 34.2 m, horiz 33.9 m, 047° M, +7° (Δh +4.1 m), path 38.5 m"
    fun northChipShort(north: NorthSolution, magnetic: Boolean): String // top bar: "N +4.0° M"
    fun northChip(north: NorthSolution, magnetic: Boolean): String // North sheet: "N +4.0° M", "N +4.0° M · 1 ref", "N +4.0° M · 2 refs"
    fun cursorLabel(startedAtEpochMs: Long?, elapsedNs: Long, distanceM: Double, zone: ZoneId): String
        // "12:40:18 · 41.2 m"; with no start time the elapsed formatDuration(): "3:05 · 41.2 m"
    fun shareText(tripName: String, runId: Int, raw: Boolean, north: NorthSolution): String
        // "Cave loop · Run 3, north +4.0° from 2 compass readings"; raw adds " (raw path)" after the run number
    fun tableHeader(magnetic: Boolean): List<String>              // From, To, Length, Azimuth M, Slope, Δh, Path
    fun tableRow(leg: TraverseLeg): List<String>                   // name, name, "34.2", "047°", "+7°", "+4.1", "38.5⌒"
    fun totalsRow(totals: LegTotals): List<String>                 // "Total", "", "136.4", "", "", "+2.0", "150.1"
    fun parseDegrees(text: String): Double?                        // trims, ',' as decimal point, null when blank/invalid/non-finite
    // Used only by Section D's UI and view model:
    const val DISAGREE: String = "References disagree: drift during the walk, or a misread bearing"  // North sheet
    fun detailLabel(detail: Detail): String                        // "Coarse (1 m)", "Normal (0.5 m)", "Fine (0.25 m)"
    fun corners(count: Int): String                                // "1 corner", "3 corners"
    fun reference(reference: CompassReference, fit: ReferenceFit?): String
        // "045° M · point to point · residual +0.3°", "225° M back-bearing · passage · not used: too short"
}
```

Rounding rules: rotations and metres are rounded to one decimal before formatting and then `+ 0.0`, so no
"-0.0" appears; degrees round with `Math.round`. `details` joins with `" · "`; `⌒` is appended to the path
value only when `leg.curved`. `stretchLine` shows `DASH` for a null fit or straightness, straightness with
two decimals. `formatDuration` is the existing top-level function in `ViewerPanels.kt`.

#### `SurveyController.kt` [Tasks 20, 21, 22], pure

```kotlin
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
    fun rotated(rotationDeg: Double): SurveyGeometry

    companion object {
        /** [shown] must have at least one point. With 0 the framed result and timeline are the plain ones. */
        fun of(shown: PathResult, runId: Int, raw: Boolean, rotationDeg: Double = 0.0): SurveyGeometry
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
    /** survey.json could not be read, or a newer app saved it: nothing is edited or saved. */
    val readOnly: Boolean = false,
)

/** Result of opening Survey mode on a trip. */
class SurveyOpen(
    val state: SurveyState,
    /** A new doc was seeded and must be saved now. */
    val seeded: Boolean,
    /** Why the survey is read-only; null otherwise. */
    val error: String?,
    /** The read-only banner offers Start over: only for an unreadable file, not a newer app's good one. */
    val canStartOver: Boolean = false,
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

object SurveyController {
    const val MAX_UNDO: Int = 50
    const val MIN_REFERENCE_HORIZONTAL_M: Double = 10.0
    const val MIN_STRAIGHTNESS: Double = 0.9
    const val MAX_FIT_GAP_DEG: Double = 3.0
    const val LARGE_CHANGE_DEG: Double = 15.0
    const val BACK_BEARING_CHANGE_DEG: Double = 45.0
    const val PATH_START_NAME: String = "Path start"
    const val PATH_END_NAME: String = "Path end"

    // --- Task 20: open, selection, cursor, readout (none of these is an edit; all work read-only) ---
    fun open(load: SurveyLoad, geo: SurveyGeometry): SurveyOpen
    fun tapStation(state: SurveyState, stationId: Int): SurveyState
    fun tapPath(state: SurveyState, geo: SurveyGeometry, distanceM: Double): SurveyState
    fun selectLeg(state: SurveyState, fromId: Int, toId: Int): SurveyState
    fun clearSelection(state: SurveyState): SurveyState
    fun setCursor(state: SurveyState, geo: SurveyGeometry, distanceM: Double): SurveyState
    fun stepCursor(state: SurveyState, geo: SurveyGeometry, delta: Int): SurveyState
    /** The two moments a pair (chain of exactly two) or a stretch spans; null otherwise. */
    fun selectionEnds(state: SurveyState, geo: SurveyGeometry): Pair<Long, Long>?
    /** The id of the one selected CORNER or USER station ("Move here"); null otherwise. */
    fun movableStationId(state: SurveyState): Int?
    fun layerSelection(state: SurveyState, geo: SurveyGeometry): LayerSelection
    fun readout(state: SurveyState, geo: SurveyGeometry): SurveyReadout?

    // --- Task 21: station edits and undo (each pushes one undo entry; refused read-only) ---
    fun addStation(state: SurveyState, tNs: Long): SurveyState
    fun moveStation(state: SurveyState, stationId: Int, tNs: Long): SurveyState
    fun renameStation(state: SurveyState, stationId: Int, name: String): SurveyState
    fun deleteStation(state: SurveyState, stationId: Int): SurveyState
    fun setDetail(state: SurveyState, geo: SurveyGeometry, detail: Detail): SurveyState
    fun cornerCounts(state: SurveyState, geo: SurveyGeometry): Map<Detail, Int>
    fun undo(state: SurveyState): SurveyState

    // --- Task 22: north edits and the Set azimuth preview ---
    fun addReference(state: SurveyState, geo: SurveyGeometry, bearingDeg: Double, backBearing: Boolean, line: ReferenceLine): SurveyState
    fun deleteReference(state: SurveyState, referenceId: Int): SurveyState
    fun setManualRotation(state: SurveyState, rotationDeg: Double, runId: Int): SurveyState
    fun confirmManualRotation(state: SurveyState, runId: Int): SurveyState
    fun resetNorth(state: SurveyState): SurveyState
    fun azimuthPreview(state: SurveyState, geo: SurveyGeometry, bearingDeg: Double, backBearing: Boolean, line: ReferenceLine): AzimuthPreview?
}
```

Behaviour, all binding:

- **open:** `Missing` gives `SurveyDoc(stations = SurveyStations.seed(geo.timeline, geo.framed.annotations))`,
  `seeded = true`. `Loaded(doc)` gives that doc unchanged, `seeded = false` (never re-seeded, even when
  empty). `Malformed(message)` gives the seeded stations in memory, `readOnly = true`, `seeded = false`,
  `error = "survey.json could not be read ($message). Survey mode is read-only and the file is left as it is."`,
  `canStartOver = true`. `Newer(doc)` gives that doc unchanged, `readOnly = true`, `seeded = false`, an error
  asking for an update and `canStartOver = false`: saving it would drop the fields this build does not know.
  In every case `cursorNs = geo.timeline.startNs`, selection `None`, empty undo.
- **tapStation:** from `None` or `Stretch`, `Chain(listOf(id))`; in a chain, tapping the last id removes it
  (an empty chain becomes `None`), any other id is appended. Unknown ids are ignored.
- **tapPath:** `t = geo.timeline.timeAtDistance(distanceM)`, cursor moves to `t`; the stretch runs from
  the last station (traverse order) with `tNs <= t` to the first with `tNs > t`, null ends where none; it
  replaces any selection.
- **selectLeg:** `Stretch(fromId, toId)` (the Legs table row).
- **selectionEnds:** `Chain` of exactly two: their times in tap order. `Stretch`: the stations' times,
  `geo.timeline.startNs` / `endNs` for null ends. Otherwise null.
- **stepCursor:** `delta < 0` uses `previousPointNs`, `delta > 0` uses `nextPointNs`, `abs(delta)` times.
- **readout:** `Chain` of one: `First`. `Chain` of two or more: `Traverse.chain(times, geo.timeline)`.
  `Stretch`: `Measure.stretch(geo.timeline, from, to)` with names from the stations or
  `PATH_START_NAME` / `PATH_END_NAME`. `None`: null.
- **edits:** a private `edit(state, newDoc)` returns `state` unchanged when read-only or when
  `newDoc == state.doc`; otherwise pushes the old doc (dropping the oldest past `MAX_UNDO`) and prunes
  the selection against the new stations (chain ids removed, a stretch with a missing id becomes
  `None`). `undo` pops the last doc and prunes the same way; selection and cursor are not undone. Undoing
  a Detail change (the popped doc's `detail` differs) clears the selection instead, as `setDetail` does,
  because the two docs' corners may reuse the same ids for different stations.
- **addStation:** `SurveyStations.user(stations, tNs)` added, list re-ordered with `SurveyStations.ordered`.
- **moveStation:** only CORNER or USER; the station keeps id and name, gets `tNs`, and a CORNER becomes
  USER (so a Detail change keeps it). Other kinds: unchanged.
- **renameStation:** made one line (`SurveyStations.oneLineName`); a blank name or the current name is
  refused (unchanged). A renamed CORNER becomes USER (so a Detail change keeps it and its name); other
  kinds keep their kind.
- **deleteStation:** removes it; no tombstone (a deleted CORNER only returns when Detail changes).
- **setDetail:** `doc.copy(detail, stations = SurveyStations.regenerateCorners(stations, geo.timeline,
  detail))`, selection cleared to `None` (ids may be reused). Choosing the current Detail is a no-op.
- **cornerCounts:** for each `Detail`, the number of corners `regenerateCorners` would give now.
- **addReference:** needs `selectionEnds` and a non-null chord azimuth on `geo.plain`; the new
  `CompassReference(id = max(reference ids) + 1, fromNs, toNs, SurveyAngles.to360(bearingDeg), backBearing,
  line)`. Refused for a non-finite bearing.
- **setManualRotation:** refused while `doc.references` is non-empty or for a non-finite angle; stores
  `SurveyAngles.wrapDeg(rotationDeg)` and `manualRotationRunId = runId`.
- **confirmManualRotation:** `manualRotationRunId = runId`.
- **resetNorth:** no references, `manualRotationDeg = 0.0`, `manualRotationRunId = null`.
- **azimuthPreview:** null without `selectionEnds`. `chordDeg` / `fittedDeg` from
  `Measure.stretch(geo.timeline, ends)`; `rotationDeg` from `NorthSolver.solve(doc with the reference added,
  geo.plain, geo.runId)`; `changeDeg = wrapDeg(rotationDeg - NorthSolver.solve(doc, geo.plain,
  geo.runId).rotationDeg)`. Warnings: `SHORT` when horizontal < 10 m; `CROOKED` when straightness < 0.9
  or `abs(wrapDeg(chord - fitted)) > 3`; `LARGE_CHANGE` when `abs(change) > 15`; `BACK_BEARING` when
  `abs(change) > 45`. Neither change warning is raised while north is arbitrary: the current solve's
  source is `NONE` and `!NorthSolver.isMagnetic(geo.shown.diagnostics, it)` (a relative-north run with no
  measured reference and no manual rotation in use), because the first reading may then turn the map by
  any angle. The dialog words `BACK_BEARING` for a ticked box as "check the reading and the box".

#### `ViewerViewModel.kt` changes [Tasks 23, 24, 25]

```kotlin
/** Snackbar text from a survey edit; Undo is offered when [undoable]. */
data class SurveyMessage(val text: String, val undoable: Boolean)

/** A written CSV waiting for the share sheet. The screen builds the Intent (SurveyShare), so the VM stays JVM-testable. */
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
    /** SurveyOpen.canStartOver while read-only: the banner offers Start over. */
    val canStartOver: Boolean,
)

// ViewerUiState gains:
    /** Plan view, orbit locked, the survey panel instead of the stats panel. */
    val surveyMode: Boolean = false,
    /** Null while Survey mode is off or its file is loading. */
    val survey: SurveyUi? = null,
    val surveyMessage: SurveyMessage? = null,
    /** One-shot: the screen hands it to the share sheet, then calls consumeCsvShare. */
    val pendingCsv: SurveyCsvShare? = null,
// and two getters:
    /** What the canvas draws: in Survey mode the north-corrected path. */
    val sceneResult: PathResult? get() = if (surveyMode) survey?.geometry?.framed ?: shownResult else shownResult
    /** Survey mode hides the overlay run. */
    val sceneOverlay: PathResult? get() = if (surveyMode) null else shownOverlay

class ViewerViewModel(
    private val tripId: Long,
    private val trips: TripRepository,
    private val files: TripFiles,
    private val tripProcessor: TripProcessor,
    private val surveys: SurveyStore = SurveyStore(files),
    /** Tests pass Dispatchers.Unconfined so file work finishes inside the call. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel()

// companion object gains [Task 24]:
    /** Shown when an edit is tried on a read-only survey; the banner says why. */
    const val READ_ONLY_MESSAGE = "Survey mode is read-only: survey.json is left as it is"

// Task 23: mode, loading, geometry, selection
fun toggleSurvey()
fun surveyTap(hit: SurveyHit)
fun clearSurveySelection()
fun setSurveyCursor(distanceM: Double)
fun stepSurveyCursor(delta: Int)
fun selectLeg(fromId: Int, toId: Int)
// Task 24: station edits, detail, undo, messages
fun addStationAt(distanceM: Double)
fun addStationAtCursor()
fun moveSelectedStationToCursor()
fun renameStation(stationId: Int, name: String)
fun deleteStation(stationId: Int)
fun setDetail(detail: Detail)
fun cornerCounts(): Map<Detail, Int>
fun surveyUndo()
fun dismissSurveyMessage()
// Task 25: north, manual prompt, CSV
fun azimuthPreview(bearingDeg: Double, backBearing: Boolean, line: ReferenceLine): AzimuthPreview?
fun addReference(bearingDeg: Double, backBearing: Boolean, line: ReferenceLine)
fun deleteReference(referenceId: Int)
fun nudgeRotation(deltaDeg: Double)
fun setRotation(rotationDeg: Double)
fun resetNorth()
fun answerManualRotation(apply: Boolean)
fun exportSurveyCsv()
fun consumeCsvShare()
```

Private VM state: `surveyState: SurveyState?`, `surveyGeometry: SurveyGeometry?`, `surveyBase: SurveyBase?`,
`readoutCache: ReadoutCache?`, `boundsSource: PathResult?`, `surveyError: String?`,
`manualPromptDismissedRunId: Int?`, `docToSave: SurveyDoc?`, `saveLock = Mutex()`. Two private nested
classes hold the caches: `SurveyBase(doc, shown, runId, raw, geometry, north, magnetic, legs, totals,
northWarning)` with `isFor(doc, shown, runId, raw)` (doc and shown compared with `===`), and
`ReadoutCache(base, selection, value: SurveyReadout?)`. Helpers:

- `publishSurvey()`: sets `survey = surveySnapshot()`, then calls `updateBounds()` only when
  `sceneResult !== boundsSource` (and stores it), because the bounds copy every point and the scrubber
  publishes on every drag frame.
- `surveySnapshot()`: null outside Survey mode or without a state, shown result or run. Reads
  `shownResult`, `selectedRunId`, `raw = showRaw && rawResult != null`. Reuses `surveyBase` when
  `isFor(state.doc, shown, runId, raw)`, else builds it: reuses `surveyGeometry` when its
  `shown === shownResult` **and** its `runId` and `raw` equal the shown run's (a StateFlow keeps the old
  instance when a new run's result is equal), else `SurveyGeometry.of(shown, runId, raw)`; solves
  `NorthSolver.solve(doc, geo.plain, runId)`; `geo = geo.rotated(north.rotationDeg)` (stored back in
  `surveyGeometry`); `Traverse.legs`, `Traverse.totals`, `magnetic`, and
  `northWarning = if (!magnetic && doc.references.isEmpty()) CalibrationMath.northProblem(diagnostics) else null`.
  Reuses `readoutCache` when its base is this base and its selection equals `state.selection`, else
  `SurveyController.readout(state, geo)`. Always builds
  `SurveyLayer.build(geo.timeline, stations, SurveyController.layerSelection(state, geo), state.cursorNs)`
  and `askManualRotation = NorthSolver.manualNeedsConfirmation(doc, runId) && manualPromptDismissedRunId != runId`.
  So a cursor move rebuilds only the layer, and a selection change only the readout and the layer.
- `applySurvey(next: SurveyState, message: SurveyMessage? = null)`: stores `next`; when
  `next.doc !== old.doc` and not read-only, `persist(next.doc)`; publishes; sets the message.
- `persist(doc)`: latest-wins save: `docToSave = doc; viewModelScope.launch { saveLock.withLock { val d =
  docToSave ?: return@withLock; docToSave = null; runCatching { withContext(ioDispatcher) {
  surveys.save(tripId, d) } }.onFailure { surveyMessage "Could not save the survey: ..." } } }`.
- `updateBounds()` uses `sceneResult` instead of `shownResult`.
- `loadRun` and `openPhoto` use `ioDispatcher` instead of `Dispatchers.IO`.
- `applyResult` and `toggleRaw` call `publishSurvey()` in place of `updateBounds()` (outside Survey mode it
  sets `survey = null` and updates the bounds).

`toggleSurvey()`: leaving sets `surveyMode = false, surveyMessage = null` and calls `publishSurvey()`
(`survey` becomes null and the bounds follow `sceneResult`); the message goes because `surveyUndo()` does
nothing outside Survey mode, so the snackbar's Undo must not stay up. The camera is left where it is and
`surveyState` is kept in memory (undo survives a re-entry). Entering needs a shown
result with at least two points (otherwise `surveyMessage = SurveyMessage("Nothing to measure yet", false)`);
it sets `surveyMode = true, selectedMarker = null`; when `surveyState` is null it loads with
`withContext(ioDispatcher) { surveys.load(tripId) }`, opens with `SurveyController.open`, saves a seeded
doc through `persist`, keeps `surveyError`; then `publishSurvey()` and `applyPreset(CameraPreset.TOP)`.
A load that finishes after the user left again is dropped.

#### Panel, sheets and dialogs [Tasks 26, 27, 28], Compose

```kotlin
// SurveyPanel.kt [Task 26]
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
)

// SurveySheets.kt [Task 27]
@OptIn(ExperimentalMaterial3Api::class) @Composable
fun LegsSheet(legs: List<TraverseLeg>, totals: LegTotals, magnetic: Boolean, onSelect: (fromId: Int, toId: Int) -> Unit, onDismiss: () -> Unit)
@OptIn(ExperimentalMaterial3Api::class) @Composable
fun StationSheet(station: Station, readOnly: Boolean, onRename: (String) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit)
@Composable
fun DetailDialog(current: Detail, counts: Map<Detail, Int>, onSelect: (Detail) -> Unit, onDismiss: () -> Unit)

// NorthDialogs.kt [Task 28]
@Composable
fun SetAzimuthDialog(
    fromName: String,
    toName: String,
    magnetic: Boolean,
    preview: (bearingDeg: Double, backBearing: Boolean, line: ReferenceLine) -> AzimuthPreview?,
    onConfirm: (bearingDeg: Double, backBearing: Boolean, line: ReferenceLine) -> Unit,
    onDismiss: () -> Unit,
)
@OptIn(ExperimentalMaterial3Api::class) @Composable
fun NorthSheet(survey: SurveyUi, onNudge: (Double) -> Unit, onSet: (Double) -> Unit, onDeleteReference: (Int) -> Unit, onReset: () -> Unit, onDismiss: () -> Unit)
@Composable
fun ManualRotationDialog(rotationDeg: Double, fromRunId: Int?, toRunId: Int, onAnswer: (apply: Boolean) -> Unit)
```

---

## 3. Key decisions writers must follow

1. **Tap precedence.** In Survey mode (`survey != null` in `ViewerCanvas`) a tap is resolved only by
   `ProjectedSurvey.hitTest`: a station within 24 dp (nearest on screen) wins, then the path within 32 dp
   (nearest screen segment), else `SurveyHit.Miss`. The scene's markers are not hit-tested (the scene is
   built with `showMarkers = false` in Survey mode, so stations replace them). `Miss` does nothing: the
   VM ignores it, and clearing is only the Clear button. Double-tap still fits (the first tap of a double
   tap is delivered, as today).
2. **Long press inside the existing gesture loop.** No second `pointerInput`. In `awaitEachGesture`,
   while Survey mode is on, one pointer is down, nothing has long-pressed yet and the finger has travelled
   less than `touchSlop`, the next event is awaited with
   `withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis - (lastChange.uptimeMillis - first.uptimeMillis)) { awaitPointerEvent() }`
   (a member of `AwaitPointerEventScope`; a non-positive remainder counts as elapsed). A null result ends
   the timer for this gesture (`longPressTimed = true`). When the hit at `lastChange.position` is a station
   or the path, it is the long press: call `onSurveyLongPress(hit)`, set `longPressed = true`, and from
   then on consume events until every pointer is up, with no orbit, pan, zoom, tap or double tap. On
   `SurveyHit.Miss` nothing happens and the gesture goes on, so "touch, pause, then drag" on empty map
   still pans (the design defines long presses only on the path and on a station).
3. **Orbit lock.** `orbitLocked` (read through `rememberUpdatedState`) turns the one-finger branch into
   `onPan(dx, dy)`; two-finger pan and pinch are unchanged. `ViewerScreen` passes `ui.surveyMode`.
   Entering applies `CameraPreset.TOP` to the corrected path's bounds; leaving keeps the camera.
4. **Seeding happens once per trip.** Only `SurveyLoad.Missing` seeds (on the shown path when Survey
   mode first opens, with rotation 0 because a new doc has no correction) and the VM saves at once. A
   loaded file is never re-seeded. A malformed file is seeded in memory only, read-only, never saved.
5. **Corners regenerate only on a Detail change**, on the currently shown (framed) path, keeping every
   non-CORNER station (START, END, MARK, USER and moved or renamed corners) as spacing anchors; the new corners are
   named C1.. in time order, skipping names already in use (a moved corner keeps its "C<n>" name, and legs
   and CSV rows name stations only by name); the selection is cleared; one undo entry. Deleting a corner
   leaves no tombstone. The Detail dialog shows `cornerCounts` for all three levels.
6. **M or R.** `NorthSolver.isMagnetic`: M when `northReference` equals `OrientationEstimator.MAGNETIC`
   or the solve used a compass reference; R otherwise, including runs without the key and runs with only
   a manual rotation. The banner shows on an R run whose doc has no reference, with
   `CalibrationMath.northProblem(diagnostics)` as the reason (never a literal "magnetic").
7. **Time placement.** Per segment by the later point's source: PDR holds then moves over the last
   `min(gap, 1.5 x medianStepNs)`, other sources are linear. Stations, references and the cursor are
   stored as `tNs`; the scrubber and path hits work in distance and convert with `timeAtDistance`
   (earliest moment at that distance). Distances are unchanged by rotation, so the plain and framed
   timelines agree on them.
8. **Rotation is a Survey-mode view.** `NorthFrame.rotate` is applied to the shown result (after the raw
   view) only in Survey mode; normal mode still draws the run as processed. The framed result is cached
   through `SurveyGeometry`, keyed by the shown instance, run id, raw flag and angle (the instance alone is
   not enough: a StateFlow keeps the old instance when a new run's result is equal). The north solve always
   uses the unrotated `geo.plain`.
9. **Manual rotation tag.** The manual rotation applies when `manualRotationRunId` is null or the shown
   run. On another run `SurveyUi.askManualRotation` is true (unless dismissed for that run this session):
   Apply calls `confirmManualRotation` (an undoable, saved edit), Not now sets
   `manualPromptDismissedRunId`. Steppers are disabled while references exist.
10. **Persistence.** After every doc change the VM writes the whole doc through `SurveyStore.save`
    (atomic), serialized by a `Mutex` with latest-wins, on `ioDispatcher`. Nothing is written while
    read-only. Selection, cursor and undo are memory only.
11. **Undo.** Every doc edit (stations, references, manual rotation, confirm, reset, Detail) is one
    snapshot on `SurveyState.undo` (max 50), behind the top-bar Undo and the snackbar's Undo.
12. **CSV and share.** The VM builds `SurveyCsv.text(legs, geo.timeline.startNs, geo.framed.points.first().p,
    magnetic)`, writes it with `SurveyCsvFile.write(files.exportDir(), SurveyCsvFile.fileName(trip.name,
    tripId, runId, raw), text)` on `ioDispatcher`, and publishes `SurveyCsvShare(file, "IMU Mapper survey:
    <trip name>", SurveyFormat.shareText(...))`. `ViewerScreen` turns it into an Intent with
    `SurveyShare.intent` and `startActivity`, then `consumeCsvShare()`, like the trip list's ZIP export.
13. **Survey mode UI.** The stats panel is replaced by `SurveyPanel`, the overlay run and scene markers
    are hidden, `MarkerCard` is not shown. Top bar in Survey mode: title "<trip name>" with the subtitle
    "Survey (beta)" ("Survey (beta) · raw" on the raw path; the run is checked in the overflow), then
    actions north chip (`SurveyFormat.northChipShort`, opens `NorthSheet`),
    Undo (`Icons.AutoMirrored.Filled.Undo`), the ruler (`Icons.Filled.SquareFoot`, toggles Survey mode;
    also shown in normal mode between the view menu and Debug), and an overflow (`Icons.Filled.MoreVert`)
    with Export CSV, Detail, Raw path and the run list. Back leaves Survey mode (`BackHandler`).
14. **No Android in tested code.** `SurveyController`, `SurveyGeometry`, `SurveyFormat`, `SurveyLayer`,
    `ProjectedSurvey`, `SurveyStore`, `SurveyCsvFile`, `ExportNames`, `AtomicFiles` use no `android.*` or
    Compose types. `SurveyShare`, `SurveyRenderer` and the composables are checked by compiling.
15. **Numbers.** `Locale.US` for every number the app formats; degrees everywhere, no mils; magnetic only,
    no declination.

---

## 4. Formats

### 4.1 CSV (Task 11)

- `BOM + HEADER + EOL`, then per leg of `Traverse.legs` one row + `EOL` (the file ends with `EOL`).
- Cells, in header order: `field(from.name)`, `field(to.name)`, `fixed((from.tNs - startNs) / 1e9, 1)`,
  `fixed((to.tNs - startNs) / 1e9, 1)`, `fixed(lengthM, 2)`, `fixed(horizontalM, 2)`,
  `fixed(heightChangeM, 2)`, azimuth `fixed(azimuthDeg, 1)` or empty (a formatted `"360.0"` becomes
  `"0.0"`), `M` or `R`, `fixed(slopeDeg, 1)`, grade `fixed(gradePct, 1)` or empty, `fixed(pathM, 2)`,
  `yes` / `no`, then `fixed(b.x - origin.x, 2)`, `fixed(b.y - origin.y, 2)`, `fixed(b.z - origin.z, 2)`.
- Example row for 5 m due east, flat, 2.5 s to 5.0 s, from the origin 5 m north:
  `C1,End,2.5,5.0,5.00,5.00,0.00,90.0,M,0.0,0.0,5.00,no,5.00,5.00,0.00`.

### 4.2 Correction and share text (Tasks 11, 19)

- `correctionText`: REFERENCES `"north %+.1f° from %d compass reading%s"` (`s` unless 1), MANUAL
  `"north %+.1f° set by hand"`, NONE `"north as recorded"`, with the angle rounded to 0.1 and `+ 0.0`.
- `shareText`: `"$tripName · Run $runId${if (raw) " (raw path)" else ""}, ${correctionText}"`.

---

## 5. Test fixtures

- **Pipeline** `pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/SurveyPaths.kt` [Task 3]:

  ```kotlin
  object SurveyPaths {
      const val T0: Long = 1_000_000_000L
      const val STEP_NS: Long = 500_000_000L
      fun tNs(index: Int, stepNs: Long = STEP_NS): Long = T0 + index * stepNs
      /**
       * Like PdrSolver: the origin at T0 (stepIndex -1, heading of the first leg), then one PDR point per
       * step along legs of (steps, heading rad) with [strideM], one step every [stepNs]. [swayM] shifts
       * each plotted point sideways by swayM * sin(1.3 k) without accumulating; [climbPerStepM] adds height.
       */
      fun steps(vararg legs: Pair<Int, Double>, strideM: Double = 0.5, stepNs: Long = STEP_NS,
                swayM: Double = 0.0, climbPerStepM: Double = 0.0): List<PathPoint>
      /** Points at [positions] every [stepNs] from T0 with [source] (VIO by default), for linear placement. */
      fun linear(vararg positions: Vec3, stepNs: Long = STEP_NS, source: PositionSource = PositionSource.VIO): List<PathPoint>
      /** A PathResult around [points] with PathBuilder.stats and the given annotations and diagnostics. */
      fun result(points: List<PathPoint>, annotations: List<PathAnnotation> = emptyList(),
                 diagnostics: Map<String, String> = emptyMap()): PathResult
  }
  ```

- **App** `app/src/test/kotlin/com/stastyle/imumapper/SurveyFixtures.kt` (package
  `com.stastyle.imumapper`) [Task 15], an L-shaped PDR walk with exact binary coordinates:

  ```kotlin
  object SurveyFixtures {
      const val T0: Long = 1_000_000_000L
      const val STEP_NS: Long = 500_000_000L
      const val STRIDE_M: Double = 0.5
      fun tNs(index: Int): Long = T0 + index * STEP_NS
      /**
       * Origin (index 0, stepIndex -1), 20 steps north to (0, 10), 10 steps east to (5, 10), positions
       * computed as STRIDE_M * n (not accumulated); one JUNCTION annotation with an empty note at index 10
       * (0, 5). northReference is MAGNETIC, or OrientationEstimator.NORTH_OFF when [magnetic] is false.
       * Seeding gives Start id 1 (t0), Junction 1 id 2 (index 10), End id 3 (index 30), C1 id 4 (index 20);
       * distances along the path: 0, 5, 10, 15.
       */
      fun lWalk(magnetic: Boolean = true): PathResult
  }
  ```

---

## 6. Task list

Sections and output files: A `10-pipeline-survey.md`, B `20-app-data-export.md`,
C `30-app-render-gestures.md`, D `40-viewer-ui.md`. Tasks run in this order; B-D depend on A.

### A. Pipeline survey package (pipeline tests)

1. **Survey document model and JSON** - `SurveyDoc.kt`; `SurveyDocTest`: round trip equal, unknown keys
   ignored, `{}` decodes to defaults, an unknown enum value decodes to the default, `toJson` of a doc with
   a NaN bearing throws `SerializationException`, a normal doc's JSON has no "NaN".
2. **Degree helpers** - `SurveyAngles.kt`; `SurveyAnglesTest`: N/E/S/W give 0/90/180/270, `to360` and
   `wrapDeg` edges (360, -0.0, 180, -180, 540, tiny negatives).
3. **Time placement along a path** - `PathTimeline.kt` and fixture `SurveyPaths.kt`; `PathTimelineTest`:
   hold then move across a standing gap and across a pause-sized gap, VIO linear, median step, clamping,
   `distanceAt` / `timeAtDistance` inverse (earliest time for a standing point), `positionAtDistance`,
   `samplesBetween` in either order, previous / next point, one-point path.
4. **Chord measurements** - `Measure.leg`, `maxDeviationM`, `LegMeasure`; `MeasureTest`: azimuths N/E/S/W,
   vertical leg "no azimuth" and +/-90 slope, grade and slope of a ramp, path length on a dogleg,
   curved flag threshold (0.3 m floor and 2 % of length), straightness, slopeUncertain.
5. **Fitted azimuth and stretch** - `Measure.fittedAzimuthDeg`, `stretch`, `StretchMeasure`; `MeasureTest`
   additions: a swaying straight walk fits within 0.5 degrees while a chord between opposite sway extremes
   is off by more, orientation follows A to B, degenerate stretch gives null.
6. **North frame rotation** - `NorthFrame.kt`; `NorthFrameTest`: +90 takes north to east, +10 raises every
   leg azimuth by 10, headings / annotations / keyframes / pointCloud / rawPoints turn, stats and times
   unchanged, 0 returns the same instance, commutes with `LoopClosure.apply` and `Smoothing.movingAverage`.
7. **North solve** - `NorthSolver.kt`; `NorthSolverTest`: one reference matched exactly, two give
   h-weighted rotation and residuals and `disagree` past 3 degrees, back-bearing, FITTED line, a
   too-short reference is skipped (and, when it is the only one, leaves 0 with the manual rotation
   unused), manual rotation used only without references and only on its run,
   `manualNeedsConfirmation`, `isMagnetic` (magnetic key, relative key, missing key, references).
8. **Corner detection** - `CornerDetector.kt`; `CornerDetectorTest`: rectangle walk gives 3 corners near
   the true corners at every Detail, a shallow dogleg is missed by COARSE and found by NORMAL, a 10 degree
   bend is dropped by the turn filter, two corners 1 m apart keep the stronger / earlier one, a corner
   within 1.5 m of a kept station is dropped, vertices are existing point indices.
9. **Stations: seeding, names, corner regeneration** - `SurveyStations.kt`; `SurveyStationsTest`: seed
   ids, names and order (REORIENT and LOOP_CLOSED skipped, "Junction 2" numbering, notes used), corners
   seeded, `regenerateCorners` keeps USER stations and renames C1.., new corners skip a name a kept
   station holds, `nextUserName`, `nextId`, `ordered`.
10. **Traverse** - `Traverse.kt`; `TraverseTest`: legs in time order with station ids, totals, chain hops
    and straight line on an out-and-back, fewer than two stations.
11. **CSV text** - `SurveyCsv.kt`; `SurveyCsvTest`: exact text for a three-leg L traverse, quoting of a
    name with a comma and a quote, empty azimuth and grade cells for a zero-length leg, R column, no
    "-0.00", `correctionText` for all sources and singular / plural.

### B. App data and export

12. **Survey store** - `AtomicFiles.kt` (from `TripProcessor`), `TripFiles.surveyFile`, `SurveyStore.kt`,
    `AppContainer.surveyStore`; `SurveyStoreTest`: missing, round trip, malformed JSON and a directory in
    the file's place give `Malformed`, no `.tmp` left behind, save replaces an older file;
    `DefaultTripProcessorTest` still passes.
13. **ZIP export and import carry survey.json** - `TripArchive.kt`, `TripImporter.kt`;
    `TripArchiveTest`: entry order with a survey, the export without one unchanged;
    `TripImporterTest`: extraction returns `survey` with the same bytes, null when absent.
14. **CSV file and share intent** - `ExportNames.kt`, `SurveyCsvFile.kt`, `SurveyShare.kt`,
    `TripExporter.kt`; `SurveyCsvFileTest`: names (Hebrew letters kept, symbols replaced, raw suffix, empty
    name), the written file starts with bytes EF BB BF and decodes back to the text; `assembleDebug`.

### C. App render layer and canvas gestures

15. **Survey layer model** - `SurveyLayer.kt`, fixture `SurveyFixtures.kt`; `SurveyLayerTest`: station
    positions and colours by kind, chain order numbers and chords, stretch polyline and its chord, cursor,
    decimated hit-test path with distances, unknown chain ids skipped.
16. **Projection and hit tests** - `ProjectedSurvey.kt`; `ProjectedSurveyTest`: top view projects stations
    where `Projector` does, a tap on a station wins over the path under it, a tap near the path returns the
    distance within 0.1 m, a far tap misses, `update` caches.
17. **Drawing the survey layer** - `SurveyRenderer.kt`; `assembleDebug`.
18. **Canvas gestures** - `ViewerCanvas.kt` (`CanvasGestures` callbacks, `survey`, `orbitLocked`,
    long press, survey drawing); `assembleDebug`, all existing app tests pass.

### D. Viewer integration and UI

19. **Survey display strings** - `SurveyFormat.kt`; `SurveyFormatTest` covering every function.
20. **Survey controller: geometry, selection, cursor, readout** - `SurveyController.kt` (types and Task 20
    functions); `SurveyControllerTest`.
21. **Survey controller: station edits, detail, undo** - `SurveyController.kt`; `SurveyEditsTest`.
22. **Survey controller: north edits and the azimuth preview** - `SurveyController.kt`;
    `SurveyNorthEditsTest`.
23. **ViewModel: Survey mode, loading, seeding, geometry** - `ViewerViewModel.kt`; `ViewerSurveyTest`
    (including a cursor move that reuses geometry, north, legs, totals and readout).
24. **ViewModel: station edits, detail, undo, messages** - `ViewerViewModel.kt`; `ViewerSurveyTest`
    (including leaving Survey mode dropping the undo message).
25. **ViewModel: north edits, manual-rotation prompt, CSV export** - `ViewerViewModel.kt`;
    `ViewerSurveyTest`.
26. **Survey panel** - `SurveyPanel.kt`; `assembleDebug`.
27. **Legs table, station sheet, Detail dialog** - `SurveySheets.kt`; `assembleDebug`.
28. **Set azimuth dialog, North sheet, manual-rotation prompt** - `NorthDialogs.kt`; `assembleDebug`.
29. **Viewer screen wiring** - `ViewerScreen.kt` (after checking it still equals commit `6023cc0`, since
    the task replaces the whole file); `assembleDebug`, full app and pipeline test runs.
30. **Document the survey package** - `CLAUDE.md` layout; final `assembleDebug :app:testDebugUnitTest`
    and pipeline `test`.

## 7. Known follow-ups (not blocking phase 1)

- With a stretch selected, `SurveyLayer.build` still runs on every scrubber move and rebuilds the stretch's
  path samples. Stretches run between neighbouring stations and are usually short, so this is cheap, but a
  long walk with no corners can stutter while scrubbing. The fix is to keep the cursor out of the layer and
  draw it separately; that changes Section C's API, so it waits for a real stutter on a phone.
- `SurveyController.setCursor` returns a new state even when the time is unchanged, so an unchanged cursor
  republishes. Harmless; skip the publish when the time is equal if it ever shows up in a profile.
