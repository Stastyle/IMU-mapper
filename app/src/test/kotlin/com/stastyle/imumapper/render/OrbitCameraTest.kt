package com.stastyle.imumapper.render

import com.stastyle.imumapper.pipeline.core.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun orbitWrapsYawAndClampsPitch() {
        val cam = OrbitCamera(yawRad = 3.0, pitchRad = 1.5).orbited(1.0, 1.0)
        assertTrue(cam.yawRad > -PI && cam.yawRad <= PI)
        assertEquals(OrbitCamera.MAX_PITCH_RAD, cam.pitchRad, 1e-9)
        val low = cam.orbited(0.0, -10.0)
        assertEquals(OrbitCamera.MIN_PITCH_RAD, low.pitchRad, 1e-9)
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
