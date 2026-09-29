package com.stastyle.imumapper.pipeline.survey

import kotlin.math.atan2

/** Degree helpers for survey numbers (the pipeline's Angles works in radians). */
object SurveyAngles {

    /** Plan direction (dE, dN) as degrees clockwise from north in [0, 360): N 0, E 90, S 180, W 270. */
    fun azimuthDeg(dE: Double, dN: Double): Double = to360(Math.toDegrees(atan2(dE, dN)))

    /** Wraps into (-180, 180]; never returns -0.0. */
    fun wrapDeg(deg: Double): Double {
        var r = deg % 360.0
        if (r > 180.0) r -= 360.0 else if (r <= -180.0) r += 360.0
        // Adding 0.0 turns -0.0 into 0.0, so no "-0" reaches a label or the CSV.
        return r + 0.0
    }

    /** Wraps into [0, 360); never returns 360.0 or -0.0. */
    fun to360(deg: Double): Double {
        var r = deg % 360.0
        if (r < 0.0) r += 360.0
        // A tiny negative plus 360 rounds to 360.0 itself, which is north again.
        if (r >= 360.0) r = 0.0
        return r + 0.0
    }
}
