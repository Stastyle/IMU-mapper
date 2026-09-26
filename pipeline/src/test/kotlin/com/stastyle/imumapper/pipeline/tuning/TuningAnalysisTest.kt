package com.stastyle.imumapper.pipeline.tuning

import com.stastyle.imumapper.pipeline.DefaultProcessor
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.pdr.SyntheticWalk
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TuningAnalysisTest {

    private fun rectangle(): SyntheticWalk =
        SyntheticWalk().still(2.0).walkTo(0.0, 10.0).walkTo(6.0, 10.0).walkTo(6.0, 0.0).walkTo(0.0, 0.0).still(1.0)

    private val info = RecordingInfo("Yard rectangle", "Sep 26, 2026 10:12", "pocket", "hand", "Synthetic", "test")

    @Test
    fun metricsDescribeTheRectangle() {
        val w = rectangle()
        val cfg = PipelineConfig(strideLengthM = w.strideM)
        val report = TuningAnalysis.analyse(w.build(cfg), cfg, DefaultProcessor())
        val m = report.metrics
        assertTrue(abs(m.distanceM - 32.0) < 3.5, "distance ${m.distanceM}")
        assertEquals(report.result.stats.stepCount, m.stepCount)
        val cadence = assertNotNull(m.cadenceHz)
        assertTrue(abs(cadence - w.cadenceHz) < 0.15, "cadence $cadence")
        val stride = assertNotNull(m.meanStrideM)
        assertTrue(abs(stride - w.strideM) < 1e-6, "stride $stride")
        val intervals = assertNotNull(m.stepIntervalS)
        assertTrue(abs(intervals.median - 1.0 / w.cadenceHz) < 0.05, "median interval ${intervals.median}")
        assertTrue(assertNotNull(m.shortIntervalFraction) < 0.05)
        val swing = assertNotNull(m.stepSwing)
        assertTrue(swing.median > 2.0 && swing.median < 6.0, "swing median ${swing.median}")
        assertTrue(m.closureM < 3.0, "closure ${m.closureM}")
        assertEquals(3, m.shape.turns.size, "turns " + m.shape.turns.map { it.angleDeg })
        assertTrue(m.shape.turns.all { abs(it.angleDeg - 90.0) < 20.0 }, m.shape.turns.map { it.angleDeg }.toString())
        assertTrue(abs(m.totalTurnDeg - 270.0) < 40.0, "total turn ${m.totalTurnDeg}")
        assertTrue(abs(m.netHeightM) < 0.5)
        assertEquals("GAME", m.diagnostics["orientationSource"])
    }

    @Test
    fun scoreRewardsTheBetterConfig() {
        val w = rectangle()
        val truth = GroundTruth(shape = WalkShape.RECTANGLE, distanceM = 32.0, turnCount = 3)
        val good = PipelineConfig(strideLengthM = w.strideM)
        val bad = good.copy(strideLengthM = w.strideM * 1.3)
        val log = w.build(good)
        val goodScore = Score.of(truth, TuningAnalysis.analyse(log, good, DefaultProcessor()).metrics)
        val badScore = Score.of(truth, TuningAnalysis.analyse(log, bad, DefaultProcessor()).metrics)
        assertEquals(listOf("Distance", "Closure", "Turns", "Turn angles"), goodScore.deviations.map { it.name })
        assertEquals(goodScore.deviations.map { it.name }, badScore.deviations.map { it.name }, "same items either way")
        val g = assertNotNull(goodScore.mean)
        val b = assertNotNull(badScore.mean)
        assertTrue(g < b, "good $g should beat bad $b")
        assertTrue(badScore.deviations[0].error > 0.2, "30 % longer stride shows in the distance: ${badScore.deviations[0].error}")
    }

    @Test
    fun scoreOnlyUsesWhatTheWalkerGave() {
        val m = TuningAnalysis.analyse(rectangle().build(), PipelineConfig(), DefaultProcessor()).metrics
        assertTrue(Score.of(GroundTruth(), m).deviations.isEmpty())
        assertEquals(listOf("Straightness", "Turns"), Score.of(GroundTruth(shape = WalkShape.STRAIGHT), m).deviations.map { it.name }.sorted())
        assertEquals(listOf("Height change"), Score.of(GroundTruth(heightChangeM = 2.0), m).deviations.map { it.name })
    }

    @Test
    fun promptHoldsEverythingTheModelNeedsAndNoSamples() {
        val w = rectangle()
        val cfg = PipelineConfig(strideLengthM = w.strideM, weinbergK = 0.0)
        val log = w.build(cfg)
        val report = TuningAnalysis.analyse(log, cfg, DefaultProcessor())
        val truth = GroundTruth(WalkShape.RECTANGLE, 32.0, 3, 0.0, "Normal pace, phone in hand.\nStarted facing north.")
        val text = PromptBundle.render("MASTER PROMPT", info, log, truth, report)

        assertTrue(text.startsWith("MASTER PROMPT"))
        assertTrue(text.contains("Yard rectangle"))
        assertTrue(text.contains("Rectangle (90° turns"))
        assertTrue(text.contains("32 m"))
        assertTrue(text.contains("> Normal pace, phone in hand.\n> Started facing north."))
        assertTrue(text.contains("\"strideLengthM\": " + w.strideM), "config JSON")
        for (f in ConfigSchema.fields) assertTrue(text.contains("| " + f.key + " |"), "schema row for " + f.key)
        assertTrue(text.contains("- **Steps:** " + report.metrics.stepCount))
        assertTrue(text.contains("Turns (positive = right / clockwise)"))
        assertTrue(text.contains("orientationSource = GAME"))
        assertTrue(text.contains("t,x,y,z,heading\n0,0,0,0,"))
        assertTrue(text.trimEnd().endsWith("Leave every other key out."))
        // The path block is bounded: at most MAX_PATH_POINTS lines plus the header.
        val block = text.substringAfter("t,x,y,z,heading\n").substringBefore("```")
        assertTrue(block.lines().count { it.isNotBlank() } <= PromptBundle.MAX_PATH_POINTS)
        assertTrue(text.length < 40_000, "prompt size ${text.length}")
        assertTrue(!text.contains("AccelSample") && !text.contains("GyroSample"))

        // The prompt is deterministic for the same inputs.
        assertEquals(text, PromptBundle.render("MASTER PROMPT", info, log, truth, report))
    }

    @Test
    fun promptListsEarlierAttempts() {
        val w = rectangle()
        val cfg = PipelineConfig(strideLengthM = w.strideM)
        val log = w.build(cfg)
        val current = TuningAnalysis.analyse(log, cfg, DefaultProcessor())
        val earlier = TuningAnalysis.analyse(log, cfg.copy(stepMinSwing = 1.5), DefaultProcessor())
        val text = PromptBundle.render("P", info, log, GroundTruth(), current, history = listOf(earlier))
        assertTrue(text.contains("## Earlier attempts on this walk"))
        assertTrue(text.contains("stepMinSwing = 1.5"))
    }

    @Test
    fun decimationKeepsEndsAndBound() {
        val pts = TuningAnalysis.analyse(rectangle().build(), PipelineConfig(), DefaultProcessor()).metrics.rawPoints
        val d = PromptBundle.decimate(pts, 10)
        assertEquals(10, d.size)
        assertEquals(pts.first(), d.first())
        assertEquals(pts.last(), d.last())
        assertEquals(pts, PromptBundle.decimate(pts, pts.size + 5))
    }
}
