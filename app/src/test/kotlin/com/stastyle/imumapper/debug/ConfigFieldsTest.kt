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
            magGateTolerance = 0.2,
            gyroBias = Vec3(0.001, -0.002, 0.0035),
            stepMinIntervalS = 0.25,
            stepMinSwing = 1.5,
            stepBandLowHz = 0.4,
            stepBandHighHz = 3.5,
            preferHardwareSteps = true,
            baroSmoothingS = 2.0,
            baroHoldWhenStill = false,
            loopClosure = false,
            smoothingWindow = 5,
            pdrFallbackWhenTrackingLost = false,
            vioResamplePeriodS = 0.2,
        )
        val parsed = ConfigFields.parse(ConfigDraft.from(original))
        assertTrue(parsed.errors.isEmpty(), parsed.errors.toString())
        val back = assertNotNull(parsed.config)
        assertFalse(ConfigFields.differs(original, back))
        assertTrue(ConfigFields.differs(original, original.copy(smoothingWindow = 3)))
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
