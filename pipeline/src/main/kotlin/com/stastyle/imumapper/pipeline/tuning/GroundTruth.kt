package com.stastyle.imumapper.pipeline.tuning

import kotlinx.serialization.Serializable

/** The overall shape of a tuning walk, as the walker describes it. */
enum class WalkShape(val label: String, val hint: String) {
    STRAIGHT("Straight line", "one direction, no turns"),
    OUT_AND_BACK("Out and back", "one 180° turn, ends where it started"),
    RECTANGLE("Rectangle", "90° turns, ends where it started"),
    CLOSED_LOOP("Closed loop", "any shape, ends where it started"),
    FREE("Free walk", "anything else; describe it in words"),
    ;

    /** True when the walk is supposed to end at its starting point. */
    val closes: Boolean get() = this == OUT_AND_BACK || this == RECTANGLE || this == CLOSED_LOOP
}

/**
 * What the walker knows about the walk that the sensors do not: the truth the pipeline output is
 * scored against. Every number is optional; the description carries whatever does not fit.
 */
@Serializable
data class GroundTruth(
    val shape: WalkShape = WalkShape.FREE,
    /** Distance actually walked, metres. */
    val distanceM: Double? = null,
    /** Number of turns of roughly 90° or more. */
    val turnCount: Int? = null,
    /** Height of the end point above the start, metres (negative when descending). */
    val heightChangeM: Double? = null,
    /** Free text: pace, stops, stairs, how the phone was carried, anything unusual. */
    val description: String = "",
) {
    val isEmpty: Boolean
        get() = shape == WalkShape.FREE && distanceM == null && turnCount == null && heightChangeM == null &&
            description.isBlank()
}
