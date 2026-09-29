package com.stastyle.imumapper.debug

import com.stastyle.imumapper.pipeline.core.HeadingAxisMode
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.ui.debug.ConfigDraft
import com.stastyle.imumapper.ui.debug.ConfigField
import com.stastyle.imumapper.ui.debug.ConfigFields
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigFieldsTest {
    @Test
    fun roundTripsEveryField() {
        val original = PipelineConfig(
            strideLengthM = 0.75,
            weinbergK = 0.42,
            headingOffsetRad = Math.toRadians(-37.5),
            useMagnetometer = false,
            northFromCompass = false,
            magGateTolerance = 0.2,
            gyroBias = Vec3(0.001, -0.002, 0.0035),
            autoReorient = false,
            carryChangeTiltRad = Math.toRadians(40.0),
            carryChangeSettleS = 2.5,
            stepMinIntervalS = 0.25,
            stepMinSwing = 1.5,
            stepBandLowHz = 0.4,
            stepBandHighHz = 3.5,
            preferHardwareSteps = true,
            baroSmoothingS = 2.0,
            baroHoldWhenStill = false,
            baroConfirmSteps = 12,
            baroConfirmStepM = 0.035,
            baroMaxHeldM = 2.75,
            loopClosure = false,
            smoothingWindow = 5,
            pdrFallbackWhenTrackingLost = false,
            vioResamplePeriodS = 0.2,
        )
        val parsed = ConfigFields.parse(ConfigDraft.from(original))
        assertTrue(parsed.errors.isEmpty(), parsed.errors.toString())
        val back = assertNotNull(parsed.config)
        assertFalse(ConfigFields.differs(original, back))
        assertEquals(12, back.baroConfirmSteps)
        assertEquals(0.035, back.baroConfirmStepM, 1e-12)
        assertEquals(2.75, back.baroMaxHeldM, 1e-12)
        assertTrue(ConfigFields.differs(original, original.copy(smoothingWindow = 3)))
        assertTrue(ConfigFields.differs(original, original.copy(autoReorient = true)))
        assertTrue(ConfigFields.differs(original, original.copy(northFromCompass = true)))
        assertTrue(ConfigFields.differs(original, original.copy(baroConfirmSteps = 11)))
        assertTrue(ConfigFields.differs(original, original.copy(baroConfirmStepM = 0.03)))
        assertTrue(ConfigFields.differs(original, original.copy(baroMaxHeldM = 2.5)))
    }

    @Test
    fun heightConfirmFieldsTakeTheSchemaRanges() {
        val base = ConfigDraft.from(PipelineConfig())
        val edges = base
            .with(ConfigField.BARO_CONFIRM_STEPS, "50")
            .with(ConfigField.BARO_CONFIRM_STEP_M, "0,5")
            .with(ConfigField.BARO_MAX_HELD_M, "50")
        val high = assertNotNull(ConfigFields.parse(edges).config)
        assertEquals(50, high.baroConfirmSteps)
        assertEquals(0.5, high.baroConfirmStepM, 1e-12)
        assertEquals(50.0, high.baroMaxHeldM, 1e-12)
        val off = base
            .with(ConfigField.BARO_CONFIRM_STEPS, " 0 ")
            .with(ConfigField.BARO_CONFIRM_STEP_M, "0")
            .with(ConfigField.BARO_MAX_HELD_M, " 0 ")
        val low = assertNotNull(ConfigFields.parse(off).config)
        assertEquals(0, low.baroConfirmSteps)
        assertEquals(0.0, low.baroConfirmStepM, 0.0)
        assertEquals(0.0, low.baroMaxHeldM, 0.0)

        for ((steps, stepM, heldM) in listOf(Triple("51", "0.51", "50.01"), Triple("-1", "-0.01", "-0.1"))) {
            val parsed = ConfigFields.parse(
                base.with(ConfigField.BARO_CONFIRM_STEPS, steps)
                    .with(ConfigField.BARO_CONFIRM_STEP_M, stepM)
                    .with(ConfigField.BARO_MAX_HELD_M, heldM),
            )
            assertNull(parsed.config)
            assertEquals(
                setOf(ConfigField.BARO_CONFIRM_STEPS, ConfigField.BARO_CONFIRM_STEP_M, ConfigField.BARO_MAX_HELD_M),
                parsed.errors.keys,
            )
            assertEquals("Must be between 0 and 50", parsed.errors[ConfigField.BARO_CONFIRM_STEPS])
            assertEquals("Must be between 0 and 0.5", parsed.errors[ConfigField.BARO_CONFIRM_STEP_M])
            assertEquals("Must be between 0 and 50", parsed.errors[ConfigField.BARO_MAX_HELD_M])
        }
        for (text in listOf("abc", "", "NaN", "Infinity")) {
            val parsed = ConfigFields.parse(base.with(ConfigField.BARO_MAX_HELD_M, text))
            assertNull(parsed.config)
            assertEquals(mapOf(ConfigField.BARO_MAX_HELD_M to "Enter a number"), parsed.errors)
        }
        for (text in listOf("2.5", "5,0", "abc", "")) {
            val parsed = ConfigFields.parse(base.with(ConfigField.BARO_CONFIRM_STEPS, text))
            assertNull(parsed.config)
            assertEquals(mapOf(ConfigField.BARO_CONFIRM_STEPS to "Enter a whole number"), parsed.errors)
        }
    }

    @Test
    fun savingFromTheEditorKeepsTheHeightConfirmValues() {
        // A Debug save or run builds the config from the draft; values missing from it would fall to defaults.
        val saved = PipelineConfig(baroConfirmSteps = 0, baroConfirmStepM = 0.08, baroMaxHeldM = 4.0)
        val edited = ConfigDraft.from(saved).with(ConfigField.STRIDE_LENGTH_M, "0.9")
        val back = assertNotNull(ConfigFields.parse(edited).config)
        assertEquals(0, back.baroConfirmSteps)
        assertEquals(0.08, back.baroConfirmStepM, 1e-12)
        assertEquals(4.0, back.baroMaxHeldM, 1e-12)
        assertEquals("0.08", edited.text(ConfigField.BARO_CONFIRM_STEP_M))
        assertEquals("4", edited.text(ConfigField.BARO_MAX_HELD_M))
    }

    @Test
    fun headingAxisSurvivesTheEditor() {
        val original = PipelineConfig(headingAxis = HeadingAxisMode.CAMERA)
        val edited = ConfigDraft.from(original).with(ConfigField.HEADING_OFFSET_DEG, "12")
        val back = assertNotNull(ConfigFields.parse(edited).config)
        assertEquals(HeadingAxisMode.CAMERA, back.headingAxis)
        assertTrue(ConfigFields.differs(original, original.copy(headingAxis = HeadingAxisMode.FORWARD)))
    }

    @Test
    fun defaultsAreValid() {
        val parsed = ConfigFields.parse(ConfigDraft.from(PipelineConfig()))
        assertTrue(parsed.errors.isEmpty())
        assertFalse(ConfigFields.differs(PipelineConfig(), assertNotNull(parsed.config)))
    }

    @Test
    fun reportsBadValuesPerField() {
        val draft = ConfigDraft.from(PipelineConfig())
            .with(ConfigField.STRIDE_LENGTH_M, "abc")
            .with(ConfigField.SMOOTHING_WINDOW, "2.5")
            .with(ConfigField.STEP_BAND_HIGH_HZ, "0.2")
            .with(ConfigField.WEINBERG_K, "-1")
        val parsed = ConfigFields.parse(draft)
        assertNull(parsed.config)
        assertEquals(
            setOf(
                ConfigField.STRIDE_LENGTH_M,
                ConfigField.SMOOTHING_WINDOW,
                ConfigField.STEP_BAND_HIGH_HZ,
                ConfigField.WEINBERG_K,
            ),
            parsed.errors.keys,
        )
    }

    @Test
    fun acceptsCommaDecimalsAndWrapsHeading() {
        val draft = ConfigDraft.from(PipelineConfig())
            .with(ConfigField.STRIDE_LENGTH_M, "0,8")
            .with(ConfigField.HEADING_OFFSET_DEG, "270")
        val c = assertNotNull(ConfigFields.parse(draft).config)
        assertEquals(0.8, c.strideLengthM, 1e-12)
        assertEquals(Math.toRadians(-90.0), c.headingOffsetRad, 1e-12)
    }

    @Test
    fun numberTextIsCompactAndLocaleFree() {
        assertEquals("0.7", ConfigDraft.num(0.7))
        assertEquals("0", ConfigDraft.num(0.0))
        assertEquals("0", ConfigDraft.num(-0.0000001))
        assertEquals("-0.002", ConfigDraft.num(-0.002))
        assertEquals("3", ConfigDraft.num(3.0))
    }
}
