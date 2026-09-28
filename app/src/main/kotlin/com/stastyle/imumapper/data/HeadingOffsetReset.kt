package com.stastyle.imumapper.data

import com.stastyle.imumapper.pipeline.core.HeadingAxisMode

/**
 * Clears the saved heading offset once, for the release that takes north from the compass. Before
 * it, the heading calibration turned the calibration walk into +Y of that session's gyro frame, whose
 * yaw differs from one recording to the next, so a stored offset holds a random session angle rather
 * than how the phone sits relative to the walking direction. Applied to compass-referenced trips it
 * would turn every path by that random angle.
 */
object HeadingOffsetReset {

    /** Calibration note saved with the reset, so the Calibration screen shows why the offset changed. */
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
}
