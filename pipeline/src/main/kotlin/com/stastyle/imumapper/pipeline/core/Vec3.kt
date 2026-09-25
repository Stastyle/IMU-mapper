package com.stastyle.imumapper.pipeline.core

import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/** Immutable 3-vector in metres (positions) or SI units (sensor values). */
@Serializable
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    operator fun div(s: Double) = Vec3(x / s, y / s, z / s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)

    infix fun dot(o: Vec3): Double = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    val length: Double get() = sqrt(x * x + y * y + z * z)
    val lengthSquared: Double get() = x * x + y * y + z * z

    fun normalized(): Vec3 {
        val l = length
        return if (l > 0.0) this / l else this
    }

    fun distanceTo(o: Vec3): Double = (this - o).length

    /** Component-wise linear interpolation: t = 0 gives this, t = 1 gives [o]. */
    fun lerp(o: Vec3, t: Double) = Vec3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val UNIT_X = Vec3(1.0, 0.0, 0.0)
        val UNIT_Y = Vec3(0.0, 1.0, 0.0)
        val UNIT_Z = Vec3(0.0, 0.0, 1.0)
        fun of(x: Float, y: Float, z: Float) = Vec3(x.toDouble(), y.toDouble(), z.toDouble())
    }
}
