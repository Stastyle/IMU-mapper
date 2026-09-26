package com.stastyle.imumapper.pipeline.log

import com.stastyle.imumapper.pipeline.core.AccelSample
import com.stastyle.imumapper.pipeline.core.AccelUncalSample
import com.stastyle.imumapper.pipeline.core.AnnotationRecord
import com.stastyle.imumapper.pipeline.core.BaroSample
import com.stastyle.imumapper.pipeline.core.EventRecord
import com.stastyle.imumapper.pipeline.core.GyroSample
import com.stastyle.imumapper.pipeline.core.GyroUncalSample
import com.stastyle.imumapper.pipeline.core.KeyframeSample
import com.stastyle.imumapper.pipeline.core.LogMeta
import com.stastyle.imumapper.pipeline.core.LogRecord
import com.stastyle.imumapper.pipeline.core.MagSample
import com.stastyle.imumapper.pipeline.core.MagUncalSample
import com.stastyle.imumapper.pipeline.core.MetaRecord
import com.stastyle.imumapper.pipeline.core.PointCloudSample
import com.stastyle.imumapper.pipeline.core.PoseSample
import com.stastyle.imumapper.pipeline.core.RotationSample
import com.stastyle.imumapper.pipeline.core.RotationSource
import com.stastyle.imumapper.pipeline.core.StepSample
import com.stastyle.imumapper.pipeline.core.TrackingState
import kotlinx.serialization.json.Json

/**
 * A fully parsed log, one list per record type, each in file (time) order.
 * This is the input of every [com.stastyle.imumapper.pipeline.core.Processor].
 *
 * The high-rate sensor streams are column stores (see SampleColumns.kt): a few bytes per sample,
 * with the data class built on each `get`. Iterate them or index them, but do not hold on to the
 * elements of a long log in another collection, which brings the boxed cost back.
 */
class RawLog(
    val meta: LogMeta?,
    /** Raw meta JSON, kept even when [meta] failed to parse. */
    val metaJson: String?,
    val accel: List<AccelSample>,
    val gyro: List<GyroSample>,
    val mag: List<MagSample>,
    val accelUncal: List<AccelUncalSample>,
    val gyroUncal: List<GyroUncalSample>,
    val magUncal: List<MagUncalSample>,
    val baro: List<BaroSample>,
    val rotation: List<RotationSample>,
    val steps: List<StepSample>,
    val poses: List<PoseSample>,
    val pointClouds: List<PointCloudSample>,
    val keyframes: List<KeyframeSample>,
    val annotations: List<AnnotationRecord>,
    val events: List<EventRecord>,
    /** True when the file ended mid-record (recorder died). Everything before is valid. */
    val truncated: Boolean = false,
    val unknownRecords: Int = 0,
) {
    val gameRotation: List<RotationSample> by lazy { RotationList.withSource(rotation, RotationSource.GAME) }
    val fusedRotation: List<RotationSample> by lazy { RotationList.withSource(rotation, RotationSource.FUSED) }

    val hasVio: Boolean get() = poses.any { it.tracking == TrackingState.TRACKING }

    val firstTimestampNs: Long by lazy {
        sequenceOf(accel, gyro, mag, baro, rotation, steps, poses, events)
            .mapNotNull { it.firstOrNull()?.tNs }.minOrNull() ?: 0L
    }

    val lastTimestampNs: Long by lazy {
        sequenceOf(accel, gyro, mag, baro, rotation, steps, poses, events)
            .mapNotNull { it.lastOrNull()?.tNs }.maxOrNull() ?: 0L
    }

    val durationS: Double get() = (lastTimestampNs - firstTimestampNs) / 1e9

    val totalRecords: Int
        get() = accel.size + gyro.size + mag.size + accelUncal.size + gyroUncal.size + magUncal.size +
            baro.size + rotation.size + steps.size + poses.size + pointClouds.size + keyframes.size +
            annotations.size + events.size + (if (metaJson != null) 1 else 0)

    /**
     * Collects records into a log. [expectedCounts], records per record type indexed by the
     * [LogFormat] type byte, sizes every column up front so nothing is copied while building;
     * without it the columns grow as records arrive.
     */
    class Builder(private val expectedCounts: IntArray? = null) {
        private fun expected(type: Int): Int =
            expectedCounts?.getOrNull(type)?.takeIf { it > 0 } ?: ColumnBuilder.DEFAULT_CAPACITY

        private var metaJson: String? = null
        private val accel = ColumnBuilder(3, expected(LogFormat.T_ACCEL))
        private val gyro = ColumnBuilder(3, expected(LogFormat.T_GYRO))
        private val mag = ColumnBuilder(3, expected(LogFormat.T_MAG))
        private val accelUncal = ColumnBuilder(6, expected(LogFormat.T_ACCEL_UNCAL))
        private val gyroUncal = ColumnBuilder(6, expected(LogFormat.T_GYRO_UNCAL))
        private val magUncal = ColumnBuilder(6, expected(LogFormat.T_MAG_UNCAL))
        private val baro = ColumnBuilder(1, expected(LogFormat.T_BARO))
        private val rotation = RotationList.Builder(expected(LogFormat.T_GAME_ROT) + expected(LogFormat.T_ROT_VEC))
        private val steps = ColumnBuilder(0, expected(LogFormat.T_STEP))
        private val poses = ArrayList<PoseSample>(expected(LogFormat.T_POSE))
        private val pointClouds = ArrayList<PointCloudSample>(expected(LogFormat.T_POINT_CLOUD))
        private val keyframes = ArrayList<KeyframeSample>()
        private val annotations = ArrayList<AnnotationRecord>()
        private val events = ArrayList<EventRecord>()

        fun add(r: LogRecord): Builder {
            when (r) {
                is MetaRecord -> if (metaJson == null) metaJson = r.json
                is AccelSample -> accel.add(r.tNs, r.x, r.y, r.z)
                is GyroSample -> gyro.add(r.tNs, r.x, r.y, r.z)
                is MagSample -> mag.add(r.tNs, r.x, r.y, r.z)
                is AccelUncalSample -> accelUncal.add(r.tNs, r.x, r.y, r.z, r.bx, r.by, r.bz)
                is GyroUncalSample -> gyroUncal.add(r.tNs, r.x, r.y, r.z, r.bx, r.by, r.bz)
                is MagUncalSample -> magUncal.add(r.tNs, r.x, r.y, r.z, r.bx, r.by, r.bz)
                is BaroSample -> baro.add(r.tNs, r.hPa)
                is RotationSample -> rotation.add(r)
                is StepSample -> steps.add(r.tNs)
                is PoseSample -> poses += r
                is PointCloudSample -> pointClouds += r
                is KeyframeSample -> keyframes += r
                is AnnotationRecord -> annotations += r
                is EventRecord -> events += r
            }
            return this
        }

        fun build(truncated: Boolean = false, unknownRecords: Int = 0): RawLog {
            val meta = metaJson?.let { runCatching { json.decodeFromString(LogMeta.serializer(), it) }.getOrNull() }
            return RawLog(
                meta = meta,
                metaJson = metaJson,
                accel = TripletList(accel.times(), accel.values(), ::AccelSample),
                gyro = TripletList(gyro.times(), gyro.values(), ::GyroSample),
                mag = TripletList(mag.times(), mag.values(), ::MagSample),
                accelUncal = SextetList(accelUncal.times(), accelUncal.values(), ::AccelUncalSample),
                gyroUncal = SextetList(gyroUncal.times(), gyroUncal.values(), ::GyroUncalSample),
                magUncal = SextetList(magUncal.times(), magUncal.values(), ::MagUncalSample),
                baro = BaroList(baro.times(), baro.values()),
                rotation = rotation.build(),
                steps = StepList(steps.times()),
                poses = poses,
                pointClouds = pointClouds,
                keyframes = keyframes,
                annotations = annotations,
                events = events,
                truncated = truncated,
                unknownRecords = unknownRecords,
            )
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}
