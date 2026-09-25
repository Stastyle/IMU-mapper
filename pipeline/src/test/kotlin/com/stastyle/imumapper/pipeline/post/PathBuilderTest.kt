package com.stastyle.imumapper.pipeline.post

import com.stastyle.imumapper.pipeline.core.AnnotationKind
import com.stastyle.imumapper.pipeline.core.AnnotationRecord
import com.stastyle.imumapper.pipeline.core.KeyframeSample
import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.PositionSource
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.log.RawLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PathBuilderTest {

    private fun pt(i: Int, x: Double, source: PositionSource = PositionSource.PDR) =
        PathPoint(i * 1_000L, Vec3(x, 0.0, 0.1 * i), source, 0.5, i)

    @Test
    fun nearestIndexPicksClosestTime() {
        val points = listOf(pt(0, 0.0), pt(1, 1.0), pt(2, 2.0))
        assertEquals(0, PathBuilder.nearestIndex(points, -5L))
        assertEquals(0, PathBuilder.nearestIndex(points, 400L))
        assertEquals(1, PathBuilder.nearestIndex(points, 600L))
        assertEquals(0, PathBuilder.nearestIndex(points, 500L), "ties go to the earlier point")
        assertEquals(2, PathBuilder.nearestIndex(points, 99_999L))
        assertEquals(-1, PathBuilder.nearestIndex(emptyList(), 1L))
    }

    @Test
    fun buildsStatsAndPlacesMarkers() {
        val points = listOf(pt(0, 0.0), pt(1, 1.0), pt(2, 2.0, PositionSource.VIO), pt(3, 3.0, PositionSource.VIO))
        val log = RawLog.Builder()
            .add(AnnotationRecord(1_100L, AnnotationKind.JUNCTION, "left"))
            .add(KeyframeSample(2_900L, "img.jpg", 0f, 0f, 0f, 0f, 0f, 0f, 1f))
            .build()
        val r = PathBuilder.build(PipelineConfig(), points, log, 3, 0.25, mapOf("k" to "v"))
        assertEquals(1, r.annotations.size)
        assertEquals(points[1].p, r.annotations[0].p)
        assertEquals("left", r.annotations[0].note)
        assertEquals(1, r.keyframes.size)
        assertEquals(points[3].p, r.keyframes[0].p)
        assertEquals(0.5, r.keyframes[0].headingRad)
        assertEquals(3, r.stats.stepCount)
        assertEquals(0.25, r.stats.closureErrorM)
        assertEquals(0.5, r.stats.vioFraction)
        assertEquals(0.0, r.stats.minZ)
        assertTrue(r.stats.maxZ > 0.29 && r.stats.maxZ < 0.31)
        assertTrue(r.stats.distanceM > 3.0 && r.stats.distanceM < 3.1)
        assertEquals("v", r.diagnostics["k"])
        val empty = PathBuilder.build(PipelineConfig(), emptyList(), log, 0, null, emptyMap())
        assertEquals(Vec3.ZERO, empty.annotations[0].p)
        assertEquals(0.0, empty.stats.distanceM)
    }
}
