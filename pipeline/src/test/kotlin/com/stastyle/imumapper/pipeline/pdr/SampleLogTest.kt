package com.stastyle.imumapper.pipeline.pdr

import com.stastyle.imumapper.pipeline.core.AnnotationKind
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.log.LogReader
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Writes the reference samples in `tools/samples/` (`synthetic_square.imul`, `synthetic_carry_change.imul`)
 * through [LogWriter] and checks they survive a round trip through [LogReader] and the PDR processor.
 * The files are only written into the repository when the environment variable IMU_MAPPER_SAMPLES_DIR
 * points at that directory; otherwise a temporary file is used. Keep the scenarios and configs in sync
 * with tools/samples/README.md.
 */
class SampleLogTest {

    companion object {
        /** 100 Hz keeps the committed file small; the pipeline does not depend on the rate. */
        fun squareWalk(): SyntheticWalk = SyntheticWalk(rateHz = 100, gameYawDriftRadPerS = 0.003)
            .still(2.0)
            .walkTo(0.0, 5.0).annotate(AnnotationKind.WAYPOINT, "corner 1")
            .walkTo(5.0, 5.0).annotate(AnnotationKind.WAYPOINT, "corner 2")
            .walkTo(5.0, 0.0).annotate(AnnotationKind.WAYPOINT, "corner 3")
            .walkTo(0.0, 0.0).annotate(AnnotationKind.LOOP_CLOSED, "back at start")
            .still(2.0)

        fun sampleConfig(walk: SyntheticWalk): PipelineConfig = PipelineConfig(strideLengthM = walk.strideM * 1.04)

        /** Straight north; the phone goes from the hand into a pocket after 8 m, no annotation. */
        fun carryChangeWalk(): SyntheticWalk = SyntheticWalk(rateHz = 100)
            .still(2.0)
            .walkTo(0.0, 8.0)
            .deviceTilt(1.3).deviceOffset(PI / 2)
            .walkTo(0.0, 20.0)
            .still(2.0)
    }

    private fun sampleFile(name: String): Pair<File, Boolean> {
        val dir = System.getenv("IMU_MAPPER_SAMPLES_DIR")
        return if (dir != null) File(dir, "$name.imul") to true else File.createTempFile(name, ".imul") to false
    }

    @Test
    fun sampleFileRoundTripsAndProcesses() {
        val walk = squareWalk()
        val config = sampleConfig(walk)
        val (file, keep) = sampleFile("synthetic_square")
        try {
            file.outputStream().use { walk.write(it, config) }
            assertTrue(file.length() < 2_000_000L, "sample must stay small: ${file.length()} bytes")
            val log = LogReader.read(file)
            assertEquals(4, log.annotations.size)
            assertNotNull(log.meta)
            assertEquals(walk.strideM * 1.04, log.meta!!.config.strideLengthM)
            assertTrue(log.gameRotation.isNotEmpty() && log.baro.isNotEmpty() && log.steps.isNotEmpty())

            val result = PdrProcessor().process(log, log.meta!!.config)
            val closure = assertNotNull(result.stats.closureErrorM)
            assertTrue(closure > 0.1 && closure < 3.0, "closure error $closure")
            assertTrue(result.points.last().p.length < 0.3, "ends at the origin after closure")
        } finally {
            if (!keep) file.delete()
        }
    }

    @Test
    fun carryChangeSampleRoundTripsAndProcesses() {
        val walk = carryChangeWalk()
        val config = PipelineConfig(strideLengthM = walk.strideM)
        val (file, keep) = sampleFile("synthetic_carry_change")
        try {
            file.outputStream().use { walk.write(it, config) }
            assertTrue(file.length() < 2_000_000L, "sample must stay small: ${file.length()} bytes")
            val log = LogReader.read(file)
            assertTrue(log.annotations.isEmpty())
            val result = PdrProcessor().process(log, assertNotNull(log.meta).config)
            assertEquals("1", result.diagnostics["carryChanges"])
            assertEquals("1", result.diagnostics["reorientCount"])
            val end = result.points.last().p
            assertTrue(end.y > 16.0 && abs(end.x) < 3.0, "the walk stays north across the move, ended at $end")
        } finally {
            if (!keep) file.delete()
        }
    }
}
