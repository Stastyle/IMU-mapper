package com.stastyle.imumapper.pipeline.post

import com.stastyle.imumapper.pipeline.core.AnnotationKind
import com.stastyle.imumapper.pipeline.core.PathAnnotation
import com.stastyle.imumapper.pipeline.core.PathKeyframe
import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.PathResult
import com.stastyle.imumapper.pipeline.core.PathStats
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.PositionSource
import com.stastyle.imumapper.pipeline.core.Vec3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RawPathTest {

    private fun pt(i: Int, x: Double, z: Double = 0.0) =
        PathPoint(i * 1_000L, Vec3(x, 0.0, z), PositionSource.PDR, 0.1 * i, i)

    /** Four raw points along x; the "final" path is the raw one shifted 1 m north and lifted 0.5 m at the end. */
    private fun result(): PathResult {
        val raw = listOf(pt(0, 0.0), pt(1, 1.0), pt(2, 2.0), pt(3, 3.0, 1.0))
        val final = raw.mapIndexed { i, p -> p.copy(p = p.p + Vec3(0.0, 1.0, if (i == 3) 0.5 else 0.0)) }
        return PathResult(
            pipelineVersion = 2,
            config = PipelineConfig(),
            points = final,
            annotations = listOf(PathAnnotation(2_100L, AnnotationKind.JUNCTION, "fork", final[2].p)),
            // A keyframe placed by its own pose: near the final point 3 but not on it.
            keyframes = listOf(PathKeyframe(3_000L, "k.jpg", final[3].p + Vec3(0.2, 0.0, 0.0), 0.7)),
            stats = PathBuilder.stats(final, 42.0, 3, 0.4),
            rawPoints = raw,
        )
    }

    @Test
    fun viewSwapsInRawPointsAndMovesMarkersWithThem() {
        val r = result()
        assertTrue(RawPath.isAvailable(r))
        val v = RawPath.view(r)
        assertEquals(r.rawPoints, v.points)
        assertTrue(v.rawPoints.isEmpty(), "the view is itself raw; nothing further to undo")
        assertEquals(r.rawPoints[2].p, v.annotations[0].p, "annotation goes back onto the raw path")
        assertEquals("fork", v.annotations[0].note)
        // The keyframe keeps its offset from the path point it was measured against.
        assertEquals(r.rawPoints[3].p + Vec3(0.2, 0.0, 0.0), v.keyframes[0].p)
        assertEquals(0.7, v.keyframes[0].headingRad)
        assertEquals(42.0, v.stats.durationS)
        assertEquals(3, v.stats.stepCount)
        assertEquals(0.4, v.stats.closureErrorM)
        assertEquals(1.0, v.stats.maxZ, "vertical range comes from the raw points")
        assertTrue(abs(v.stats.distanceM - (2.0 + kotlin.math.sqrt(2.0))) < 1e-9)
        assertEquals(r.config, v.config)
        assertEquals(r.pipelineVersion, v.pipelineVersion)
    }

    @Test
    fun resultWithoutRawPointsIsReturnedAsIs() {
        val r = result().copy(rawPoints = emptyList())
        assertFalse(RawPath.isAvailable(r))
        assertSame(r, RawPath.view(r))
    }

    @Test
    fun mismatchedPointCountsFallBackToUnshiftedMarkers() {
        val r = result()
        val odd = r.copy(rawPoints = r.rawPoints.drop(1))
        val v = RawPath.view(odd)
        assertEquals(odd.rawPoints, v.points)
        assertEquals(r.annotations[0].p, v.annotations[0].p, "no index alignment, so the marker stays put")
    }

    @Test
    fun jsonWithoutRawPointsStillLoads() {
        // A run-<n>.json written by pipeline version 1 has no rawPoints field.
        val legacy = """
            {"pipelineVersion":1,"config":{},"points":[
              {"tNs":0,"p":{"x":0.0,"y":0.0,"z":0.0},"source":"PDR","headingRad":0.0,"stepIndex":-1}],
             "stats":{"distanceM":0.0,"durationS":1.0,"stepCount":0,"minZ":0.0,"maxZ":0.0}}
        """.trimIndent()
        val r = PathResult.fromJson(legacy)
        assertTrue(r.rawPoints.isEmpty())
        assertFalse(RawPath.isAvailable(r))
        assertEquals(1, r.points.size)
        // And the field round-trips when present.
        val again = PathResult.fromJson(result().toJson())
        assertEquals(4, again.rawPoints.size)
        assertEquals(result().rawPoints, again.rawPoints)
    }

    @Test
    fun statsOfViewMatchBuilderOnRawPoints() {
        val r = result()
        val expected = PathBuilder.stats(r.rawPoints, r.stats.durationS, r.stats.stepCount, r.stats.closureErrorM)
        assertEquals(expected, RawPath.view(r).stats)
        assertEquals(PathStats::class, expected::class)
    }
}
