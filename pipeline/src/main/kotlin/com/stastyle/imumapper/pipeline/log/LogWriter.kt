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
import kotlinx.serialization.json.Json
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes records in the [LogFormat] encoding. Thread-safe: the recorder calls [write] from several
 * sensor threads. Call [flush] periodically so a crash loses at most a few seconds.
 */
class LogWriter(out: OutputStream) : Closeable {

    private val out = BufferedOutputStream(out, 1 shl 16)
    private var buf: ByteBuffer = ByteBuffer.allocate(1 shl 12).order(ByteOrder.LITTLE_ENDIAN)
    private val header = ByteBuffer.allocate(LogFormat.RECORD_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
    private var closed = false
    var recordsWritten: Long = 0
        private set

    init {
        val h = ByteBuffer.allocate(LogFormat.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        h.put(LogFormat.MAGIC.toByteArray(Charsets.US_ASCII))
        h.putShort(LogFormat.VERSION.toShort())
        h.putShort(0)
        this.out.write(h.array())
    }

    fun writeMeta(meta: LogMeta) = write(MetaRecord(json.encodeToString(LogMeta.serializer(), meta)))

    @Synchronized
    fun write(record: LogRecord) {
        check(!closed) { "LogWriter is closed" }
        when (record) {
            is AccelSample -> vec(LogFormat.T_ACCEL, record.tNs, record.x, record.y, record.z)
            is GyroSample -> vec(LogFormat.T_GYRO, record.tNs, record.x, record.y, record.z)
            is MagSample -> vec(LogFormat.T_MAG, record.tNs, record.x, record.y, record.z)
            is BaroSample -> {
                begin(12)
                buf.putLong(record.tNs).putFloat(record.hPa)
                end(LogFormat.T_BARO)
            }
            is RotationSample -> {
                begin(28)
                buf.putLong(record.tNs)
                    .putFloat(record.qx).putFloat(record.qy).putFloat(record.qz).putFloat(record.qw)
                    .putFloat(record.headingAccuracyRad)
                end(if (record.source == RotationSource.GAME) LogFormat.T_GAME_ROT else LogFormat.T_ROT_VEC)
            }
            is StepSample -> {
                begin(8)
                buf.putLong(record.tNs)
                end(LogFormat.T_STEP)
            }
            is AccelUncalSample -> uncal(LogFormat.T_ACCEL_UNCAL, record.tNs, record.x, record.y, record.z, record.bx, record.by, record.bz)
            is GyroUncalSample -> uncal(LogFormat.T_GYRO_UNCAL, record.tNs, record.x, record.y, record.z, record.bx, record.by, record.bz)
            is MagUncalSample -> uncal(LogFormat.T_MAG_UNCAL, record.tNs, record.x, record.y, record.z, record.bx, record.by, record.bz)
            is PoseSample -> {
                begin(8 + 8 + 7 * 4 + 2)
                buf.putLong(record.tNs).putLong(record.frameTimestampNs)
                    .putFloat(record.tx).putFloat(record.ty).putFloat(record.tz)
                    .putFloat(record.qx).putFloat(record.qy).putFloat(record.qz).putFloat(record.qw)
                    .put(record.tracking.ordinal.toByte())
                    .put(record.failureReason.coerceIn(0, 255).toByte())
                end(LogFormat.T_POSE)
            }
            is PointCloudSample -> {
                val n = record.count
                begin(8 + 4 + n * 16)
                buf.putLong(record.tNs).putInt(n)
                for (i in 0 until n * 4) buf.putFloat(record.xyzc[i])
                end(LogFormat.T_POINT_CLOUD)
            }
            is KeyframeSample -> {
                val name = record.fileName.toByteArray(Charsets.UTF_8)
                require(name.size <= 0xFFFF) { "file name too long" }
                begin(8 + 7 * 4 + 2 + name.size)
                buf.putLong(record.tNs)
                    .putFloat(record.tx).putFloat(record.ty).putFloat(record.tz)
                    .putFloat(record.qx).putFloat(record.qy).putFloat(record.qz).putFloat(record.qw)
                    .putShort(name.size.toShort()).put(name)
                end(LogFormat.T_KEYFRAME)
            }
            is AnnotationRecord -> {
                val note = record.note.toByteArray(Charsets.UTF_8)
                require(note.size <= 0xFFFF) { "note too long" }
                begin(8 + 1 + 2 + note.size)
                buf.putLong(record.tNs).put(record.kind.ordinal.toByte()).putShort(note.size.toShort()).put(note)
                end(LogFormat.T_ANNOTATION)
            }
            is EventRecord -> {
                begin(9)
                buf.putLong(record.tNs).put(record.kind.ordinal.toByte())
                end(LogFormat.T_EVENT)
            }
            is MetaRecord -> {
                val bytes = record.json.toByteArray(Charsets.UTF_8)
                begin(4 + bytes.size)
                buf.putInt(bytes.size).put(bytes)
                end(LogFormat.T_META)
            }
        }
        recordsWritten++
    }

    @Synchronized
    fun flush() {
        if (!closed) out.flush()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        out.flush()
        out.close()
    }

    private fun vec(type: Int, t: Long, x: Float, y: Float, z: Float) {
        begin(20)
        buf.putLong(t).putFloat(x).putFloat(y).putFloat(z)
        end(type)
    }

    private fun uncal(type: Int, t: Long, x: Float, y: Float, z: Float, bx: Float, by: Float, bz: Float) {
        begin(32)
        buf.putLong(t).putFloat(x).putFloat(y).putFloat(z).putFloat(bx).putFloat(by).putFloat(bz)
        end(type)
    }

    private fun begin(capacity: Int) {
        if (buf.capacity() < capacity) {
            buf = ByteBuffer.allocate(Integer.highestOneBit(capacity) shl 1).order(ByteOrder.LITTLE_ENDIAN)
        }
        buf.clear()
    }

    private fun end(type: Int) {
        val len = buf.position()
        header.clear()
        header.put(type.toByte()).putInt(len)
        out.write(header.array(), 0, LogFormat.RECORD_HEADER_SIZE)
        out.write(buf.array(), 0, len)
    }

    private companion object {
        val json = Json { encodeDefaults = true }
    }
}
