## Section C: App render layer and canvas gestures

Tasks 15 to 18 add Survey mode's overlay to `render/` and teach `ViewerCanvas` the survey gestures.
`SurveyLayer` and `ProjectedSurvey` are pure (no Compose, no `android.*`) and unit tested on the JVM;
`SurveyRenderer` and the `ViewerCanvas` changes are Compose and are proven by compiling plus the whole
existing app test suite.

**Depends on:** Task 1 (`Station`, `StationKind` in `com.stastyle.imumapper.pipeline.survey`) and Task 3
(`PathTimeline`: `points`, `positionAt`, `distanceOfPoint`, `samplesBetween`). Nothing in this section
touches `ViewerScreen`: every new parameter is defaulted, so the viewer behaves exactly as before until
Task 29 passes `survey`, `orbitLocked`, `onSurveyTap` and `onSurveyLongPress`.

**Interface notes (all compatible with section 2.3 / 2.4 of the architecture, nothing renamed):**

- `ProjectedSurvey.cursorVisible` is `var` with a `private set`; readers are unaffected.
- `SurveyLayer.stations` keeps the order of the `stations` argument (the doc's traverse order). Station
  hit-test ties go to the earlier one in that order; path hit-test ties go to the earlier segment.
- `SurveyLayer.build` drops unknown ids from `chainIds` before numbering and before building chords, so
  `[1, 99, 4]` behaves like `[1, 4]`.
- `ViewerCanvas` re-runs `ProjectedSurvey.update` with the latest camera and the pointer-input size
  before every survey hit test (a no-op when nothing changed), because the layer a tap just rebuilt may
  not have been drawn yet.

Existing APIs used (all read in the worktree or checked in the Compose 1.7.6 jars):
`PathScene.decimate(count, maxCount)`, `SceneColors.argb/START/END`, `PathRenderer.BACKGROUND`,
`PathRenderer.labelOrigin(x, y, w, h)`, `PathRenderer.hitTest`, `Projector(camera, w, h).project(x, y, z,
out, offset)` and `project(Vec3)`, `OrbitCamera.withPreset/panned`, `Bounds.of`, `CameraPreset.TOP`,
`PathBuilder.stats(points, durationS, stepCount, closureErrorM)`, `PIPELINE_VERSION`,
`OrientationEstimator.NORTH_REFERENCE/MAGNETIC/NORTH_OFF`; Compose `DrawScope.drawPath/drawLine/drawCircle`,
`PathEffect.dashPathEffect`, `Stroke(width, cap, join)`, `StrokeJoin.Round`, `Shadow`, `Path()`,
`TextMeasurer.measure(String, TextStyle)`, `DrawScope.drawText(TextLayoutResult, topLeft = ...)`,
`DrawScope.drawText(textMeasurer, text, topLeft, style)`, `AwaitPointerEventScope.withTimeoutOrNull`,
`ViewConfiguration.longPressTimeoutMillis`, `PointerInputScope.size`.

---

### Task 15: Survey layer model

**Files:**
- Create: `app/src/test/kotlin/com/stastyle/imumapper/SurveyFixtures.kt` (shared app fixture, also used by Section D)
- Create: `app/src/main/kotlin/com/stastyle/imumapper/render/SurveyLayer.kt`
- Test: `app/src/test/kotlin/com/stastyle/imumapper/render/SurveyLayerTest.kt`

The fixture is an L-shaped PDR walk with exact binary coordinates: point `i` is at time
`T0 + i * 0.5 s`; points 1..20 go north 0.5 m per step to (0, 10), points 21..30 go east to (5, 10).
Every step gap is 0.5 s, so `PathTimeline.medianStepNs` is 0.5 s and the hold window
`min(gap, 1.5 x 0.5 s)` equals the whole gap: placement is linear inside every segment. That is how the
expected numbers below were derived:

- `positionAt(tNs(i))` is point `i`; half-way through step 6 (`tNs(5) + 0.25 s`) is (0, 2.75).
- Distances along the path are `0.5 * i` (0 at the start, 5 at index 10, 10 at the corner, 15 at the end).
- `samplesBetween(tNs(10), tNs(20))` is the two ends plus points 11..19: 11 vertices, (0, 5) to (0, 10).

**Step 1: Write the app fixture**

Create `app/src/test/kotlin/com/stastyle/imumapper/SurveyFixtures.kt`:

```kotlin
package com.stastyle.imumapper

import com.stastyle.imumapper.pipeline.core.AnnotationKind
import com.stastyle.imumapper.pipeline.core.PIPELINE_VERSION
import com.stastyle.imumapper.pipeline.core.PathAnnotation
import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.PathResult
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.PositionSource
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.pdr.OrientationEstimator
import com.stastyle.imumapper.pipeline.post.PathBuilder
import kotlin.math.PI

/**
 * Survey-mode walks for app tests. Coordinates and times are exact binary numbers, so expected
 * positions and distances can be written as literals.
 */
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
    fun lWalk(magnetic: Boolean = true): PathResult {
        // Like PdrSolver: the origin carries the first leg's heading and no step.
        val points = ArrayList<PathPoint>(31)
        points += PathPoint(tNs(0), Vec3.ZERO, PositionSource.PDR, 0.0, -1)
        for (n in 1..20) {
            points += PathPoint(tNs(n), Vec3(0.0, STRIDE_M * n, 0.0), PositionSource.PDR, 0.0, n - 1)
        }
        for (n in 1..10) {
            points += PathPoint(tNs(20 + n), Vec3(STRIDE_M * n, 10.0, 0.0), PositionSource.PDR, PI / 2, 19 + n)
        }
        val north = if (magnetic) OrientationEstimator.MAGNETIC else OrientationEstimator.NORTH_OFF
        return PathResult(
            pipelineVersion = PIPELINE_VERSION,
            config = PipelineConfig(),
            points = points,
            annotations = listOf(PathAnnotation(tNs(10), AnnotationKind.JUNCTION, "", Vec3(0.0, 5.0, 0.0))),
            stats = PathBuilder.stats(points, durationS = (tNs(30) - T0) / 1e9, stepCount = 30, closureErrorM = null),
            diagnostics = mapOf(OrientationEstimator.NORTH_REFERENCE to north),
        )
    }
}
```

**Step 2: Write the failing test**

Create `app/src/test/kotlin/com/stastyle/imumapper/render/SurveyLayerTest.kt`:

```kotlin
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
```

**Step 3: Run the test to see it fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.stastyle.imumapper.render.SurveyLayerTest'`

Expected: `BUILD FAILED` in `:app:compileDebugUnitTestKotlin`, with errors such as
`e: file:///.../render/SurveyLayerTest.kt:...: Unresolved reference 'LayerSelection'.` and
`Unresolved reference 'SurveyLayer'.` (and follow-on "Cannot infer type" errors). If instead the errors
name `PathTimeline` or `Station`, Tasks 1 and 3 are not done yet: stop and do them first.

**Step 4: Write the implementation**

Create `app/src/main/kotlin/com/stastyle/imumapper/render/SurveyLayer.kt`:

```kotlin
package com.stastyle.imumapper.render

import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.survey.PathTimeline
import com.stastyle.imumapper.pipeline.survey.Station
import com.stastyle.imumapper.pipeline.survey.StationKind

/**
 * ARGB colours of the survey layer. START and END are the scene's own, so a station reads as the
 * marker it replaces in Survey mode.
 */
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

    fun forKind(kind: StationKind): Int = when (kind) {
        StationKind.START -> START
        StationKind.END -> END
        StationKind.MARK -> MARK
        StationKind.CORNER -> CORNER
        StationKind.USER -> USER
    }
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
    /** In the order given to [build], which is the traverse order the doc keeps. */
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
        /** Enough for a smooth hit anywhere on a long trip while a re-projection stays cheap. */
        const val MAX_PATH_VERTICES: Int = 4000
        const val MAX_STRETCH_VERTICES: Int = 2000

        fun build(
            timeline: PathTimeline,
            stations: List<Station>,
            selection: LayerSelection,
            cursorNs: Long?,
        ): SurveyLayer {
            val known = stations.mapTo(HashSet()) { it.id }
            // The controller prunes the selection after every edit; this only guards a stale id.
            val chain = selection.chainIds.filter { it in known }
            // Later entries overwrite earlier ones, so a station tapped twice shows its last place.
            val order = HashMap<Int, Int>()
            chain.forEachIndexed { k, id -> order[id] = k + 1 }
            val ringed = order.keys + selection.stretchIds

            val layerStations = stations.map { s ->
                LayerStation(
                    id = s.id,
                    position = timeline.positionAt(s.tNs),
                    name = s.name,
                    color = SurveyColors.forKind(s.kind),
                    selected = s.id in ringed,
                    order = order[s.id] ?: 0,
                )
            }
            val positionOf = layerStations.associate { it.id to it.position }

            val chordEnds = ArrayList<Vec3>()
            for (k in 0 until chain.size - 1) {
                chordEnds += positionOf.getValue(chain[k])
                chordEnds += positionOf.getValue(chain[k + 1])
            }
            var stretch: List<Vec3> = emptyList()
            val span = selection.stretchNs
            if (span != null) {
                val (fromNs, toNs) = span
                chordEnds += timeline.positionAt(fromNs)
                chordEnds += timeline.positionAt(toNs)
                val samples = timeline.samplesBetween(fromNs, toNs)
                stretch = PathScene.decimate(samples.size, MAX_STRETCH_VERTICES).map { samples[it] }
            }

            val points = timeline.points
            val keep = PathScene.decimate(points.size, MAX_PATH_VERTICES)
            val pathDistances = DoubleArray(keep.size) { k -> timeline.distanceOfPoint(keep[k]) }

            return SurveyLayer(
                stations = layerStations,
                chordCount = chordEnds.size / 2,
                chordCoords = flatten(chordEnds),
                stretchCount = stretch.size,
                stretchCoords = flatten(stretch),
                cursor = cursorNs?.let { timeline.positionAt(it) },
                pathCount = keep.size,
                pathCoords = flatten(keep.map { points[it].p }),
                pathDistances = pathDistances,
            )
        }

        private fun flatten(vertices: List<Vec3>): DoubleArray {
            val out = DoubleArray(vertices.size * 3)
            for (i in vertices.indices) {
                val v = vertices[i]
                out[i * 3] = v.x
                out[i * 3 + 1] = v.y
                out[i * 3 + 2] = v.z
            }
            return out
        }
    }
}
```

**Step 5: Run the test to see it pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.stastyle.imumapper.render.SurveyLayerTest'`

Expected: `BUILD SUCCESSFUL` (only failing tests print). Confirm all eight ran:

```bash
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-com.stastyle.imumapper.render.SurveyLayerTest.xml
```

prints `tests="8" skipped="0" failures="0" errors="0"`.

**Step 6: Commit**

```bash
git add app/src/test/kotlin/com/stastyle/imumapper/SurveyFixtures.kt \
  app/src/main/kotlin/com/stastyle/imumapper/render/SurveyLayer.kt \
  app/src/test/kotlin/com/stastyle/imumapper/render/SurveyLayerTest.kt
git commit -F - <<'EOF'
Build the survey overlay: stations, chords, the highlighted stretch and the cursor

Survey mode draws its stations and selection over the path scene with the same camera. Keeping them
in a small layer of their own means a tap or an edit rebuilds only this, never the scene of thousands
of segments. The layer also carries a decimated copy of the path with the distance of each vertex, so
a tap on the path can be turned back into a moment of the walk.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

---

### Task 16: Survey projection and hit tests

**Files:**
- Create: `app/src/main/kotlin/com/stastyle/imumapper/render/ProjectedSurvey.kt`
- Test: `app/src/test/kotlin/com/stastyle/imumapper/render/ProjectedSurveyTest.kt`

How the test's numbers come about: the TOP preset fitted to the L walk's bounds (0..5 x 0..10 m) at
1080 x 1920 px puts the eye 23.79 m above the centre (2.5, 5, 0) looking straight down, north up, at
about 86.5 px per metre. So the stations are at least 5 m = 216 px apart (far outside the 24 px station
radius), a tap 10 px beside a leg is inside the 32 px path radius and nowhere near another leg, and a tap
500 px right of the start is more than 500 px from every station and segment. Exact screen positions
are not hard-coded: the test asks `Projector` for them, which is the claim under test. A pan of 50 px
moves every point 50 px, because `panned` shifts the target by 50 x metresPerPixel and at that depth one
metre is exactly 1 / metresPerPixel pixels.

**Step 1: Write the failing test**

Create `app/src/test/kotlin/com/stastyle/imumapper/render/ProjectedSurveyTest.kt`:

```kotlin
package com.stastyle.imumapper.render

import com.stastyle.imumapper.SurveyFixtures
import com.stastyle.imumapper.SurveyFixtures.tNs
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
```

**Step 2: Run the test to see it fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.stastyle.imumapper.render.ProjectedSurveyTest'`

Expected: `BUILD FAILED` in `:app:compileDebugUnitTestKotlin`, with
`e: file:///.../render/ProjectedSurveyTest.kt:...: Unresolved reference 'ProjectedSurvey'.` and
`Unresolved reference 'SurveyHit'.` among the errors.

**Step 3: Write the implementation**

Create `app/src/main/kotlin/com/stastyle/imumapper/render/ProjectedSurvey.kt`:

```kotlin
package com.stastyle.imumapper.render

/** What a survey tap or long press landed on. Stations lie on the path, so a station always wins. */
sealed interface SurveyHit {
    data class OnStation(val stationId: Int) : SurveyHit
    data class OnPath(val distanceM: Double) : SurveyHit
    data object Miss : SurveyHit
}

/**
 * Screen-space copy of a SurveyLayer, refilled on each camera or viewport change (like ProjectedScene).
 * Buffers are allocated once per layer; a survey edit builds a new layer and so a new projection, while
 * a camera move only refills these arrays.
 */
class ProjectedSurvey(val layer: SurveyLayer) {
    /** 2 floats per station. */
    val stationScreen = FloatArray(layer.stations.size * 2)
    val stationVisible = BooleanArray(layer.stations.size)

    /** 4 floats per chord: ax, ay, bx, by. */
    val chordScreen = FloatArray(layer.chordCount * 4)

    /** Both ends in front of the near plane. Chords span a few stations, so they are dropped rather than clipped. */
    val chordVisible = BooleanArray(layer.chordCount)

    /** 2 floats per stretch vertex. */
    val stretchScreen = FloatArray(layer.stretchCount * 2)
    val stretchVisible = BooleanArray(layer.stretchCount)

    val cursorScreen = FloatArray(2)
    var cursorVisible: Boolean = false
        private set

    /** 2 floats per hit-test vertex. */
    val pathScreen = FloatArray(layer.pathCount * 2)
    val pathVisible = BooleanArray(layer.pathCount)

    private var lastCamera: OrbitCamera? = null
    private var lastWidth = -1f
    private var lastHeight = -1f
    private val tmp = FloatArray(3)

    /** Re-projects when camera or viewport changed; true when work was done. */
    fun update(camera: OrbitCamera, widthPx: Float, heightPx: Float): Boolean {
        if (camera == lastCamera && widthPx == lastWidth && heightPx == lastHeight) return false
        lastCamera = camera
        lastWidth = widthPx
        lastHeight = heightPx
        val projector = Projector(camera, widthPx, heightPx)

        for (i in layer.stations.indices) {
            val p = layer.stations[i].position
            stationVisible[i] = project(projector, p.x, p.y, p.z, stationScreen, i * 2)
        }
        val cc = layer.chordCoords
        for (i in 0 until layer.chordCount) {
            val o = i * 6
            chordVisible[i] = project(projector, cc[o], cc[o + 1], cc[o + 2], chordScreen, i * 4) &&
                project(projector, cc[o + 3], cc[o + 4], cc[o + 5], chordScreen, i * 4 + 2)
        }
        projectVertices(projector, layer.stretchCoords, layer.stretchCount, stretchScreen, stretchVisible)
        projectVertices(projector, layer.pathCoords, layer.pathCount, pathScreen, pathVisible)
        val c = layer.cursor
        cursorVisible = c != null && project(projector, c.x, c.y, c.z, cursorScreen, 0)
        return true
    }

    /** Index into layer.stations nearest on screen within [radiusPx], or -1. */
    fun hitTestStation(xPx: Float, yPx: Float, radiusPx: Float): Int {
        val r2 = radiusPx * radiusPx
        var best = -1
        var bestD2 = Float.POSITIVE_INFINITY
        for (i in stationVisible.indices) {
            if (!stationVisible[i]) continue
            val dx = stationScreen[i * 2] - xPx
            val dy = stationScreen[i * 2 + 1] - yPx
            val d2 = dx * dx + dy * dy
            if (d2 <= r2 && d2 < bestD2) {
                bestD2 = d2
                best = i
            }
        }
        return best
    }

    /** Distance along the path of the nearest point on a visible screen segment within [radiusPx], or -1.0. */
    fun hitTestPath(xPx: Float, yPx: Float, radiusPx: Float): Double {
        val r2 = radiusPx * radiusPx
        var best = -1.0
        var bestD2 = Float.POSITIVE_INFINITY
        for (k in 0 until layer.pathCount - 1) {
            if (!pathVisible[k] || !pathVisible[k + 1]) continue
            val ax = pathScreen[k * 2]
            val ay = pathScreen[k * 2 + 1]
            val abx = pathScreen[k * 2 + 2] - ax
            val aby = pathScreen[k * 2 + 3] - ay
            val len2 = abx * abx + aby * aby
            // A segment that is a dot on screen (standing still, or seen end-on) is hit at its start.
            val f = if (len2 > 0f) (((xPx - ax) * abx + (yPx - ay) * aby) / len2).coerceIn(0f, 1f) else 0f
            val dx = ax + abx * f - xPx
            val dy = ay + aby * f - yPx
            val d2 = dx * dx + dy * dy
            if (d2 <= r2 && d2 < bestD2) {
                bestD2 = d2
                val from = layer.pathDistances[k]
                best = from + (layer.pathDistances[k + 1] - from) * f
            }
        }
        return best
    }

    /** Station within STATION_HIT_DP, else path within PATH_HIT_DP, else Miss; radii scaled by [density]. */
    fun hitTest(xPx: Float, yPx: Float, density: Float): SurveyHit {
        val station = hitTestStation(xPx, yPx, STATION_HIT_DP * density)
        if (station >= 0) return SurveyHit.OnStation(layer.stations[station].id)
        val distance = hitTestPath(xPx, yPx, PATH_HIT_DP * density)
        return if (distance >= 0.0) SurveyHit.OnPath(distance) else SurveyHit.Miss
    }

    /** Projector writes x, y and depth; the buffers here keep only x and y, so it goes through [tmp]. */
    private fun project(projector: Projector, x: Double, y: Double, z: Double, out: FloatArray, offset: Int): Boolean {
        if (!projector.project(x, y, z, tmp, 0)) return false
        out[offset] = tmp[0]
        out[offset + 1] = tmp[1]
        return true
    }

    private fun projectVertices(
        projector: Projector,
        coords: DoubleArray,
        count: Int,
        screen: FloatArray,
        visible: BooleanArray,
    ) {
        for (i in 0 until count) {
            val o = i * 3
            visible[i] = project(projector, coords[o], coords[o + 1], coords[o + 2], screen, i * 2)
        }
    }

    companion object {
        /** Same as PathRenderer.HIT_RADIUS_DP, repeated because this file stays free of Compose. */
        const val STATION_HIT_DP: Float = 24f

        /** Wider than a station's so a tap need not land on a thin line. */
        const val PATH_HIT_DP: Float = 32f
    }
}
```

**Step 4: Run the test to see it pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.stastyle.imumapper.render.ProjectedSurveyTest'`

Expected: `BUILD SUCCESSFUL`, and

```bash
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-com.stastyle.imumapper.render.ProjectedSurveyTest.xml
```

prints `tests="7" skipped="0" failures="0" errors="0"`. Also re-run the whole render package, which must
stay green: `./gradlew :app:testDebugUnitTest --tests 'com.stastyle.imumapper.render.*'` gives
`BUILD SUCCESSFUL`.

**Step 5: Commit**

```bash
git add app/src/main/kotlin/com/stastyle/imumapper/render/ProjectedSurvey.kt \
  app/src/test/kotlin/com/stastyle/imumapper/render/ProjectedSurveyTest.kt
git commit -F - <<'EOF'
Tell a survey tap on a station from a tap on the path, and find where along the path it landed

Stations lie on the path, so the station test runs first and wins. The path is hit within a wider
32 dp, since a line is harder to land on than a dot, and the hit comes back as a distance along the
path, which the viewer turns into a moment of the walk.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

---

### Task 17: Draw the survey layer

**Files:**
- Create: `app/src/main/kotlin/com/stastyle/imumapper/render/SurveyRenderer.kt`

Compose drawing only; there is no failing-test step. The logic it relies on (which station is selected,
its chain number, which vertices are visible) is already tested in Tasks 15 and 16. Drawing order: the
translucent stretch first (one `Path`, so overlapping segment ends do not show as darker beads), dashed
chords over a dark underlay, the cursor (ring plus a cross that reaches past it), filled stations with a
white ring (bigger and thicker when selected), then all text. Names are placed only through
`PathRenderer.labelOrigin` (`.claude/rules/app.md`: a `drawText` origin past the right or bottom edge
throws). The chain number is drawn from a pre-measured `TextLayoutResult`, which lays nothing out against
the canvas edge, and only while the station centre is on the canvas.

**Step 1: Write the renderer**

Create `app/src/main/kotlin/com/stastyle/imumapper/render/SurveyRenderer.kt`:

```kotlin
package com.stastyle.imumapper.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Draws a [ProjectedSurvey] over the path scene with the same camera. The stretch goes under
 * everything so the path shows through it, the chords and the cursor over it, and the stations and
 * their names last, so a selection never hides what was selected.
 */
object SurveyRenderer {
    private const val STRETCH_WIDTH_DP = 10f
    private const val CHORD_WIDTH_DP = 2f
    private const val CHORD_UNDERLAY_DP = 4f
    private const val CHORD_DASH_DP = 8f
    private const val CHORD_GAP_DP = 6f
    private const val CURSOR_RADIUS_DP = 11f
    private const val CURSOR_ARM_DP = 16f
    private const val CURSOR_WIDTH_DP = 2f
    private const val STATION_RADIUS_DP = 6f
    private const val SELECTED_RADIUS_DP = 10f
    private const val RING_DP = 1.5f
    private const val SELECTED_RING_DP = 2.5f

    private val stretchColor = Color(SurveyColors.STRETCH)
    private val chordColor = Color(SurveyColors.CHORD)

    /** A dark line under each dashed chord keeps it readable on the yellow stretch and the pale grid. */
    private val chordUnderlayColor = Color(0x99000000)
    private val cursorColor = Color(SurveyColors.CURSOR)
    private val ringColor = Color.White
    private val nameStyle = TextStyle(
        color = Color(SurveyColors.LABEL),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        shadow = Shadow(color = Color.Black, offset = Offset(0f, 1f), blurRadius = 3f),
    )

    /** Dark on the station's own colour: every station colour is light enough for it. */
    private val orderStyle = TextStyle(color = PathRenderer.BACKGROUND, fontSize = 11.sp, fontWeight = FontWeight.Bold)

    /** Draws stretch, chords, cursor, stations and names over the scene; call after update(). */
    fun DrawScope.drawSurvey(projected: ProjectedSurvey, textMeasurer: TextMeasurer?) {
        drawStretch(projected)
        drawChords(projected)
        drawCursor(projected)
        drawStations(projected)
        if (textMeasurer != null) drawStationText(projected, textMeasurer)
    }

    private fun DrawScope.drawStretch(projected: ProjectedSurvey) {
        val count = projected.layer.stretchCount
        if (count < 2) return
        val screen = projected.stretchScreen
        // One path rather than a line per segment: the band is translucent, and overlapping segment
        // ends would show as darker beads at every vertex.
        val path = Path()
        var open = false
        for (i in 0 until count) {
            if (!projected.stretchVisible[i]) {
                open = false
                continue
            }
            val x = screen[i * 2]
            val y = screen[i * 2 + 1]
            if (open) path.lineTo(x, y) else path.moveTo(x, y)
            open = true
        }
        drawPath(
            path = path,
            color = stretchColor,
            style = Stroke(width = STRETCH_WIDTH_DP.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }

    private fun DrawScope.drawChords(projected: ProjectedSurvey) {
        val count = projected.layer.chordCount
        if (count == 0) return
        val screen = projected.chordScreen
        val width = CHORD_WIDTH_DP.dp.toPx()
        val underlay = CHORD_UNDERLAY_DP.dp.toPx()
        val dash = PathEffect.dashPathEffect(floatArrayOf(CHORD_DASH_DP.dp.toPx(), CHORD_GAP_DP.dp.toPx()))
        for (i in 0 until count) {
            if (!projected.chordVisible[i]) continue
            val o = i * 4
            val a = Offset(screen[o], screen[o + 1])
            val b = Offset(screen[o + 2], screen[o + 3])
            drawLine(chordUnderlayColor, a, b, strokeWidth = underlay, cap = StrokeCap.Round)
            drawLine(chordColor, a, b, strokeWidth = width, pathEffect = dash)
        }
    }

    private fun DrawScope.drawCursor(projected: ProjectedSurvey) {
        if (!projected.cursorVisible) return
        val x = projected.cursorScreen[0]
        val y = projected.cursorScreen[1]
        val width = CURSOR_WIDTH_DP.dp.toPx()
        val arm = CURSOR_ARM_DP.dp.toPx()
        drawCircle(cursorColor, CURSOR_RADIUS_DP.dp.toPx(), Offset(x, y), style = Stroke(width = width))
        // The cross reaches past the ring, so the exact spot still shows when a station is drawn over it.
        drawLine(cursorColor, Offset(x - arm, y), Offset(x + arm, y), strokeWidth = width)
        drawLine(cursorColor, Offset(x, y - arm), Offset(x, y + arm), strokeWidth = width)
    }

    private fun DrawScope.drawStations(projected: ProjectedSurvey) {
        val stations = projected.layer.stations
        val screen = projected.stationScreen
        for (i in stations.indices) {
            if (!projected.stationVisible[i]) continue
            val station = stations[i]
            val center = Offset(screen[i * 2], screen[i * 2 + 1])
            val radius = radiusOf(station)
            val ring = (if (station.selected) SELECTED_RING_DP else RING_DP).dp.toPx()
            drawCircle(Color(station.color), radius, center)
            drawCircle(ringColor, radius, center, style = Stroke(width = ring))
        }
    }

    /**
     * Names go through PathRenderer.labelOrigin like the scene's labels, so drawText never gets an
     * origin past the right or bottom edge. The chain number is measured first and drawn from that
     * layout, which lays nothing out against the canvas edge; it is drawn only while the station's
     * centre is on the canvas, a positive test that a NaN fails as well. Text is drawn after every
     * circle so no station covers another one's name.
     */
    @OptIn(ExperimentalTextApi::class)
    private fun DrawScope.drawStationText(projected: ProjectedSurvey, textMeasurer: TextMeasurer) {
        val stations = projected.layer.stations
        val screen = projected.stationScreen
        for (i in stations.indices) {
            if (!projected.stationVisible[i]) continue
            val station = stations[i]
            val x = screen[i * 2]
            val y = screen[i * 2 + 1]
            if (station.order > 0 && x >= 0f && y >= 0f && x < size.width && y < size.height) {
                val layout = textMeasurer.measure(station.order.toString(), orderStyle)
                drawText(layout, topLeft = Offset(x - layout.size.width / 2f, y - layout.size.height / 2f))
            }
            val origin = PathRenderer.labelOrigin(x + radiusOf(station), y, size.width, size.height) ?: continue
            drawText(textMeasurer = textMeasurer, text = station.name, topLeft = origin, style = nameStyle)
        }
    }

    private fun DrawScope.radiusOf(station: LayerStation): Float =
        (if (station.selected) SELECTED_RADIUS_DP else STATION_RADIUS_DP).dp.toPx()
}
```

**Step 2: Compile**

Run: `./gradlew :app:assembleDebug`

Expected: `BUILD SUCCESSFUL` with no `e:` lines. `drawSurvey` has no caller until Task 18; that is fine
(no warning for an unused public member).

**Step 3: Commit**

```bash
git add app/src/main/kotlin/com/stastyle/imumapper/render/SurveyRenderer.kt
git commit -F - <<'EOF'
Draw survey stations, chords, the highlighted stretch and the cursor over the path

Station names are placed through PathRenderer.labelOrigin like the scene's labels, because drawText
throws when its origin lies past the right or bottom edge. The chain number is measured before it is
drawn, so it never asks for a layout against the edge at all.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

---

### Task 18: Canvas gestures: survey taps, long press and orbit lock

**Files:**
- Modify: `app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/ViewerCanvas.kt` (currently 150 lines;
  edits at lines 11-12, 20-21, 33-35, 40-55, 63-68, 76-80, 92-98, 130, 148-150; about 78 lines added and
  8 removed, 220 lines after)

What changes and why (architecture decisions 1 to 3):

- `CanvasGestures` gains `onSurveyTap` and `onSurveyLongPress`, defaulted to `{}` so `ViewerScreen`
  compiles unchanged.
- `ViewerCanvas` gains `survey: SurveyLayer? = null` and `orbitLocked: Boolean = false`, remembers one
  `ProjectedSurvey` per layer, and draws it after the scene with `SurveyRenderer.drawSurvey`.
- In Survey mode (`survey != null`) a tap goes only through `ProjectedSurvey.hitTest` (station, then path,
  else `Miss`); scene markers are not tested. The double-tap logic is untouched, so a double tap still
  fits and its first tap is still delivered.
- The long press lives inside the existing `awaitEachGesture` loop: while Survey mode is on, one pointer
  has ever been down, the timer has not run out yet in this gesture and the finger has moved less than
  `touchSlop`, the next event is awaited with the member `withTimeoutOrNull(longPressTimeoutMillis -
  elapsed)`; a null result (or a non-positive remainder) ends the timer. When the finger is on a station
  or the path, that is the long press, and every later event is consumed until all fingers are up, so the
  same gesture never also orbits, pans, zooms, taps or double-taps. On empty map (`SurveyHit.Miss`) it is
  not a long press: the design defines one only on the path and on a station, and the common "touch,
  pause, then drag" must still pan.
- `orbitLocked` (read through `rememberUpdatedState`) turns one-finger drags into `onPan`; two-finger pan
  and pinch are unchanged.
- Survey hit tests first call `ProjectedSurvey.update` with the latest camera and the pointer-input
  `size`: a tap that just rebuilt the layer creates a new, not yet drawn, projection. `update` returns at
  once when nothing changed.

Do not import `kotlinx.coroutines.withTimeoutOrNull`: inside `awaitEachGesture` the call must resolve to
the `AwaitPointerEventScope` member, which is the one that cooperates with pointer dispatch.

**Step 1: Edit `ViewerCanvas.kt`**

Apply these nine exact replacements (old text first, new text second). If an old snippet does not match,
stop: the file changed since this plan was written.

1a. Imports, first block. Old:

```kotlin
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Paint
```

New:

```kotlin
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Paint
```

1b. Imports, render block. Old:

```kotlin
import com.stastyle.imumapper.render.ProjectedScene
import com.stastyle.imumapper.render.SceneModel
```

New:

```kotlin
import com.stastyle.imumapper.render.ProjectedScene
import com.stastyle.imumapper.render.ProjectedSurvey
import com.stastyle.imumapper.render.SceneModel
import com.stastyle.imumapper.render.SurveyHit
import com.stastyle.imumapper.render.SurveyLayer
import com.stastyle.imumapper.render.SurveyRenderer
```

1c. `CanvasGestures`. Old:

```kotlin
    /** Marker index in the scene, or -1 when the tap hit nothing. */
    val onTap: (markerIndex: Int) -> Unit,
)
```

New:

```kotlin
    /** Marker index in the scene, or -1 when the tap hit nothing. Outside Survey mode only. */
    val onTap: (markerIndex: Int) -> Unit,
    /** Survey mode: what a tap hit. */
    val onSurveyTap: (SurveyHit) -> Unit = {},
    /** Survey mode: the station or path a long press hit; a pause on empty map is not one, so never Miss. */
    val onSurveyLongPress: (SurveyHit) -> Unit = {},
)
```

1d. KDoc, signature and remembered state. Old:

```kotlin
 * awaitPointerEventScope so the pointer count decides what a drag means.
 */
@Composable
fun ViewerCanvas(
    scene: SceneModel?,
    camera: OrbitCamera,
    selectedMarker: Int,
    gestures: CanvasGestures,
    modifier: Modifier = Modifier,
) {
    val projected = remember(scene) { scene?.let { ProjectedScene(it) } }
    val textMeasurer = rememberTextMeasurer()
    val paint = remember { Paint() }
    val density = LocalDensity.current.density
    val latestGestures = rememberUpdatedState(gestures)
    val latestProjected = rememberUpdatedState(projected)
```

New:

```kotlin
 * awaitPointerEventScope so the pointer count decides what a drag means. In Survey mode ([survey] set)
 * the layer is drawn over the scene, taps and long presses are resolved against its stations and
 * path, and the long press is timed inside the same gesture loop so it can never also orbit or tap.
 */
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
) {
    val projected = remember(scene) { scene?.let { ProjectedScene(it) } }
    // A survey edit rebuilds only the layer, so only this small projection is re-created.
    val projectedSurvey = remember(survey) { survey?.let { ProjectedSurvey(it) } }
    val textMeasurer = rememberTextMeasurer()
    val paint = remember { Paint() }
    val density = LocalDensity.current.density
    val latestGestures = rememberUpdatedState(gestures)
    val latestProjected = rememberUpdatedState(projected)
    val latestSurvey = rememberUpdatedState(projectedSurvey)
    val latestCamera = rememberUpdatedState(camera)
    val latestOrbitLocked = rememberUpdatedState(orbitLocked)
```

1e. Pointer-input set-up. Old:

```kotlin
                val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
                val slop = viewConfiguration.touchSlop
                var lastTapTime = 0L
                var lastTapX = 0f
                var lastTapY = 0f
                awaitEachGesture {
```

New:

```kotlin
                val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
                val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                val slop = viewConfiguration.touchSlop
                var lastTapTime = 0L
                var lastTapX = 0f
                var lastTapY = 0f

                // Projects before testing: the layer the previous tap rebuilt may not have been drawn yet.
                fun surveyHit(position: Offset): SurveyHit {
                    val p = latestSurvey.value ?: return SurveyHit.Miss
                    p.update(latestCamera.value, size.width.toFloat(), size.height.toFloat())
                    return p.hitTest(position.x, position.y, density)
                }

                awaitEachGesture {
```

1f. Head of the event loop: the long-press timer and what follows a long press. Old:

```kotlin
                    var lastChange: PointerInputChange = first
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
```

New:

```kotlin
                    var lastChange: PointerInputChange = first
                    var longPressed = false
                    // The timer runs once per gesture; a pause on empty map ends it without a long press.
                    var longPressTimed = false
                    while (true) {
                        val timing = latestSurvey.value != null && !longPressTimed &&
                            maxPointers == 1 && travelled < slop
                        val event = if (timing) {
                            // One finger at rest in Survey mode: waiting for its next move times the long press.
                            val remaining = longPressTimeout - (lastChange.uptimeMillis - first.uptimeMillis)
                            if (remaining > 0L) withTimeoutOrNull(remaining) { awaitPointerEvent() } else null
                        } else {
                            awaitPointerEvent()
                        }
                        if (event == null) {
                            longPressTimed = true
                            val hit = surveyHit(lastChange.position)
                            // A pause on empty map is not a long press: the drag that follows still pans.
                            if (hit == SurveyHit.Miss) continue
                            longPressed = true
                            lastTapTime = 0L
                            latestGestures.value.onSurveyLongPress(hit)
                            continue
                        }
                        val pressed = event.changes.filter { it.pressed }
                        if (longPressed) {
                            // The press was answered: the rest of this gesture must not orbit, pan, zoom or tap.
                            for (c in pressed) c.consume()
                            if (pressed.isEmpty()) break
                            continue
                        }
                        if (pressed.isEmpty()) {
```

1g. Tap dispatch. Old:

```kotlin
                                    lastTapY = up.position.y
                                    val p = latestProjected.value
                                    val hit = if (p == null) -1 else {
                                        PathRenderer.hitTest(p, up.position.x, up.position.y, density)
                                    }
                                    latestGestures.value.onTap(hit)
                                }
```

New:

```kotlin
                                    lastTapY = up.position.y
                                    if (latestSurvey.value != null) {
                                        // Survey mode has no scene markers: stations and the path take the tap.
                                        latestGestures.value.onSurveyTap(surveyHit(up.position))
                                    } else {
                                        val p = latestProjected.value
                                        val hit = if (p == null) -1 else {
                                            PathRenderer.hitTest(p, up.position.x, up.position.y, density)
                                        }
                                        latestGestures.value.onTap(hit)
                                    }
                                }
```

1h. One-finger drag. Old:

```kotlin
                                if (dx != 0f || dy != 0f) latestGestures.value.onOrbit(dx, dy)
```

New:

```kotlin
                                if (dx != 0f || dy != 0f) {
                                    if (latestOrbitLocked.value) {
                                        latestGestures.value.onPan(dx, dy)
                                    } else {
                                        latestGestures.value.onOrbit(dx, dy)
                                    }
                                }
```

1i. Drawing. Old:

```kotlin
        with(PathRenderer) { drawScene(p, textMeasurer, selectedMarker, paint) }
    }
}
```

New:

```kotlin
        with(PathRenderer) { drawScene(p, textMeasurer, selectedMarker, paint) }
        val s = projectedSurvey ?: return@Canvas
        s.update(camera, size.width, size.height)
        with(SurveyRenderer) { drawSurvey(s, textMeasurer) }
    }
}
```

Check: `git diff --stat app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/ViewerCanvas.kt` shows about
78 insertions and 8 deletions, and no line of the file is longer than 120 columns
(`tr -d '\r' < app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/ViewerCanvas.kt | awk 'length > 120'`
prints nothing).

**Step 2: Compile**

Run: `./gradlew :app:assembleDebug`

Expected: `BUILD SUCCESSFUL`. `ViewerScreen.kt` is not touched and still compiles: it builds
`CanvasGestures` with named arguments and calls `ViewerCanvas` without the new parameters.

**Step 3: Run every app unit test**

Run: `./gradlew :app:testDebugUnitTest`

Expected: `BUILD SUCCESSFUL`; `grep -l '<failure' app/build/test-results/testDebugUnitTest/*.xml` prints
nothing. `ViewerViewModelTest`, `TripArchiveTest`, `TripImporterTest` and `DefaultTripProcessorTest` are
among them and must stay green.

**Step 4: Manual check (optional now, required after Task 29)**

Nothing passes `survey` until Task 29, so on a phone the viewer must behave exactly as before: one-finger
drag orbits, two-finger drag pans, pinch zooms, a tap on a marker opens its card, a tap on empty canvas
closes it, a double tap fits, and holding a finger still does nothing. Once Task 29 wires Survey mode,
check on a phone:

- One-finger drag pans the plan (the map follows the finger, no rotation); pinch and double tap still work.
- A tap on a station grows and rings it with the number 1; a second station gets 2 and a dashed chord.
- A tap on the path between stations highlights the stretch in translucent yellow, rings its end
  stations, draws its chord, and moves the pink cursor to the tapped spot.
- A tap on empty canvas changes nothing.
- Holding still on the path for about half a second adds a station (S1); lifting or dragging afterwards
  neither pans nor taps. Holding on a station opens its sheet.
- Holding still on empty map, then dragging, pans the plan; nothing is added and no sheet opens.
- Panning a station name against the right or bottom edge never crashes the viewer.
- Leaving Survey mode brings back orbiting and marker taps.

**Step 5: Commit**

```bash
git add app/src/main/kotlin/com/stastyle/imumapper/ui/viewer/ViewerCanvas.kt
git commit -F - <<'EOF'
Let the viewer canvas report survey taps and long presses, and pan with one finger in plan view

The long press is timed inside the existing gesture loop rather than by a second pointerInput, so a
press that became a long press can never also orbit, pan or count as a tap. Nothing passes the new
parameters yet, and their defaults keep the viewer as it was.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```
