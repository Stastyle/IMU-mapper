package com.stastyle.imumapper.ui.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import com.stastyle.imumapper.render.OrbitCamera
import com.stastyle.imumapper.render.PathRenderer
import com.stastyle.imumapper.render.ProjectedScene
import com.stastyle.imumapper.render.ProjectedSurvey
import com.stastyle.imumapper.render.SceneModel
import com.stastyle.imumapper.render.SurveyHit
import com.stastyle.imumapper.render.SurveyLayer
import com.stastyle.imumapper.render.SurveyRenderer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** What the canvas reports back; every callback runs on the main thread. */
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
    /** Survey mode's double-tap; its first tap already went to [onSurveyTap], which this may take back. */
    val onSurveyDoubleTap: () -> Unit = onDoubleTap,
    /** Survey mode: the station or path a long press hit; a pause on empty map is not one, so never Miss. */
    val onSurveyLongPress: (SurveyHit) -> Unit = {},
)

/**
 * The 3D view: a Canvas that re-projects the scene only when the camera or size changed, plus the
 * orbit / pinch / two-finger pan / tap / double-tap gestures written directly on top of
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

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(PathRenderer.BACKGROUND)
            .onSizeChanged { latestGestures.value.onViewport(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(Unit) {
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
                    val first = awaitFirstDown(requireUnconsumed = false)
                    var previousCount = 1
                    var previousX = first.position.x
                    var previousY = first.position.y
                    var previousSpread = 0f
                    var travelled = 0f
                    var maxPointers = 1
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
                            val up = event.changes.firstOrNull() ?: lastChange
                            if (maxPointers == 1 && travelled < slop) {
                                val now = up.uptimeMillis
                                val nearLast = abs(up.position.x - lastTapX) < slop * 4 &&
                                    abs(up.position.y - lastTapY) < slop * 4
                                if (now - lastTapTime < doubleTapTimeout && nearLast) {
                                    lastTapTime = 0L
                                    if (latestSurvey.value != null) {
                                        latestGestures.value.onSurveyDoubleTap()
                                    } else {
                                        latestGestures.value.onDoubleTap()
                                    }
                                } else {
                                    lastTapTime = now
                                    lastTapX = up.position.x
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
                            }
                            break
                        }
                        lastChange = pressed[0]
                        val count = pressed.size
                        var cx = 0f
                        var cy = 0f
                        for (c in pressed) {
                            cx += c.position.x
                            cy += c.position.y
                        }
                        cx /= count
                        cy /= count
                        var spread = 0f
                        if (count >= 2) {
                            for (c in pressed) {
                                val dx = c.position.x - cx
                                val dy = c.position.y - cy
                                spread += sqrt(dx * dx + dy * dy)
                            }
                            spread /= count
                        }
                        if (count != previousCount) {
                            // A finger went down or up: restart the deltas from the new centroid.
                            previousCount = count
                            maxPointers = max(maxPointers, count)
                        } else {
                            val dx = cx - previousX
                            val dy = cy - previousY
                            travelled += sqrt(dx * dx + dy * dy)
                            if (count == 1) {
                                if (dx != 0f || dy != 0f) {
                                    if (latestOrbitLocked.value) {
                                        latestGestures.value.onPan(dx, dy)
                                    } else {
                                        latestGestures.value.onOrbit(dx, dy)
                                    }
                                }
                            } else {
                                if (dx != 0f || dy != 0f) latestGestures.value.onPan(dx, dy)
                                if (previousSpread > 0f && spread > 0f && spread != previousSpread) {
                                    latestGestures.value.onZoom(spread / previousSpread)
                                }
                            }
                        }
                        previousX = cx
                        previousY = cy
                        previousSpread = spread
                        for (c in pressed) c.consume()
                    }
                }
            },
    ) {
        val p = projected ?: return@Canvas
        p.update(camera, size.width, size.height)
        with(PathRenderer) { drawScene(p, textMeasurer, selectedMarker, paint) }
        val s = projectedSurvey ?: return@Canvas
        s.update(camera, size.width, size.height)
        with(SurveyRenderer) { drawSurvey(s, textMeasurer) }
    }
}
