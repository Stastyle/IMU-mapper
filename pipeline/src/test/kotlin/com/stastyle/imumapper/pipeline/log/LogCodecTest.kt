package com.stastyle.imumapper.pipeline.log

import com.stastyle.imumapper.pipeline.core.AccelSample
import com.stastyle.imumapper.pipeline.core.AccelUncalSample
import com.stastyle.imumapper.pipeline.core.AnnotationKind
import com.stastyle.imumapper.pipeline.core.AnnotationRecord
import com.stastyle.imumapper.pipeline.core.BaroSample
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.EventKind
import com.stastyle.imumapper.pipeline.core.EventRecord
import com.stastyle.imumapper.pipeline.core.GyroSample
import com.stastyle.imumapper.pipeline.core.GyroUncalSample
import com.stastyle.imumapper.pipeline.core.KeyframeSample
import com.stastyle.imumapper.pipeline.core.LogMeta
import com.stastyle.imumapper.pipeline.core.MagSample
import com.stastyle.imumapper.pipeline.core.MagUncalSample
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.PointCloudSample
import com.stastyle.imumapper.pipeline.core.PoseSample
import com.stastyle.imumapper.pipeline.core.RotationSample
import com.stastyle.imumapper.pipeline.core.RotationSource
import com.stastyle.imumapper.pipeline.core.StepSample
import com.stastyle.imumapper.pipeline.core.TrackingState
import com.stastyle.imumapper.pipeline.core.TripMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LogCodecTest {

    private val meta = LogMeta(
        appVersion = "0.0.1",
        deviceModel = "SM-S948B",
        androidSdk = 36,
        mode = TripMode.FLASHLIGHT,
        carryPosition = CarryPosition.HAND,
        startedAtEpochMs = 1_700_000_000_000L,
        sensorPeriodsUs = mapOf("accelerometer" to 2500),
        config = PipelineConfig(strideLengthM = 0.72, headingOffsetRad = 0.1),
        notes = "unit test",
    )

    private fun encodeAll(): ByteArray {
        val bytes = ByteArrayOutputStream()
        LogWriter(bytes).use { w ->
            w.writeMeta(meta)
            w.write(AccelSample(1_000L, 0.1f, 9.8f, -0.2f))
            w.write(GyroSample(1_100L, 0.01f, -0.02f, 0.03f))
            w.write(MagSample(1_200L, 20f, -5f, 40f))
            w.write(AccelUncalSample(1_300L, 1f, 2f, 3f, 0.1f, 0.2f, 0.3f))
            w.write(GyroUncalSample(1_400L, 4f, 5f, 6f, 0.4f, 0.5f, 0.6f))
            w.write(MagUncalSample(1_500L, 7f, 8f, 9f, 0.7f, 0.8f, 0.9f))
            w.write(BaroSample(2_000L, 1013.25f))
            w.write(RotationSample(3_000L, 0f, 0f, 0.7071f, 0.7071f, -1f, RotationSource.GAME))
            w.write(RotationSample(3_100L, 0f, 0f, 0f, 1f, 0.05f, RotationSource.FUSED))
            w.write(StepSample(4_000L))
            w.write(PoseSample(5_000L, 4_990L, 1f, 2f, 3f, 0f, 0f, 0f, 1f, TrackingState.TRACKING, 0))
            w.write(PoseSample(5_100L, 5_090L, 1f, 2f, 3f, 0f, 0f, 0f, 1f, TrackingState.PAUSED, 3))
            w.write(PointCloudSample(6_000L, floatArrayOf(1f, 2f, 3f, 0.9f, -1f, -2f, -3f, 0.5f)))
            w.write(KeyframeSample(7_000L, "kf-0001.jpg", 1f, 2f, 3f, 0f, 0f, 0f, 1f))
            w.write(AnnotationRecord(8_000L, AnnotationKind.JUNCTION, "left fork — ניסוי"))
            w.write(EventRecord(9_000L, EventKind.STOP))
        }
        return bytes.toByteArray()
    }

    @Test
    fun roundTripsEveryRecordType() {
        val log = LogReader.read(ByteArrayInputStream(encodeAll()))

        assertFalse(log.truncated)
        assertEquals(0, log.unknownRecords)
        assertEquals(meta, log.meta)
        assertEquals(listOf(AccelSample(1_000L, 0.1f, 9.8f, -0.2f)), log.accel)
        assertEquals(listOf(GyroSample(1_100L, 0.01f, -0.02f, 0.03f)), log.gyro)
        assertEquals(listOf(MagSample(1_200L, 20f, -5f, 40f)), log.mag)
        assertEquals(listOf(AccelUncalSample(1_300L, 1f, 2f, 3f, 0.1f, 0.2f, 0.3f)), log.accelUncal)
        assertEquals(listOf(GyroUncalSample(1_400L, 4f, 5f, 6f, 0.4f, 0.5f, 0.6f)), log.gyroUncal)
        assertEquals(listOf(MagUncalSample(1_500L, 7f, 8f, 9f, 0.7f, 0.8f, 0.9f)), log.magUncal)
        assertEquals(listOf(BaroSample(2_000L, 1013.25f)), log.baro)
        assertEquals(2, log.rotation.size)
        assertEquals(RotationSource.GAME, log.rotation[0].source)
        assertEquals(0.7071f, log.rotation[0].qw)
        assertEquals(RotationSource.FUSED, log.rotation[1].source)
        assertEquals(0.05f, log.rotation[1].headingAccuracyRad)
        assertEquals(listOf(StepSample(4_000L)), log.steps)
        assertEquals(2, log.poses.size)
        assertEquals(TrackingState.TRACKING, log.poses[0].tracking)
        assertEquals(4_990L, log.poses[0].frameTimestampNs)
        assertEquals(TrackingState.PAUSED, log.poses[1].tracking)
        assertEquals(3, log.poses[1].failureReason)
        assertEquals(1, log.pointClouds.size)
        assertEquals(2, log.pointClouds[0].count)
        assertEquals(-3f, log.pointClouds[0].xyzc[6])
        assertEquals(KeyframeSample(7_000L, "kf-0001.jpg", 1f, 2f, 3f, 0f, 0f, 0f, 1f), log.keyframes.single())
        assertEquals(AnnotationRecord(8_000L, AnnotationKind.JUNCTION, "left fork — ניסוי"), log.annotations.single())
        assertEquals(EventRecord(9_000L, EventKind.STOP), log.events.single())
        assertTrue(log.hasVio)
        assertEquals(1_000L, log.firstTimestampNs)
        assertEquals(9_000L, log.lastTimestampNs)
    }

    @Test
    fun skippedTypesLeaveTheirStreamsEmpty() {
        val log = LogReader.read(ByteArrayInputStream(encodeAll()), LogReader.UNCALIBRATED_TYPES)

        assertTrue(log.accelUncal.isEmpty())
        assertTrue(log.gyroUncal.isEmpty())
        assertTrue(log.magUncal.isEmpty())
        // Nothing else is affected, including the record count and the time span.
        assertEquals(listOf(AccelSample(1_000L, 0.1f, 9.8f, -0.2f)), log.accel)
        assertEquals(2, log.rotation.size)
        assertEquals(0, log.unknownRecords)
        assertFalse(log.truncated)
        assertEquals(1_000L, log.firstTimestampNs)
        assertEquals(9_000L, log.lastTimestampNs)
    }

    @Test
    fun sampleStreamsBehaveAsLists() {
        val bytes = ByteArrayOutputStream()
        LogWriter(bytes).use { w ->
            for (i in 0 until 3000) {
                w.write(AccelSample(i.toLong(), i.toFloat(), -i.toFloat(), 9.8f))
                w.write(BaroSample(i.toLong(), 1000f + i))
                w.write(StepSample(i.toLong()))
                w.write(RotationSample(i.toLong(), 0f, 0f, 0f, 1f, 0.1f, if (i % 3 == 0) RotationSource.FUSED else RotationSource.GAME))
                w.write(GyroUncalSample(i.toLong(), 1f, 2f, 3f, 4f, 5f, 6f))
            }
        }
        val log = LogReader.read(ByteArrayInputStream(bytes.toByteArray()))

        // Column stores grow past their initial capacity here; every element must survive the growth.
        assertEquals(3000, log.accel.size)
        assertEquals(AccelSample(2999L, 2999f, -2999f, 9.8f), log.accel.last())
        assertEquals(AccelSample(1024L, 1024f, -1024f, 9.8f), log.accel[1024])
        assertEquals(BaroSample(1500L, 2500f), log.baro[1500])
        assertEquals(StepSample(2048L), log.steps[2048])
        assertEquals(GyroUncalSample(1023L, 1f, 2f, 3f, 4f, 5f, 6f), log.gyroUncal[1023])
        assertEquals(1000, log.fusedRotation.size)
        assertEquals(2000, log.gameRotation.size)
        assertTrue(log.fusedRotation.all { it.source == RotationSource.FUSED && it.tNs % 3 == 0L })
        assertEquals(3L, log.fusedRotation[1].tNs)
        // Plain List behaviour: indexOf, subList, equality with an ordinary list.
        assertEquals(7, log.accel.indexOf(AccelSample(7L, 7f, -7f, 9.8f)))
        assertEquals(listOf(StepSample(10L), StepSample(11L)), log.steps.subList(10, 12))
        assertEquals(log.accel.take(5), log.accel.subList(0, 5))
        assertFailsWith<IndexOutOfBoundsException> { log.accel[3000] }
    }

    @Test
    fun truncatedTailIsDroppedNotFatal() {
        val full = encodeAll()
        // Cut in the middle of the last record (EVENT: 5 header + 9 payload bytes).
        val cut = full.copyOf(full.size - 4)
        val log = LogReader.read(ByteArrayInputStream(cut))
        assertTrue(log.truncated)
        assertTrue(log.events.isEmpty())
        assertEquals(1, log.annotations.size)
        assertNotNull(log.meta)
    }

    @Test
    fun unknownRecordTypesAreSkipped() {
        val bytes = ByteArrayOutputStream()
        LogWriter(bytes).use { it.write(StepSample(1L)) }
        // Append a record of an unknown type 0x7F with a 3-byte payload, then a valid step.
        val extra = ByteBuffer.allocate(5 + 3 + 5 + 8).order(ByteOrder.LITTLE_ENDIAN)
        extra.put(0x7F.toByte()).putInt(3).put(byteArrayOf(1, 2, 3))
        extra.put(LogFormat.T_STEP.toByte()).putInt(8).putLong(2L)
        bytes.write(extra.array())

        val log = LogReader.read(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(listOf(StepSample(1L), StepSample(2L)), log.steps)
        assertEquals(1, log.unknownRecords)
        assertFalse(log.truncated)
    }

    @Test
    fun rejectsBadMagic() {
        assertFailsWith<LogFormatException> {
            LogReader.read(ByteArrayInputStream("NOPE\u0001\u0000\u0000\u0000".toByteArray(Charsets.US_ASCII)))
        }
    }

    @Test
    fun emptyLogHasNoTruncationAndNoRecords() {
        val bytes = ByteArrayOutputStream()
        LogWriter(bytes).use { }
        val log = LogReader.read(ByteArrayInputStream(bytes.toByteArray()))
        assertFalse(log.truncated)
        assertEquals(0, log.totalRecords)
        assertEquals(0.0, log.durationS)
    }
}
