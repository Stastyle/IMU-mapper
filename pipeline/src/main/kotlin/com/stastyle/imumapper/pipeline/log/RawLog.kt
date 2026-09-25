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
    val gameRotation: List<RotationSample> by lazy { rotation.filter { it.source == RotationSource.GAME } }
    val fusedRotation: List<RotationSample> by lazy { rotation.filter { it.source == RotationSource.FUSED } }

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

    class Builder {
        private var metaJson: String? = null
        private val accel = ArrayList<AccelSample>()
        private val gyro = ArrayList<GyroSample>()
        private val mag = ArrayList<MagSample>()
        private val accelUncal = ArrayList<AccelUncalSample>()
        private val gyroUncal = ArrayList<GyroUncalSample>()
        private val magUncal = ArrayList<MagUncalSample>()
        private val baro = ArrayList<BaroSample>()
        private val rotation = ArrayList<RotationSample>()
        private val steps = ArrayList<StepSample>()
        private val poses = ArrayList<PoseSample>()
        private val pointClouds = ArrayList<PointCloudSample>()
        private val keyframes = ArrayList<KeyframeSample>()
        private val annotations = ArrayList<AnnotationRecord>()
        private val events = ArrayList<EventRecord>()

        fun add(r: LogRecord): Builder {
            when (r) {
                is MetaRecord -> if (metaJson == null) metaJson = r.json
                is AccelSample -> accel += r
                is GyroSample -> gyro += r
                is MagSample -> mag += r
                is AccelUncalSample -> accelUncal += r
                is GyroUncalSample -> gyroUncal += r
                is MagUncalSample -> magUncal += r
                is BaroSample -> baro += r
                is RotationSample -> rotation += r
                is StepSample -> steps += r
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
                meta, metaJson, accel, gyro, mag, accelUncal, gyroUncal, magUncal, baro, rotation, steps,
                poses, pointClouds, keyframes, annotations, events, truncated, unknownRecords,
            )
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}
