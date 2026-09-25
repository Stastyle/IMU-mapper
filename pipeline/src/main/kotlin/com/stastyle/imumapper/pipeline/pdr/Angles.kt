package com.stastyle.imumapper.pipeline.pdr

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Angle helpers shared by the PDR classes. Headings are radians clockwise from north, (-pi, pi]. */
object Angles {
    const val TWO_PI: Double = 2.0 * PI

    /** Wraps [rad] into (-pi, pi]. */
    fun wrap(rad: Double): Double {
        var r = rad % TWO_PI
        if (r > PI) r -= TWO_PI else if (r <= -PI) r += TWO_PI
        return r
    }

    /** Circular mean of [angles] over [from, to); returns null when the window is empty or degenerate. */
    fun circularMean(angles: DoubleArray, from: Int, to: Int): Double? {
        var c = 0.0
        var s = 0.0
        for (i in from until to) {
            c += cos(angles[i])
            s += sin(angles[i])
        }
        if (from >= to || (c * c + s * s) < 1e-12) return null
        return atan2(s, c)
    }

    /** Smallest signed difference a - b, in (-pi, pi]. */
    fun diff(a: Double, b: Double): Double = wrap(a - b)
}
