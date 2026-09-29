package com.stastyle.imumapper.ui.settings

import com.stastyle.imumapper.pipeline.core.PipelineConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** What the Settings stepper for steps to confirm a height change writes into the calibration. */
class ConfirmStepsSettingTest {

    /** A calibration with non-default values everywhere the step count must not touch. */
    private val calibrated = PipelineConfig(
        strideLengthM = 0.81,
        northFromCompass = false,
        baroSmoothingS = 2.5,
        baroHoldWhenStill = false,
        baroConfirmSteps = 5,
        baroConfirmStepM = 0.03,
    )

    @Test
    fun unchangedCountWritesNothing() {
        assertNull(withConfirmSteps(calibrated, 5))
        assertNull(withConfirmSteps(PipelineConfig(), PipelineConfig().baroConfirmSteps))
    }

    @Test
    fun changedCountKeepsEveryOtherValue() {
        val fewer = assertNotNull(withConfirmSteps(calibrated, 4))
        assertEquals(calibrated.copy(baroConfirmSteps = 4), fewer)
        val more = assertNotNull(withConfirmSteps(calibrated, 6))
        assertEquals(calibrated.copy(baroConfirmSteps = 6), more)
    }

    @Test
    fun zeroTurnsTheFilterOff() {
        assertEquals(0, assertNotNull(withConfirmSteps(calibrated, 0)).baroConfirmSteps)
    }

    @Test
    fun negativeCountIsClampedToOff() {
        assertEquals(calibrated.copy(baroConfirmSteps = 0), withConfirmSteps(calibrated, -1))
        assertEquals(calibrated.copy(baroConfirmSteps = 0), withConfirmSteps(calibrated, Int.MIN_VALUE))
        // Minus pressed while already off writes nothing.
        assertNull(withConfirmSteps(calibrated.copy(baroConfirmSteps = 0), -1))
    }

    @Test
    fun countsAboveTheStepperMaximumArePassedThrough() {
        // A value set in the Debug editor (up to 50) counts down one step at a time from the stepper.
        val high = calibrated.copy(baroConfirmSteps = 30)
        assertEquals(high.copy(baroConfirmSteps = 29), withConfirmSteps(high, 29))
    }
}
