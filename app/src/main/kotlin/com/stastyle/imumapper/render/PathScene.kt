package com.stastyle.imumapper.render

import com.stastyle.imumapper.pipeline.core.AnnotationKind
import com.stastyle.imumapper.pipeline.core.PathResult
import com.stastyle.imumapper.pipeline.core.PositionSource
import com.stastyle.imumapper.pipeline.core.Vec3
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

enum class ColorMode { TIME, ALTITUDE, SOURCE }

enum class MarkerKind { START, END, ANNOTATION, KEYFRAME }

/** A tappable point of interest on the path. [color] is ARGB. */
data class SceneMarker(
    val kind: MarkerKind,
    val position: Vec3,
    val tNs: Long,
    val elapsedS: Double,
    val title: String,
    val detail: String,
    val color: Int,
    val annotationKind: AnnotationKind? = null,
    /** Photo file inside the trip's photo directory, keyframes only. */
    val fileName: String? = null,
    val headingRad: Double = 0.0,
)

/** Short text anchored at a world position, e.g. the axis letters. [color] is ARGB. */
data class SceneLabel(val position: Vec3, val text: String, val color: Int)

data class SceneOptions(
    val colorMode: ColorMode = ColorMode.TIME,
    val showGrid: Boolean = true,
    val showPointCloud: Boolean = true,
    val showMarkers: Boolean = true,
    /** The path is decimated to this many segments so a drag stays smooth with 5 000+ points. */
    val maxPathSegments: Int = 3000,
    val maxCloudPoints: Int = 20000,
)

/**
 * Everything the renderer draws, in world (ENU) coordinates. Lines and cloud points live in flat
 * arrays so projecting them allocates nothing; markers and labels are few and stay as objects.
 */
class SceneModel(
    val bounds: Bounds,
    val lineCount: Int,
    /** 6 doubles per line: ax, ay, az, bx, by, bz. */
    val lineCoords: DoubleArray,
    /** ARGB per line. */
    val lineColors: IntArray,
    /** Base stroke width per line in dp; the projection scales it with distance. */
    val lineWidths: FloatArray,
    val cloudCount: Int,
    /** 3 doubles per cloud point. */
    val cloudCoords: DoubleArray,
    val cloudColor: Int,
    val markers: List<SceneMarker>,
    val labels: List<SceneLabel>,
)

/** ARGB colour helpers; Compose's Color is not available in pure Kotlin. */
object SceneColors {
    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    fun alpha(c: Int): Int = (c ushr 24) and 0xFF
    fun red(c: Int): Int = (c ushr 16) and 0xFF
    fun green(c: Int): Int = (c ushr 8) and 0xFF
    fun blue(c: Int): Int = c and 0xFF

    fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or ((a and 0xFF) shl 24)

    fun lerp(a: Int, b: Int, t: Double): Int {
        val f = t.coerceIn(0.0, 1.0)
        fun ch(x: Int, y: Int) = (x + (y - x) * f).roundToInt()
        return argb(ch(alpha(a), alpha(b)), ch(red(a), red(b)), ch(green(a), green(b)), ch(blue(a), blue(b)))
    }

    /** Colour at [t] in [0, 1] along a multi-stop gradient. */
    fun gradient(stops: IntArray, t: Double): Int {
        if (stops.isEmpty()) return 0
        if (stops.size == 1) return stops[0]
        val f = t.coerceIn(0.0, 1.0) * (stops.size - 1)
        val i = floor(f).toInt().coerceIn(0, stops.size - 2)
        return lerp(stops[i], stops[i + 1], f - i)
    }

    /** Blue -> cyan -> green -> yellow -> red, readable on the dark canvas. */
    val TIME_STOPS = intArrayOf(
        argb(255, 66, 133, 244),
        argb(255, 38, 198, 218),
        argb(255, 102, 187, 106),
        argb(255, 255, 214, 0),
        argb(255, 239, 83, 80),
    )

    /** Deep purple (low) -> orange -> pale yellow (high). */
    val ALTITUDE_STOPS = intArrayOf(
        argb(255, 94, 53, 177),
        argb(255, 30, 136, 229),
        argb(255, 67, 160, 71),
        argb(255, 251, 140, 0),
        argb(255, 255, 241, 118),
    )

    val SOURCE_PDR = argb(255, 255, 167, 38)
    val SOURCE_VIO = argb(255, 38, 198, 218)
    val SOURCE_INTERPOLATED = argb(255, 158, 158, 158)

    val START = argb(255, 76, 175, 80)
    val END = argb(255, 244, 67, 54)
    val KEYFRAME = argb(255, 100, 181, 246)
    val CLOUD = argb(80, 176, 190, 197)
    val GRID_MINOR = argb(40, 255, 255, 255)
    val GRID_MAJOR = argb(90, 255, 255, 255)
    val AXIS_EAST = argb(255, 229, 57, 53)
    val AXIS_NORTH = argb(255, 67, 160, 71)
    val AXIS_UP = argb(255, 66, 165, 245)
    val NORTH_ARROW = argb(255, 255, 193, 7)

    fun forSource(source: PositionSource): Int = when (source) {
        PositionSource.PDR -> SOURCE_PDR
        PositionSource.VIO -> SOURCE_VIO
        PositionSource.INTERPOLATED -> SOURCE_INTERPOLATED
    }

    fun forAnnotation(kind: AnnotationKind): Int = when (kind) {
        AnnotationKind.WAYPOINT -> argb(255, 255, 202, 40)
        AnnotationKind.JUNCTION -> argb(255, 171, 71, 188)
        AnnotationKind.CHAMBER -> argb(255, 38, 198, 218)
        AnnotationKind.NOTE -> argb(255, 236, 239, 241)
        AnnotationKind.LOOP_CLOSED -> argb(255, 102, 187, 106)
        AnnotationKind.REORIENT -> argb(255, 255, 112, 67)
    }
}

/** Growable flat line list used while building a [SceneModel]. */
internal class LineSink(initialCapacity: Int) {
    var count = 0
        private set
    private var coords = DoubleArray(max(initialCapacity, 16) * 6)
    private var colors = IntArray(max(initialCapacity, 16))
    private var widths = FloatArray(max(initialCapacity, 16))

    fun add(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double, color: Int, width: Float) {
        if (count == colors.size) grow()
        val o = count * 6
        coords[o] = ax
        coords[o + 1] = ay
        coords[o + 2] = az
        coords[o + 3] = bx
        coords[o + 4] = by
        coords[o + 5] = bz
        colors[count] = color
        widths[count] = width
        count++
    }

    fun add(a: Vec3, b: Vec3, color: Int, width: Float) = add(a.x, a.y, a.z, b.x, b.y, b.z, color, width)

    private fun grow() {
        val n = colors.size * 2
        coords = coords.copyOf(n * 6)
        colors = colors.copyOf(n)
        widths = widths.copyOf(n)
    }

    fun coords(): DoubleArray = coords.copyOf(count * 6)
    fun colors(): IntArray = colors.copyOf(count)
    fun widths(): FloatArray = widths.copyOf(count)
}

/** Turns a [PathResult] into a [SceneModel]. Pure and deterministic, so it is unit tested on the JVM. */
object PathScene {
    const val PATH_WIDTH_DP = 3.5f
    const val OVERLAY_WIDTH_DP = 2f
    const val GRID_WIDTH_DP = 0.8f
    const val AXIS_WIDTH_DP = 2f
    const val OVERLAY_ALPHA = 0x70

    /**
     * Indices to keep when at most [maxCount] of [count] items may be shown: exactly [maxCount]
     * evenly spread indices including the first and the last, so the end of the path is never cut
     * off. Returns all indices when nothing needs dropping.
     */
    fun decimate(count: Int, maxCount: Int): IntArray {
        if (count <= 0) return IntArray(0)
        val limit = max(maxCount, 2)
        if (count <= limit) return IntArray(count) { it }
        // A fractional step keeps the output at the limit instead of undershooting with an integer stride.
        val step = (count - 1).toDouble() / (limit - 1)
        val out = IntArray(limit) { k -> (k * step).roundToInt().coerceIn(0, count - 1) }
        out[limit - 1] = count - 1
        return out
    }

    /** Grid spacing in metres that keeps the number of lines readable for a path of [extentM]. */
    fun gridSpacing(extentM: Double): Double = when {
        extentM <= 60.0 -> 1.0
        extentM <= 150.0 -> 2.0
        extentM <= 400.0 -> 5.0
        extentM <= 1000.0 -> 10.0
        else -> 50.0
    }

    fun build(result: PathResult, options: SceneOptions = SceneOptions(), overlay: PathResult? = null): SceneModel {
        val positions = result.points.map { it.p }
        var bounds = Bounds.of(positions)
        if (overlay != null && overlay.points.isNotEmpty()) {
            bounds = bounds.union(Bounds.of(overlay.points.map { it.p }))
        }

        val lines = LineSink(result.points.size + (overlay?.points?.size ?: 0) + 256)
        val labels = ArrayList<SceneLabel>()
        val spacing = gridSpacing(max(bounds.size.x, bounds.size.y))
        if (options.showGrid) addGrid(lines, labels, bounds, spacing)
        addAxes(lines, labels, spacing)

        if (overlay != null) addPath(lines, overlay, options, OVERLAY_WIDTH_DP, OVERLAY_ALPHA)
        addPath(lines, result, options, PATH_WIDTH_DP, 0xFF)

        val markers = if (options.showMarkers) buildMarkers(result) else emptyList()

        val cloudIdx = if (options.showPointCloud) {
            decimate(result.pointCloud.size, options.maxCloudPoints)
        } else {
            IntArray(0)
        }
        val cloud = DoubleArray(cloudIdx.size * 3)
        for (k in cloudIdx.indices) {
            val p = result.pointCloud[cloudIdx[k]]
            cloud[k * 3] = p.x
            cloud[k * 3 + 1] = p.y
            cloud[k * 3 + 2] = p.z
        }

        return SceneModel(
            bounds = bounds,
            lineCount = lines.count,
            lineCoords = lines.coords(),
            lineColors = lines.colors(),
            lineWidths = lines.widths(),
            cloudCount = cloudIdx.size,
            cloudCoords = cloud,
            cloudColor = SceneColors.CLOUD,
            markers = markers,
            labels = labels,
        )
    }

    private fun addGrid(lines: LineSink, labels: MutableList<SceneLabel>, bounds: Bounds, spacing: Double) {
        // The floor sits on the lowest metre so the path never dips below the grid.
        val floorZ = floor(bounds.min.z / spacing) * spacing
        val minX = floor(bounds.min.x / spacing) * spacing - spacing
        val maxX = ceil(bounds.max.x / spacing) * spacing + spacing
        val minY = floor(bounds.min.y / spacing) * spacing - spacing
        val maxY = ceil(bounds.max.y / spacing) * spacing + spacing
        val nx = ((maxX - minX) / spacing).roundToInt()
        val ny = ((maxY - minY) / spacing).roundToInt()
        for (i in 0..nx) {
            val x = minX + i * spacing
            val major = (x / spacing).roundToInt() % 5 == 0
            val color = if (major) SceneColors.GRID_MAJOR else SceneColors.GRID_MINOR
            lines.add(x, minY, floorZ, x, maxY, floorZ, color, GRID_WIDTH_DP)
        }
        for (j in 0..ny) {
            val y = minY + j * spacing
            val major = (y / spacing).roundToInt() % 5 == 0
            val color = if (major) SceneColors.GRID_MAJOR else SceneColors.GRID_MINOR
            lines.add(minX, y, floorZ, maxX, y, floorZ, color, GRID_WIDTH_DP)
        }
        // North arrow in the south-east corner of the grid, clear of the path.
        val len = 2.0 * spacing
        val base = Vec3(maxX, minY, floorZ)
        val tip = Vec3(maxX, minY + len, floorZ)
        val head = spacing * 0.4
        lines.add(base, tip, SceneColors.NORTH_ARROW, AXIS_WIDTH_DP)
        lines.add(tip, Vec3(maxX - head, minY + len - head, floorZ), SceneColors.NORTH_ARROW, AXIS_WIDTH_DP)
        lines.add(tip, Vec3(maxX + head, minY + len - head, floorZ), SceneColors.NORTH_ARROW, AXIS_WIDTH_DP)
        labels.add(SceneLabel(Vec3(maxX, minY + len + head, floorZ), "N", SceneColors.NORTH_ARROW))
        val spacingText = if (spacing >= 1.0) "${spacing.roundToInt()} m grid" else "$spacing m grid"
        labels.add(SceneLabel(Vec3(minX, minY, floorZ), spacingText, SceneColors.GRID_MAJOR))
    }

    private fun addAxes(lines: LineSink, labels: MutableList<SceneLabel>, spacing: Double) {
        val len = max(1.0, spacing)
        val o = Vec3.ZERO
        lines.add(o, Vec3(len, 0.0, 0.0), SceneColors.AXIS_EAST, AXIS_WIDTH_DP)
        lines.add(o, Vec3(0.0, len, 0.0), SceneColors.AXIS_NORTH, AXIS_WIDTH_DP)
        lines.add(o, Vec3(0.0, 0.0, len), SceneColors.AXIS_UP, AXIS_WIDTH_DP)
        labels.add(SceneLabel(Vec3(len * 1.15, 0.0, 0.0), "E", SceneColors.AXIS_EAST))
        labels.add(SceneLabel(Vec3(0.0, len * 1.15, 0.0), "N", SceneColors.AXIS_NORTH))
        labels.add(SceneLabel(Vec3(0.0, 0.0, len * 1.15), "Up", SceneColors.AXIS_UP))
    }

    private fun addPath(lines: LineSink, result: PathResult, options: SceneOptions, width: Float, alpha: Int) {
        val pts = result.points
        if (pts.size < 2) return
        val idx = decimate(pts.size, options.maxPathSegments + 1)
        val t0 = pts.first().tNs
        val tSpan = max((pts.last().tNs - t0).toDouble(), 1.0)
        val zMin = result.stats.minZ
        val zSpan = max(result.stats.maxZ - zMin, 1e-6)
        for (k in 0 until idx.size - 1) {
            val a = pts[idx[k]]
            val b = pts[idx[k + 1]]
            val color = when (options.colorMode) {
                ColorMode.TIME -> SceneColors.gradient(SceneColors.TIME_STOPS, (a.tNs - t0) / tSpan)
                ColorMode.ALTITUDE -> SceneColors.gradient(SceneColors.ALTITUDE_STOPS, (a.p.z - zMin) / zSpan)
                ColorMode.SOURCE -> SceneColors.forSource(a.source)
            }
            lines.add(a.p, b.p, SceneColors.withAlpha(color, alpha), width)
        }
    }

    private fun buildMarkers(result: PathResult): List<SceneMarker> {
        val out = ArrayList<SceneMarker>()
        val pts = result.points
        if (pts.isEmpty()) return out
        val t0 = pts.first().tNs
        fun elapsed(tNs: Long) = (tNs - t0) / 1e9
        out.add(
            SceneMarker(
                kind = MarkerKind.START, position = pts.first().p, tNs = pts.first().tNs, elapsedS = 0.0,
                title = "Start", detail = "Trip origin", color = SceneColors.START,
            ),
        )
        for (a in result.annotations) {
            val kindName = a.kind.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
            out.add(
                SceneMarker(
                    kind = MarkerKind.ANNOTATION, position = a.p, tNs = a.tNs, elapsedS = elapsed(a.tNs),
                    title = kindName, detail = a.note, color = SceneColors.forAnnotation(a.kind),
                    annotationKind = a.kind,
                ),
            )
        }
        for (k in result.keyframes) {
            out.add(
                SceneMarker(
                    kind = MarkerKind.KEYFRAME, position = k.p, tNs = k.tNs, elapsedS = elapsed(k.tNs),
                    title = "Photo", detail = k.fileName, color = SceneColors.KEYFRAME, fileName = k.fileName,
                    headingRad = k.headingRad,
                ),
            )
        }
        // The end marker is added last so it is found first when it overlaps the start of a closed loop.
        out.add(
            SceneMarker(
                kind = MarkerKind.END, position = pts.last().p, tNs = pts.last().tNs,
                elapsedS = elapsed(pts.last().tNs), title = "End", detail = "Trip end", color = SceneColors.END,
            ),
        )
        return out
    }
}

/**
 * Screen-space copy of a [SceneModel] for one camera and viewport. Buffers are allocated once per
 * scene and refilled on every camera change; [update] is a no-op when nothing moved, so the draw
 * pass can call it unconditionally. Drawing order is back to front by depth.
 */
class ProjectedScene(val scene: SceneModel) {
    val lineCount: Int = scene.lineCount
    /** 4 floats per line: ax, ay, bx, by (pixels). */
    val lineScreen = FloatArray(lineCount * 4)
    val lineDepth = FloatArray(lineCount)
    /** Stroke width in dp after distance fading. */
    val lineWidth = FloatArray(lineCount)
    val lineVisible = BooleanArray(lineCount)

    val markerCount: Int = scene.markers.size
    /** 2 floats per marker. */
    val markerScreen = FloatArray(markerCount * 2)
    val markerDepth = FloatArray(markerCount)
    val markerVisible = BooleanArray(markerCount)

    val labelCount: Int = scene.labels.size
    val labelScreen = FloatArray(labelCount * 2)
    val labelVisible = BooleanArray(labelCount)

    /**
     * 2 floats per cloud point. Points behind the camera are parked far off screen instead of being
     * compacted, so the whole array can be handed to a single native drawPoints call.
     */
    val cloudScreen = FloatArray(scene.cloudCount * 2)
    var cloudVisibleCount = 0
        private set

    /**
     * Draw order, nearest first: each entry packs the depth's float bits in the high 32 bits and an
     * item index in the low 32, so a primitive LongArray sort orders by depth with no allocation.
     * Indices below [lineCount] are lines, the rest are markers offset by [lineCount].
     */
    val order = LongArray(lineCount + markerCount)
    var orderCount = 0
        private set

    var lastCamera: OrbitCamera? = null
        private set
    var lastWidth = -1f
        private set
    var lastHeight = -1f
        private set

    private val tmp = FloatArray(3)

    /** Re-projects everything when the camera or viewport changed. Returns true when work was done. */
    fun update(camera: OrbitCamera, widthPx: Float, heightPx: Float): Boolean {
        if (camera == lastCamera && widthPx == lastWidth && heightPx == lastHeight) return false
        lastCamera = camera
        lastWidth = widthPx
        lastHeight = heightPx
        val projector = Projector(camera, widthPx, heightPx)
        val reference = camera.distance.toFloat()
        val margin = max(widthPx, heightPx) * 0.5f
        orderCount = 0

        val lc = scene.lineCoords
        for (i in 0 until lineCount) {
            val o = i * 6
            var visible = projector.project(lc[o], lc[o + 1], lc[o + 2], tmp, 0)
            var ax = 0f
            var ay = 0f
            var da = 0f
            if (visible) {
                ax = tmp[0]
                ay = tmp[1]
                da = tmp[2]
                visible = projector.project(lc[o + 3], lc[o + 4], lc[o + 5], tmp, 0)
            }
            if (visible) {
                val bx = tmp[0]
                val by = tmp[1]
                val db = tmp[2]
                // Cheap cull: both ends well outside the viewport on the same side.
                visible = !(
                    (ax < -margin && bx < -margin) || (ax > widthPx + margin && bx > widthPx + margin) ||
                        (ay < -margin && by < -margin) || (ay > heightPx + margin && by > heightPx + margin)
                    )
                if (visible) {
                    val s = i * 4
                    lineScreen[s] = ax
                    lineScreen[s + 1] = ay
                    lineScreen[s + 2] = bx
                    lineScreen[s + 3] = by
                    val depth = (da + db) * 0.5f
                    lineDepth[i] = depth
                    val fade = (reference / depth).coerceIn(MIN_WIDTH_SCALE, MAX_WIDTH_SCALE)
                    lineWidth[i] = scene.lineWidths[i] * fade
                    order[orderCount++] = pack(depth, i)
                }
            }
            lineVisible[i] = visible
        }

        for (i in 0 until markerCount) {
            val p = scene.markers[i].position
            val visible = projector.project(p.x, p.y, p.z, tmp, 0)
            markerVisible[i] = visible
            if (visible) {
                markerScreen[i * 2] = tmp[0]
                markerScreen[i * 2 + 1] = tmp[1]
                markerDepth[i] = tmp[2]
                order[orderCount++] = pack(tmp[2], lineCount + i)
            }
        }

        for (i in 0 until labelCount) {
            val p = scene.labels[i].position
            val visible = projector.project(p.x, p.y, p.z, tmp, 0)
            labelVisible[i] = visible
            if (visible) {
                labelScreen[i * 2] = tmp[0]
                labelScreen[i * 2 + 1] = tmp[1]
            }
        }

        val cc = scene.cloudCoords
        cloudVisibleCount = 0
        for (i in 0 until scene.cloudCount) {
            val o = i * 3
            if (projector.project(cc[o], cc[o + 1], cc[o + 2], tmp, 0)) {
                cloudScreen[i * 2] = tmp[0]
                cloudScreen[i * 2 + 1] = tmp[1]
                cloudVisibleCount++
            } else {
                cloudScreen[i * 2] = OFFSCREEN
                cloudScreen[i * 2 + 1] = OFFSCREEN
            }
        }

        // Ascending sort = nearest first; the renderer walks it backwards to paint far things first.
        order.sort(0, orderCount)
        return true
    }

    /** Item index of the [i]-th entry in [order]: a line when below [lineCount], else marker index + lineCount. */
    fun orderItem(i: Int): Int = (order[i] and 0xFFFFFFFFL).toInt()

    fun isLineItem(item: Int): Boolean = item < lineCount

    /**
     * Index of the marker under ([xPx], [yPx]) within [radiusPx], preferring the one nearest the
     * camera when several overlap; -1 when none.
     */
    fun hitTestMarker(xPx: Float, yPx: Float, radiusPx: Float): Int {
        var best = -1
        var bestDepth = Float.POSITIVE_INFINITY
        val r2 = radiusPx * radiusPx
        for (i in 0 until markerCount) {
            if (!markerVisible[i]) continue
            val dx = markerScreen[i * 2] - xPx
            val dy = markerScreen[i * 2 + 1] - yPx
            if (dx * dx + dy * dy > r2) continue
            if (markerDepth[i] < bestDepth) {
                bestDepth = markerDepth[i]
                best = i
            }
        }
        return best
    }

    companion object {
        const val MIN_WIDTH_SCALE = 0.35f
        const val MAX_WIDTH_SCALE = 1.6f
        const val OFFSCREEN = -100000f

        /** Depth is always positive here, so its float bits order like the value itself. */
        fun pack(depth: Float, index: Int): Long = (depth.toBits().toLong() shl 32) or (index.toLong() and 0xFFFFFFFFL)
    }
}
