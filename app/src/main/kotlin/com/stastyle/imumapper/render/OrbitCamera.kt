package com.stastyle.imumapper.render

import com.stastyle.imumapper.pipeline.core.Vec3
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/** Axis-aligned box in ENU metres around the things the viewer shows. */
data class Bounds(val min: Vec3, val max: Vec3) {
    val center: Vec3 get() = (min + max) * 0.5
    val size: Vec3 get() = max - min

    /** Radius of the bounding sphere, never below [MIN_RADIUS] so a single point still gets a sensible camera. */
    val radius: Double get() = max(size.length * 0.5, MIN_RADIUS)

    fun expanded(margin: Double): Bounds =
        Bounds(min - Vec3(margin, margin, margin), max + Vec3(margin, margin, margin))

    fun union(other: Bounds): Bounds = Bounds(
        Vec3(min(min.x, other.min.x), min(min.y, other.min.y), min(min.z, other.min.z)),
        Vec3(max(max.x, other.max.x), max(max.y, other.max.y), max(max.z, other.max.z)),
    )

    companion object {
        const val MIN_RADIUS = 0.5

        /** A 1 m box around the origin, used when there is nothing to show yet. */
        val EMPTY = Bounds(Vec3(-0.5, -0.5, -0.5), Vec3(0.5, 0.5, 0.5))

        fun of(points: List<Vec3>): Bounds {
            if (points.isEmpty()) return EMPTY
            var minX = Double.POSITIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var minZ = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            var maxZ = Double.NEGATIVE_INFINITY
            for (p in points) {
                if (p.x < minX) minX = p.x
                if (p.y < minY) minY = p.y
                if (p.z < minZ) minZ = p.z
                if (p.x > maxX) maxX = p.x
                if (p.y > maxY) maxY = p.y
                if (p.z > maxZ) maxZ = p.z
            }
            return Bounds(Vec3(minX, minY, minZ), Vec3(maxX, maxY, maxZ))
        }
    }
}

enum class CameraPreset { THREE_D, TOP, SIDE }

/**
 * Orbit camera looking at [target] from [distance] metres away. [yawRad] rotates the eye around the
 * world up axis (0 = eye south of the target looking north, growing counter-clockwise seen from
 * above) and [pitchRad] is the elevation of the eye above the horizontal plane (pi/2 = straight
 * down). The basis is built analytically from the two angles rather than with cross products so the
 * straight-down top view is not degenerate: at pitch = pi/2 the screen's up direction is north.
 *
 * Immutable: gestures produce a new camera with the `orbited`, `zoomed`, `panned` and `fitted` helpers.
 * No Android or Compose types so it can be unit tested on the JVM.
 */
data class OrbitCamera(
    val target: Vec3 = Vec3.ZERO,
    val distance: Double = 10.0,
    val yawRad: Double = DEFAULT_YAW_RAD,
    val pitchRad: Double = DEFAULT_PITCH_RAD,
    val fovYRad: Double = DEFAULT_FOV_Y_RAD,
) {
    /** Unit vector from the target towards the eye. */
    private fun eyeDirection(): Vec3 {
        val cp = cos(pitchRad)
        return Vec3(-sin(yawRad) * cp, -cos(yawRad) * cp, sin(pitchRad))
    }

    fun eye(): Vec3 = target + eyeDirection() * distance

    /** Unit view direction (from the eye towards the target). */
    fun forward(): Vec3 = -eyeDirection()

    /** Unit vector pointing to the right of the screen, always horizontal. */
    fun right(): Vec3 = Vec3(cos(yawRad), -sin(yawRad), 0.0)

    /** Unit vector pointing to the top of the screen (right x forward). */
    fun up(): Vec3 {
        val sp = sin(pitchRad)
        return Vec3(sin(yawRad) * sp, cos(yawRad) * sp, cos(pitchRad))
    }

    fun orbited(deltaYawRad: Double, deltaPitchRad: Double): OrbitCamera = copy(
        yawRad = wrapAngle(yawRad + deltaYawRad),
        pitchRad = (pitchRad + deltaPitchRad).coerceIn(MIN_PITCH_RAD, MAX_PITCH_RAD),
    )

    /** [factor] > 1 moves the eye closer (pinch out), < 1 further away. */
    fun zoomed(factor: Double): OrbitCamera {
        if (factor <= 0.0 || factor.isNaN()) return this
        return copy(distance = (distance / factor).coerceIn(MIN_DISTANCE, MAX_DISTANCE))
    }

    /** Metres covered by one screen pixel in the plane through the target facing the camera. */
    fun metresPerPixel(viewportHeightPx: Float): Double {
        val h = max(viewportHeightPx.toDouble(), 1.0)
        return 2.0 * distance * tan(fovYRad / 2.0) / h
    }

    /**
     * Moves the target so the scene follows a finger dragged by ([dxPx], [dyPx]) screen pixels
     * (y down). The scene moves with the finger, so the target moves the opposite way.
     */
    fun panned(dxPx: Float, dyPx: Float, viewportHeightPx: Float): OrbitCamera {
        val mpp = metresPerPixel(viewportHeightPx)
        val shift = right() * (-dxPx * mpp) + up() * (dyPx * mpp)
        return copy(target = target + shift)
    }

    /** Keeps the orientation and moves the target and distance so the whole of [bounds] is on screen. */
    fun fitted(bounds: Bounds, viewportWidthPx: Float, viewportHeightPx: Float): OrbitCamera {
        val aspect = if (viewportHeightPx > 0f && viewportWidthPx > 0f) viewportWidthPx / viewportHeightPx else 1f
        val fovX = 2.0 * atan(tan(fovYRad / 2.0) * aspect)
        val halfFov = min(fovYRad, fovX) / 2.0
        val d = bounds.radius / sin(halfFov) * FIT_MARGIN
        return copy(target = bounds.center, distance = d.coerceIn(MIN_DISTANCE, MAX_DISTANCE))
    }

    fun withPreset(preset: CameraPreset, bounds: Bounds, viewportWidthPx: Float, viewportHeightPx: Float): OrbitCamera {
        val oriented = when (preset) {
            CameraPreset.THREE_D -> copy(yawRad = DEFAULT_YAW_RAD, pitchRad = DEFAULT_PITCH_RAD)
            CameraPreset.TOP -> copy(yawRad = 0.0, pitchRad = MAX_PITCH_RAD)
            // Eye east of the path looking west, so north is on the right and up is up.
            CameraPreset.SIDE -> copy(yawRad = -PI / 2.0, pitchRad = 0.0)
        }
        return oriented.fitted(bounds, viewportWidthPx, viewportHeightPx)
    }

    companion object {
        const val DEFAULT_YAW_RAD = 0.5
        const val DEFAULT_PITCH_RAD = 0.6
        const val DEFAULT_FOV_Y_RAD = 50.0 * PI / 180.0
        const val MIN_PITCH_RAD = -0.95 * PI / 2.0
        const val MAX_PITCH_RAD = PI / 2.0
        const val MIN_DISTANCE = 0.5
        const val MAX_DISTANCE = 5000.0
        private const val FIT_MARGIN = 1.08

        private fun wrapAngle(a: Double): Double {
            var r = a
            while (r > PI) r -= 2.0 * PI
            while (r <= -PI) r += 2.0 * PI
            return r
        }
    }
}

/** A world point on screen: pixel x, y (y down) and depth along the view direction in metres. */
data class ProjectedPoint(val x: Float, val y: Float, val depth: Float)

/**
 * Perspective projection of world points for one camera and viewport. The camera basis is computed
 * once here so projecting thousands of points costs a few multiplications each and allocates nothing
 * when the array overload is used.
 */
class Projector(val camera: OrbitCamera, val widthPx: Float, val heightPx: Float) {
    private val eye = camera.eye()
    private val right = camera.right()
    private val up = camera.up()
    private val forward = camera.forward()
    private val centerX = widthPx / 2f
    private val centerY = heightPx / 2f

    /** Pixels per metre at depth 1. */
    val focalPx: Float = (max(heightPx, 1f) / 2f / tan(camera.fovYRad / 2.0)).toFloat()

    /** Depth along the view direction, metres; positive when in front of the camera. */
    fun depthOf(x: Double, y: Double, z: Double): Double {
        val vx = x - eye.x
        val vy = y - eye.y
        val vz = z - eye.z
        return vx * forward.x + vy * forward.y + vz * forward.z
    }

    /**
     * Writes screen x, y and depth into [out] at [offset] (three floats). Returns false when the
     * point is behind the near plane; [out] is then left untouched.
     */
    fun project(x: Double, y: Double, z: Double, out: FloatArray, offset: Int): Boolean {
        val vx = x - eye.x
        val vy = y - eye.y
        val vz = z - eye.z
        val depth = vx * forward.x + vy * forward.y + vz * forward.z
        if (depth < NEAR_M) return false
        val cx = vx * right.x + vy * right.y + vz * right.z
        val cy = vx * up.x + vy * up.y + vz * up.z
        val scale = focalPx / depth
        out[offset] = (centerX + cx * scale).toFloat()
        out[offset + 1] = (centerY - cy * scale).toFloat()
        out[offset + 2] = depth.toFloat()
        return true
    }

    fun project(v: Vec3): ProjectedPoint? {
        val tmp = FloatArray(3)
        return if (project(v.x, v.y, v.z, tmp, 0)) ProjectedPoint(tmp[0], tmp[1], tmp[2]) else null
    }

    fun isInsideViewport(x: Float, y: Float): Boolean = x >= 0f && y >= 0f && x <= widthPx && y <= heightPx

    companion object {
        /** Points closer than this to the eye are not drawn; avoids division blow-ups. */
        const val NEAR_M = 0.05
    }
}
