package com.stastyle.imumapper.pipeline.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class PositionSource { PDR, VIO, INTERPOLATED }

/** One vertex of the output path, ENU metres relative to the trip start. */
@Serializable
data class PathPoint(
    val tNs: Long,
    val p: Vec3,
    val source: PositionSource,
    /** Walking direction at this point, radians clockwise from north. */
    val headingRad: Double,
    /** Index of the step that produced this point, -1 for VIO samples. */
    val stepIndex: Int = -1,
)

@Serializable
data class PathAnnotation(val tNs: Long, val kind: AnnotationKind, val note: String, val p: Vec3)

@Serializable
data class PathKeyframe(val tNs: Long, val fileName: String, val p: Vec3, val headingRad: Double)

@Serializable
data class PathStats(
    val distanceM: Double,
    val durationS: Double,
    val stepCount: Int,
    val minZ: Double,
    val maxZ: Double,
    /** End-to-start error before loop closure, null when no loop was declared. */
    val closureErrorM: Double? = null,
    /** Fraction of points that came from ARCore tracking, 0 for pocket mode. */
    val vioFraction: Double = 0.0,
)

/**
 * The processed trip. Stored as JSON next to the raw log, one file per pipeline run, so old and
 * new results can be compared in the viewer.
 */
@Serializable
data class PathResult(
    val pipelineVersion: Int,
    val config: PipelineConfig,
    val points: List<PathPoint>,
    val annotations: List<PathAnnotation> = emptyList(),
    val keyframes: List<PathKeyframe> = emptyList(),
    /** Sparse feature points in ENU metres, decimated for display. */
    val pointCloud: List<Vec3> = emptyList(),
    val stats: PathStats,
    /** Free-form numbers and notes from the pipeline for the debug screen. */
    val diagnostics: Map<String, String> = emptyMap(),
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun fromJson(text: String): PathResult = json.decodeFromString(serializer(), text)
    }
}
