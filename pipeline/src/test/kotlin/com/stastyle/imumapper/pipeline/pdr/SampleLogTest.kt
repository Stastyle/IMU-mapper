package com.stastyle.imumapper.pipeline.pdr

import com.stastyle.imumapper.pipeline.core.AnnotationKind
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.log.LogReader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Writes the reference sample `tools/samples/synthetic_square.imul` through [LogWriter] and checks it
 * survives a round trip through [LogReader] and the PDR processor. The file is only written into
 * the repository when the environment variable IMU_MAPPER_SAMPLES_DIR points at that directory;
 * otherwise a temporary file is used. Keep the scenario and config in sync with tools/samples/README.md.
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
    }

    @Test
    fun sampleFileRoundTripsAndProcesses() {
        val walk = squareWalk()
        val config = sampleConfig(walk)
        val dir = System.getenv("IMU_MAPPER_SAMPLES_DIR")
        val file = if (dir != null) {
            File(dir, "synthetic_square.imul")
        } else {
            File.createTempFile("synthetic_square", ".imul")
        }
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
            if (dir == null) file.delete()
        }
    }
}
