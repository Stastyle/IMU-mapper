package com.stastyle.imumapper.data

import com.stastyle.imumapper.pipeline.core.HeadingAxisMode
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import kotlinx.coroutines.CancellationException
import kotlin.math.abs

/**
 * Clears the saved heading offset once, for the release that takes north from the compass. Before
 * it, the heading calibration turned the calibration walk into +Y of that session's gyro frame, whose
 * yaw differs from one recording to the next, so a stored offset holds a random session angle rather
 * than how the phone sits relative to the walking direction. Applied to compass-referenced trips it
 * would turn every path by that random angle.
 *
 * Every screen that saves a heading offset the user chose goes through [saveCalibration], which marks
 * the reset done as well: an offset calibrated after the update is the user's own, and a reset that
 * failed at the first start and runs again at a later one must not wipe it.
 */
object HeadingOffsetReset {

    /**
     * Calibration note saved with the reset. It is stored in the calibration row, which no screen
     * shows; it tells anyone reading the stored calibration why the offset changed, until the next
     * save with a note of its own replaces it.
     */
    const val NOTE = "Heading offset reset: north now comes from the compass"

    /**
     * Resets [CalibrationRepository]'s heading offset and axis, keeping every other value, unless
     * [isDone] says this already ran; then records that it ran with [markDone]. A failure before
     * [markDone] propagates, so the reset is tried again at the next start. Returns true when a
     * stored offset or axis was reset.
     */
    suspend fun runOnce(
        calibration: CalibrationRepository,
        isDone: suspend () -> Boolean,
        markDone: suspend () -> Unit,
    ): Boolean {
        if (isDone()) return false
        val config = calibration.getConfig()
        val stale = config.headingOffsetRad != 0.0 || config.headingAxis != HeadingAxisMode.AUTO
        if (stale) {
            calibration.saveConfig(config.copy(headingOffsetRad = 0.0, headingAxis = HeadingAxisMode.AUTO), NOTE)
        }
        markDone()
        return stale
    }

    /**
     * True when [saved] has another heading offset or axis than [previous]: a value the user chose. Offsets
     * within [OFFSET_TOLERANCE_RAD] count as the same, because the Debug editor shows the offset in degrees
     * with six decimals and parses it back, so saving there carries the stored offset along only nearly
     * unchanged.
     */
    fun changesOffset(previous: PipelineConfig, saved: PipelineConfig): Boolean =
        abs(saved.headingOffsetRad - previous.headingOffsetRad) > OFFSET_TOLERANCE_RAD ||
            saved.headingAxis != previous.headingAxis

    /** The same tolerance as the Debug editor's own change test (`ConfigFields.differs`). */
    const val OFFSET_TOLERANCE_RAD: Double = 1e-6

    /**
     * Saves [config] with [notes] as the calibration, and records the reset as done with [markDone] when
     * the heading offset in it is the user's own: [offsetMeasured] (the heading calibration just
     * measured it), or an offset or axis other than the stored one ([changesOffset]). A config that
     * only carries the stored offset along leaves the reset alone, so a stale offset is still reset.
     *
     * A failed save propagates and marks nothing. A failure of [markDone] is returned instead of thrown,
     * for the caller to log: the config is saved by then, and reporting the save as failed would be
     * wrong. Marking comes second so that a failure leaves the offset exposed to the reset (it falls
     * back to zero, visibly) rather than a stale random offset protected from it.
     */
    suspend fun saveCalibration(
        calibration: CalibrationRepository,
        config: PipelineConfig,
        notes: String,
        markDone: suspend () -> Unit,
        offsetMeasured: Boolean = false,
    ): Exception? {
        val previous = calibration.getConfig()
        calibration.saveConfig(config, notes)
        if (!offsetMeasured && !changesOffset(previous, config)) return null
        return try {
            markDone()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }
    }
}
