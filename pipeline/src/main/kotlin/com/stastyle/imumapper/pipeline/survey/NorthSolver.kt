package com.stastyle.imumapper.pipeline.survey

import com.stastyle.imumapper.pipeline.pdr.OrientationEstimator
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Where the rotation in use came from; only REFERENCES makes a relative-north run magnetic. */
enum class NorthSource { NONE, MANUAL, REFERENCES }

/** One compass reference after the solve. */
data class ReferenceFit(
    val referenceId: Int,
    /** Azimuth of the reference's line on the uncorrected path; null when its chord is under 0.3 m. */
    val measuredDeg: Double?,
    val horizontalM: Double,
    /** wrapDeg(R - M - rotation); null when measuredDeg is. */
    val residualDeg: Double?,
)

/** The rotation for the shown run and how well each compass reference agrees with it. */
data class NorthSolution(
    /** Degrees in (-180, 180]; positive turns the map clockwise. */
    val rotationDeg: Double,
    val source: NorthSource,
    val fits: List<ReferenceFit>,
) {
    /** References that entered the solve. */
    val usedCount: Int get() = fits.count { it.residualDeg != null }

    /** Two or more references and one misses by more than NorthSolver.DISAGREE_DEG. */
    val disagree: Boolean
        get() = usedCount >= 2 && fits.any { fit ->
            val residual = fit.residualDeg
            residual != null && abs(residual) > NorthSolver.DISAGREE_DEG
        }
}

/** Solves the north rotation from the survey's facts for whichever run is shown. */
object NorthSolver {
    const val DISAGREE_DEG: Double = 3.0

    /**
     * [plain] is the shown path before any rotation. With references: rotation = atan2(sum h sin(R-M),
     * sum h cos(R-M)) over references with a measured azimuth (source REFERENCES); when none can be
     * measured on [plain] the sums are empty, giving 0 with source NONE. Without references, the manual
     * rotation when manualApplies (source MANUAL, or NONE when it is 0), else 0 with source NONE.
     * Weighting by horizontal length lets a long, reliable reference outvote a short one.
     */
    fun solve(doc: SurveyDoc, plain: PathTimeline, runId: Int): NorthSolution {
        val measured = doc.references.map { ref ->
            Triple(ref, measuredDeg(ref, plain), Measure.leg(plain, ref.fromNs, ref.toNs).horizontalM)
        }
        var sumSin = 0.0
        var sumCos = 0.0
        var used = 0
        for ((ref, m, h) in measured) {
            if (m == null) continue
            val delta = Math.toRadians(forwardBearingDeg(ref) - m)
            sumSin += h * sin(delta)
            sumCos += h * cos(delta)
            used++
        }
        if (used > 0) {
            val rotation = SurveyAngles.wrapDeg(Math.toDegrees(atan2(sumSin, sumCos)))
            val fits = measured.map { (ref, m, h) ->
                ReferenceFit(ref.id, m, h, m?.let { SurveyAngles.wrapDeg(forwardBearingDeg(ref) - it - rotation) })
            }
            return NorthSolution(rotation, NorthSource.REFERENCES, fits)
        }
        val fits = measured.map { (ref, m, h) -> ReferenceFit(ref.id, m, h, null) }
        // References exist but none can be measured on this path: the design's atan2(0, 0) = 0. The manual
        // angle stays unused, since its steppers are disabled while references exist.
        if (doc.references.isNotEmpty()) return NorthSolution(0.0, NorthSource.NONE, fits)
        val manual = SurveyAngles.wrapDeg(doc.manualRotationDeg)
        return if (manual != 0.0 && manualApplies(doc, runId)) {
            NorthSolution(manual, NorthSource.MANUAL, fits)
        } else {
            NorthSolution(0.0, NorthSource.NONE, fits)
        }
    }

    /** The bearing from A to B: bearingDeg, plus 180 for a back-bearing, in [0, 360). */
    fun forwardBearingDeg(reference: CompassReference): Double =
        SurveyAngles.to360(reference.bearingDeg + if (reference.backBearing) 180.0 else 0.0)

    /** The reference's azimuth on [plain]: the chord's, or the fitted line's for ReferenceLine.FITTED. */
    fun measuredDeg(reference: CompassReference, plain: PathTimeline): Double? = when (reference.line) {
        ReferenceLine.CHORD -> Measure.leg(plain, reference.fromNs, reference.toNs).azimuthDeg
        ReferenceLine.FITTED -> Measure.fittedAzimuthDeg(plain, reference.fromNs, reference.toNs)
    }

    /** The manual rotation was set on [runId], or was never tagged. */
    fun manualApplies(doc: SurveyDoc, runId: Int): Boolean =
        doc.manualRotationRunId == null || doc.manualRotationRunId == runId

    /** A manual rotation exists, no reference does, and it was set on another run: the app asks first. */
    fun manualNeedsConfirmation(doc: SurveyDoc, runId: Int): Boolean =
        doc.manualRotationDeg != 0.0 && doc.references.isEmpty() && !manualApplies(doc, runId)

    /**
     * Azimuths are magnetic (suffix M): the run's northReference is MAGNETIC, or compass references were
     * applied. A manual rotation alone never makes a run magnetic, and a run without the key is R.
     */
    fun isMagnetic(diagnostics: Map<String, String>, solution: NorthSolution): Boolean =
        diagnostics[OrientationEstimator.NORTH_REFERENCE]?.trim() == OrientationEstimator.MAGNETIC ||
            solution.source == NorthSource.REFERENCES
}
