package com.stastyle.imumapper.pipeline.tuning

import kotlin.math.abs

/** One thing the walker knows compared with what the pipeline produced. */
class Deviation(
    val name: String,
    val expected: String,
    val actual: String,
    /** Size of the miss on a scale where 0 is perfect and 1 is a miss as large as the quantity itself. */
    val error: Double,
)

/**
 * How well a run matches the ground truth: a list of deviations and their mean. Only what the
 * walker filled in is scored, so two runs of the same walk are always scored on the same items
 * and their scores can be compared. Lower is better.
 */
class Score(val deviations: List<Deviation>) {
    val mean: Double? get() = if (deviations.isEmpty()) null else deviations.sumOf { it.error } / deviations.size

    companion object {
        fun of(truth: GroundTruth, m: TuningMetrics): Score {
            val out = ArrayList<Deviation>()
            val d = truth.distanceM
            if (d != null && d > 0.0) {
                out.add(
                    Deviation(
                        "Distance", ConfigSchema.num(d, 1) + " m", ConfigSchema.num(m.distanceM, 1) + " m",
                        abs(m.distanceM - d) / d,
                    ),
                )
            }
            if (truth.shape.closes) {
                val pct = m.closurePercent
                out.add(
                    Deviation(
                        "Closure", "0 m (ends at the start)",
                        ConfigSchema.num(m.closureM, 2) + " m" + (if (pct == null) "" else " (" + ConfigSchema.num(pct, 1) + " %)"),
                        if (pct == null) 1.0 else minOf(1.0, pct / 100.0),
                    ),
                )
            }
            val turns = when {
                truth.turnCount != null -> truth.turnCount
                truth.shape == WalkShape.STRAIGHT -> 0
                truth.shape == WalkShape.OUT_AND_BACK -> 1
                else -> null
            }
            if (turns != null) {
                val found = m.shape.turns.size
                out.add(Deviation("Turns", turns.toString(), found.toString(), abs(found - turns).toDouble() / maxOf(turns, 1)))
            }
            when (truth.shape) {
                WalkShape.STRAIGHT -> {
                    val s = m.straightness
                    out.add(
                        Deviation(
                            "Straightness", "1.00", if (s == null) "n/a" else ConfigSchema.num(s, 2),
                            if (s == null) 1.0 else (1.0 - s).coerceIn(0.0, 1.0),
                        ),
                    )
                }
                WalkShape.RECTANGLE -> {
                    val angles = m.shape.turns.map { abs(it.angleDeg) }
                    if (angles.isNotEmpty()) {
                        val meanMiss = angles.sumOf { abs(it - 90.0) } / angles.size
                        out.add(
                            Deviation(
                                "Turn angles", "90° each",
                                angles.joinToString(", ") { ConfigSchema.num(it, 0) + "°" },
                                minOf(1.0, meanMiss / 90.0),
                            ),
                        )
                    }
                }
                WalkShape.OUT_AND_BACK -> {
                    val first = m.shape.turns.firstOrNull()
                    if (first != null) {
                        out.add(
                            Deviation(
                                "Turn angle", "180°", ConfigSchema.num(abs(first.angleDeg), 0) + "°",
                                minOf(1.0, abs(abs(first.angleDeg) - 180.0) / 180.0),
                            ),
                        )
                    }
                }
                else -> Unit
            }
            val h = truth.heightChangeM
            if (h != null) {
                val scale = maxOf(abs(h), 1.0)
                out.add(
                    Deviation(
                        "Height change", ConfigSchema.num(h, 1) + " m", ConfigSchema.num(m.netHeightM, 1) + " m",
                        minOf(1.0, abs(m.netHeightM - h) / scale),
                    ),
                )
            }
            return Score(out)
        }
    }
}
