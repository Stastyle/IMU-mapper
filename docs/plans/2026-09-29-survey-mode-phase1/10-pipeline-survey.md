## Section A: Pipeline survey package (pure math and model, pipeline tests)

Tasks 1 to 11 build `com.stastyle.imumapper.pipeline.survey`, a pure package like `tuning/`: no Android, no
clock, no randomness, no I/O. The processors never call it, so there is no `PIPELINE_VERSION` bump and no
`tools/replay.py` port. Sections B to D depend on every name here; the signatures are the ones in section 2.1
of the architecture, unchanged.

Before Task 1, read the plan header ("Before you start"): every Gradle command below needs the `JAVA_HOME`
prefix, and runs from the worktree root `/x/IMU-mapper-survey`.

How the tests in this section work:

- **Framework:** `kotlin.test` on JUnit 5 (`pipeline/build.gradle.kts` has `useJUnitPlatform()`), like
  `pipeline/src/test/.../post/LoopClosureTest.kt`. Doubles that go through `sin`, `cos` or `atan2` are
  compared with `kotlin.test.assertEquals(expected, actual, absoluteTolerance)`; values that are exact in
  binary (0.5 m strides, 0.5 s steps) are compared exactly. Positions use the `assertNear(Vec3, Vec3)`
  helper that Task 3 adds to the fixture file.
- **Fixture:** `SurveyPaths` (Task 3) builds PDR walks shaped like `PdrSolver` output: an origin point at
  `T0` with `stepIndex = -1`, then one PDR point per step. Point index `i` is at time `tNs(i)` =
  `T0 + i * 0.5 s`, and on a walk due north it is at `y = 0.5 * i` exactly.
- **Red, then green:** a new class makes the test fail to compile (`Unresolved reference`); that is the
  expected failure. Only failing tests print. `BUILD SUCCESSFUL` means every test of the class passed; the
  count is in `pipeline/build/reports/tests/test/index.html`.
- **Copy code exactly.** Every test and implementation below was run in this order on a copy of the
  worktree and passed. The CSV code in Task 11 contains the Kotlin escape `\uFEFF` (backslash, `u`, `FEFF`):
  type those six characters, never an invisible byte-order mark.
- **Commits:** stage the listed paths only, never `git add -A`. Do not push.

---

### Task 1: Survey document model and JSON

**Files:**
- Create: `pipeline/src/main/kotlin/com/stastyle/imumapper/pipeline/survey/SurveyDoc.kt`
- Test: `pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/SurveyDocTest.kt`

The per-trip `survey.json` holds facts only. `coerceInputValues` makes an enum value written by a newer
build decode as the field's default; `allowSpecialFloatingPointValues` stays off so a NaN can never be
written (`toJson()` throws a `SerializationException`, which `JsonEncodingException` extends).

**Step 1: Write the failing test**

Create `pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/SurveyDocTest.kt`:

```kotlin
package com.stastyle.imumapper.pipeline.survey

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SurveyDocTest {

    private fun sample() = SurveyDoc(
        stations = listOf(
            Station(1, StationKind.START, "Start", 1_000_000_000L),
            Station(2, StationKind.MARK, "מערה, north", 6_000_000_000L),
            Station(3, StationKind.CORNER, "C1", 11_000_000_000L),
            Station(4, StationKind.USER, "S1", 13_500_000_000L),
            Station(5, StationKind.END, "End", 16_000_000_000L),
        ),
        references = listOf(
            CompassReference(1, 1_000_000_000L, 11_000_000_000L, 44.5, backBearing = false),
            CompassReference(2, 11_000_000_000L, 16_000_000_000L, 272.0, true, ReferenceLine.FITTED),
        ),
        manualRotationDeg = -3.5,
        manualRotationRunId = 7,
        detail = Detail.FINE,
    )

    @Test
    fun roundTripKeepsEveryField() {
        val doc = sample()
        assertEquals(doc, SurveyDoc.fromJson(doc.toJson()))
    }

    @Test
    fun defaultsAreWrittenOut() {
        val json = SurveyDoc().toJson()
        assertTrue("\"formatVersion\": 1" in json, json)
        assertTrue("\"detail\": \"NORMAL\"" in json, json)
        assertEquals(1, SurveyDoc.FORMAT_VERSION)
    }

    @Test
    fun unknownKeysAreIgnored() {
        val text = """
            {"formatVersion": 2, "planImage": {"scale": 100},
             "stations": [{"id": 3, "kind": "MARK", "name": "Fork", "tNs": 5, "colour": "red"}]}
        """.trimIndent()
        val doc = SurveyDoc.fromJson(text)
        assertEquals(2, doc.formatVersion)
        assertEquals(listOf(Station(3, StationKind.MARK, "Fork", 5L)), doc.stations)
    }

    @Test
    fun emptyObjectDecodesToDefaults() {
        val doc = SurveyDoc.fromJson("{}")
        assertEquals(SurveyDoc(), doc)
        assertEquals(SurveyDoc.FORMAT_VERSION, doc.formatVersion)
        assertEquals(Detail.NORMAL, doc.detail)
        assertEquals(null, doc.manualRotationRunId)
    }

    @Test
    fun unknownEnumValueFallsBackToTheFieldDefault() {
        // A file written by a newer build with a Detail, kind or line this build does not know.
        val text = """
            {"detail": "ULTRA",
             "stations": [{"id": 1, "kind": "WALL", "name": "w", "tNs": 7}],
             "references": [{"id": 1, "fromNs": 1, "toNs": 2, "bearingDeg": 10.0, "line": "CURVE"}]}
        """.trimIndent()
        val doc = SurveyDoc.fromJson(text)
        assertEquals(Detail.NORMAL, doc.detail)
        assertEquals(StationKind.USER, doc.stations.single().kind)
        assertEquals(ReferenceLine.CHORD, doc.references.single().line)
        assertEquals(10.0, doc.references.single().bearingDeg)
    }

    @Test
    fun aNanBearingCannotBeWritten() {
        val doc = SurveyDoc(references = listOf(CompassReference(id = 1, bearingDeg = Double.NaN)))
        assertFailsWith<SerializationException> { doc.toJson() }
        val infinite = SurveyDoc(manualRotationDeg = Double.POSITIVE_INFINITY)
        assertFailsWith<SerializationException> { infinite.toJson() }
    }

    @Test
    fun aNormalDocHoldsNoNan() {
        val json = sample().toJson()
        assertFalse("NaN" in json, json)
        assertFalse("Infinity" in json, json)
    }
}
```

**Step 2: Run it and see it fail**

```bash
(cd pipeline && ../gradlew test --tests '*SurveyDocTest')
```

Expected: compilation fails, for example

```
e: file:///X:/IMU-mapper-survey/pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/SurveyDocTest.kt:12:28 Unresolved reference 'SurveyDoc'.
e: ...SurveyDocTest.kt:14:13 Unresolved reference 'Station'.
> Task :compileTestKotlin FAILED
BUILD FAILED
```

**Step 3: Write the implementation**

Create `pipeline/src/main/kotlin/com/stastyle/imumapper/pipeline/survey/SurveyDoc.kt`:

```kotlin
package com.stastyle.imumapper.pipeline.survey

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
    /** Throws a SerializationException on a NaN or infinite value, so a bad number never reaches the file. */
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

**Step 4: Run it and see it pass**

```bash
(cd pipeline && ../gradlew test --tests '*SurveyDocTest')
```

Expected: `BUILD SUCCESSFUL` (7 tests).

**Step 5: Commit**

```bash
git add pipeline/src/main/kotlin/com/stastyle/imumapper/pipeline/survey/SurveyDoc.kt pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/SurveyDocTest.kt
git commit -F - <<'EOF'
Keep a trip's survey stations, compass bearings and north settings as a small JSON document

Survey mode stores facts only (station times and names, compass bearings, a manual rotation and the run
it was set on, the corner detail), so every number can be recomputed on whichever run is shown. Every
field has a default and unknown keys or enum values fall back to it, so files from older and newer
builds keep loading, and a NaN cannot be written.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

---

### Task 2: Degree helpers for azimuths

**Files:**
- Create: `pipeline/src/main/kotlin/com/stastyle/imumapper/pipeline/survey/SurveyAngles.kt`
- Test: `pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/SurveyAnglesTest.kt`

The pipeline's `pdr/Angles.kt` works in radians. Survey numbers are degrees, and two float traps matter
for labels and the CSV: `atan2(-0.0, -1.0)` is -180 degrees (south must read 180), and `-1e-15 + 360`
rounds to `360.0` (which must read 0). Adding `0.0` turns `-0.0` into `0.0`.

**Step 1: Write the failing test**

Create `pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/SurveyAnglesTest.kt`:

```kotlin
package com.stastyle.imumapper.pipeline.survey

import kotlin.test.Test
import kotlin.test.assertEquals

class SurveyAnglesTest {

    /** assertEquals on boxed doubles tells 0.0 from -0.0, but say it outright. */
    private fun assertPositiveZero(actual: Double, what: String) =
        assertEquals(0L, actual.toRawBits(), "$what should be +0.0, got $actual")

    @Test
    fun cardinalDirections() {
        assertEquals(0.0, SurveyAngles.azimuthDeg(0.0, 1.0), 1e-9)
        assertEquals(90.0, SurveyAngles.azimuthDeg(1.0, 0.0), 1e-9)
        assertEquals(180.0, SurveyAngles.azimuthDeg(0.0, -1.0), 1e-9)
        // atan2(-0.0, -1) is -180 degrees: south must still read 180, not -180.
        assertEquals(180.0, SurveyAngles.azimuthDeg(-0.0, -1.0), 1e-9)
        assertEquals(270.0, SurveyAngles.azimuthDeg(-1.0, 0.0), 1e-9)
        assertEquals(45.0, SurveyAngles.azimuthDeg(2.0, 2.0), 1e-9)
        assertEquals(315.0, SurveyAngles.azimuthDeg(-2.0, 2.0), 1e-9)
        assertPositiveZero(SurveyAngles.azimuthDeg(0.0, 1.0), "north")
    }

    @Test
    fun to360Edges() {
        assertPositiveZero(SurveyAngles.to360(360.0), "360")
        assertPositiveZero(SurveyAngles.to360(-0.0), "-0.0")
        // -1e-15 + 360 rounds to 360.0 itself: it must come back as north.
        assertPositiveZero(SurveyAngles.to360(-1e-15), "tiny negative")
        assertEquals(180.0, SurveyAngles.to360(540.0))
        assertEquals(270.0, SurveyAngles.to360(-90.0))
        assertEquals(359.5, SurveyAngles.to360(-0.5))
        assertEquals(12.25, SurveyAngles.to360(12.25))
    }

    @Test
    fun wrapDegEdges() {
        assertEquals(180.0, SurveyAngles.wrapDeg(180.0))
        assertEquals(180.0, SurveyAngles.wrapDeg(-180.0))
        assertEquals(-170.0, SurveyAngles.wrapDeg(190.0))
        assertEquals(170.0, SurveyAngles.wrapDeg(-190.0))
        assertEquals(180.0, SurveyAngles.wrapDeg(540.0))
        assertEquals(-90.0, SurveyAngles.wrapDeg(270.0))
        assertEquals(4.5, SurveyAngles.wrapDeg(364.5))
        assertPositiveZero(SurveyAngles.wrapDeg(-0.0), "-0.0")
        assertPositiveZero(SurveyAngles.wrapDeg(-360.0), "-360")
    }
}
```

**Step 2: Run it and see it fail**

```bash
(cd pipeline && ../gradlew test --tests '*SurveyAnglesTest')
```

Expected: `e: ...SurveyAnglesTest.kt:14:27 Unresolved reference 'SurveyAngles'.` and `BUILD FAILED`.

**Step 3: Write the implementation**

Create `pipeline/src/main/kotlin/com/stastyle/imumapper/pipeline/survey/SurveyAngles.kt`:

```kotlin
package com.stastyle.imumapper.pipeline.survey

import kotlin.math.atan2

/** Degree helpers for survey numbers (the pipeline's Angles works in radians). */
object SurveyAngles {

    /** Plan direction (dE, dN) as degrees clockwise from north in [0, 360): N 0, E 90, S 180, W 270. */
    fun azimuthDeg(dE: Double, dN: Double): Double = to360(Math.toDegrees(atan2(dE, dN)))

    /** Wraps into (-180, 180]; never returns -0.0. */
    fun wrapDeg(deg: Double): Double {
        var r = deg % 360.0
        if (r > 180.0) r -= 360.0 else if (r <= -180.0) r += 360.0
        // Adding 0.0 turns -0.0 into 0.0, so no "-0" reaches a label or the CSV.
        return r + 0.0
    }

    /** Wraps into [0, 360); never returns 360.0 or -0.0. */
    fun to360(deg: Double): Double {
        var r = deg % 360.0
        if (r < 0.0) r += 360.0
        // A tiny negative plus 360 rounds to 360.0 itself, which is north again.
        if (r >= 360.0) r = 0.0
        return r + 0.0
    }
}
```

**Step 4: Run it and see it pass**

```bash
(cd pipeline && ../gradlew test --tests '*SurveyAnglesTest')
```

Expected: `BUILD SUCCESSFUL` (3 tests).

**Step 5: Commit**

```bash
git add pipeline/src/main/kotlin/com/stastyle/imumapper/pipeline/survey/SurveyAngles.kt pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/SurveyAnglesTest.kt
git commit -F - <<'EOF'
Add degree helpers so survey azimuths never read 360 or minus zero

Azimuths are shown and exported in degrees from 0 to 360. Rounding and atan2's signed zero can produce
360.0, -180 or -0.0 for north or south; the helpers fold those onto 0 and 180.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

---

### Task 3: Time placement along a path

**Files:**
- Create: `pipeline/src/main/kotlin/com/stastyle/imumapper/pipeline/survey/PathTimeline.kt`
- Create (test fixture): `pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/SurveyPaths.kt`
- Test: `pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/PathTimelineTest.kt`

Stations, references and the cursor are stored by time. A PDR path has one point per step, so a moment
between two steps separated by a long gap is placed where the walker stood (the earlier step) until the
last `min(gap, 1.5 x median step)` of the gap. Worked numbers for the standing test: 10 strides north,
20 s extra after point 5. Point 5 is at (0, 2.5) and 3.5 s; point 6 at (0, 3.0) and 24.0 s. The median
step is 0.5 s (eight 0.5 s gaps and one 20.5 s gap between step points; the origin's gap is not a step
gap), so the move window is 0.75 s: the walker stands at (0, 2.5) until 23.25 s and is halfway, (0, 2.75),
at 23.625 s. `timeAtDistance(2.5)` is 3.5 s (the earliest moment at that distance) and
`timeAtDistance(2.75)` is 23.625 s.

**Step 1: Write the fixture and the failing test**

Create `pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/SurveyPaths.kt` (shared by the
rest of this section):

```kotlin
package com.stastyle.imumapper.pipeline.survey

import com.stastyle.imumapper.pipeline.core.PIPELINE_VERSION
import com.stastyle.imumapper.pipeline.core.PathAnnotation
import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.PathResult
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.PositionSource
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.post.PathBuilder
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertTrue

/**
 * Synthetic paths for the survey tests. Stride 0.5 m and step 0.5 s are exact in binary, so a walk due
 * north (and east, whose cos(pi / 2) residue rounds away) has coordinates that can be written as literals.
 */
object SurveyPaths {
    const val T0: Long = 1_000_000_000L
    const val STEP_NS: Long = 500_000_000L

    fun tNs(index: Int, stepNs: Long = STEP_NS): Long = T0 + index * stepNs

    /**
     * Like PdrSolver: the origin at T0 (stepIndex -1, heading of the first leg), then one PDR point per
     * step along legs of (steps, heading rad) with [strideM], one step every [stepNs]. [swayM] shifts
     * each plotted point sideways by swayM * sin(1.3 k) without accumulating; [climbPerStepM] adds height.
     * Point index i is step i - 1, at tNs(i, stepNs).
     */
    fun steps(
        vararg legs: Pair<Int, Double>,
        strideM: Double = 0.5,
        stepNs: Long = STEP_NS,
        swayM: Double = 0.0,
        climbPerStepM: Double = 0.0,
    ): List<PathPoint> {
        val out = ArrayList<PathPoint>()
        out.add(PathPoint(T0, Vec3.ZERO, PositionSource.PDR, legs[0].second, -1))
        var legStartX = 0.0
        var legStartY = 0.0
        var k = 0
        for ((count, heading) in legs) {
            val dirE = sin(heading)
            val dirN = cos(heading)
            for (j in 1..count) {
                // Positions are the leg start plus j strides, not a running sum, so they stay exact.
                val x = legStartX + j * strideM * dirE
                val y = legStartY + j * strideM * dirN
                val side = swayM * sin(1.3 * k)
                // Sideways is to the walker's right: (cos h, -sin h).
                val p = Vec3(x + side * dirN, y - side * dirE, (k + 1) * climbPerStepM)
                out.add(PathPoint(tNs(k + 1, stepNs), p, PositionSource.PDR, heading, k))
                k++
            }
            legStartX += count * strideM * dirE
            legStartY += count * strideM * dirN
        }
        return out
    }

    /** Points at [positions] every [stepNs] from T0 with [source] (VIO by default), for linear placement. */
    fun linear(
        vararg positions: Vec3,
        stepNs: Long = STEP_NS,
        source: PositionSource = PositionSource.VIO,
    ): List<PathPoint> = positions.mapIndexed { i, p -> PathPoint(tNs(i, stepNs), p, source, 0.0, -1) }

    /** A PathResult around [points] with PathBuilder.stats and the given annotations and diagnostics. */
    fun result(
        points: List<PathPoint>,
        annotations: List<PathAnnotation> = emptyList(),
        diagnostics: Map<String, String> = emptyMap(),
    ): PathResult {
        val durationS = if (points.isEmpty()) 0.0 else (points.last().tNs - points.first().tNs) / 1e9
        return PathResult(
            pipelineVersion = PIPELINE_VERSION,
            config = PipelineConfig(),
            points = points,
            annotations = annotations,
            stats = PathBuilder.stats(points, durationS, points.count { it.stepIndex >= 0 }, null),
            diagnostics = diagnostics,
        )
    }
}

/** Component-wise closeness of two positions, with both printed on failure. */
fun assertNear(expected: Vec3, actual: Vec3, tolerance: Double = 1e-9, message: String = "") {
    assertTrue(
        (expected - actual).length <= tolerance,
        "$message expected $expected, got $actual".trim(),
    )
}
```

Create `pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/PathTimelineTest.kt`:

```kotlin
package com.stastyle.imumapper.pipeline.survey

import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.PositionSource
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.survey.SurveyPaths.T0
import com.stastyle.imumapper.pipeline.survey.SurveyPaths.tNs
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PathTimelineTest {

    private val second = 1_000_000_000L

    /** A north walk (point i at y = 0.5 i) whose points after [afterIndex] come [gapNs] later. */
    private fun northWithGap(steps: Int, afterIndex: Int, gapNs: Long): List<PathPoint> =
        SurveyPaths.steps(steps to 0.0).mapIndexed { i, p -> if (i > afterIndex) p.copy(tNs = p.tNs + gapNs) else p }

    /**
     * Across the gap after point [i] the walker stands at p(i), then covers the last stride in the
     * last 1.5 x 0.5 s = 0.75 s before point i + 1: halfway 0.375 s before it.
     */
    private fun assertHoldsThenMoves(points: List<PathPoint>, i: Int) {
        val tl = PathTimeline(points)
        assertEquals(SurveyPaths.STEP_NS, tl.medianStepNs)
        val before = points[i].tNs
        val after = points[i + 1].tNs
        val here = Vec3(0.0, 0.5 * i, 0.0)
        assertEquals(here, tl.positionAt(before + second))
        assertEquals(here, tl.positionAt((before + after) / 2))
        assertEquals(here, tl.positionAt(after - 750_000_000L), "the move starts 0.75 s before the next step")
        assertEquals(Vec3(0.0, 0.5 * i + 0.25, 0.0), tl.positionAt(after - 375_000_000L))
        assertEquals(Vec3(0.0, 0.5 * (i + 1), 0.0), tl.positionAt(after))
        assertEquals(0.5 * i, tl.distanceAt(before + 10 * second))
        assertEquals(0.5 * i + 0.25, tl.distanceAt(after - 375_000_000L))
    }

    @Test
    fun standingGapHoldsAtTheEarlierStep() {
        // Standing 20 s after the fifth stride.
        assertHoldsThenMoves(northWithGap(10, afterIndex = 5, gapNs = 20 * second), 5)
    }

    @Test
    fun pauseGapHoldsTheSameWay() {
        // A 60 s pause: the recorder dropped no point, the gap is only longer.
        assertHoldsThenMoves(northWithGap(8, afterIndex = 3, gapNs = 60 * second), 3)
    }

    @Test
    fun normalStepsAreLinear() {
        val tl = PathTimeline(SurveyPaths.steps(10 to 0.0))
        // Gap 0.5 s is shorter than the 0.75 s move window, so the whole gap is the move.
        assertEquals(Vec3(0.0, 1.25, 0.0), tl.positionAt(tNs(2) + 250_000_000L))
        assertEquals(1.1, tl.distanceAt(tNs(2) + 100_000_000L), 1e-12)
        assertEquals(5.0, tl.lengthM)
        assertEquals(T0, tl.startNs)
        assertEquals(tNs(10), tl.endNs)
        assertEquals(2.5, tl.distanceOfPoint(5))
    }

    @Test
    fun vioSegmentsAreLinearOverAnyGap() {
        val tenSeconds = 10 * second
        val tl = PathTimeline(
            SurveyPaths.linear(Vec3(0.0, 0.0, 0.0), Vec3(0.0, 4.0, 0.0), Vec3(4.0, 4.0, 0.0), stepNs = tenSeconds),
        )
        assertEquals(PathTimeline.DEFAULT_STEP_NS, tl.medianStepNs, "a VIO path has no steps")
        assertEquals(Vec3(0.0, 1.0, 0.0), tl.positionAt(T0 + 2_500_000_000L))
        assertEquals(Vec3(2.0, 4.0, 0.0), tl.positionAt(T0 + 15 * second))
        assertEquals(6.0, tl.distanceAt(T0 + 15 * second))
        assertEquals(T0 + 15 * second, tl.timeAtDistance(6.0))
        val gapFill = SurveyPaths.linear(
            Vec3.ZERO, Vec3(0.0, 4.0, 0.0), stepNs = tenSeconds, source = PositionSource.INTERPOLATED,
        )
        val interpolated = PathTimeline(gapFill)
        assertEquals(Vec3(0.0, 1.0, 0.0), interpolated.positionAt(T0 + 2_500_000_000L))
    }

    @Test
    fun medianStepIgnoresTheOriginAndLongGaps() {
        assertEquals(600_000_000L, PathTimeline(SurveyPaths.steps(9 to 0.0, stepNs = 600_000_000L)).medianStepNs)
        // One step: no gap between two step points, so the default cadence sets the move window.
        val oneStep = listOf(
            PathPoint(T0, Vec3.ZERO, PositionSource.PDR, 0.0, -1),
            PathPoint(T0 + 10 * second, Vec3(0.0, 0.5, 0.0), PositionSource.PDR, 0.0, 0),
        )
        val tl = PathTimeline(oneStep)
        assertEquals(PathTimeline.DEFAULT_STEP_NS, tl.medianStepNs)
        // Window 1.5 x 0.55 s = 0.825 s; halfway is 0.4125 s before the step.
        assertEquals(Vec3.ZERO, tl.positionAt(T0 + 10 * second - 825_000_000L))
        assertEquals(Vec3(0.0, 0.25, 0.0), tl.positionAt(T0 + 10 * second - 412_500_000L))
    }

    @Test
    fun timesOutsideThePathAreClamped() {
        val tl = PathTimeline(SurveyPaths.steps(10 to 0.0))
        assertEquals(Vec3.ZERO, tl.positionAt(T0 - second))
        assertEquals(Vec3(0.0, 5.0, 0.0), tl.positionAt(tNs(10) + 5 * second))
        assertEquals(0.0, tl.distanceAt(T0 - second))
        assertEquals(5.0, tl.distanceAt(tNs(10) + 5 * second))
        assertEquals(Vec3.ZERO, tl.positionAtDistance(-1.0))
        assertEquals(Vec3(0.0, 5.0, 0.0), tl.positionAtDistance(8.0))
        assertEquals(T0, tl.timeAtDistance(-1.0))
        assertEquals(tNs(10), tl.timeAtDistance(8.0))
    }

    @Test
    fun timeAtDistanceInvertsDistanceAt() {
        val points = northWithGap(10, afterIndex = 5, gapNs = 20 * second)
        val tl = PathTimeline(points)
        for (t in listOf(tNs(1) + 123_456_789L, tNs(3) + 100_000_000L, points[6].tNs - 375_000_000L, points[9].tNs)) {
            val back = tl.timeAtDistance(tl.distanceAt(t))
            assertTrue(abs(back - t) <= 1, "time $t came back as $back")
        }
        // Standing still takes no distance: a moment during the stand maps to its first moment.
        assertEquals(points[5].tNs, tl.timeAtDistance(tl.distanceAt(points[5].tNs + 10 * second)))
        assertEquals(points[6].tNs - 375_000_000L, tl.timeAtDistance(2.75))
        assertEquals(T0, tl.timeAtDistance(0.0))
    }

    @Test
    fun positionAtDistanceIsLinearByDistance() {
        val tl = PathTimeline(SurveyPaths.linear(Vec3.ZERO, Vec3(0.0, 4.0, 0.0), Vec3(4.0, 4.0, 2.0)))
        assertEquals(Vec3(0.0, 1.0, 0.0), tl.positionAtDistance(1.0))
        assertEquals(Vec3(0.0, 4.0, 0.0), tl.positionAtDistance(4.0))
        // The second segment is sqrt(20) long; halfway along it is (2, 4, 1).
        assertNear(Vec3(2.0, 4.0, 1.0), tl.positionAtDistance(4.0 + (tl.lengthM - 4.0) / 2), 1e-12)
    }

    @Test
    fun samplesBetweenInEitherOrder() {
        val tl = PathTimeline(SurveyPaths.steps(10 to 0.0))
        val a = tNs(2) + 250_000_000L
        val b = tNs(5)
        val expected = listOf(Vec3(0.0, 1.25, 0.0), Vec3(0.0, 1.5, 0.0), Vec3(0.0, 2.0, 0.0), Vec3(0.0, 2.5, 0.0))
        assertEquals(expected, tl.samplesBetween(a, b))
        assertEquals(expected, tl.samplesBetween(b, a))
        assertEquals(listOf(Vec3(0.0, 1.5, 0.0), Vec3(0.0, 1.5, 0.0)), tl.samplesBetween(tNs(3), tNs(3)))
    }

    @Test
    fun previousAndNextPoint() {
        val tl = PathTimeline(SurveyPaths.steps(10 to 0.0))
        assertEquals(tNs(2), tl.previousPointNs(tNs(3)))
        assertEquals(tNs(3), tl.previousPointNs(tNs(3) + 1))
        assertEquals(T0, tl.previousPointNs(T0))
        assertEquals(T0, tl.previousPointNs(T0 - second))
        assertEquals(tNs(4), tl.nextPointNs(tNs(3)))
        assertEquals(tNs(3), tl.nextPointNs(tNs(3) - 1))
        assertEquals(tNs(10), tl.nextPointNs(tNs(10)))
        assertEquals(T0, tl.nextPointNs(T0 - second))
    }

    @Test
    fun onePointPath() {
        val p = Vec3(1.0, 2.0, 3.0)
        val tl = PathTimeline(listOf(PathPoint(T0, p, PositionSource.PDR, 0.0, -1)))
        assertEquals(0.0, tl.lengthM)
        assertEquals(T0, tl.startNs)
        assertEquals(T0, tl.endNs)
        assertEquals(p, tl.positionAt(T0 + second))
        assertEquals(p, tl.positionAt(T0 - second))
        assertEquals(0.0, tl.distanceAt(T0 + second))
        assertEquals(p, tl.positionAtDistance(3.0))
        assertEquals(T0, tl.timeAtDistance(3.0))
        assertEquals(listOf(p, p), tl.samplesBetween(T0, T0 + second))
        // The point itself lies strictly between an earlier and a later moment.
        assertEquals(listOf(p, p, p), tl.samplesBetween(T0 - second, T0 + second))
        assertEquals(T0, tl.previousPointNs(T0 + second))
        assertEquals(T0, tl.nextPointNs(T0 - second))
        assertEquals(PathTimeline.DEFAULT_STEP_NS, tl.medianStepNs)
    }

    @Test
    fun emptyPathIsRefused() {
        assertFailsWith<IllegalArgumentException> { PathTimeline(emptyList()) }
    }
}
```

**Step 2: Run it and see it fail**

```bash
(cd pipeline && ../gradlew test --tests '*PathTimelineTest')
```

Expected: `e: ...PathTimelineTest.kt:27:18 Unresolved reference 'PathTimeline'.` (plus follow-on type
errors) and `BUILD FAILED`.

**Step 3: Write the implementation**

Create `pipeline/src/main/kotlin/com/stastyle/imumapper/pipeline/survey/PathTimeline.kt`:

```kotlin
package com.stastyle.imumapper.pipeline.survey

import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.PositionSource
import com.stastyle.imumapper.pipeline.core.Vec3

/**
 * Places moments of the walk on a path and measures distance along it. A PDR path has one point per
 * step, and between two steps separated by a long gap (standing, or a pause) the walker stayed at the
 * earlier step: in the segment from point i to i+1, when point i+1 is a PDR point, the position holds at
 * p(i) and moves to p(i+1) over the last min(gap, HOLD_STEP_FACTOR x medianStepNs) of the gap. Other
 * segments (VIO, INTERPOLATED) are linear in time. Distances are 3D and cumulative from the first point.
 */
class PathTimeline(val points: List<PathPoint>) {
    init {
        require(points.isNotEmpty()) { "a timeline needs at least one point" }
    }

    private val n = points.size
    private val t = LongArray(n) { points[it].tNs }
    private val cum = DoubleArray(n).also { c ->
        for (i in 1 until n) c[i] = c[i - 1] + points[i].p.distanceTo(points[i - 1].p)
    }

    val startNs: Long = t[0]
    val endNs: Long = t[n - 1]

    /** 3D length of the whole path, metres. */
    val lengthM: Double = cum[n - 1]

    /**
     * Median positive gap between consecutive step points (both stepIndex >= 0, later one PDR);
     * DEFAULT_STEP_NS when there is none.
     */
    val medianStepNs: Long = medianStep()

    /** When the walker leaves p(i) in segment i; t[i] for a linear segment. */
    private val moveStart = LongArray(n - 1).also { m ->
        val window = Math.round(HOLD_STEP_FACTOR * medianStepNs)
        for (i in 0 until n - 1) {
            m[i] = if (points[i + 1].source == PositionSource.PDR) t[i + 1] - minOf(t[i + 1] - t[i], window) else t[i]
        }
    }

    /** Distance along the path at point [index]. */
    fun distanceOfPoint(index: Int): Double = cum[index]

    /** Where the walker was at [tNs]; clamped to the path's time range. */
    fun positionAt(tNs: Long): Vec3 {
        if (tNs <= startNs) return points[0].p
        if (tNs >= endNs) return points[n - 1].p
        val i = firstAfter(tNs) - 1
        return points[i].p.lerp(points[i + 1].p, fraction(i, tNs))
    }

    /** Metres along the path at [tNs]; clamped to [0, lengthM]. */
    fun distanceAt(tNs: Long): Double {
        if (tNs <= startNs) return 0.0
        if (tNs >= endNs) return lengthM
        val i = firstAfter(tNs) - 1
        return cum[i] + (cum[i + 1] - cum[i]) * fraction(i, tNs)
    }

    /** The point [distanceM] along the path (linear by distance inside a segment); clamped. */
    fun positionAtDistance(distanceM: Double): Vec3 {
        val s = distanceM.coerceIn(0.0, lengthM)
        val j = firstReaching(s)
        if (j == 0 || cum[j] == s) return points[j].p
        val f = (s - cum[j - 1]) / (cum[j] - cum[j - 1])
        return points[j - 1].p.lerp(points[j].p, f)
    }

    /** The earliest moment the walker reached [distanceM]; clamped. Standing still takes no distance. */
    fun timeAtDistance(distanceM: Double): Long {
        val s = distanceM.coerceIn(0.0, lengthM)
        val j = firstReaching(s)
        if (j == 0) return startNs
        if (cum[j] == s) return t[j]
        // Inside segment j - 1 the distance only grows while the walker moves, from moveStart to t[j].
        val from = moveStart[j - 1]
        val f = (s - cum[j - 1]) / (cum[j] - cum[j - 1])
        return from + Math.round(f * (t[j] - from))
    }

    /**
     * positionAt(min), every point strictly between the two times, positionAt(max), in time order; the
     * ends may be given in either order.
     */
    fun samplesBetween(fromNs: Long, toNs: Long): List<Vec3> {
        val lo = minOf(fromNs, toNs)
        val hi = maxOf(fromNs, toNs)
        val out = ArrayList<Vec3>()
        out.add(positionAt(lo))
        var k = firstAfter(lo)
        while (k < n && t[k] < hi) out.add(points[k++].p)
        out.add(positionAt(hi))
        return out
    }

    /** Time of the last point strictly before [tNs], or startNs. Used by the scrubber's back button. */
    fun previousPointNs(tNs: Long): Long {
        val k = firstNotBefore(tNs) - 1
        return if (k < 0) startNs else t[k]
    }

    /** Time of the first point strictly after [tNs], or endNs. */
    fun nextPointNs(tNs: Long): Long {
        val k = firstAfter(tNs)
        return if (k >= n) endNs else t[k]
    }

    /** Share of segment [i] covered at [tNs]: 0 while the walker still stands at p(i). */
    private fun fraction(i: Int, tNs: Long): Double {
        val end = t[i + 1]
        val from = moveStart[i]
        return when {
            tNs >= end -> 1.0
            tNs <= from -> 0.0
            else -> (tNs - from).toDouble() / (end - from)
        }
    }

    /** First index whose time is after [tNs], or n. */
    private fun firstAfter(tNs: Long): Int {
        var lo = 0
        var hi = n
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (t[mid] <= tNs) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** First index whose time is at or after [tNs], or n. */
    private fun firstNotBefore(tNs: Long): Int {
        var lo = 0
        var hi = n
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (t[mid] < tNs) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** First index whose cumulative distance is at least [s] (s within [0, lengthM]). */
    private fun firstReaching(s: Double): Int {
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cum[mid] < s) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun medianStep(): Long {
        val gaps = ArrayList<Long>()
        for (i in 0 until n - 1) {
            val a = points[i]
            val b = points[i + 1]
            val gap = b.tNs - a.tNs
            if (a.stepIndex >= 0 && b.stepIndex >= 0 && b.source == PositionSource.PDR && gap > 0) gaps.add(gap)
        }
        if (gaps.isEmpty()) return DEFAULT_STEP_NS
        gaps.sort()
        // Standing gaps are few and long, so the median is the walking cadence.
        return (gaps[(gaps.size - 1) / 2] + gaps[gaps.size / 2]) / 2
    }

    companion object {
        const val HOLD_STEP_FACTOR: Double = 1.5

        /** A typical walking step period, used when a path has too few steps to measure one. */
        const val DEFAULT_STEP_NS: Long = 550_000_000L
    }
}
```

**Step 4: Run it and see it pass**

```bash
(cd pipeline && ../gradlew test --tests '*PathTimelineTest')
```

Expected: `BUILD SUCCESSFUL` (12 tests).

**Step 5: Commit**

```bash
git add pipeline/src/main/kotlin/com/stastyle/imumapper/pipeline/survey/PathTimeline.kt pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/SurveyPaths.kt pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/PathTimelineTest.kt
git commit -F - <<'EOF'
Place any moment of a recorded walk on its path, standing still through long gaps

A PDR path has one point per step, so a moment between two steps with a long gap is placed where the
walker stood until the last stride of the gap; VIO paths are interpolated linearly. Distance along the
path maps back to the earliest moment that reached it, so standing takes no room on the scrubber.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

---

### Task 4: Chord measurements between two moments

**Files:**
- Create: `pipeline/src/main/kotlin/com/stastyle/imumapper/pipeline/survey/Measure.kt`
- Test: `pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/MeasureTest.kt`

A leg is the straight line (chord) from A to B on the shown path. Hand-derived numbers used below: the
ramp (6, 8, -2) has H = 10, so grade = 100 x -2 / 10 = -20 %, slope = -atan(0.2) = -11.31 degrees and
azimuth = atan(6 / 8) = 36.87 degrees. The dogleg 4 m north then 3 m east has chord 5, path 7, and its
corner (0, 4) lies 2.4 m from the chord (the foot of the perpendicular is 0.64 of the way, (1.92, 2.56),
and |(-1.92, 1.44)| = 2.4). `curved` needs more than max(0.3, 0.02 x length): 0.3 m on a 10 m leg,
0.8 m on a 40 m leg.

**Step 1: Write the failing test**

Create `pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/MeasureTest.kt`:

```kotlin
package com.stastyle.imumapper.pipeline.survey

import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.survey.SurveyPaths.T0
import com.stastyle.imumapper.pipeline.survey.SurveyPaths.tNs
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeasureTest {

    /** A 10 m square walked N, E, S, W with exact corners, one corner every 0.5 s. */
    private val square = PathTimeline(
        SurveyPaths.linear(
            Vec3(0.0, 0.0, 0.0), Vec3(0.0, 10.0, 0.0), Vec3(10.0, 10.0, 0.0), Vec3(10.0, 0.0, 0.0), Vec3(0.0, 0.0, 0.0),
        ),
    )

    /** One leg 6 m east, 8 m north, 2 m down: horizontal 10 m. */
    private val ramp = PathTimeline(SurveyPaths.linear(Vec3.ZERO, Vec3(6.0, 8.0, -2.0)))

    @Test
    fun cardinalLegsReadZeroNinetyOneEightyTwoSeventy() {
        val expected = listOf(0.0, 90.0, 180.0, 270.0)
        for (i in 0 until 4) {
            val leg = Measure.leg(square, tNs(i), tNs(i + 1))
            assertEquals(expected[i], assertNotNull(leg.azimuthDeg), 1e-9, "leg $i")
            assertEquals(10.0, leg.lengthM)
            assertEquals(10.0, leg.horizontalM)
            assertEquals(0.0, leg.heightChangeM)
            assertEquals(0.0, leg.slopeDeg)
            assertEquals(0.0, leg.gradePct)
            assertEquals(10.0, leg.pathM)
            assertFalse(leg.curved)
            assertEquals(1.0, leg.straightness)
            assertEquals(tNs(i), leg.fromNs)
            assertEquals(tNs(i + 1), leg.toNs)
        }
    }

    @Test
    fun verticalLegHasNoAzimuthOrGrade() {
        val shaft = PathTimeline(SurveyPaths.linear(Vec3.ZERO, Vec3(0.0, 0.0, 3.0), Vec3.ZERO))
        val up = Measure.leg(shaft, T0, tNs(1))
        assertNull(up.azimuthDeg)
        assertNull(up.gradePct)
        assertEquals(90.0, up.slopeDeg, 1e-9)
        assertEquals(3.0, up.lengthM)
        assertEquals(0.0, up.horizontalM)
        assertEquals(3.0, up.heightChangeM)
        assertTrue(up.slopeUncertain)
        val down = Measure.leg(shaft, tNs(1), tNs(2))
        assertEquals(-90.0, down.slopeDeg, 1e-9)
        assertEquals(-3.0, down.heightChangeM)
    }

    @Test
    fun rampGivesSlopeAndGrade() {
        val leg = Measure.leg(ramp, T0, tNs(1))
        // tan(azimuth) = dE / dN = 6 / 8; tan(slope) = dz / H = -2 / 10.
        assertEquals(Math.toDegrees(atan(0.75)), assertNotNull(leg.azimuthDeg), 1e-9)
        assertEquals(-Math.toDegrees(atan(0.2)), leg.slopeDeg, 1e-9)
        assertEquals(-20.0, assertNotNull(leg.gradePct), 1e-9)
        assertEquals(10.0, leg.horizontalM, 1e-12)
        assertEquals(sqrt(104.0), leg.lengthM, 1e-12)
        assertEquals(-2.0, leg.heightChangeM)
        assertFalse(leg.slopeUncertain)
    }

    @Test
    fun laterFirstMomentGivesTheReverseLeg() {
        val leg = Measure.leg(ramp, tNs(1), T0)
        assertEquals(180.0 + Math.toDegrees(atan(0.75)), assertNotNull(leg.azimuthDeg), 1e-9)
        assertEquals(2.0, leg.heightChangeM)
        assertEquals(20.0, assertNotNull(leg.gradePct), 1e-9)
        assertEquals(sqrt(104.0), leg.pathM, 1e-12)
        assertEquals(Vec3(6.0, 8.0, -2.0), leg.a)
        assertEquals(Vec3.ZERO, leg.b)
    }

    @Test
    fun doglegPathIsLongerThanItsChord() {
        // 4 m north, then 3 m east: chord 5 m, path 7 m, corner 2.4 m off the chord.
        val tl = PathTimeline(SurveyPaths.steps(8 to 0.0, 6 to PI / 2))
        val leg = Measure.leg(tl, T0, tNs(14))
        assertEquals(5.0, leg.lengthM, 1e-9)
        assertEquals(7.0, leg.pathM, 1e-9)
        assertEquals(Math.toDegrees(atan(0.75)), assertNotNull(leg.azimuthDeg), 1e-9)
        assertTrue(leg.curved)
        assertEquals(5.0 / 7.0, assertNotNull(leg.straightness), 1e-9)
        assertEquals(2.4, Measure.maxDeviationM(tl.samplesBetween(T0, tNs(14)), leg.a, leg.b), 1e-9)
    }

    @Test
    fun curvedNeedsThirtyCentimetresOnAShortLeg() {
        // 10 m leg: the limit is max(0.3, 0.2) = 0.3 m.
        val within = PathTimeline(SurveyPaths.linear(Vec3.ZERO, Vec3(0.25, 5.0, 0.0), Vec3(0.0, 10.0, 0.0)))
        assertFalse(Measure.leg(within, T0, tNs(2)).curved)
        val past = PathTimeline(SurveyPaths.linear(Vec3.ZERO, Vec3(0.375, 5.0, 0.0), Vec3(0.0, 10.0, 0.0)))
        assertTrue(Measure.leg(past, T0, tNs(2)).curved)
    }

    @Test
    fun curvedNeedsTwoPercentOnALongLeg() {
        // 40 m leg: the limit is max(0.3, 0.8) = 0.8 m.
        val within = PathTimeline(SurveyPaths.linear(Vec3.ZERO, Vec3(0.75, 20.0, 0.0), Vec3(0.0, 40.0, 0.0)))
        assertFalse(Measure.leg(within, T0, tNs(2)).curved)
        val past = PathTimeline(SurveyPaths.linear(Vec3.ZERO, Vec3(0.875, 20.0, 0.0), Vec3(0.0, 40.0, 0.0)))
        assertTrue(Measure.leg(past, T0, tNs(2)).curved)
    }

    @Test
    fun sameMomentMeasuresNothing() {
        val leg = Measure.leg(square, tNs(1), tNs(1))
        assertEquals(0.0, leg.lengthM)
        assertEquals(0.0, leg.pathM)
        assertNull(leg.azimuthDeg)
        assertNull(leg.gradePct)
        assertNull(leg.straightness)
        assertEquals(0.0, leg.slopeDeg)
        assertFalse(leg.curved)
        assertTrue(leg.slopeUncertain)
    }

    @Test
    fun slopeIsUncertainUnderFiveMetresHorizontal() {
        val tl = PathTimeline(SurveyPaths.linear(Vec3.ZERO, Vec3(0.0, 4.5, 0.5), Vec3(0.0, 9.5, 1.0)))
        assertTrue(Measure.leg(tl, T0, tNs(1)).slopeUncertain)
        assertFalse(Measure.leg(tl, T0, tNs(2)).slopeUncertain)
    }

    @Test
    fun maxDeviationToCoincidentEndsIsTheDistanceToThem() {
        val a = Vec3(1.0, 1.0, 0.0)
        assertEquals(5.0, Measure.maxDeviationM(listOf(a, Vec3(4.0, 5.0, 0.0)), a, a))
        assertEquals(0.0, Measure.maxDeviationM(emptyList(), a, Vec3(2.0, 2.0, 0.0)))
    }
}
```

**Step 2: Run it and see it fail**

```bash
(cd pipeline && ../gradlew test --tests '*MeasureTest')
```

Expected: `e: ...MeasureTest.kt:32:23 Unresolved reference 'Measure'.` and `BUILD FAILED`.

**Step 3: Write the implementation**

Create `pipeline/src/main/kotlin/com/stastyle/imumapper/pipeline/survey/Measure.kt`:

```kotlin
package com.stastyle.imumapper.pipeline.survey

import com.stastyle.imumapper.pipeline.core.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

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

/**
 * Survey numbers between two moments. The headline is always the chord, never a fitted line:
 * dead-reckoning error accumulates along the walk instead of scattering about a line, and chords chain,
 * so the legs of a traverse add up to the net displacement.
 */
object Measure {
    const val MIN_HORIZONTAL_M: Double = 0.3
    const val MIN_SLOPE_HORIZONTAL_M: Double = 5.0
    const val CURVE_MIN_M: Double = 0.3
    const val CURVE_FRACTION: Double = 0.02

    /** The chord numbers for two moments of [timeline], in the order given (A may be later than B). */
    fun leg(timeline: PathTimeline, fromNs: Long, toNs: Long): LegMeasure {
        val a = timeline.positionAt(fromNs)
        val b = timeline.positionAt(toNs)
        val d = b - a
        val length = d.length
        val horizontal = hypot(d.x, d.y)
        val flat = horizontal < MIN_HORIZONTAL_M
        val deviation = maxDeviationM(timeline.samplesBetween(fromNs, toNs), a, b)
        return LegMeasure(
            fromNs = fromNs,
            toNs = toNs,
            a = a,
            b = b,
            lengthM = length,
            horizontalM = horizontal,
            heightChangeM = d.z,
            azimuthDeg = if (flat) null else SurveyAngles.azimuthDeg(d.x, d.y),
            // + 0.0 keeps a level leg from reading -0.
            slopeDeg = Math.toDegrees(atan2(d.z, horizontal)) + 0.0,
            gradePct = if (flat) null else 100.0 * d.z / horizontal,
            pathM = abs(timeline.distanceAt(toNs) - timeline.distanceAt(fromNs)),
            curved = deviation > max(CURVE_MIN_M, CURVE_FRACTION * length),
        )
    }

    /** Largest 3D distance from [samples] to the segment [a]-[b] (to [a] when they coincide). */
    fun maxDeviationM(samples: List<Vec3>, a: Vec3, b: Vec3): Double {
        val e = b - a
        val len2 = e.lengthSquared
        var worst = 0.0
        for (s in samples) {
            val r = s - a
            val f = if (len2 > 0.0) ((r dot e) / len2).coerceIn(0.0, 1.0) else 0.0
            worst = max(worst, (r - e * f).length)
        }
        return worst
    }
}
```

**Step 4: Run it and see it pass**

```bash
(cd pipeline && ../gradlew test --tests '*MeasureTest')
```

Expected: `BUILD SUCCESSFUL` (10 tests).

**Step 5: Commit**

```bash
git add pipeline/src/main/kotlin/com/stastyle/imumapper/pipeline/survey/Measure.kt pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/MeasureTest.kt
git commit -F - <<'EOF'
Measure length, azimuth and slope of the straight line between two moments of a walk

Every survey number is the chord between two moments, plus the walked length between them and a flag
when the path strays from the chord. Azimuth and grade are left out when the horizontal run is too short
to point anywhere.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

---

### Task 5: Fitted azimuth and stretch measure

**Files:**
- Modify: `pipeline/src/main/kotlin/com/stastyle/imumapper/pipeline/survey/Measure.kt` (imports lines 5-7,
  the constants after line 46, the end of the file from line 84)
- Modify: `pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/MeasureTest.kt` (imports line 6,
  the end of the file)

The fitted line is the total-least-squares direction of the stretch's plan points, resampled every
0.25 m of path: `phi = 0.5 * atan2(2 cEN, cEE - cNN)` is the major axis counter-clockwise from east, so
the unit vector is `(cos phi, sin phi)` in (E, N), flipped to point from A to B. The test walk is 60
strides at 30 degrees swaying 0.5 m by `sin(1.3 k)`: from the sway peak at point 7 to the trough at point
53 the chord reads 27.5 degrees (about 1 m across over 23 m along, atan(1 / 23) = 2.5 degrees off), while
the fit reads 29.9. Over the whole walk the fit reads 29.96.

**Step 1: Add the failing tests**

In `MeasureTest.kt`, replace

```kotlin
import kotlin.math.PI
import kotlin.math.atan
```

with

```kotlin
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
```

and replace the end of the file

```kotlin
        assertEquals(0.0, Measure.maxDeviationM(emptyList(), a, Vec3(2.0, 2.0, 0.0)))
    }
}
```

with

```kotlin
        assertEquals(0.0, Measure.maxDeviationM(emptyList(), a, Vec3(2.0, 2.0, 0.0)))
    }

    /**
     * 30 m at 30 degrees with 0.5 m of sway to either side. sin(1.3 k) peaks at step 6 (point 7, 0.9985) and
     * troughs at step 52 (point 53, -0.9984): about 1 m across over 23 m along, so that chord reads about 27.5.
     */
    private val swaying = PathTimeline(SurveyPaths.steps(60 to Math.toRadians(30.0), swayM = 0.5))

    @Test
    fun fitFollowsThePassageWhereTheChordFollowsTheSway() {
        val peakToTrough = Measure.stretch(swaying, tNs(7), tNs(53))
        val chord = assertNotNull(peakToTrough.leg.azimuthDeg)
        val fitted = assertNotNull(peakToTrough.fittedAzimuthDeg)
        assertTrue(abs(chord - 30.0) > 1.0, "chord $chord")
        assertTrue(abs(fitted - 30.0) < 0.5, "fitted $fitted")
        val whole = assertNotNull(Measure.fittedAzimuthDeg(swaying, T0, tNs(60)))
        assertTrue(abs(whole - 30.0) < 0.5, "whole walk fitted $whole")
    }

    @Test
    fun straightWalkFitsItsChord() {
        val tl = PathTimeline(SurveyPaths.steps(20 to Math.toRadians(30.0)))
        assertEquals(30.0, assertNotNull(Measure.fittedAzimuthDeg(tl, T0, tNs(20))), 1e-9)
        assertEquals(30.0, assertNotNull(Measure.leg(tl, T0, tNs(20)).azimuthDeg), 1e-9)
    }

    @Test
    fun fitIsOrientedFromAToB() {
        val forward = assertNotNull(Measure.fittedAzimuthDeg(swaying, tNs(7), tNs(53)))
        val backward = assertNotNull(Measure.fittedAzimuthDeg(swaying, tNs(53), tNs(7)))
        assertEquals(0.0, SurveyAngles.wrapDeg(backward - forward - 180.0), 1e-9)
        // A walk to the south-west fits to the south-west, not to its opposite.
        val southWest = PathTimeline(SurveyPaths.steps(20 to Math.toRadians(-135.0), swayM = 0.2))
        assertTrue(abs(assertNotNull(Measure.fittedAzimuthDeg(southWest, T0, tNs(20))) - 225.0) < 1.0)
    }

    @Test
    fun shortOrClosedStretchHasNoFit() {
        val outAndBack = PathTimeline(SurveyPaths.steps(10 to 0.0, 10 to PI))
        val closed = Measure.stretch(outAndBack, T0, tNs(20))
        assertNull(closed.leg.azimuthDeg)
        assertNull(closed.fittedAzimuthDeg)
        // Half a stride: 0.25 m of chord.
        assertNull(Measure.fittedAzimuthDeg(outAndBack, T0, T0 + 250_000_000L))
    }

    @Test
    fun stretchIsTheLegPlusTheFit() {
        val s = Measure.stretch(swaying, tNs(3), tNs(30))
        assertEquals(Measure.leg(swaying, tNs(3), tNs(30)), s.leg)
        assertEquals(Measure.fittedAzimuthDeg(swaying, tNs(3), tNs(30)), s.fittedAzimuthDeg)
    }
}
```

**Step 2: Run it and see it fail**

```bash
(cd pipeline && ../gradlew test --tests '*MeasureTest')
```

Expected: `e: ...MeasureTest.kt:154:36 Unresolved reference 'stretch'.` (and `fittedAzimuthDeg`) and
`BUILD FAILED`.

**Step 3: Write the implementation**

In `Measure.kt`, replace the imports

```kotlin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
```

with

```kotlin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
```

add the resampling step after `CURVE_FRACTION`, replacing

```kotlin
    const val CURVE_FRACTION: Double = 0.02
```

with

```kotlin
    const val CURVE_FRACTION: Double = 0.02
    const val FIT_SPACING_M: Double = 0.25
```

and replace the end of the file (the end of `maxDeviationM` and the object's closing brace)

```kotlin
        return worst
    }
}
```

with

```kotlin
        return worst
    }

    /**
     * Total-least-squares direction of the stretch's plan points, resampled every FIT_SPACING_M of path.
     * Resampling by distance keeps a pause or a slow stretch from weighing more; height is left out so
     * barometer noise cannot tilt the line. Oriented from A to B.
     */
    fun fittedAzimuthDeg(timeline: PathTimeline, fromNs: Long, toNs: Long): Double? {
        val a = timeline.positionAt(fromNs)
        val b = timeline.positionAt(toNs)
        val dE = b.x - a.x
        val dN = b.y - a.y
        if (hypot(dE, dN) < MIN_HORIZONTAL_M) return null
        val d1 = timeline.distanceAt(fromNs)
        val d2 = timeline.distanceAt(toNs)
        val lo = min(d1, d2)
        val hi = max(d1, d2)
        val samples = ArrayList<Vec3>()
        val count = floor((hi - lo) / FIT_SPACING_M).toInt()
        for (k in 0..count) {
            val s = lo + k * FIT_SPACING_M
            if (s < hi) samples.add(timeline.positionAtDistance(s))
        }
        samples.add(timeline.positionAtDistance(hi))
        val meanE = samples.sumOf { it.x } / samples.size
        val meanN = samples.sumOf { it.y } / samples.size
        var cEE = 0.0
        var cNN = 0.0
        var cEN = 0.0
        for (p in samples) {
            val e = p.x - meanE
            val n = p.y - meanN
            cEE += e * e
            cNN += n * n
            cEN += e * n
        }
        cEE /= samples.size
        cNN /= samples.size
        cEN /= samples.size
        if (cEE + cNN < 1e-12) return null
        // The major axis, counter-clockwise from east; the fit has no direction of its own, so take A to B's.
        val phi = 0.5 * atan2(2.0 * cEN, cEE - cNN)
        var uE = cos(phi)
        var uN = sin(phi)
        if (uE * dE + uN * dN < 0.0) {
            uE = -uE
            uN = -uN
        }
        return SurveyAngles.azimuthDeg(uE, uN)
    }

    /** leg() plus fittedAzimuthDeg(). */
    fun stretch(timeline: PathTimeline, fromNs: Long, toNs: Long): StretchMeasure =
        StretchMeasure(leg(timeline, fromNs, toNs), fittedAzimuthDeg(timeline, fromNs, toNs))
}

/** A stretch selection: the chord plus the direction of the passage fitted through it. */
data class StretchMeasure(
    val leg: LegMeasure,
    /** Degrees in [0, 360), oriented from A to B; null when the chord has no azimuth or the fit is degenerate. */
    val fittedAzimuthDeg: Double?,
)
```

The whole file is now:

```kotlin
package com.stastyle.imumapper.pipeline.survey

import com.stastyle.imumapper.pipeline.core.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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

/**
 * Survey numbers between two moments. The headline is always the chord, never a fitted line:
 * dead-reckoning error accumulates along the walk instead of scattering about a line, and chords chain,
 * so the legs of a traverse add up to the net displacement.
 */
object Measure {
    const val MIN_HORIZONTAL_M: Double = 0.3
    const val MIN_SLOPE_HORIZONTAL_M: Double = 5.0
    const val CURVE_MIN_M: Double = 0.3
    const val CURVE_FRACTION: Double = 0.02
    const val FIT_SPACING_M: Double = 0.25

    /** The chord numbers for two moments of [timeline], in the order given (A may be later than B). */
    fun leg(timeline: PathTimeline, fromNs: Long, toNs: Long): LegMeasure {
        val a = timeline.positionAt(fromNs)
        val b = timeline.positionAt(toNs)
        val d = b - a
        val length = d.length
        val horizontal = hypot(d.x, d.y)
        val flat = horizontal < MIN_HORIZONTAL_M
        val deviation = maxDeviationM(timeline.samplesBetween(fromNs, toNs), a, b)
        return LegMeasure(
            fromNs = fromNs,
            toNs = toNs,
            a = a,
            b = b,
            lengthM = length,
            horizontalM = horizontal,
            heightChangeM = d.z,
            azimuthDeg = if (flat) null else SurveyAngles.azimuthDeg(d.x, d.y),
            // + 0.0 keeps a level leg from reading -0.
            slopeDeg = Math.toDegrees(atan2(d.z, horizontal)) + 0.0,
            gradePct = if (flat) null else 100.0 * d.z / horizontal,
            pathM = abs(timeline.distanceAt(toNs) - timeline.distanceAt(fromNs)),
            curved = deviation > max(CURVE_MIN_M, CURVE_FRACTION * length),
        )
    }

    /** Largest 3D distance from [samples] to the segment [a]-[b] (to [a] when they coincide). */
    fun maxDeviationM(samples: List<Vec3>, a: Vec3, b: Vec3): Double {
        val e = b - a
        val len2 = e.lengthSquared
        var worst = 0.0
        for (s in samples) {
            val r = s - a
            val f = if (len2 > 0.0) ((r dot e) / len2).coerceIn(0.0, 1.0) else 0.0
            worst = max(worst, (r - e * f).length)
        }
        return worst
    }

    /**
     * Total-least-squares direction of the stretch's plan points, resampled every FIT_SPACING_M of path.
     * Resampling by distance keeps a pause or a slow stretch from weighing more; height is left out so
     * barometer noise cannot tilt the line. Oriented from A to B.
     */
    fun fittedAzimuthDeg(timeline: PathTimeline, fromNs: Long, toNs: Long): Double? {
        val a = timeline.positionAt(fromNs)
        val b = timeline.positionAt(toNs)
        val dE = b.x - a.x
        val dN = b.y - a.y
        if (hypot(dE, dN) < MIN_HORIZONTAL_M) return null
        val d1 = timeline.distanceAt(fromNs)
        val d2 = timeline.distanceAt(toNs)
        val lo = min(d1, d2)
        val hi = max(d1, d2)
        val samples = ArrayList<Vec3>()
        val count = floor((hi - lo) / FIT_SPACING_M).toInt()
        for (k in 0..count) {
            val s = lo + k * FIT_SPACING_M
            if (s < hi) samples.add(timeline.positionAtDistance(s))
        }
        samples.add(timeline.positionAtDistance(hi))
        val meanE = samples.sumOf { it.x } / samples.size
        val meanN = samples.sumOf { it.y } / samples.size
        var cEE = 0.0
        var cNN = 0.0
        var cEN = 0.0
        for (p in samples) {
            val e = p.x - meanE
            val n = p.y - meanN
            cEE += e * e
            cNN += n * n
            cEN += e * n
        }
        cEE /= samples.size
        cNN /= samples.size
        cEN /= samples.size
        if (cEE + cNN < 1e-12) return null
        // The major axis, counter-clockwise from east; the fit has no direction of its own, so take A to B's.
        val phi = 0.5 * atan2(2.0 * cEN, cEE - cNN)
        var uE = cos(phi)
        var uN = sin(phi)
        if (uE * dE + uN * dN < 0.0) {
            uE = -uE
            uN = -uN
        }
        return SurveyAngles.azimuthDeg(uE, uN)
    }

    /** leg() plus fittedAzimuthDeg(). */
    fun stretch(timeline: PathTimeline, fromNs: Long, toNs: Long): StretchMeasure =
        StretchMeasure(leg(timeline, fromNs, toNs), fittedAzimuthDeg(timeline, fromNs, toNs))
}

/** A stretch selection: the chord plus the direction of the passage fitted through it. */
data class StretchMeasure(
    val leg: LegMeasure,
    /** Degrees in [0, 360), oriented from A to B; null when the chord has no azimuth or the fit is degenerate. */
    val fittedAzimuthDeg: Double?,
)
```

**Step 4: Run it and see it pass**

```bash
(cd pipeline && ../gradlew test --tests '*MeasureTest')
```

Expected: `BUILD SUCCESSFUL` (15 tests).

**Step 5: Commit**

```bash
git add pipeline/src/main/kotlin/com/stastyle/imumapper/pipeline/survey/Measure.kt pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/MeasureTest.kt
git commit -F - <<'EOF'
Fit the direction of a passage through a stretch, so a compass bearing can ignore the walker's sway

A chord from one side of a swaying walk to the other can be off by several degrees; a line fitted
through the stretch, resampled by distance so standing still adds no weight, follows the passage. The
fit leaves height out so barometer noise cannot tilt it, and points from the first moment to the second.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

---

### Task 6: North frame rotation of a result

**Files:**
- Create: `pipeline/src/main/kotlin/com/stastyle/imumapper/pipeline/survey/NorthFrame.kt`
- Test: `pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/NorthFrameTest.kt`

Rotation about the first point O, clockwise on the map for a positive angle theta:
`x' = Ox + (x - Ox) cos + (y - Oy) sin`, `y' = Oy - (x - Ox) sin + (y - Oy) cos`, `z' = z`,
`heading' = Angles.wrap(heading + theta)`. Check with theta = 90: (0, 1) goes to (1, 0), so north reads
east and every azimuth grows by theta. The test's hand-worked cases about O = (1, 1) at +90: offset
(0, +1) goes to (+1, 0), offset (-1, 0) to (0, +1), offset (0, -1) to (-1, 0); heading 3.0 becomes
3.0 + pi/2 - 2 pi = 3.0 - 1.5 pi, and 0.75 pi becomes 1.25 pi, wrapped to -0.75 pi. Loop closure and
smoothing only add distance-weighted fractions of vectors and weighted averages, so a rotation about any
pivot commutes with both (`post/LoopClosure.kt`, `post/Smoothing.kt`).

**Step 1: Write the failing test**

Create `pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/NorthFrameTest.kt`:

```kotlin
package com.stastyle.imumapper.pipeline.survey

import com.stastyle.imumapper.pipeline.core.AnnotationKind
import com.stastyle.imumapper.pipeline.core.PathAnnotation
import com.stastyle.imumapper.pipeline.core.PathKeyframe
import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.PositionSource
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.post.LoopClosure
import com.stastyle.imumapper.pipeline.post.Smoothing
import com.stastyle.imumapper.pipeline.survey.SurveyPaths.T0
import com.stastyle.imumapper.pipeline.survey.SurveyPaths.tNs
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class NorthFrameTest {

    private fun pt(i: Int, x: Double, y: Double, z: Double = 0.0, heading: Double = 0.0) =
        PathPoint(tNs(i), Vec3(x, y, z), PositionSource.PDR, heading, i - 1)

    @Test
    fun ninetyDegreesTakesNorthToEast() {
        val points = listOf(pt(0, 0.0, 0.0), pt(1, 0.0, 1.0, 0.5))
        val turned = NorthFrame.rotatePoints(points, Vec3.ZERO, 90.0)
        assertNear(Vec3(1.0, 0.0, 0.5), turned[1].p, 1e-12)
        assertEquals(PI / 2, turned[1].headingRad, 1e-12)
        // About a pivot that is not the origin.
        val shifted = NorthFrame.rotatePoints(listOf(pt(0, 2.0, 3.0), pt(1, 2.0, 4.0)), Vec3(2.0, 3.0, 0.0), 90.0)
        assertNear(Vec3(3.0, 3.0, 0.0), shifted[1].p, 1e-12)
    }

    @Test
    fun rotateTurnsEveryPartOfTheResultAboutTheFirstPoint() {
        val points = listOf(pt(0, 1.0, 1.0), pt(1, 1.0, 2.0, 0.5, heading = 3.0))
        val result = SurveyPaths.result(
            points,
            annotations = listOf(PathAnnotation(tNs(1), AnnotationKind.JUNCTION, "fork", Vec3(1.0, 3.0, 1.0))),
        ).copy(
            rawPoints = listOf(pt(0, 1.0, 1.0), pt(1, 1.0, 2.5)),
            keyframes = listOf(PathKeyframe(tNs(1), "k.jpg", Vec3(0.0, 1.0, 0.0), 0.75 * PI)),
            pointCloud = listOf(Vec3(1.0, 0.0, 2.0)),
        )
        val r = NorthFrame.rotate(result, 90.0)
        // O = (1, 1): (0, +1) from O goes to (+1, 0), (-1, 0) to (0, +1), (0, -1) to (-1, 0).
        assertNear(Vec3(1.0, 1.0, 0.0), r.points[0].p, 1e-12)
        assertNear(Vec3(2.0, 1.0, 0.5), r.points[1].p, 1e-12)
        assertNear(Vec3(2.5, 1.0, 0.0), r.rawPoints[1].p, 1e-12)
        assertNear(Vec3(3.0, 1.0, 1.0), r.annotations[0].p, 1e-12)
        assertNear(Vec3(1.0, 2.0, 0.0), r.keyframes[0].p, 1e-12)
        assertNear(Vec3(0.0, 1.0, 2.0), r.pointCloud[0], 1e-12)
        // Headings wrap: 3.0 + pi/2 is past pi, and 0.75 pi + 0.5 pi is -0.75 pi.
        assertEquals(3.0 - 1.5 * PI, r.points[1].headingRad, 1e-12)
        assertEquals(-0.75 * PI, r.keyframes[0].headingRad, 1e-12)
        assertEquals(result.stats, r.stats)
        assertEquals(result.points.map { it.tNs }, r.points.map { it.tNs })
        assertEquals(result.points.map { it.source }, r.points.map { it.source })
        assertEquals(result.points.map { it.stepIndex }, r.points.map { it.stepIndex })
        assertEquals("fork", r.annotations[0].note)
        assertEquals("k.jpg", r.keyframes[0].fileName)
        assertEquals(result.diagnostics, r.diagnostics)
    }

    @Test
    fun zeroOrEmptyIsTheSameInstance() {
        val result = SurveyPaths.result(SurveyPaths.steps(4 to 0.0))
        assertSame(result, NorthFrame.rotate(result, 0.0))
        val empty = SurveyPaths.result(emptyList())
        assertSame(empty, NorthFrame.rotate(empty, 30.0))
        assertSame(result.points, NorthFrame.rotatePoints(result.points, Vec3.ZERO, 0.0))
    }

    @Test
    fun tenDegreesRaisesEveryLegAzimuthByTen() {
        // Legs to about 17, 115, 217 and 354 degrees: the last one wraps past north.
        val points = SurveyPaths.steps(8 to 0.3, 6 to 2.0, 10 to -2.5, 6 to -0.1, climbPerStepM = 0.05)
        val plain = PathTimeline(points)
        val turned = PathTimeline(NorthFrame.rotate(SurveyPaths.result(points), 10.0).points)
        val moments = listOf(T0, tNs(4), tNs(8), tNs(11), tNs(14), tNs(20), tNs(24), tNs(27), tNs(30))
        for ((from, to) in moments.zipWithNext()) {
            val before = Measure.leg(plain, from, to)
            val after = Measure.leg(turned, from, to)
            val b = assertNotNull(before.azimuthDeg)
            val a = assertNotNull(after.azimuthDeg)
            assertEquals(0.0, SurveyAngles.wrapDeg(a - b - 10.0), 1e-9, "leg $from-$to: $b became $a")
            assertEquals(before.lengthM, after.lengthM, 1e-9)
            assertEquals(before.horizontalM, after.horizontalM, 1e-9)
            assertEquals(before.heightChangeM, after.heightChangeM, 1e-12)
            assertEquals(before.slopeDeg, after.slopeDeg, 1e-9)
            assertEquals(before.pathM, after.pathM, 1e-9)
        }
    }

    /** A lap that misses its start by (0.6, -0.4), moved off the origin so the pivot matters. */
    private fun openLap(): List<PathPoint> =
        SurveyPaths.steps(10 to 0.2, 8 to 1.9, 12 to -2.8, 6 to -1.2, swayM = 0.2, climbPerStepM = 0.02)
            .map { it.copy(p = it.p + Vec3(3.0, -2.0, 1.0)) }

    private fun assertSamePoints(expected: List<PathPoint>, actual: List<PathPoint>) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertNear(expected[i].p, actual[i].p, 1e-9, "point $i")
            assertEquals(expected[i].headingRad, actual[i].headingRad, 1e-12)
            assertEquals(expected[i].tNs, actual[i].tNs)
        }
    }

    @Test
    fun rotationCommutesWithLoopClosure() {
        val points = openLap()
        val origin = points[0].p
        val closeAt = listOf(points.last().tNs)
        val closed = assertNotNull(LoopClosure.apply(points, closeAt)).points
        val closedThenTurned = NorthFrame.rotatePoints(closed, origin, 25.0)
        val turned = NorthFrame.rotatePoints(points, origin, 25.0)
        val turnedThenClosed = assertNotNull(LoopClosure.apply(turned, closeAt)).points
        assertSamePoints(closedThenTurned, turnedThenClosed)
    }

    @Test
    fun rotationCommutesWithSmoothing() {
        val points = openLap()
        val origin = points[0].p
        val smoothedThenTurned = NorthFrame.rotatePoints(Smoothing.movingAverage(points, 5), origin, -40.0)
        val turnedThenSmoothed = Smoothing.movingAverage(NorthFrame.rotatePoints(points, origin, -40.0), 5)
        assertSamePoints(smoothedThenTurned, turnedThenSmoothed)
    }
}
```

**Step 2: Run it and see it fail**

```bash
(cd pipeline && ../gradlew test --tests '*NorthFrameTest')
```

Expected: `e: ...NorthFrameTest.kt:27:22 Unresolved reference 'NorthFrame'.` and `BUILD FAILED`.

**Step 3: Write the implementation**

Create `pipeline/src/main/kotlin/com/stastyle/imumapper/pipeline/survey/NorthFrame.kt`:

```kotlin
package com.stastyle.imumapper.pipeline.survey

import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.PathResult
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.pdr.Angles
import kotlin.math.cos
import kotlin.math.sin

/**
 * Turns a result onto corrected north about its first point O, clockwise on the map for positive
 * angles so every azimuth grows by the angle: x' = Ox + (x-Ox)cos + (y-Oy)sin, y' = Oy - (x-Ox)sin +
 * (y-Oy)cos, z' = z, heading' = wrap(heading + angle). Rotation commutes with LoopClosure.apply and
 * Smoothing.movingAverage, so rotating the output equals rotating inside the pipeline.
 */
object NorthFrame {

    /**
     * Rotates points, rawPoints, annotations, keyframes (position and heading) and pointCloud; stats are
     * kept, since lengths and heights do not change. Same instance for 0 or an empty path.
     */
    fun rotate(result: PathResult, rotationDeg: Double): PathResult {
        if (rotationDeg == 0.0 || result.points.isEmpty()) return result
        val turn = Turn(result.points[0].p, rotationDeg)
        return result.copy(
            points = result.points.map(turn::point),
            rawPoints = result.rawPoints.map(turn::point),
            annotations = result.annotations.map { it.copy(p = turn.position(it.p)) },
            keyframes = result.keyframes.map { k ->
                k.copy(p = turn.position(k.p), headingRad = turn.heading(k.headingRad))
            },
            pointCloud = result.pointCloud.map(turn::position),
        )
    }

    /** The points turned about [origin]; tNs, source, stepIndex kept, headingRad turned. Same list for 0. */
    fun rotatePoints(points: List<PathPoint>, origin: Vec3, rotationDeg: Double): List<PathPoint> {
        if (rotationDeg == 0.0) return points
        return points.map(Turn(origin, rotationDeg)::point)
    }

    private class Turn(private val origin: Vec3, rotationDeg: Double) {
        private val theta = Math.toRadians(rotationDeg)
        private val c = cos(theta)
        private val s = sin(theta)

        fun position(p: Vec3): Vec3 {
            val dx = p.x - origin.x
            val dy = p.y - origin.y
            return Vec3(origin.x + dx * c + dy * s, origin.y - dx * s + dy * c, p.z)
        }

        fun heading(rad: Double): Double = Angles.wrap(rad + theta)

        fun point(p: PathPoint): PathPoint = p.copy(p = position(p.p), headingRad = heading(p.headingRad))
    }
}
```

**Step 4: Run it and see it pass**

```bash
(cd pipeline && ../gradlew test --tests '*NorthFrameTest')
```

Expected: `BUILD SUCCESSFUL` (6 tests).

**Step 5: Commit**

```bash
git add pipeline/src/main/kotlin/com/stastyle/imumapper/pipeline/survey/NorthFrame.kt pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/NorthFrameTest.kt
git commit -F - <<'EOF'
Turn a processed walk onto corrected north about its start without reprocessing it

A rotation about the first point turns every azimuth by the same angle and keeps lengths, heights and
statistics. It commutes with loop closure and smoothing, so rotating the stored result gives the same
path as rotating inside the pipeline, and no new run is needed.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

---

### Task 7: North solve from compass references and manual rotation

**Files:**
- Create: `pipeline/src/main/kotlin/com/stastyle/imumapper/pipeline/survey/NorthSolver.kt`
- Test: `pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/NorthSolverTest.kt`

With references k (compass bearing R, measured azimuth M on the unrotated path, horizontal length h):
`theta = atan2(sum h sin(R - M), sum h cos(R - M))`, `residual = wrapDeg(R - M - theta)`. On the test's
L walk the north leg is M = 0, h = 10 and the east leg M = 90, h = 5. With bearings 4 and 92 the
differences are 4 and 2, so theta = atan2(10 sin 4 + 5 sin 2, 10 cos 4 + 5 cos 2) = 3.33 degrees and the
residuals are +0.67 and -1.33; by construction `10 sin(r1) + 5 sin(r2) = 0`. With 4 and 100 the
differences are 4 and 10, theta is about 6.0 and the residuals about -2 and +4: past 3, so `disagree`.
`isMagnetic` reads the diagnostics through `OrientationEstimator.NORTH_REFERENCE` and
`OrientationEstimator.MAGNETIC` (constants, never literals: `.claude/rules/pipeline.md`).

**Step 1: Write the failing test**

Create `pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/NorthSolverTest.kt`:

```kotlin
package com.stastyle.imumapper.pipeline.survey

import com.stastyle.imumapper.pipeline.pdr.OrientationEstimator
import com.stastyle.imumapper.pipeline.survey.SurveyPaths.T0
import com.stastyle.imumapper.pipeline.survey.SurveyPaths.tNs
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NorthSolverTest {

    /** 10 m due north (points 0-20), then 5 m due east (points 20-30). */
    private val lPath = SurveyPaths.steps(20 to 0.0, 10 to PI / 2)
    private val plain = PathTimeline(lPath)

    private fun northLeg(bearing: Double, id: Int = 1, back: Boolean = false) =
        CompassReference(id, T0, tNs(20), bearing, backBearing = back)

    private fun eastLeg(bearing: Double, id: Int = 2) = CompassReference(id, tNs(20), tNs(30), bearing)

    private fun sinD(deg: Double) = sin(Math.toRadians(deg))
    private fun cosD(deg: Double) = cos(Math.toRadians(deg))

    @Test
    fun oneReferenceIsMatchedExactly() {
        val doc = SurveyDoc(references = listOf(northLeg(45.0)))
        val north = NorthSolver.solve(doc, plain, runId = 1)
        assertEquals(NorthSource.REFERENCES, north.source)
        assertEquals(45.0, north.rotationDeg, 1e-9)
        val fit = north.fits.single()
        assertEquals(1, fit.referenceId)
        assertEquals(0.0, assertNotNull(fit.measuredDeg), 1e-9)
        assertEquals(10.0, fit.horizontalM, 1e-9)
        assertEquals(0.0, assertNotNull(fit.residualDeg), 1e-9)
        assertEquals(1, north.usedCount)
        assertFalse(north.disagree)
        // Turned by the solution, the leg reads what the compass read.
        val turned = PathTimeline(NorthFrame.rotate(SurveyPaths.result(lPath), north.rotationDeg).points)
        assertEquals(45.0, assertNotNull(Measure.leg(turned, T0, tNs(20)).azimuthDeg), 1e-9)
    }

    @Test
    fun twoReferencesAreWeightedByHorizontalLength() {
        // The 10 m north leg reads 4 degrees, the 5 m east leg 92: differences 4 and 2.
        val doc = SurveyDoc(references = listOf(northLeg(4.0), eastLeg(92.0)))
        val north = NorthSolver.solve(doc, plain, runId = 1)
        val expected = Math.toDegrees(atan2(10 * sinD(4.0) + 5 * sinD(2.0), 10 * cosD(4.0) + 5 * cosD(2.0)))
        assertEquals(expected, north.rotationDeg, 1e-9)
        val r1 = assertNotNull(north.fits[0].residualDeg)
        val r2 = assertNotNull(north.fits[1].residualDeg)
        assertEquals(4.0 - expected, r1, 1e-9)
        assertEquals(2.0 - expected, r2, 1e-9)
        // The weighted residuals balance: that is what makes the rotation their best fit.
        assertEquals(0.0, 10 * sinD(r1) + 5 * sinD(r2), 1e-9)
        assertEquals(2, north.usedCount)
        assertFalse(north.disagree, "residuals ${r1} and ${r2} are within 3 degrees")
    }

    @Test
    fun referencesDisagreePastThreeDegrees() {
        // Differences 4 and 10: the rotation lands near 6, leaving about -2 and +4.
        val north = NorthSolver.solve(SurveyDoc(references = listOf(northLeg(4.0), eastLeg(100.0))), plain, 1)
        assertTrue(abs(assertNotNull(north.fits[1].residualDeg)) > NorthSolver.DISAGREE_DEG)
        assertTrue(north.disagree)
    }

    @Test
    fun backBearingAddsHalfATurn() {
        assertEquals(20.0, NorthSolver.forwardBearingDeg(CompassReference(bearingDeg = 200.0, backBearing = true)))
        assertEquals(170.0, NorthSolver.forwardBearingDeg(CompassReference(bearingDeg = 350.0, backBearing = true)))
        assertEquals(90.0, NorthSolver.forwardBearingDeg(CompassReference(bearingDeg = 90.0)))
        val north = NorthSolver.solve(SurveyDoc(references = listOf(northLeg(224.0, back = true))), plain, 1)
        assertEquals(44.0, north.rotationDeg, 1e-9)
        assertEquals(0.0, assertNotNull(north.fits.single().residualDeg), 1e-9)
    }

    @Test
    fun fittedReferenceUsesTheFittedLine() {
        // Sway peak to sway trough: the chord and the fitted line differ by more than 2 degrees.
        val swaying = PathTimeline(SurveyPaths.steps(60 to Math.toRadians(30.0), swayM = 0.5))
        val chordRef = CompassReference(1, tNs(7), tNs(53), 35.0, line = ReferenceLine.CHORD)
        val fittedRef = chordRef.copy(line = ReferenceLine.FITTED)
        val chord = assertNotNull(NorthSolver.measuredDeg(chordRef, swaying))
        val fitted = assertNotNull(NorthSolver.measuredDeg(fittedRef, swaying))
        assertEquals(Measure.leg(swaying, tNs(7), tNs(53)).azimuthDeg, chord)
        assertEquals(Measure.fittedAzimuthDeg(swaying, tNs(7), tNs(53)), fitted)
        assertTrue(abs(fitted - chord) > 2.0, "fitted $fitted, chord $chord")
        val north = NorthSolver.solve(SurveyDoc(references = listOf(fittedRef)), swaying, 1)
        assertEquals(35.0 - fitted, north.rotationDeg, 1e-9)
        assertEquals(fitted, north.fits.single().measuredDeg)
    }

    @Test
    fun referenceShorterThanThirtyCentimetresIsSkipped() {
        // Half a stride along the north leg: 0.25 m.
        val short = CompassReference(3, tNs(3), tNs(3) + 250_000_000L, 170.0)
        val alone = NorthSolver.solve(SurveyDoc(references = listOf(short)), plain, 1)
        assertEquals(NorthSource.NONE, alone.source)
        assertEquals(0.0, alone.rotationDeg)
        val fit = alone.fits.single()
        assertNull(fit.measuredDeg)
        assertNull(fit.residualDeg)
        assertEquals(0.25, fit.horizontalM, 1e-12)
        assertEquals(0, alone.usedCount)
        val mixed = NorthSolver.solve(SurveyDoc(references = listOf(short, northLeg(6.0))), plain, 1)
        assertEquals(6.0, mixed.rotationDeg, 1e-9)
        assertEquals(1, mixed.usedCount)
        assertNull(mixed.fits[0].residualDeg)
        // A reference exists, so the manual rotation stays unused even when none can be measured here:
        // the steppers are disabled while references exist, and a hidden angle must not steer the map.
        val shadowed = NorthSolver.solve(SurveyDoc(references = listOf(short), manualRotationDeg = 5.0), plain, 1)
        assertEquals(NorthSource.NONE, shadowed.source)
        assertEquals(0.0, shadowed.rotationDeg)
        assertEquals(1, shadowed.fits.size)
    }

    @Test
    fun manualRotationOnlyWithoutReferencesAndOnItsRun() {
        val manual = SurveyDoc(manualRotationDeg = -7.5, manualRotationRunId = 3)
        val own = NorthSolver.solve(manual, plain, runId = 3)
        assertEquals(NorthSource.MANUAL, own.source)
        assertEquals(-7.5, own.rotationDeg)
        assertTrue(own.fits.isEmpty())
        val other = NorthSolver.solve(manual, plain, runId = 4)
        assertEquals(NorthSource.NONE, other.source)
        assertEquals(0.0, other.rotationDeg)
        val untagged = NorthSolver.solve(manual.copy(manualRotationRunId = null), plain, runId = 4)
        assertEquals(NorthSource.MANUAL, untagged.source)
        val withReference = NorthSolver.solve(manual.copy(references = listOf(northLeg(2.0))), plain, runId = 3)
        assertEquals(NorthSource.REFERENCES, withReference.source)
        assertEquals(2.0, withReference.rotationDeg, 1e-9)
        assertEquals(NorthSource.NONE, NorthSolver.solve(SurveyDoc(), plain, 3).source)
        assertEquals(-170.0, NorthSolver.solve(SurveyDoc(manualRotationDeg = 190.0), plain, 3).rotationDeg)
    }

    @Test
    fun manualAppliesAndNeedsConfirmation() {
        val manual = SurveyDoc(manualRotationDeg = -7.5, manualRotationRunId = 3)
        assertTrue(NorthSolver.manualApplies(manual, 3))
        assertFalse(NorthSolver.manualApplies(manual, 4))
        assertTrue(NorthSolver.manualApplies(manual.copy(manualRotationRunId = null), 4))
        assertTrue(NorthSolver.manualNeedsConfirmation(manual, 4))
        assertFalse(NorthSolver.manualNeedsConfirmation(manual, 3))
        assertFalse(NorthSolver.manualNeedsConfirmation(manual.copy(references = listOf(northLeg(2.0))), 4))
        assertFalse(NorthSolver.manualNeedsConfirmation(manual.copy(manualRotationDeg = 0.0), 4))
        assertFalse(NorthSolver.manualNeedsConfirmation(manual.copy(manualRotationRunId = null), 4))
    }

    @Test
    fun magneticOnlyFromTheRunOrFromCompassReferences() {
        val none = NorthSolution(0.0, NorthSource.NONE, emptyList())
        val manual = NorthSolution(5.0, NorthSource.MANUAL, emptyList())
        val refs = NorthSolution(5.0, NorthSource.REFERENCES, listOf(ReferenceFit(1, 10.0, 12.0, 0.0)))
        val magnetic = mapOf(OrientationEstimator.NORTH_REFERENCE to OrientationEstimator.MAGNETIC)
        val relative = mapOf(OrientationEstimator.NORTH_REFERENCE to OrientationEstimator.NORTH_OFF)
        assertTrue(NorthSolver.isMagnetic(magnetic, none))
        val padded = mapOf(OrientationEstimator.NORTH_REFERENCE to " ${OrientationEstimator.MAGNETIC} ")
        assertTrue(NorthSolver.isMagnetic(padded, none))
        assertFalse(NorthSolver.isMagnetic(relative, none))
        assertFalse(NorthSolver.isMagnetic(relative, manual), "a hand rotation is not a compass")
        assertFalse(NorthSolver.isMagnetic(emptyMap(), none), "an unknown north is never magnetic")
        assertTrue(NorthSolver.isMagnetic(relative, refs))
        assertTrue(NorthSolver.isMagnetic(emptyMap(), refs))
    }
}
```

**Step 2: Run it and see it fail**

```bash
(cd pipeline && ../gradlew test --tests '*NorthSolverTest')
```

Expected: `e: ...NorthSolverTest.kt:35:21 Unresolved reference 'NorthSolver'.` and `BUILD FAILED`.

**Step 3: Write the implementation**

Create `pipeline/src/main/kotlin/com/stastyle/imumapper/pipeline/survey/NorthSolver.kt`:

```kotlin
package com.stastyle.imumapper.pipeline.survey

import com.stastyle.imumapper.pipeline.pdr.OrientationEstimator
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Where the rotation in use came from; only REFERENCES makes a relative-north run magnetic. */
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

/** The rotation for the shown run and how well each compass reference agrees with it. */
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
        get() = usedCount >= 2 && fits.any { fit ->
            val residual = fit.residualDeg
            residual != null && abs(residual) > NorthSolver.DISAGREE_DEG
        }
}

/** Solves the north rotation from the survey's facts for whichever run is shown. */
object NorthSolver {
    const val DISAGREE_DEG: Double = 3.0

    /**
     * [plain] is the shown path before any rotation. With references: rotation = atan2(sum h sin(R-M),
     * sum h cos(R-M)) over references with a measured azimuth (source REFERENCES); when none can be
     * measured on [plain] the sums are empty, giving 0 with source NONE. Without references, the manual
     * rotation when manualApplies (source MANUAL, or NONE when it is 0), else 0 with source NONE.
     * Weighting by horizontal length lets a long, reliable reference outvote a short one.
     */
    fun solve(doc: SurveyDoc, plain: PathTimeline, runId: Int): NorthSolution {
        val measured = doc.references.map { ref ->
            Triple(ref, measuredDeg(ref, plain), Measure.leg(plain, ref.fromNs, ref.toNs).horizontalM)
        }
        var sumSin = 0.0
        var sumCos = 0.0
        var used = 0
        for ((ref, m, h) in measured) {
            if (m == null) continue
            val delta = Math.toRadians(forwardBearingDeg(ref) - m)
            sumSin += h * sin(delta)
            sumCos += h * cos(delta)
            used++
        }
        if (used > 0) {
            val rotation = SurveyAngles.wrapDeg(Math.toDegrees(atan2(sumSin, sumCos)))
            val fits = measured.map { (ref, m, h) ->
                ReferenceFit(ref.id, m, h, m?.let { SurveyAngles.wrapDeg(forwardBearingDeg(ref) - it - rotation) })
            }
            return NorthSolution(rotation, NorthSource.REFERENCES, fits)
        }
        val fits = measured.map { (ref, m, h) -> ReferenceFit(ref.id, m, h, null) }
        // References exist but none can be measured on this path: the design's atan2(0, 0) = 0. The manual
        // angle stays unused, since its steppers are disabled while references exist.
        if (doc.references.isNotEmpty()) return NorthSolution(0.0, NorthSource.NONE, fits)
        val manual = SurveyAngles.wrapDeg(doc.manualRotationDeg)
        return if (manual != 0.0 && manualApplies(doc, runId)) {
            NorthSolution(manual, NorthSource.MANUAL, fits)
        } else {
            NorthSolution(0.0, NorthSource.NONE, fits)
        }
    }

    /** The bearing from A to B: bearingDeg, plus 180 for a back-bearing, in [0, 360). */
    fun forwardBearingDeg(reference: CompassReference): Double =
        SurveyAngles.to360(reference.bearingDeg + if (reference.backBearing) 180.0 else 0.0)

    /** The reference's azimuth on [plain]: the chord's, or the fitted line's for ReferenceLine.FITTED. */
    fun measuredDeg(reference: CompassReference, plain: PathTimeline): Double? = when (reference.line) {
        ReferenceLine.CHORD -> Measure.leg(plain, reference.fromNs, reference.toNs).azimuthDeg
        ReferenceLine.FITTED -> Measure.fittedAzimuthDeg(plain, reference.fromNs, reference.toNs)
    }

    /** The manual rotation was set on [runId], or was never tagged. */
    fun manualApplies(doc: SurveyDoc, runId: Int): Boolean =
        doc.manualRotationRunId == null || doc.manualRotationRunId == runId

    /** A manual rotation exists, no reference does, and it was set on another run: the app asks first. */
    fun manualNeedsConfirmation(doc: SurveyDoc, runId: Int): Boolean =
        doc.manualRotationDeg != 0.0 && doc.references.isEmpty() && !manualApplies(doc, runId)

    /**
     * Azimuths are magnetic (suffix M): the run's northReference is MAGNETIC, or compass references were
     * applied. A manual rotation alone never makes a run magnetic, and a run without the key is R.
     */
    fun isMagnetic(diagnostics: Map<String, String>, solution: NorthSolution): Boolean =
        diagnostics[OrientationEstimator.NORTH_REFERENCE]?.trim() == OrientationEstimator.MAGNETIC ||
            solution.source == NorthSource.REFERENCES
}
```

**Step 4: Run it and see it pass**

```bash
(cd pipeline && ../gradlew test --tests '*NorthSolverTest')
```

Expected: `BUILD SUCCESSFUL` (9 tests).

**Step 5: Commit**

```bash
git add pipeline/src/main/kotlin/com/stastyle/imumapper/pipeline/survey/NorthSolver.kt pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/NorthSolverTest.kt
git commit -F - <<'EOF'
Solve the north correction from hand-compass bearings or a manual rotation

One compass bearing is matched exactly; several are combined weighted by their horizontal length, and
each keeps its residual so a misread bearing stands out. A manual rotation is used only without
bearings and only on the run it was set on, because a re-process can change the heading offset.
Azimuths count as magnetic when the run's north was magnetic or a compass bearing was applied.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

---

### Task 8: Automatic corner detection

**Files:**
- Create: `pipeline/src/main/kotlin/com/stastyle/imumapper/pipeline/survey/CornerDetector.kt`
- Test: `pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/CornerDetectorTest.kt`

Ramer-Douglas-Peucker on the plan, with the distance to the segment (not the infinite line) so an
out-and-back, whose ends coincide, still finds its far end. Then the turn filter (weakest vertex under
20 degrees removed, neighbours re-checked), then spacing: strongest first, dropped within 1.5 m of path
distance (strictly less) of a kept station time or a corner already taken. Hand-derived fixtures:

- Dogleg 10 m N, 6 strides (3 m) at 40 degrees, 10 m N: end (1.928, 22.298); both bends lie
  1.928 x 10 / 22.38 = 0.861 m off the start-to-end chord, so COARSE (1.0) keeps none and NORMAL (0.5)
  keeps both (points 20 and 26, turns 40 each, 3 m apart).
- Bend of 10 degrees after 10 m N (then 10 m at 10 degrees): the bend is 0.87 m off the chord, kept by RDP
  at 0.5 m, removed by the 20 degree filter.
- Hairpin (0,0), (0,10), (1,10), (1,0): both bends turn exactly 90 (atan2 of exact axis vectors), 1 m
  apart; the tie goes to the earlier. With (-1, 0) as the last point the second bend comes in heading 90
  and leaves along (-2, -10), heading 180 + atan(2 / 10) = 191.3, so it turns 101.3 degrees and wins.

**Step 1: Write the failing test**

Create `pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/CornerDetectorTest.kt`:

```kotlin
package com.stastyle.imumapper.pipeline.survey

import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.survey.SurveyPaths.T0
import com.stastyle.imumapper.pipeline.survey.SurveyPaths.tNs
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CornerDetectorTest {

    private val deg40 = Math.toRadians(40.0)

    /** Plan distance, since corners are found on the plan. */
    private fun planDistance(a: Vec3, b: Vec3) = hypot(a.x - b.x, a.y - b.y)

    @Test
    fun swayingRectangleHasThreeCornersAtEveryDetail() {
        // 10 m N, 6 m E, 10 m S, 6 m W, swaying 0.15 m: corners at (0, 10), (6, 10) and (6, 0).
        val points = SurveyPaths.steps(20 to 0.0, 12 to PI / 2, 20 to PI, 12 to -PI / 2, swayM = 0.15)
        val tl = PathTimeline(points)
        val trueCorners = listOf(Vec3(0.0, 10.0, 0.0), Vec3(6.0, 10.0, 0.0), Vec3(6.0, 0.0, 0.0))
        for (detail in Detail.entries) {
            val times = CornerDetector.corners(tl, detail, listOf(tl.startNs, tl.endNs))
            assertEquals(3, times.size, "$detail: ${times.map { tl.positionAt(it) }}")
            for (k in 0 until 3) {
                val at = tl.positionAt(times[k])
                assertTrue(planDistance(trueCorners[k], at) < 0.6, "$detail corner $k at $at")
            }
            assertTrue(times.all { t -> points.any { it.tNs == t } }, "corners are moments the walker stood at a point")
        }
    }

    @Test
    fun shallowDoglegNeedsNormalDetail() {
        // 10 m N, 3 m at 40 degrees, 10 m N: the jog's two bends lie 0.86 m off the start-to-end chord.
        val tl = PathTimeline(SurveyPaths.steps(20 to 0.0, 6 to deg40, 20 to 0.0))
        val kept = listOf(tl.startNs, tl.endNs)
        assertEquals(emptyList(), CornerDetector.corners(tl, Detail.COARSE, kept))
        assertEquals(listOf(tNs(20), tNs(26)), CornerDetector.corners(tl, Detail.NORMAL, kept))
        assertEquals(listOf(tNs(20), tNs(26)), CornerDetector.corners(tl, Detail.FINE, kept))
    }

    @Test
    fun gentleBendIsNotACorner() {
        // 10 m N, then 10 m at 10 degrees: the bend is 0.87 m off the chord, so RDP keeps it, but it turns only 10.
        val points = SurveyPaths.steps(20 to 0.0, 20 to Math.toRadians(10.0))
        val vertices = CornerDetector.simplify(points, Detail.NORMAL.toleranceM)
        assertContentEquals(intArrayOf(0, 20, 40), vertices)
        assertEquals(emptyList(), CornerDetector.turning(points, vertices))
        val loose = CornerDetector.turning(points, vertices, minTurnDeg = 5.0).single()
        assertEquals(20, loose.pointIndex)
        assertEquals(tNs(20), loose.tNs)
        assertEquals(10.0, loose.turnDeg, 1e-9)
        val tl = PathTimeline(points)
        assertEquals(emptyList(), CornerDetector.corners(tl, Detail.NORMAL, listOf(tl.startNs, tl.endNs)))
    }

    @Test
    fun cornersOneMetreApartKeepOne() {
        // A hairpin: 10 m N, 1 m E, 10 m S. Both bends turn exactly 90 degrees, so the earlier one wins.
        val hairpin = PathTimeline(
            SurveyPaths.linear(Vec3.ZERO, Vec3(0.0, 10.0, 0.0), Vec3(1.0, 10.0, 0.0), Vec3(1.0, 0.0, 0.0)),
        )
        assertEquals(listOf(tNs(1)), CornerDetector.corners(hairpin, Detail.NORMAL, listOf(T0, tNs(3))))
        // Coming back to the south-west makes the second bend the stronger (about 101 degrees): it wins.
        val skewed = PathTimeline(
            SurveyPaths.linear(Vec3.ZERO, Vec3(0.0, 10.0, 0.0), Vec3(1.0, 10.0, 0.0), Vec3(-1.0, 0.0, 0.0)),
        )
        assertEquals(listOf(tNs(2)), CornerDetector.corners(skewed, Detail.NORMAL, listOf(T0, tNs(3))))
    }

    @Test
    fun cornerNearAKeptStationIsDropped() {
        // L walk: the corner is point 20, 10 m along. A station at point 18 (1 m before) hides it; one at
        // point 16 (2 m before) does not.
        val tl = PathTimeline(SurveyPaths.steps(20 to 0.0, 10 to PI / 2))
        val ends = listOf(tl.startNs, tl.endNs)
        assertEquals(listOf(tNs(20)), CornerDetector.corners(tl, Detail.NORMAL, ends))
        assertEquals(emptyList(), CornerDetector.corners(tl, Detail.NORMAL, ends + tNs(18)))
        assertEquals(listOf(tNs(20)), CornerDetector.corners(tl, Detail.NORMAL, ends + tNs(16)))
    }

    @Test
    fun simplifyKeepsTheEndsAndSortedIndices() {
        val points = SurveyPaths.steps(20 to 0.0, 10 to PI / 2)
        assertContentEquals(intArrayOf(0, 20, 30), CornerDetector.simplify(points, 0.5))
        assertContentEquals(intArrayOf(0, 1), CornerDetector.simplify(points.take(2), 0.5))
        assertContentEquals(intArrayOf(0), CornerDetector.simplify(points.take(1), 0.5))
        // An out-and-back ends where it started; the far end still counts.
        val outAndBack = SurveyPaths.steps(10 to 0.0, 10 to PI)
        assertContentEquals(intArrayOf(0, 10, 20), CornerDetector.simplify(outAndBack, 0.5))
        val reversal = CornerDetector.turning(outAndBack, intArrayOf(0, 10, 20)).single()
        assertEquals(180.0, reversal.turnDeg, 1e-9)
    }

    @Test
    fun vertexNextToAStandStillTurnsByZero() {
        // Points 1 and 2 coincide (the walker stood), so each touches a segment with no direction.
        val points = SurveyPaths.linear(Vec3.ZERO, Vec3(0.0, 5.0, 0.0), Vec3(0.0, 5.0, 0.0), Vec3(5.0, 5.0, 0.0))
        val all = CornerDetector.turning(points, intArrayOf(0, 1, 2, 3), minTurnDeg = 0.0)
        assertEquals(listOf(0.0, 0.0), all.map { it.turnDeg })
        // Removing the weaker (the earlier, on a tie) re-checks its neighbour, which now turns 90 degrees.
        val corner = CornerDetector.turning(points, intArrayOf(0, 1, 2, 3)).single()
        assertEquals(2, corner.pointIndex)
        assertEquals(90.0, corner.turnDeg, 1e-9)
    }
}
```

**Step 2: Run it and see it fail**

```bash
(cd pipeline && ../gradlew test --tests '*CornerDetectorTest')
```

Expected: `e: ...CornerDetectorTest.kt:27:25 Unresolved reference 'CornerDetector'.` and `BUILD FAILED`.

**Step 3: Write the implementation**

Create `pipeline/src/main/kotlin/com/stastyle/imumapper/pipeline/survey/CornerDetector.kt`:

```kotlin
package com.stastyle.imumapper.pipeline.survey

import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.pdr.Angles
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** A turning vertex of the simplified plan. The vertex is always an existing path point: a place the walker stood. */
data class Corner(val pointIndex: Int, val tNs: Long, val turnDeg: Double)

/**
 * Finds the turns of a walk where nothing was marked: Ramer-Douglas-Peucker on the plan, then a turn
 * filter so a gentle bend or step sway is not a corner, then spacing so no leg is shorter than
 * MIN_SPACING_M of path.
 */
object CornerDetector {
    const val MIN_TURN_DEG: Double = 20.0
    const val MIN_SPACING_M: Double = 1.5

    /** Plan segments shorter than this (1 micrometre) have no direction. */
    private const val MIN_SEGMENT_SQUARED_M2: Double = 1e-12

    /** Ramer-Douglas-Peucker on the plan (x, y) of [points], iterative; sorted indices, first and last always kept. */
    fun simplify(points: List<PathPoint>, toleranceM: Double): IntArray {
        val n = points.size
        if (n <= 2) return IntArray(n) { it }
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        // An explicit stack instead of recursion: an hour's walk has thousands of points.
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, n - 1))
        while (stack.isNotEmpty()) {
            val (lo, hi) = stack.removeLast()
            var best = -1
            var bestDistance = toleranceM
            for (k in lo + 1 until hi) {
                val d = planDistance(points[k].p, points[lo].p, points[hi].p)
                if (d > bestDistance) {
                    best = k
                    bestDistance = d
                }
            }
            if (best < 0) continue
            keep[best] = true
            stack.addLast(intArrayOf(lo, best))
            stack.addLast(intArrayOf(best, hi))
        }
        return (0 until n).filter { keep[it] }.toIntArray()
    }

    /**
     * The interior vertices whose plan direction turns by at least [minTurnDeg], in path order. The
     * weakest vertex below the limit is removed and its neighbours re-checked until every one passes.
     * A vertex next to a zero-length plan segment turns by 0.
     */
    fun turning(points: List<PathPoint>, vertices: IntArray, minTurnDeg: Double = MIN_TURN_DEG): List<Corner> {
        val kept = vertices.toMutableList()
        // turns[k] belongs to kept[k]; the ends never turn.
        val turns = MutableList(kept.size) { k ->
            if (k == 0 || k == kept.size - 1) Double.MAX_VALUE else turnDeg(points, kept[k - 1], kept[k], kept[k + 1])
        }
        while (true) {
            var weakest = -1
            for (k in 1 until kept.size - 1) {
                if (turns[k] < minTurnDeg && (weakest < 0 || turns[k] < turns[weakest])) weakest = k
            }
            if (weakest < 0) break
            kept.removeAt(weakest)
            turns.removeAt(weakest)
            // Removing a vertex straightens its neighbours' segments, so only they change.
            for (k in weakest - 1..weakest) {
                if (k >= 1 && k <= kept.size - 2) turns[k] = turnDeg(points, kept[k - 1], kept[k], kept[k + 1])
            }
        }
        return (1 until kept.size - 1).map { k -> Corner(kept[k], points[kept[k]].tNs, turns[k]) }
    }

    /**
     * Corner times for [detail] on [timeline]: turning vertices taken strongest first (ties: earlier
     * first), each dropped when it lies within MIN_SPACING_M of path distance of a kept time or of a
     * corner already taken; returned in time order.
     */
    fun corners(timeline: PathTimeline, detail: Detail, keptTimesNs: List<Long>): List<Long> {
        val points = timeline.points
        val candidates = turning(points, simplify(points, detail.toleranceM))
        val anchors = keptTimesNs.map { timeline.distanceAt(it) }
        val takenAt = ArrayList<Double>()
        val taken = ArrayList<Long>()
        for (corner in candidates.sortedWith(compareByDescending<Corner> { it.turnDeg }.thenBy { it.tNs })) {
            val d = timeline.distanceAt(corner.tNs)
            if (anchors.any { abs(it - d) < MIN_SPACING_M } || takenAt.any { abs(it - d) < MIN_SPACING_M }) continue
            takenAt.add(d)
            taken.add(corner.tNs)
        }
        return taken.sorted()
    }

    /** Degrees the plan direction turns at [b] between the segments from [a] and to [c]. */
    private fun turnDeg(points: List<PathPoint>, a: Int, b: Int, c: Int): Double {
        val p = points[a].p
        val q = points[b].p
        val r = points[c].p
        val x1 = q.x - p.x
        val y1 = q.y - p.y
        val x2 = r.x - q.x
        val y2 = r.y - q.y
        if (x1 * x1 + y1 * y1 < MIN_SEGMENT_SQUARED_M2 || x2 * x2 + y2 * y2 < MIN_SEGMENT_SQUARED_M2) return 0.0
        return Math.toDegrees(abs(Angles.diff(atan2(x2, y2), atan2(x1, y1))))
    }

    /** Plan distance from [p] to the segment [a]-[b], or to [a] when they coincide. */
    private fun planDistance(p: Vec3, a: Vec3, b: Vec3): Double {
        val ex = b.x - a.x
        val ey = b.y - a.y
        val px = p.x - a.x
        val py = p.y - a.y
        val len2 = ex * ex + ey * ey
        // A segment, not a line: an out-and-back ends where it started, and its far end must still count.
        val f = if (len2 > 0.0) ((px * ex + py * ey) / len2).coerceIn(0.0, 1.0) else 0.0
        return hypot(px - f * ex, py - f * ey)
    }
}
```

**Step 4: Run it and see it pass**

```bash
(cd pipeline && ../gradlew test --tests '*CornerDetectorTest')
```

Expected: `BUILD SUCCESSFUL` (7 tests).

**Step 5: Commit**

```bash
git add pipeline/src/main/kotlin/com/stastyle/imumapper/pipeline/survey/CornerDetector.kt pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/CornerDetectorTest.kt
git commit -F - <<'EOF'
Find the turns of a walk automatically, so a trip with no marks still has stations

Most walks are recorded without marks. The plan is simplified at the chosen detail, vertices that turn
less than 20 degrees are dropped, and corners closer than 1.5 m of path to another station are skipped
so no leg is too short to mean anything. A corner is always a recorded point: a place the walker stood.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

---

### Task 9: Stations: seeding, names and corner regeneration

**Files:**
- Create: `pipeline/src/main/kotlin/com/stastyle/imumapper/pipeline/survey/SurveyStations.kt`
- Test: `pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/SurveyStationsTest.kt`

Seeding on the L walk (corner at point 20, 10 m along) with WAYPOINT " Entrance " at point 2, JUNCTIONs
at points 12 and 26 (no note, and a blank note), REORIENT at 5 and LOOP_CLOSED at 30: Start id 1, marks
ids 2 to 4 in time order ("Entrance", "Junction 1", "Junction 2"), End id 5, then C1 id 6 at point 20
(nearest kept station 3 m away), listed in time order. Regeneration on the rectangle (corners at points
20, 32, 52: 10, 16 and 26 m) with a USER station at point 33 (16.5 m) keeps that USER station, drops the
16 m corner (0.5 m from it), and names the other two C1 and C2 with ids from 6. A moved corner is a USER
station that kept its name, so when the kept USER station at point 33 is called "C1", the new corners skip
that name and become C2 and C3: no two stations of the traverse share a name.

**Step 1: Write the failing test**

Create `pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/SurveyStationsTest.kt`:

```kotlin
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
```

**Step 2: Run it and see it fail**

```bash
(cd pipeline && ../gradlew test --tests '*SurveyStationsTest')
```

Expected: `e: ...SurveyStationsTest.kt:40:32 Unresolved reference 'SurveyStations'.` and `BUILD FAILED`.

**Step 3: Write the implementation**

Create `pipeline/src/main/kotlin/com/stastyle/imumapper/pipeline/survey/SurveyStations.kt`:

```kotlin
package com.stastyle.imumapper.pipeline.survey

import com.stastyle.imumapper.pipeline.core.AnnotationKind
import com.stastyle.imumapper.pipeline.core.PathAnnotation

/** Builds, names and orders the stations of a survey. */
object SurveyStations {

    /** Annotation kinds that become MARK stations; LOOP_CLOSED and REORIENT do not. */
    val MARK_KINDS: Set<AnnotationKind> =
        setOf(AnnotationKind.WAYPOINT, AnnotationKind.JUNCTION, AnnotationKind.CHAMBER, AnnotationKind.NOTE)

    private val USER_NAME = Regex("S(\\d+)")

    /** The traverse order: by tNs, then by id. */
    fun ordered(stations: List<Station>): List<Station> = stations.sortedWith(compareBy({ it.tNs }, { it.id }))

    /**
     * First-open stations: START "Start" (id 1) at startNs, one MARK per MARK_KINDS annotation in time
     * order (ids 2, 3, ...), END "End" at endNs (next id), then the CORNER stations of [detail] with every
     * other station kept (next ids, named C1, C2, ... in time order). Returned in traverse order.
     */
    fun seed(
        timeline: PathTimeline,
        annotations: List<PathAnnotation>,
        detail: Detail = Detail.NORMAL,
    ): List<Station> {
        val out = ArrayList<Station>()
        out.add(Station(1, StationKind.START, "Start", timeline.startNs))
        val ordinals = HashMap<AnnotationKind, Int>()
        for (a in annotations.filter { it.kind in MARK_KINDS }.sortedBy { it.tNs }) {
            val ordinal = (ordinals[a.kind] ?: 0) + 1
            ordinals[a.kind] = ordinal
            out.add(Station(out.size + 1, StationKind.MARK, markName(a.kind, a.note, ordinal), a.tNs))
        }
        out.add(Station(out.size + 1, StationKind.END, "End", timeline.endNs))
        return regenerateCorners(out, timeline, detail)
    }

    /** The trimmed note, or the kind in title case and its 1-based [ordinal] among that kind ("Junction 2"). */
    fun markName(kind: AnnotationKind, note: String, ordinal: Int): String =
        note.trim().ifEmpty { kind.name.lowercase().replaceFirstChar { it.uppercase() } + " " + ordinal }

    /**
     * Removes every CORNER, detects corners for [detail] keeping all other stations as spacing anchors,
     * names them C1.. in time order, skipping names already in use, ids from nextId of the kept stations.
     * A moved corner is a USER station that kept its name, and the legs, the CSV and the copied line name
     * stations only by name, so a new corner must not take it again.
     */
    fun regenerateCorners(stations: List<Station>, timeline: PathTimeline, detail: Detail): List<Station> {
        val kept = stations.filter { it.kind != StationKind.CORNER }
        val firstId = nextId(kept)
        val taken = kept.mapTo(HashSet()) { it.name }
        var n = 0
        val corners = CornerDetector.corners(timeline, detail, kept.map { it.tNs }).mapIndexed { k, tNs ->
            do {
                n++
            } while ("C$n" in taken)
            Station(firstId + k, StationKind.CORNER, "C$n", tNs)
        }
        return ordered(kept + corners)
    }

    /** max(id) + 1, or 1. */
    fun nextId(stations: List<Station>): Int = (stations.maxOfOrNull { it.id } ?: 0) + 1

    /** "S" + (1 + the largest n of any name "S<n>"), "S1" first. */
    fun nextUserName(stations: List<Station>): String {
        val largest = stations.mapNotNull { USER_NAME.matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 0
        return "S${largest + 1}"
    }

    /** A new USER station at [tNs] with nextId and nextUserName. */
    fun user(stations: List<Station>, tNs: Long): Station =
        Station(nextId(stations), StationKind.USER, nextUserName(stations), tNs)
}
```

**Step 4: Run it and see it pass**

```bash
(cd pipeline && ../gradlew test --tests '*SurveyStationsTest')
```

Expected: `BUILD SUCCESSFUL` (7 tests).

**Step 5: Commit**

```bash
git add pipeline/src/main/kotlin/com/stastyle/imumapper/pipeline/survey/SurveyStations.kt pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/SurveyStationsTest.kt
git commit -F - <<'EOF'
Seed survey stations from the start, the end, the marks and the automatic corners

The first time Survey mode opens, the marks made while walking become named stations ("Junction 2"
when a mark has no note), and the corners fill the gaps between them. Changing the detail regenerates
only the corners, keeping stations the user added or moved; a new corner skips a name a moved one kept.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

---

### Task 10: Traverse legs, totals and chains

**Files:**
- Create: `pipeline/src/main/kotlin/com/stastyle/imumapper/pipeline/survey/Traverse.kt`
- Test: `pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/TraverseTest.kt`

The test's climbing L walk rises 0.25 m per stride: each 10-stride leg is 5 m across and 2.5 m up, so
its chord is sqrt(5^2 + 2.5^2) and, being straight, equals its path; the net climb over 30 strides is
7.5 m. On the out-and-back (10 m north, 10 m back), the chain start, turn, point 30 has hops 10 + 5 = 15 m
against a straight line of 5 m; start, turn, end has hops 20 m, path 20 m (the walked length) and a
straight line of 0 with no azimuth.

**Step 1: Write the failing test**

Create `pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/TraverseTest.kt`:

```kotlin
package com.stastyle.imumapper.pipeline.survey

import com.stastyle.imumapper.pipeline.survey.SurveyPaths.T0
import com.stastyle.imumapper.pipeline.survey.SurveyPaths.tNs
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraverseTest {

    /** 10 m north then 5 m east, climbing 0.25 m per step: 7.5 m up in all. */
    private val climbingL = PathTimeline(SurveyPaths.steps(20 to 0.0, 10 to PI / 2, climbPerStepM = 0.25))

    private val start = Station(1, StationKind.START, "Start", T0)
    private val junction = Station(2, StationKind.MARK, "Junction 1", tNs(10))
    private val end = Station(3, StationKind.END, "End", tNs(30))
    private val corner = Station(4, StationKind.CORNER, "C1", tNs(20))

    @Test
    fun legsFollowTimeOrderNotListOrder() {
        val legs = Traverse.legs(listOf(end, start, corner, junction), climbingL)
        assertEquals(listOf(start to junction, junction to corner, corner to end), legs.map { it.from to it.to })
        val azimuths = legs.map { assertNotNull(it.measure.azimuthDeg) }
        assertEquals(0.0, azimuths[0], 1e-9)
        assertEquals(0.0, azimuths[1], 1e-9)
        assertEquals(90.0, azimuths[2], 1e-9)
        assertEquals(Measure.leg(climbingL, tNs(10), tNs(20)), legs[1].measure)
    }

    @Test
    fun totalsSumTheLegs() {
        val legs = Traverse.legs(listOf(start, junction, corner, end), climbingL)
        val totals = Traverse.totals(legs)
        // Each leg is straight: 5 m across and 2.5 m up, so chord and path agree.
        val leg = sqrt(5.0 * 5.0 + 2.5 * 2.5)
        assertEquals(3 * leg, totals.lengthM, 1e-9)
        assertEquals(15.0, totals.horizontalM, 1e-9)
        assertEquals(7.5, totals.heightChangeM, 1e-9)
        assertEquals(climbingL.lengthM, totals.pathM, 1e-9)
        assertEquals(LegTotals(0.0, 0.0, 0.0, 0.0), Traverse.totals(emptyList()))
    }

    @Test
    fun chainOnAnOutAndBack() {
        // 10 m out to the north and 10 m back.
        val outAndBack = PathTimeline(SurveyPaths.steps(20 to 0.0, 20 to PI))
        val partWay = Traverse.chain(listOf(T0, tNs(20), tNs(30)), outAndBack)
        assertEquals(2, partWay.hops.size)
        assertEquals(15.0, partWay.hopLengthSumM, 1e-9)
        assertEquals(15.0, partWay.hopPathSumM, 1e-9)
        assertEquals(5.0, partWay.straight.lengthM, 1e-9)
        assertTrue(partWay.hopLengthSumM > partWay.straight.lengthM)
        val home = Traverse.chain(listOf(T0, tNs(20), tNs(40)), outAndBack)
        assertEquals(outAndBack.lengthM, home.hopPathSumM, 1e-9)
        assertEquals(20.0, home.hopLengthSumM, 1e-9)
        assertTrue(home.straight.lengthM < 1e-9)
        assertNull(home.straight.azimuthDeg)
        assertEquals(Measure.leg(outAndBack, T0, tNs(20)), home.hops[0])
    }

    @Test
    fun fewerThanTwoStationsHaveNoLegs() {
        assertTrue(Traverse.legs(emptyList(), climbingL).isEmpty())
        assertTrue(Traverse.legs(listOf(start), climbingL).isEmpty())
        assertFailsWith<IllegalArgumentException> { Traverse.chain(listOf(T0), climbingL) }
    }
}
```

**Step 2: Run it and see it fail**

```bash
(cd pipeline && ../gradlew test --tests '*TraverseTest')
```

Expected: `e: ...TraverseTest.kt:26:20 Unresolved reference 'Traverse'.` and `BUILD FAILED`.

**Step 3: Write the implementation**

Create `pipeline/src/main/kotlin/com/stastyle/imumapper/pipeline/survey/Traverse.kt`:

```kotlin
package com.stastyle.imumapper.pipeline.survey

/** Consecutive stations of the traverse and the chord between them. */
data class TraverseLeg(val from: Station, val to: Station, val measure: LegMeasure)

/** The Legs table's totals row. */
data class LegTotals(val lengthM: Double, val horizontalM: Double, val heightChangeM: Double, val pathM: Double)

/** A chain selection: each hop, the straight line from the first to the last, and the sums. */
data class ChainMeasure(
    val hops: List<LegMeasure>,
    val straight: LegMeasure,
    val hopLengthSumM: Double,
    val hopPathSumM: Double,
)

/** The traverse (stations in time order) and chains of tapped stations, measured as chords. */
object Traverse {

    /** Legs between consecutive stations in SurveyStations.ordered order; empty for fewer than two. */
    fun legs(stations: List<Station>, timeline: PathTimeline): List<TraverseLeg> =
        SurveyStations.ordered(stations).zipWithNext { from, to ->
            TraverseLeg(from, to, Measure.leg(timeline, from.tNs, to.tNs))
        }

    /** Sums of the legs (heightChange sums to the net climb). */
    fun totals(legs: List<TraverseLeg>): LegTotals = LegTotals(
        lengthM = legs.sumOf { it.measure.lengthM },
        horizontalM = legs.sumOf { it.measure.horizontalM },
        heightChangeM = legs.sumOf { it.measure.heightChangeM },
        pathM = legs.sumOf { it.measure.pathM },
    )

    /** [timesNs] in tap order, at least two. */
    fun chain(timesNs: List<Long>, timeline: PathTimeline): ChainMeasure {
        require(timesNs.size >= 2) { "a chain needs at least two stations" }
        val hops = timesNs.zipWithNext { from, to -> Measure.leg(timeline, from, to) }
        return ChainMeasure(
            hops = hops,
            straight = Measure.leg(timeline, timesNs.first(), timesNs.last()),
            hopLengthSumM = hops.sumOf { it.lengthM },
            hopPathSumM = hops.sumOf { it.pathM },
        )
    }
}
```

**Step 4: Run it and see it pass**

```bash
(cd pipeline && ../gradlew test --tests '*TraverseTest')
```

Expected: `BUILD SUCCESSFUL` (4 tests).

**Step 5: Commit**

```bash
git add pipeline/src/main/kotlin/com/stastyle/imumapper/pipeline/survey/Traverse.kt pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/TraverseTest.kt
git commit -F - <<'EOF'
List the traverse legs between stations, their totals, and chains of tapped stations

The traverse is the stations in time order, and each consecutive pair is a leg. A chain of tapped
stations reports each hop, the sums, and the straight line from its first station to its last, which
shows how far an out-and-back strays from a direct route.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

---

### Task 11: CSV text and correction sentence

**Files:**
- Create: `pipeline/src/main/kotlin/com/stastyle/imumapper/pipeline/survey/SurveyCsv.kt`
- Test: `pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/SurveyCsvTest.kt`

Format per architecture section 4.1: byte-order mark, header, CRLF after every row including the last,
`Locale.US`, an empty cell where the panel shows a dash, "-0.00" printed as "0.00" and an azimuth that
rounds to "360.0" printed as "0.0". Expected rows for the L traverse (10 m north, 5 m east, origin at the
start, 0.5 s per stride): Start to Junction 1 is 0 to 5 s, 5 m at 0 degrees ending at (0, 5); Junction 1
to C1 is 5 to 10 s ending at (0, 10); C1 to End is 10 to 15 s, 5 m at 90 degrees ending at (5, 10).
`correctionText` rounds to 0.1 before formatting, so -0.04 reads "+0.0".

**Step 1: Write the failing test**

Create `pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/SurveyCsvTest.kt` (the first
string of `expected` is the escape `\uFEFF`, six characters):

```kotlin
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
```

**Step 2: Run it and see it fail**

```bash
(cd pipeline && ../gradlew test --tests '*SurveyCsvTest')
```

Expected: `e: ...SurveyCsvTest.kt:19:27 Unresolved reference 'SurveyCsv'.` and `BUILD FAILED`.

**Step 3: Write the implementation**

Create `pipeline/src/main/kotlin/com/stastyle/imumapper/pipeline/survey/SurveyCsv.kt` (`BOM` is the
escape `\uFEFF`, six characters):

```kotlin
package com.stastyle.imumapper.pipeline.survey

import com.stastyle.imumapper.pipeline.core.Vec3
import java.util.Locale

/** The survey's CSV table and the sentence that says how north was corrected. */
object SurveyCsv {
    /** Excel needs the byte-order mark to read UTF-8 (Hebrew station names). */
    const val BOM: String = "\uFEFF"
    const val HEADER: String = "from,to,from_s,to_s,length_m,horizontal_m,height_change_m,azimuth_deg,north," +
        "slope_deg,grade_pct,path_m,curved,to_east_m,to_north_m,to_up_m"

    /** RFC 4180 line ends, which Excel and LibreOffice both expect. */
    const val EOL: String = "\r\n"

    /** The whole file: BOM + HEADER + EOL, then one row + EOL per leg; a value that cannot be measured is empty. */
    fun text(legs: List<TraverseLeg>, startNs: Long, origin: Vec3, magnetic: Boolean): String {
        val out = StringBuilder()
        out.append(BOM).append(HEADER).append(EOL)
        for (leg in legs) {
            val m = leg.measure
            val cells = listOf(
                field(leg.from.name),
                field(leg.to.name),
                fixed((leg.from.tNs - startNs) / 1e9, 1),
                fixed((leg.to.tNs - startNs) / 1e9, 1),
                fixed(m.lengthM, 2),
                fixed(m.horizontalM, 2),
                fixed(m.heightChangeM, 2),
                m.azimuthDeg?.let(::azimuth) ?: "",
                if (magnetic) "M" else "R",
                fixed(m.slopeDeg, 1),
                m.gradePct?.let { fixed(it, 1) } ?: "",
                fixed(m.pathM, 2),
                if (m.curved) "yes" else "no",
                fixed(m.b.x - origin.x, 2),
                fixed(m.b.y - origin.y, 2),
                fixed(m.b.z - origin.z, 2),
            )
            out.append(cells.joinToString(",")).append(EOL)
        }
        return out.toString()
    }

    /** RFC 4180 quoting: wrapped in quotes, quotes doubled, when the value holds , " CR or LF. */
    fun field(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    /** Locale.US fixed-point with [decimals] places; "-0.00" becomes "0.00". */
    fun fixed(value: Double, decimals: Int): String {
        val text = String.format(Locale.US, "%.${decimals}f", value)
        // A small negative rounds to "-0.00", which reads as a direction where there is none.
        return if (text.startsWith("-") && text.all { it == '-' || it == '0' || it == '.' }) text.substring(1) else text
    }

    /**
     * "north +4.0° from 2 compass readings", "north +4.0° from 1 compass reading", "north -1.5° set by
     * hand", "north as recorded".
     */
    fun correctionText(solution: NorthSolution): String {
        // Rounded first so -0.04 reads "+0.0", not "-0.0".
        val deg = Math.round(solution.rotationDeg * 10.0) / 10.0 + 0.0
        return when (solution.source) {
            NorthSource.REFERENCES -> {
                val count = solution.usedCount
                val plural = if (count == 1) "" else "s"
                String.format(Locale.US, "north %+.1f° from %d compass reading%s", deg, count, plural)
            }
            NorthSource.MANUAL -> String.format(Locale.US, "north %+.1f° set by hand", deg)
            NorthSource.NONE -> "north as recorded"
        }
    }

    /** One decimal, and a value that rounds to 360.0 is north: "0.0". */
    private fun azimuth(deg: Double): String = fixed(deg, 1).let { if (it == "360.0") "0.0" else it }
}
```

**Step 4: Run it and see it pass, then the whole pipeline suite**

```bash
(cd pipeline && ../gradlew test --tests '*SurveyCsvTest')
(cd pipeline && ../gradlew test)
```

Expected: `BUILD SUCCESSFUL` both times (9 tests in `SurveyCsvTest`; 79 tests in the survey package; the
existing pipeline tests are untouched).

**Step 5: Commit**

```bash
git add pipeline/src/main/kotlin/com/stastyle/imumapper/pipeline/survey/SurveyCsv.kt pipeline/src/test/kotlin/com/stastyle/imumapper/pipeline/survey/SurveyCsvTest.kt
git commit -F - <<'EOF'
Write the survey traverse as a CSV table and say in words how north was corrected

One row per leg, UTF-8 with a byte-order mark so Excel shows Hebrew station names, Locale.US numbers
and an empty cell where a value cannot be measured. The same sentence about the north correction goes
into the share text, so a reader knows what the azimuths are relative to.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```
