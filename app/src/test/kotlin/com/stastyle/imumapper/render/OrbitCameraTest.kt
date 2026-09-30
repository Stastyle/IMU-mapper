package com.stastyle.imumapper.render

import com.stastyle.imumapper.pipeline.core.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrbitCameraTest {
    private val width = 1080f
    private val height = 1920f

    @Test
    fun targetProjectsToViewportCentre() {
        val cam = OrbitCamera(target = Vec3(3.0, -2.0, 1.0), distance = 8.0, yawRad = 0.7, pitchRad = 0.4)
        val p = assertNotNull(Projector(cam, width, height).project(cam.target))
        assertEquals(width / 2f, p.x, 0.01f)
        assertEquals(height / 2f, p.y, 0.01f)
        assertEquals(8f, p.depth, 1e-4f)
    }

    @Test
    fun pointsInFrontProjectInsideViewportAndBehindAreDropped() {
        val cam = OrbitCamera(target = Vec3.ZERO, distance = 10.0, yawRad = 0.0, pitchRad = 0.0)
        val projector = Projector(cam, width, height)
        // Eye is 10 m south of the origin looking north; a point a little east projects right of centre.
        val east = assertNotNull(projector.project(Vec3(1.0, 0.0, 0.0)))
        assertTrue(east.x > width / 2f && projector.isInsideViewport(east.x, east.y))
        val up = assertNotNull(projector.project(Vec3(0.0, 0.0, 1.0)))
        assertTrue(up.y < height / 2f && projector.isInsideViewport(up.x, up.y))
        // Behind the eye: y < -10.
        assertNull(projector.project(Vec3(0.0, -12.0, 0.0)))
    }

    @Test
    fun depthGrowsAlongViewDirection() {
        val cam = OrbitCamera(target = Vec3.ZERO, distance = 10.0, yawRad = 0.0, pitchRad = 0.0)
        val projector = Projector(cam, width, height)
        val near = assertNotNull(projector.project(Vec3(0.0, -3.0, 0.0)))
        val far = assertNotNull(projector.project(Vec3(0.0, 5.0, 0.0)))
        assertTrue(near.depth < far.depth)
        assertEquals(7f, near.depth, 1e-4f)
        assertEquals(15f, far.depth, 1e-4f)
    }

    @Test
    fun topPresetShowsNorthUpAndEastRight() {
        val bounds = Bounds(Vec3(-5.0, -5.0, 0.0), Vec3(5.0, 5.0, 2.0))
        val cam = OrbitCamera().withPreset(CameraPreset.TOP, bounds, width, height)
        val projector = Projector(cam, width, height)
        val north = assertNotNull(projector.project(Vec3(0.0, 4.0, 1.0)))
        val east = assertNotNull(projector.project(Vec3(4.0, 0.0, 1.0)))
        val centre = assertNotNull(projector.project(Vec3(0.0, 0.0, 1.0)))
        assertTrue(north.y < centre.y, "north should be above the centre")
        assertTrue(abs(north.x - centre.x) < 0.01f)
        assertTrue(east.x > centre.x, "east should be right of the centre")
        assertTrue(abs(east.y - centre.y) < 0.01f)
    }

    @Test
    fun fitToBoundsKeepsEveryCornerOnScreen() {
        val bounds = Bounds(Vec3(-12.0, -3.0, -1.0), Vec3(20.0, 30.0, 4.0))
        for (preset in CameraPreset.entries) {
            val cam = OrbitCamera(yawRad = 1.1, pitchRad = 0.3).withPreset(preset, bounds, width, height)
            val projector = Projector(cam, width, height)
            assertEquals(bounds.center, cam.target)
            for (x in listOf(bounds.min.x, bounds.max.x)) {
                for (y in listOf(bounds.min.y, bounds.max.y)) {
                    for (z in listOf(bounds.min.z, bounds.max.z)) {
                        val p = assertNotNull(projector.project(Vec3(x, y, z)), "corner $x $y $z behind camera")
                        assertTrue(projector.isInsideViewport(p.x, p.y), "$preset corner $x $y $z at ${p.x},${p.y}")
                    }
                }
            }
        }
    }

    @Test
    fun noInsetFitsAsBefore() {
        val bounds = Bounds(Vec3(-12.0, -3.0, -1.0), Vec3(20.0, 30.0, 4.0))
        for ((w, h) in listOf(width to height, height to width)) {
            for (preset in CameraPreset.entries) {
                val start = OrbitCamera(yawRad = 1.1, pitchRad = 0.3)
                val cam = start.withPreset(preset, bounds, w, h)
                assertEquals(cam, start.withPreset(preset, bounds, w, h, bottomInsetPx = 0f))
                assertEquals(bounds.center, cam.target)
                // The fit the viewer always had: the bounding sphere inside the narrower field of view, plus 8 %.
                val fovX = 2.0 * atan(tan(cam.fovYRad / 2.0) * (w / h))
                val expected = bounds.radius / sin(min(cam.fovYRad, fovX) / 2.0) * 1.08
                assertEquals(expected, cam.distance, 1e-9 * expected)
            }
        }
    }

    @Test
    fun insetFitCentresAFlatPlanInTheBandAboveThePanel() {
        val plan = Bounds(Vec3(-12.0, -3.0, 0.0), Vec3(20.0, 30.0, 0.0))
        for (inset in listOf(300f, 700f, 1100f)) {
            val cam = OrbitCamera().withPreset(CameraPreset.TOP, plan, width, height, bottomInsetPx = inset)
            val projector = Projector(cam, width, height)
            val centre = assertNotNull(projector.project(plan.center))
            assertEquals(width / 2f, centre.x, 0.5f)
            assertEquals((height - inset) / 2f, centre.y, 0.5f)
            val ys = cornersOf(plan).map { assertNotNull(projector.project(it)).y }
            assertTrue(ys.min() >= 0f && ys.max() <= height - inset, "inset $inset: ${ys.min()}..${ys.max()}")
            assertEquals((height - inset) / 2f, (ys.min() + ys.max()) / 2f, 0.5f)
        }
    }

    @Test
    fun insetFitKeepsEveryCornerAboveThePanel() {
        val bounds = Bounds(Vec3(-12.0, -3.0, -1.0), Vec3(20.0, 30.0, 4.0))
        for ((w, h) in listOf(width to height, height to width)) {
            for (inset in listOf(200f, h * 0.4f, h * 0.7f)) {
                for (preset in CameraPreset.entries) {
                    val cam = OrbitCamera(yawRad = 1.1, pitchRad = 0.3).withPreset(preset, bounds, w, h, inset)
                    val projector = Projector(cam, w, h)
                    for (corner in cornersOf(bounds)) {
                        val p = assertNotNull(projector.project(corner), "$corner behind camera")
                        val where = "${w}x$h inset $inset $preset corner $corner at ${p.x},${p.y}"
                        assertTrue(p.x >= 0f && p.x <= w && p.y >= 0f && p.y <= h - inset, where)
                    }
                }
            }
        }
    }

    @Test
    fun anInsetTallerThanTheViewportStillLeavesAQuarterForThePlan() {
        val plan = Bounds(Vec3(-12.0, -3.0, 0.0), Vec3(20.0, 30.0, 0.0))
        val cam = OrbitCamera().withPreset(CameraPreset.TOP, plan, width, height, bottomInsetPx = 5000f)
        val projector = Projector(cam, width, height)
        val ys = cornersOf(plan).map { assertNotNull(projector.project(it)).y }
        assertTrue(ys.min() >= 0f && ys.max() <= height / 4f, "corners span ${ys.min()}..${ys.max()}")
    }

    @Test
    fun aChangedInsetMovesTheViewByHalfTheChange() {
        val cam = OrbitCamera(
            target = Vec3(2.0, 3.0, 0.0),
            distance = 30.0,
            yawRad = 0.4,
            pitchRad = OrbitCamera.MAX_PITCH_RAD,
        )
        val point = Vec3(5.0, -4.0, 0.0)
        val before = assertNotNull(Projector(cam, width, height).project(point))
        // The panel grows by 400 px: what was centred above it stays centred, 200 px higher, at the same zoom.
        val grown = cam.shiftedForInset(300f, 700f, height)
        val after = assertNotNull(Projector(grown, width, height).project(point))
        assertEquals(before.x, after.x, 0.01f)
        assertEquals(before.y - 200f, after.y, 0.01f)
        assertEquals(cam.distance, grown.distance)
        // Shrinking back returns the view to where it was, so a selection made and cleared does not drift it.
        val back = grown.shiftedForInset(700f, 300f, height)
        assertEquals(0.0, (back.target - cam.target).length, 1e-9)
        assertEquals(cam, cam.shiftedForInset(300f, 300f, height))
    }

    private fun cornersOf(bounds: Bounds): List<Vec3> = buildList {
        for (x in listOf(bounds.min.x, bounds.max.x)) {
            for (y in listOf(bounds.min.y, bounds.max.y)) {
                for (z in listOf(bounds.min.z, bounds.max.z)) add(Vec3(x, y, z))
            }
        }
    }

    @Test
    fun orbitWrapsYawAndClampsPitch() {
        val cam = OrbitCamera(yawRad = 3.0, pitchRad = 1.5).orbited(1.0, 1.0)
        assertTrue(cam.yawRad > -PI && cam.yawRad <= PI)
        assertEquals(OrbitCamera.MAX_PITCH_RAD, cam.pitchRad, 1e-9)
        val low = cam.orbited(0.0, -10.0)
        assertEquals(OrbitCamera.MIN_PITCH_RAD, low.pitchRad, 1e-9)
    }

    @Test
    fun topDownAfterTheTopPresetOrAnOrbitToTheClamp() {
        val bounds = Bounds(Vec3(-5.0, -5.0, 0.0), Vec3(5.0, 5.0, 2.0))
        val start = OrbitCamera()
        assertTrue(start.withPreset(CameraPreset.TOP, bounds, width, height).isTopDown)
        assertTrue(start.orbited(0.3, 10.0).isTopDown)
        // Pan and zoom keep the view straight down.
        val top = start.withPreset(CameraPreset.TOP, bounds, width, height)
        assertTrue(top.panned(40f, -25f, height).zoomed(1.7).isTopDown)
        // A rounding error short of the clamp is still straight down; a visible tilt is not.
        assertTrue(OrbitCamera(pitchRad = OrbitCamera.MAX_PITCH_RAD - 1e-9).isTopDown)
        assertFalse(OrbitCamera(pitchRad = OrbitCamera.MAX_PITCH_RAD - 0.01).isTopDown)
    }

    @Test
    fun notTopDownForThe3dAndSidePresets() {
        val bounds = Bounds(Vec3(-5.0, -5.0, 0.0), Vec3(5.0, 5.0, 2.0))
        val top = OrbitCamera().withPreset(CameraPreset.TOP, bounds, width, height)
        assertFalse(top.withPreset(CameraPreset.THREE_D, bounds, width, height).isTopDown)
        assertFalse(top.withPreset(CameraPreset.SIDE, bounds, width, height).isTopDown)
        assertFalse(OrbitCamera().isTopDown)
        // Tilting down from the top view leaves it.
        assertFalse(top.orbited(0.0, -0.1).isTopDown)
    }

    @Test
    fun zoomAndPanMoveTheCamera() {
        val cam = OrbitCamera(distance = 10.0, yawRad = 0.0, pitchRad = 0.0)
        assertEquals(5.0, cam.zoomed(2.0).distance, 1e-9)
        assertEquals(OrbitCamera.MIN_DISTANCE, cam.zoomed(1e9).distance, 1e-9)
        assertEquals(cam, cam.zoomed(0.0))
        // Dragging the scene to the right moves the target west (screen right is east at yaw 0).
        val panned = cam.panned(100f, 0f, height)
        assertTrue(panned.target.x < 0.0)
        assertEquals(0.0, panned.target.z, 1e-9)
        // Dragging down moves the scene down, so the target goes up.
        val down = cam.panned(0f, 100f, height)
        assertTrue(down.target.z > 0.0)
    }

    @Test
    fun boundsOfPointsAndEmpty() {
        assertEquals(Bounds.EMPTY, Bounds.of(emptyList()))
        val b = Bounds.of(listOf(Vec3(1.0, 2.0, 3.0), Vec3(-1.0, 5.0, 0.0)))
        assertEquals(Vec3(-1.0, 2.0, 0.0), b.min)
        assertEquals(Vec3(1.0, 5.0, 3.0), b.max)
        assertTrue(Bounds.of(listOf(Vec3.ZERO)).radius >= Bounds.MIN_RADIUS)
    }
}
