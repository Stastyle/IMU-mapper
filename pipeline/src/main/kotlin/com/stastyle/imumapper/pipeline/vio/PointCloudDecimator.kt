package com.stastyle.imumapper.pipeline.vio

import com.stastyle.imumapper.pipeline.core.Vec3
import kotlin.math.floor

/**
 * Turns ARCore feature points into a display-sized ENU point cloud: low-confidence points are
 * dropped, the first point seen in every voxel is kept (so a wall scanned for a minute costs the
 * same as one scanned once), and the survivors are thinned uniformly in time to the cap so a long
 * trip does not lose its second half.
 */
class PointCloudDecimator(
    private val voxelSizeM: Double,
    private val maxPoints: Int,
    private val minConfidence: Float,
) {
    private val voxels = HashSet<Long>()
    private val points = ArrayList<Vec3>()
    var rawCount: Int = 0
        private set
    var confidentCount: Int = 0
        private set

    /** Adds one frame's points: 4 floats per point (x, y, z, confidence) in ARCore coordinates. */
    fun add(xyzc: FloatArray, transform: FrameTransform) {
        var i = 0
        while (i + 3 < xyzc.size) {
            rawCount++
            val c = xyzc[i + 3]
            if (c >= minConfidence) {
                confidentCount++
                val p = transform.position(Vec3.of(xyzc[i], xyzc[i + 1], xyzc[i + 2]))
                if (voxels.add(key(p))) points.add(p)
            }
            i += 4
        }
    }

    /** Number of distinct voxels seen so far. */
    val voxelCount: Int get() = points.size

    fun result(): List<Vec3> {
        if (maxPoints <= 0) return emptyList()
        val n = points.size
        if (n <= maxPoints) return ArrayList(points)
        // Exactly maxPoints survivors, evenly spread over the recording order (which is time order).
        val out = ArrayList<Vec3>(maxPoints)
        for (i in 0 until maxPoints) out.add(points[(i.toLong() * n / maxPoints).toInt()])
        return out
    }

    private fun key(p: Vec3): Long {
        // 21 bits per axis at 0.1 m covers +-100 km, far beyond any walk; the offset keeps indices positive.
        val ix = floor(p.x / voxelSizeM).toLong() + OFFSET
        val iy = floor(p.y / voxelSizeM).toLong() + OFFSET
        val iz = floor(p.z / voxelSizeM).toLong() + OFFSET
        return ((ix and MASK) shl 42) or ((iy and MASK) shl 21) or (iz and MASK)
    }

    private companion object {
        const val OFFSET: Long = 1L shl 20
        const val MASK: Long = (1L shl 21) - 1
    }
}
