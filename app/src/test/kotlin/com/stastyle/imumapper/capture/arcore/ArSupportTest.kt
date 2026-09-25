package com.stastyle.imumapper.capture.arcore

import com.stastyle.imumapper.pipeline.core.Quat
import com.stastyle.imumapper.pipeline.core.Vec3
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KeyframePolicyTest {
    private val s = 1_000_000_000L

    @Test
    fun firstFrameAlwaysCaptures() {
        val p = KeyframePolicy()
        assertTrue(p.shouldCapture(0L, Vec3.ZERO, 0.0))
    }

    @Test
    fun capturesAfterTwoMetresButNotWithinOneSecond() {
        val p = KeyframePolicy()
        p.markCaptured(0L, Vec3.ZERO, 0.0)
        assertFalse(p.shouldCapture(s / 2, Vec3(3.0, 0.0, 0.0), 0.0), "rate limited")
        assertFalse(p.shouldCapture(2 * s, Vec3(1.9, 0.0, 0.0), 0.0), "too close")
        assertTrue(p.shouldCapture(2 * s, Vec3(2.1, 0.0, 0.0), 0.0))
    }

    @Test
    fun capturesOnHeadingChangeAcrossWrap() {
        val p = KeyframePolicy()
        p.markCaptured(0L, Vec3.ZERO, PI - 0.1)
        // 20 degrees the short way around the wrap: not enough.
        assertFalse(p.shouldCapture(2 * s, Vec3.ZERO, -PI + 0.1 + Math.toRadians(20.0) - 0.2))
        assertTrue(p.shouldCapture(2 * s, Vec3.ZERO, PI - 0.1 - Math.toRadians(31.0)))
    }

    @Test
    fun resetForgetsHistory() {
        val p = KeyframePolicy()
        p.markCaptured(0L, Vec3.ZERO, 0.0)
        assertFalse(p.shouldCapture(1L, Vec3.ZERO, 0.0))
        p.reset()
        assertTrue(p.shouldCapture(1L, Vec3.ZERO, 0.0))
    }
}

class HeadingTest {
    @Test
    fun identityLooksDownMinusZ() {
        assertEquals(0.0, arHeadingRad(0f, 0f, 0f, 1f), 1e-9)
    }

    @Test
    fun yawAboutUpAxisChangesHeading() {
        val q = Quat.fromAxisAngle(Vec3.UNIT_Y, Math.toRadians(90.0))
        val h = arHeadingRad(q.x.toFloat(), q.y.toFloat(), q.z.toFloat(), q.w.toFloat())
        assertEquals(Math.toRadians(90.0), kotlin.math.abs(h), 1e-5)
    }

    @Test
    fun deltaWraps() {
        assertEquals(0.2, headingDeltaRad(PI - 0.1, -PI + 0.1), 1e-9)
        assertEquals(PI, headingDeltaRad(0.0, PI), 1e-9)
        assertEquals(0.0, headingDeltaRad(1.0, 1.0 + 2 * PI), 1e-9)
    }
}

class PointCloudTest {
    @Test
    fun smallCloudPassesThroughUntouched() {
        val input = FloatArray(40) { it.toFloat() }
        assertSame(input, decimatePointCloud(input, 10))
    }

    @Test
    fun trailingPartialPointIsDropped() {
        val input = FloatArray(9) { it.toFloat() }
        assertContentEquals(FloatArray(8) { it.toFloat() }, decimatePointCloud(input, 10))
    }

    @Test
    fun largeCloudIsSpreadUniformly() {
        val n = 1000
        val input = FloatArray(n * 4) { (it / 4).toFloat() }
        val out = decimatePointCloud(input, 100)
        assertEquals(400, out.size)
        assertEquals(0f, out[0])
        assertEquals(10f, out[4])
        assertEquals(990f, out[396])
        assertEquals(990f, out[399])
    }
}

class NamesAndOrientationTest {
    @Test
    fun keyframeNamesAreZeroPadded() {
        assertEquals("kf-0001.jpg", keyframeFileName(1))
        assertEquals("kf-0123.jpg", keyframeFileName(123))
        assertEquals("kf-12345.jpg", keyframeFileName(12345))
    }

    @Test
    fun keyframeIndexIsParsedBackFromTheName() {
        assertEquals(1, keyframeIndexOf("kf-0001.jpg"))
        assertEquals(12345, keyframeIndexOf("kf-12345.jpg"))
        assertEquals(7, keyframeIndexOf(keyframeFileName(7)))
        assertEquals(null, keyframeIndexOf("kf-0007.jpg.tmp"))
        assertEquals(null, keyframeIndexOf("photo.jpg"))
        assertEquals(null, keyframeIndexOf("kf-.jpg"))
    }

    @Test
    fun existingKeyframesSeedTheNextSession() {
        val listing = listOf("kf-0003.jpg", "kf-0001.jpg", "kf-0002.jpg.tmp", "notes.txt")
        val existing = existingKeyframeIndices(listing)
        assertEquals(listOf(3, 1), existing)
        assertEquals("kf-0004.jpg", keyframeFileName((existing.maxOrNull() ?: 0) + 1))
        assertEquals("kf-0001.jpg", keyframeFileName((existingKeyframeIndices(emptyList()).maxOrNull() ?: 0) + 1))
    }

    @Test
    fun rotationAndExif() {
        assertEquals(90, jpegRotationDegrees(90, 0))
        assertEquals(0, jpegRotationDegrees(90, 90))
        assertEquals(180, jpegRotationDegrees(270, 90))
        assertEquals(270, jpegRotationDegrees(0, 90))
        assertEquals(6, exifOrientationFor(90))
        assertEquals(3, exifOrientationFor(180))
        assertEquals(8, exifOrientationFor(270))
        assertEquals(1, exifOrientationFor(0))
    }
}

class Yuv420ToNv21Test {
    // 4x2 image. Y rows padded to a row stride of 6; chroma is 2x1.
    private val yBytes = byteArrayOf(1, 2, 3, 4, 0, 0, 5, 6, 7, 8)
    private val expected = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, /* V0 U0 V1 U1 */ 30, 20, 31, 21)

    @Test
    fun planarChroma() {
        val y = PlaneData(ByteBuffer.wrap(yBytes), rowStride = 6, pixelStride = 1)
        val u = PlaneData(ByteBuffer.wrap(byteArrayOf(20, 21)), rowStride = 4, pixelStride = 1)
        val v = PlaneData(ByteBuffer.wrap(byteArrayOf(30, 31)), rowStride = 4, pixelStride = 1)
        assertContentEquals(expected, yuv420ToNv21(4, 2, y, u, v))
    }

    @Test
    fun semiPlanarChromaSharesOneBuffer() {
        // Interleaved U/V as most devices deliver it: u buffer starts at U0, v buffer at V0, stride 2.
        val uv = byteArrayOf(20, 30, 21, 31)
        val y = PlaneData(ByteBuffer.wrap(yBytes), rowStride = 6, pixelStride = 1)
        val u = PlaneData(ByteBuffer.wrap(uv, 0, 4).slice(), rowStride = 4, pixelStride = 2)
        val v = PlaneData(ByteBuffer.wrap(uv, 1, 3).slice(), rowStride = 4, pixelStride = 2)
        assertContentEquals(expected, yuv420ToNv21(4, 2, y, u, v))
    }

    @Test
    fun yWithPixelStrideTwo() {
        val yWide = byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6, 0, 7, 0, 8, 0)
        val y = PlaneData(ByteBuffer.wrap(yWide), rowStride = 8, pixelStride = 2)
        val u = PlaneData(ByteBuffer.wrap(byteArrayOf(20, 21)), rowStride = 2, pixelStride = 1)
        val v = PlaneData(ByteBuffer.wrap(byteArrayOf(30, 31)), rowStride = 2, pixelStride = 1)
        val out = yuv420ToNv21(4, 2, y, u, v)
        assertContentEquals(expected, out)
        assertEquals(0, y.buffer.position(), "input buffer position must be untouched")
    }

    @Test
    fun outputSizeMatchesNv21() {
        val w = 8
        val h = 6
        val y = PlaneData(ByteBuffer.allocate(w * h), w, 1)
        val u = PlaneData(ByteBuffer.allocate(w * h / 4), w / 2, 1)
        val v = PlaneData(ByteBuffer.allocate(w * h / 4), w / 2, 1)
        assertEquals(w * h * 3 / 2, yuv420ToNv21(w, h, y, u, v).size)
    }
}
