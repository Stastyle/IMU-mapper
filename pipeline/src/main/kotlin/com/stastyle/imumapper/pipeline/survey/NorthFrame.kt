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
