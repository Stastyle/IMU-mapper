package com.stastyle.imumapper.render

import com.stastyle.imumapper.pipeline.core.AnnotationKind
import com.stastyle.imumapper.pipeline.core.PathAnnotation
import com.stastyle.imumapper.pipeline.core.PathKeyframe
import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.PathResult
import com.stastyle.imumapper.pipeline.core.PathStats
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.PositionSource
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.post.PathBuilder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PathSceneTest {

    /** A circle of [n] points walked in 60 s, rising 2 m, with a few annotations and a point cloud. */
    private fun result(n: Int, cloud: Int = 0, source: PositionSource = PositionSource.PDR): PathResult {
        val t0 = 1_000_000_000L
        val points = (0 until n).map { i ->
            val f = i.toDouble() / (n - 1)
            val a = f * 2 * PI
            PathPoint(
                tNs = t0 + (f * 60e9).toLong(),
                p = Vec3(5.0 * cos(a), 5.0 * sin(a), 2.0 * f),
                source = if (i % 2 == 0) source else PositionSource.VIO,
                headingRad = a,
            )
        }
        val pointCloud = (0 until cloud).map { i -> Vec3(i * 0.01, 1.0, 0.5) }
        return PathResult(
            pipelineVersion = 1,
            config = PipelineConfig(),
            points = points,
            annotations = listOf(
                PathAnnotation(t0 + 10_000_000_000L, AnnotationKind.JUNCTION, "left fork", Vec3(1.0, 1.0, 0.0)),
            ),
            keyframes = listOf(PathKeyframe(t0 + 20_000_000_000L, "kf-1.jpg", Vec3(2.0, 2.0, 0.5), 0.3)),
            pointCloud = pointCloud,
            stats = PathStats(distanceM = 31.4, durationS = 60.0, stepCount = 40, minZ = 0.0, maxZ = 2.0),
        )
    }

    @Test
    fun decimateKeepsEndsAndRespectsLimit() {
        assertContentEquals(intArrayOf(0, 1, 2), PathScene.decimate(3, 10))
        assertContentEquals(IntArray(0), PathScene.decimate(0, 10))
        val idx = PathScene.decimate(5000, 3000)
        assertEquals(3000, idx.size)
        assertEquals(0, idx.first())
        assertEquals(4999, idx.last())
        for (i in 1 until idx.size) assertTrue(idx[i] > idx[i - 1])
        val cloud = PathScene.decimate(100_000, 20_000)
        assertEquals(20_000, cloud.size)
        assertEquals(99_999, cloud.last())
    }

    @Test
    fun sceneDecimatesPathAndCloud() {
        val r = result(n = 5000, cloud = 60_000)
        val scene = PathScene.build(r, SceneOptions(maxPathSegments = 1000, maxCloudPoints = 20_000))
        assertEquals(20_000, scene.cloudCount)
        assertEquals(scene.cloudCount * 3, scene.cloudCoords.size)
        // Grid + axes + arrow are well under 500 lines for a 10 m path.
        assertTrue(scene.lineCount in 1000..1200, "lines ${scene.lineCount}")
        assertEquals(scene.lineCount * 6, scene.lineCoords.size)
        assertEquals(scene.lineCount, scene.lineColors.size)
        // Markers: start, one annotation, one keyframe, end.
        val expectedKinds = listOf(MarkerKind.START, MarkerKind.ANNOTATION, MarkerKind.KEYFRAME, MarkerKind.END)
        assertEquals(expectedKinds, scene.markers.map { it.kind })
        assertEquals("kf-1.jpg", scene.markers[2].fileName)
        assertEquals(10.0, scene.markers[1].elapsedS, 1e-9)
        assertTrue(scene.labels.any { it.text == "N" })
    }

    @Test
    fun togglesRemovePrimitives() {
        val r = result(n = 50, cloud = 100)
        val full = PathScene.build(r)
        val bare = PathScene.build(r, SceneOptions(showGrid = false, showPointCloud = false, showMarkers = false))
        assertTrue(bare.lineCount < full.lineCount)
        assertEquals(0, bare.cloudCount)
        assertTrue(bare.markers.isEmpty())
        // Without the grid only the axis triad and the path remain.
        assertEquals(3 + 49, bare.lineCount)
    }

    @Test
    fun colourModesDifferAndOverlayIsDimmer() {
        val r = result(n = 20)
        val byTime = PathScene.build(r, SceneOptions(showGrid = false))
        val bySource = PathScene.build(r, SceneOptions(showGrid = false, colorMode = ColorMode.SOURCE))
        val first = 3 // after the axis triad
        assertNotEquals(byTime.lineColors[first], bySource.lineColors[first])
        assertEquals(SceneColors.SOURCE_PDR, bySource.lineColors[first])
        assertEquals(SceneColors.SOURCE_VIO, bySource.lineColors[first + 1])
        assertEquals(SceneColors.TIME_STOPS.first(), byTime.lineColors[first])

        val withOverlay = PathScene.build(r, SceneOptions(showGrid = false), overlay = r)
        assertEquals(3 + 19 + 19, withOverlay.lineCount)
        assertEquals(PathScene.OVERLAY_ALPHA, SceneColors.alpha(withOverlay.lineColors[first]))
        assertEquals(0xFF, SceneColors.alpha(withOverlay.lineColors[first + 19]))
        assertTrue(withOverlay.lineWidths[first] < withOverlay.lineWidths[first + 19])
    }

    @Test
    fun gridSpacingGrowsWithExtent() {
        assertEquals(1.0, PathScene.gridSpacing(10.0))
        assertEquals(2.0, PathScene.gridSpacing(100.0))
        assertEquals(5.0, PathScene.gridSpacing(300.0))
        assertEquals(10.0, PathScene.gridSpacing(900.0))
    }

    @Test
    fun projectedSceneSortsBackToFrontAndCaches() {
        val r = result(n = 200, cloud = 500)
        val scene = PathScene.build(r)
        val projected = ProjectedScene(scene)
        val cam = OrbitCamera(yawRad = 0.4, pitchRad = 0.5).fitted(scene.bounds, 1080f, 1920f)
        assertTrue(projected.update(cam, 1080f, 1920f))
        assertTrue(projected.orderCount > 0)
        assertTrue(projected.cloudVisibleCount == 500)
        var previous = Float.NEGATIVE_INFINITY
        for (i in 0 until projected.orderCount) {
            val item = projected.orderItem(i)
            val depth = if (projected.isLineItem(item)) {
                projected.lineDepth[item]
            } else {
                projected.markerDepth[item - projected.lineCount]
            }
            assertTrue(depth >= previous, "order not ascending at $i")
            previous = depth
        }
        // Same camera again does nothing; a moved camera re-projects.
        assertTrue(!projected.update(cam, 1080f, 1920f))
        assertTrue(projected.update(cam.orbited(0.1, 0.0), 1080f, 1920f))
    }

    @Test
    fun lineWidthFadesWithDistance() {
        val r = result(n = 20)
        val scene = PathScene.build(r, SceneOptions(showGrid = false))
        val projected = ProjectedScene(scene)
        val cam = OrbitCamera(target = Vec3.ZERO, distance = 10.0, yawRad = 0.0, pitchRad = 0.0)
        projected.update(cam, 1080f, 1920f)
        // Compare two path segments: one on the near (south) side, one on the far (north) side.
        var nearest = -1
        var farthest = -1
        for (i in 3 until scene.lineCount) {
            if (!projected.lineVisible[i]) continue
            if (nearest < 0 || projected.lineDepth[i] < projected.lineDepth[nearest]) nearest = i
            if (farthest < 0 || projected.lineDepth[i] > projected.lineDepth[farthest]) farthest = i
        }
        assertTrue(nearest >= 0 && farthest >= 0)
        assertTrue(projected.lineWidth[nearest] > projected.lineWidth[farthest])
    }

    @Test
    fun hitTestFindsNearestMarkerWithinRadius() {
        val r = result(n = 20)
        val scene = PathScene.build(r, SceneOptions(showGrid = false))
        val projected = ProjectedScene(scene)
        val cam = OrbitCamera().withPreset(CameraPreset.TOP, scene.bounds, 1080f, 1920f)
        projected.update(cam, 1080f, 1920f)
        val endIndex = scene.markers.indexOfFirst { it.kind == MarkerKind.END }
        val x = projected.markerScreen[endIndex * 2]
        val y = projected.markerScreen[endIndex * 2 + 1]
        // Start and end coincide on a closed circle; the hit test still returns exactly one of them.
        val hit = projected.hitTestMarker(x + 5f, y - 5f, 24f)
        assertTrue(hit == endIndex || scene.markers[hit].kind == MarkerKind.START)
        assertEquals(-1, projected.hitTestMarker(x + 500f, y + 500f, 24f))
    }

    @Test
    fun linesCrossingTheNearPlaneAreClippedNotDropped() {
        // One 20 m line along north through the origin, and one entirely behind the camera.
        val scene = SceneModel(
            bounds = Bounds.EMPTY, lineCount = 2,
            lineCoords = doubleArrayOf(0.0, -10.0, 0.0, 0.0, 10.0, 0.0, 0.0, -10.0, 0.0, 0.0, -5.0, 0.0),
            lineColors = intArrayOf(0, 0), lineWidths = floatArrayOf(1f, 1f),
            cloudCount = 0, cloudCoords = DoubleArray(0), cloudColor = 0, markers = emptyList(), labels = emptyList(),
        )
        val projected = ProjectedScene(scene)
        // Eye 1 m south of the origin at 0.3 m height, looking north: the line's south end is behind it.
        val cam = OrbitCamera(target = Vec3(0.0, 0.0, 0.3), distance = 1.0, yawRad = 0.0, pitchRad = 0.0)
        val projector = Projector(cam, 1080f, 1920f)
        assertTrue(projector.depthOf(0.0, -10.0, 0.0) < 0.0)
        assertTrue(projector.depthOf(0.0, 10.0, 0.0) > 0.0)
        projected.update(cam, 1080f, 1920f)
        assertTrue(projected.lineVisible[0], "line crossing the near plane must survive")
        assertTrue(!projected.lineVisible[1], "line wholly behind the camera is culled")
        assertEquals(1, projected.orderCount)
        // The clipped end sits on the near plane, so the mean depth is halfway to the far end's depth.
        val farDepth = projector.depthOf(0.0, 10.0, 0.0)
        assertEquals((Projector.NEAR_M + farDepth) / 2, projected.lineDepth[0].toDouble(), 1e-3)
        // Both screen endpoints are finite and the clipped one lies below the far one (floor seen from above).
        val s = projected.lineScreen
        for (k in 0 until 4) assertTrue(s[k].isFinite(), "screen coord $k")
        assertTrue(s[1] > s[3], "near end of a floor line is lower on screen than the far end")
        assertTrue(s[1] > 1920f, "near end projects well below the viewport")
        assertEquals(540f, s[2], 1e-2f)
    }

    @Test
    fun clipFractionLandsJustInFrontOfNearPlane() {
        val t = ProjectedScene.clipFraction(-1.0, 3.0)
        val depth = -1.0 + (3.0 - -1.0) * t
        assertTrue(depth >= Projector.NEAR_M && depth < Projector.NEAR_M + 1e-5, "depth $depth")
        assertEquals(1.0, ProjectedScene.clipFraction(-1.0, Projector.NEAR_M))
    }

    /** A path through [positions], one point a second, with no annotations. */
    private fun pathThrough(positions: List<Vec3>): PathResult {
        val points = positions.mapIndexed { i, p -> PathPoint(i * 1_000_000_000L, p, PositionSource.PDR, 0.0) }
        return PathResult(
            pipelineVersion = 1,
            config = PipelineConfig(),
            points = points,
            stats = PathBuilder.stats(points, positions.size.toDouble(), 0, null),
        )
    }

    @Test
    fun theDefaultColourModeStaysTime() {
        assertEquals(ColorMode.TIME, SceneOptions().colorMode)
    }

    @Test
    fun progressColoursEachSegmentByTheShareOfTheLengthAtItsStart() {
        val scene = PathScene.build(result(n = 20), SceneOptions(showGrid = false, colorMode = ColorMode.PROGRESS))
        val first = 3 // after the axis triad
        assertEquals(3 + 19, scene.lineCount)
        assertEquals(SceneColors.PROGRESS_STOPS.first(), scene.lineColors[first])
        // The circle's 19 segments are equally long, so the last one starts 18/19 of the way along.
        val expectedLast = SceneColors.gradient(SceneColors.PROGRESS_STOPS, 18.0 / 19)
        assertEquals(expectedLast, scene.lineColors[scene.lineCount - 1])
    }

    @Test
    fun progressColoursTheOverlayByItsOwnLength() {
        val main = result(n = 20)
        // Half as long a run: its own fractions, not the main path's, set its colours.
        val overlay = pathThrough((0 until 11).map { Vec3(it * 0.5, 0.0, 0.0) })
        val scene = PathScene.build(main, SceneOptions(showGrid = false, colorMode = ColorMode.PROGRESS), overlay)
        val overlayFirst = 3
        val overlayLast = overlayFirst + 9
        val expectedLast = SceneColors.gradient(SceneColors.PROGRESS_STOPS, 0.9)
        assertEquals(SceneColors.withAlpha(expectedLast, PathScene.OVERLAY_ALPHA), scene.lineColors[overlayLast])
        assertEquals(SceneColors.PROGRESS_STOPS.first(), scene.lineColors[overlayLast + 1])
    }

    @Test
    fun aStandingPauseKeepsTheProgressColourButNotTheTimeColour() {
        // 10 m east, 20 s standing at the far end, then 10 m more.
        val positions = (0..10).map { Vec3(it.toDouble(), 0.0, 0.0) } +
            List(20) { Vec3(10.0, 0.0, 0.0) } +
            (1..10).map { Vec3(10.0 + it, 0.0, 0.0) }
        val r = pathThrough(positions)
        val options = SceneOptions(showGrid = false, showMarkers = false)
        val byProgress = PathScene.build(r, options.copy(colorMode = ColorMode.PROGRESS))
        val byTime = PathScene.build(r, options.copy(colorMode = ColorMode.TIME))
        // Segments starting at the first and the last standing point: points 10 and 30, lines 3 + 10 and 3 + 30.
        val arrive = 3 + 10
        val leave = 3 + 30
        assertEquals(byProgress.lineColors[arrive], byProgress.lineColors[leave])
        assertNotEquals(byTime.lineColors[arrive], byTime.lineColors[leave])
    }

    @Test
    fun coincidentPointsBuildUnderProgress() {
        val r = pathThrough(List(5) { Vec3(1.0, 1.0, 0.0) })
        val scene = PathScene.build(r, SceneOptions(colorMode = ColorMode.PROGRESS))
        val path = scene.pathLineStart until scene.pathLineEnd
        assertEquals(4, path.count())
        // Index fractions: the colours still run from the first stop to near the last.
        assertEquals(SceneColors.PROGRESS_STOPS.first(), scene.lineColors[path.first])
        assertEquals(SceneColors.gradient(SceneColors.PROGRESS_STOPS, 0.75), scene.lineColors[path.last])
        for (v in scene.lineCoords) assertTrue(v.isFinite())
    }

    @Test
    fun theGridSpacingIsNoLongerALabel() {
        val scene = PathScene.build(result(n = 50))
        assertTrue(scene.labels.none { it.text.contains("grid") }, "labels ${scene.labels.map { it.text }}")
        // The north arrow's N and the axis letters stay.
        assertEquals(listOf("N", "E", "N", "Up"), scene.labels.map { it.text })
        val bare = PathScene.build(result(n = 50), SceneOptions(showGrid = false))
        assertEquals(listOf("E", "N", "Up"), bare.labels.map { it.text })
    }

    @Test
    fun thePathLineRangeCoversExactlyTheMainPath() {
        val r = result(n = 20)
        val bare = PathScene.build(r, SceneOptions(showGrid = false))
        assertEquals(3, bare.pathLineStart)
        assertEquals(3 + 19, bare.pathLineEnd)
        assertEquals(bare.lineCount, bare.pathLineEnd)

        val withOverlay = PathScene.build(r, SceneOptions(showGrid = false), overlay = r)
        assertEquals(3 + 19, withOverlay.pathLineStart)
        assertEquals(3 + 19 + 19, withOverlay.pathLineEnd)
        assertTrue(!withOverlay.isPathLine(3 + 18), "the overlay's last line is not the main path")
        assertTrue(withOverlay.isPathLine(3 + 19))

        val withGrid = PathScene.build(r, overlay = r)
        assertEquals(withGrid.lineCount - 19, withGrid.pathLineStart)
        assertEquals(withGrid.lineCount, withGrid.pathLineEnd)
        // Only the main path is drawn opaque at the path width; grid, axes, arrow and overlay all differ.
        for (i in 0 until withGrid.lineCount) {
            val opaque = SceneColors.alpha(withGrid.lineColors[i]) == 0xFF
            val main = opaque && withGrid.lineWidths[i] == PathScene.PATH_WIDTH_DP
            assertEquals(main, withGrid.isPathLine(i), "line $i")
        }

        // A single point has no segment, so the range is empty.
        val single = PathScene.build(pathThrough(listOf(Vec3.ZERO)))
        assertEquals(single.pathLineStart, single.pathLineEnd)
        assertTrue((0 until single.lineCount).none { single.isPathLine(it) })
    }

    @Test
    fun aHandBuiltSceneHasNoPathLines() {
        val scene = SceneModel(
            bounds = Bounds.EMPTY, lineCount = 1, lineCoords = DoubleArray(6), lineColors = intArrayOf(0),
            lineWidths = floatArrayOf(1f), cloudCount = 0, cloudCoords = DoubleArray(0), cloudColor = 0,
            markers = emptyList(), labels = emptyList(),
        )
        assertTrue(!scene.isPathLine(0))
    }

    @Test
    fun theSceneTakesEveryColourFromItsPalette() {
        val r = result(n = 20, cloud = 10)
        val light = CanvasPalette.Light
        val scene = PathScene.build(r, SceneOptions(colorMode = ColorMode.SOURCE, palette = light))
        val first = scene.pathLineStart
        assertEquals(light.sourcePdr, scene.lineColors[first])
        assertEquals(light.sourceVio, scene.lineColors[first + 1])
        assertEquals(light.cloud, scene.cloudColor)
        assertEquals(listOf(light.start, light.junction, light.keyframe, light.end), scene.markers.map { it.color })
        assertEquals(
            listOf(light.northArrow, light.axisEast, light.axisNorth, light.axisUp),
            scene.labels.map { it.color },
        )
        val furniture = setOf(
            light.gridMinor, light.gridMajor, light.northArrow, light.axisEast, light.axisNorth, light.axisUp,
        )
        for (i in 0 until first) assertTrue(scene.lineColors[i] in furniture, "line $i")

        // The same geometry as on the dark canvas: a theme changes colours only.
        val dark = PathScene.build(r, SceneOptions(colorMode = ColorMode.SOURCE))
        assertContentEquals(dark.lineCoords, scene.lineCoords)
        assertContentEquals(dark.lineWidths, scene.lineWidths)
        assertEquals(dark.pathLineStart, scene.pathLineStart)

        val progressOptions = SceneOptions(showGrid = false, colorMode = ColorMode.PROGRESS, palette = light)
        val byProgress = PathScene.build(r, progressOptions)
        assertEquals(light.progressStops.first(), byProgress.lineColors[3])
        // A theme change is an option change, so the viewer's scene cache rebuilds on it.
        assertNotEquals(SceneOptions(palette = light), SceneOptions())
    }

    @Test
    fun aSelectedMarkerIsFoundAgainAfterAThemeChange() {
        val r = result(n = 20)
        val dark = PathScene.build(r)
        val light = PathScene.build(r, SceneOptions(palette = CanvasPalette.Light))
        // The marker the viewer kept from the dark scene differs from the light scene's copy in colour, so plain
        // equality would drop the selection and its card.
        assertEquals(-1, light.markers.indexOf(dark.markers[1]))
        for (i in dark.markers.indices) assertEquals(i, light.indexOfMarker(dark.markers[i]), "marker $i")
        assertEquals(1, dark.indexOfMarker(dark.markers[1]))
        // Only the colour is ignored: a marker that is not in the scene is still not found.
        assertEquals(-1, light.indexOfMarker(dark.markers[1].copy(detail = "right fork")))
        assertEquals(-1, light.indexOfMarker(dark.markers[2].copy(position = Vec3(9.0, 9.0, 9.0))))
    }

    @Test
    fun packOrdersByDepthThenIndex() {
        assertTrue(ProjectedScene.pack(1.5f, 7) < ProjectedScene.pack(2.0f, 3))
        assertTrue(ProjectedScene.pack(2.0f, 3) < ProjectedScene.pack(2.0f, 4))
        assertEquals(123456, (ProjectedScene.pack(9.0f, 123456) and 0xFFFFFFFFL).toInt())
    }
}
