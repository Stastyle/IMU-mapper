package com.stastyle.imumapper.pipeline.tuning

import com.stastyle.imumapper.pipeline.core.HeadingAxisMode
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.Vec3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProposalParserTest {

    private val base = PipelineConfig(strideLengthM = 0.72, headingAxis = HeadingAxisMode.FORWARD)

    private fun accepted(text: String, from: PipelineConfig = base): Proposal {
        val outcome = ProposalParser.parse(text, from)
        assertIs<ProposalOutcome.Accepted>(outcome, "expected acceptance, got " + (outcome as? ProposalOutcome.Rejected)?.errors)
        return outcome.proposal
    }

    private fun rejected(text: String): List<String> {
        val outcome = ProposalParser.parse(text, base)
        assertIs<ProposalOutcome.Rejected>(outcome, "expected rejection")
        return outcome.errors
    }

    @Test
    fun fullAnswerFormatAppliesOnlyTheGivenKeys() {
        val text = """
            Here is my analysis. The swing distribution shows a cluster near 1.0.

            ```json
            {
              "config": { "stepMinSwing": 1.4, "stepMinIntervalS": 0.35, "useMagnetometer": false },
              "changes": [
                { "key": "stepMinSwing", "value": 1.4, "reason": "cuts the tiny cycles counted while standing" },
                { "key": "useMagnetometer", "value": false, "reason": "gate passed 8 % of samples" }
              ],
              "reasoning": "Too many steps for 20 m.",
              "expected": "Step count drops by about 15 %."
            }
            ```
            Let me know how it goes.
        """.trimIndent()
        val p = accepted(text)
        assertEquals(1.4, p.config.stepMinSwing)
        assertEquals(0.35, p.config.stepMinIntervalS)
        assertEquals(false, p.config.useMagnetometer)
        assertEquals(0.72, p.config.strideLengthM, "untouched keys keep the base value")
        assertEquals(HeadingAxisMode.FORWARD, p.config.headingAxis)
        assertEquals(listOf("useMagnetometer", "stepMinIntervalS", "stepMinSwing"), p.changes.map { it.key })
        assertEquals("cuts the tiny cycles counted while standing", p.changes.first { it.key == "stepMinSwing" }.reason)
        assertEquals("1", p.changes.first { it.key == "stepMinSwing" }.from)
        assertEquals("1.4", p.changes.first { it.key == "stepMinSwing" }.to)
        assertEquals("Too many steps for 20 m.", p.reasoning)
        assertEquals("Step count drops by about 15 %.", p.expected)
        assertTrue(p.warnings.isEmpty(), p.warnings.toString())
    }

    @Test
    fun bareConfigObjectIsAccepted() {
        val p = accepted("""{"stepBandHighHz": 2.5, "smoothingWindow": 5}""")
        assertEquals(2.5, p.config.stepBandHighHz)
        assertEquals(5, p.config.smoothingWindow)
        assertEquals(2, p.changes.size)
    }

    @Test
    fun fullConfigEchoedBackChangesOnlyWhatDiffers() {
        val text = PipelineConfigJson.encode(base.copy(weinbergK = 0.45))
        val p = accepted(text)
        assertEquals(listOf("weinbergK"), p.changes.map { it.key })
    }

    @Test
    fun degreesVectorsAndEnumsAreUnderstood() {
        val p = accepted("""{"headingOffsetDeg": -90, "gyroBias": [0.01, -0.02, 0.003], "headingAxis": "camera"}""")
        assertTrue(abs(p.config.headingOffsetRad + Math.PI / 2) < 1e-9)
        assertEquals(Vec3(0.01, -0.02, 0.003), p.config.gyroBias)
        assertEquals(HeadingAxisMode.CAMERA, p.config.headingAxis)
        val o = accepted("""{"gyroBias": {"x": 0.1, "y": 0.2, "z": 0.3}, "useMagnetometer": "false"}""")
        assertEquals(Vec3(0.1, 0.2, 0.3), o.config.gyroBias)
        assertEquals(false, o.config.useMagnetometer)
    }

    @Test
    fun outOfRangeRejectsTheWholeAnswer() {
        val errors = rejected("""{"stepMinSwing": 1.2, "strideLengthM": 7.5}""")
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("strideLengthM"), errors[0])
    }

    @Test
    fun bandOrderAndTypesAreChecked() {
        assertTrue(rejected("""{"stepBandLowHz": 4.0}""").any { it.contains("stepBandHighHz") })
        assertTrue(rejected("""{"smoothingWindow": 2.5}""").any { it.contains("whole number") })
        assertTrue(rejected("""{"useMagnetometer": "maybe"}""").any { it.contains("true or false") })
        assertTrue(rejected("""{"headingAxis": "sideways"}""").any { it.contains("headingAxis") })
        assertTrue(rejected("""{"gyroBias": [1, 2]}""").any { it.contains("gyroBias") })
    }

    @Test
    fun unknownKeysAreWarningsNotErrors() {
        val p = accepted("""{"stepMinSwing": 1.3, "magicFactor": 3}""")
        assertEquals(1.3, p.config.stepMinSwing)
        assertEquals(1, p.warnings.size)
        assertTrue(p.warnings[0].contains("magicFactor"))
    }

    @Test
    fun noJsonIsRejectedAndNoChangeIsWarned() {
        assertTrue(rejected("I would lower the swing threshold a bit.").isNotEmpty())
        val p = accepted("""{"config": {}, "reasoning": "looks fine"}""")
        assertTrue(p.changes.isEmpty())
        assertTrue(p.warnings.any { it.contains("changes nothing") })
    }

    @Test
    fun proseWithBracesOutsideTheBlockStillParsesTheBlock() {
        val text = "Note {this} is not JSON.\n```json\n{\"stepMinSwing\": 1.1}\n```\nand {neither} is this."
        assertEquals(1.1, accepted(text).config.stepMinSwing)
        assertNotNull(ProposalParser.extractObject(text))
    }
}

/** Test helper: the config as the app stores it (defaults included), so an echo of it round-trips. */
object PipelineConfigJson {
    fun encode(c: PipelineConfig): String =
        kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(PipelineConfig.serializer(), c)
}
