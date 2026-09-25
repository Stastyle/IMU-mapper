package com.stastyle.imumapper.capture.arcore

import com.stastyle.imumapper.pipeline.core.Quat
import com.stastyle.imumapper.pipeline.core.Vec3
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

/*
 * Pure helpers for the ARCore capture path. Nothing here touches Android so the keyframe policy,
 * point-cloud decimation and the YUV conversion can be unit tested on the JVM.
 */

/** Sample rate limit for point clouds: every Nth frame while tracking. */
const val POINT_CLOUD_EVERY_N_FRAMES: Int = 5

/** Hard cap on points per [com.stastyle.imumapper.pipeline.core.PointCloudSample]. */
const val MAX_POINTS_PER_CLOUD: Int = 2000

/** JPEG quality for keyframes. */
const val KEYFRAME_JPEG_QUALITY: Int = 85

/**
 * Yaw of the ARCore camera's viewing direction (-Z of the camera pose) about the +Y up axis, radians,
 * range (-pi, pi]. The absolute value is arbitrary (ARCore fixes its yaw at session start); only
 * differences are used, to decide when the view has turned enough for a new keyframe.
 */
fun arHeadingRad(qx: Float, qy: Float, qz: Float, qw: Float): Double {
    val q = Quat.fromXyzw(qx.toDouble(), qy.toDouble(), qz.toDouble(), qw.toDouble())
    val forward = q.rotate(Vec3(0.0, 0.0, -1.0))
    return atan2(forward.x, -forward.z)
}

/** Absolute difference between two angles in radians, wrapped to [0, pi]. */
fun headingDeltaRad(a: Double, b: Double): Double {
    var d = abs(a - b) % (2 * PI)
    if (d > PI) d = 2 * PI - d
    return d
}

/**
 * Decides when to take a photo keyframe: after [minDistanceM] of travel or [minHeadingRad] of turn
 * since the last one, but never more often than [minIntervalNs]. The first call always captures.
 */
class KeyframePolicy(
    private val minDistanceM: Double = 2.0,
    private val minHeadingRad: Double = Math.toRadians(30.0),
    private val minIntervalNs: Long = 1_000_000_000L,
) {
    private var lastTNs = 0L
    private var lastPosition: Vec3? = null
    private var lastHeading = 0.0

    fun shouldCapture(tNs: Long, position: Vec3, headingRad: Double): Boolean {
        val last = lastPosition ?: return true
        if (tNs - lastTNs < minIntervalNs) return false
        return position.distanceTo(last) >= minDistanceM || headingDeltaRad(headingRad, lastHeading) >= minHeadingRad
    }

    /** Records that a keyframe was taken; called only once the capture was actually started. */
    fun markCaptured(tNs: Long, position: Vec3, headingRad: Double) {
        lastTNs = tNs
        lastPosition = position
        lastHeading = headingRad
    }

    fun reset() {
        lastTNs = 0L
        lastPosition = null
        lastHeading = 0.0
    }
}

/**
 * Keeps at most [maxPoints] points of an x, y, z, confidence array, spread uniformly over the input
 * so a dense cloud still covers the whole view instead of only its first rows.
 */
fun decimatePointCloud(xyzc: FloatArray, maxPoints: Int = MAX_POINTS_PER_CLOUD): FloatArray {
    val n = xyzc.size / 4
    if (n <= maxPoints) return if (xyzc.size == n * 4) xyzc else xyzc.copyOf(n * 4)
    val out = FloatArray(maxPoints * 4)
    for (k in 0 until maxPoints) {
        val src = (k.toLong() * n / maxPoints).toInt() * 4
        out[k * 4] = xyzc[src]
        out[k * 4 + 1] = xyzc[src + 1]
        out[k * 4 + 2] = xyzc[src + 2]
        out[k * 4 + 3] = xyzc[src + 3]
    }
    return out
}

/** File name of the [index]th keyframe (1-based), relative to the trip's photo directory. */
fun keyframeFileName(index: Int): String = String.format(Locale.US, "kf-%04d.jpg", index)

/**
 * Degrees a camera image must be rotated clockwise to appear upright for a back-facing camera whose
 * sensor is mounted at [sensorOrientation] degrees while the display is at [displayRotationDegrees].
 */
fun jpegRotationDegrees(sensorOrientation: Int, displayRotationDegrees: Int): Int =
    ((sensorOrientation - displayRotationDegrees) % 360 + 360) % 360

/** EXIF orientation tag value that tells viewers to rotate the image clockwise by [rotationDegrees]. */
fun exifOrientationFor(rotationDegrees: Int): Int = when (rotationDegrees) {
    90 -> 6
    180 -> 3
    270 -> 8
    else -> 1
}

/** One plane of a YUV_420_888 image, as `android.media.Image.Plane` exposes it. */
class PlaneData(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int)

/**
 * Converts YUV_420_888 planes to NV21 (full-size Y followed by interleaved V, U at half resolution),
 * the layout `YuvImage` compresses to JPEG. Handles both planar (pixel stride 1) and semi-planar
 * (pixel stride 2) chroma. The buffers' positions are left untouched.
 */
fun yuv420ToNv21(width: Int, height: Int, y: PlaneData, u: PlaneData, v: PlaneData): ByteArray {
    val chromaW = width / 2
    val chromaH = height / 2
    val out = ByteArray(width * height + 2 * chromaW * chromaH)

    val yb = y.buffer.duplicate()
    for (row in 0 until height) {
        val base = row * y.rowStride
        if (y.pixelStride == 1) {
            yb.position(base)
            yb.get(out, row * width, width)
        } else {
            for (col in 0 until width) out[row * width + col] = yb.get(base + col * y.pixelStride)
        }
    }

    val ub = u.buffer.duplicate()
    val vb = v.buffer.duplicate()
    var o = width * height
    for (row in 0 until chromaH) {
        val uBase = row * u.rowStride
        val vBase = row * v.rowStride
        for (col in 0 until chromaW) {
            out[o++] = vb.get(vBase + col * v.pixelStride)
            out[o++] = ub.get(uBase + col * u.pixelStride)
        }
    }
    return out
}
