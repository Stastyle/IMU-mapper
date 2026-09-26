package com.stastyle.imumapper.pipeline.log

import com.stastyle.imumapper.pipeline.core.AccelSample
import com.stastyle.imumapper.pipeline.core.AccelUncalSample
import com.stastyle.imumapper.pipeline.core.AnnotationKind
import com.stastyle.imumapper.pipeline.core.AnnotationRecord
import com.stastyle.imumapper.pipeline.core.BaroSample
import com.stastyle.imumapper.pipeline.core.EventKind
import com.stastyle.imumapper.pipeline.core.EventRecord
import com.stastyle.imumapper.pipeline.core.GyroSample
import com.stastyle.imumapper.pipeline.core.GyroUncalSample
import com.stastyle.imumapper.pipeline.core.KeyframeSample
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
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LogFormatException(message: String) : IOException(message)

/** Reads [LogFormat] streams. Tolerates a truncated tail and unknown record types. */
object LogReader {

    /** Outcome of a streaming read. [records] counts the records handed to the consumer. */
    data class Summary(val records: Long, val unknownRecords: Int, val truncated: Boolean)

    /**
     * The uncalibrated sensor streams ([LogFormat.T_ACCEL_UNCAL], [LogFormat.T_GYRO_UNCAL],
     * [LogFormat.T_MAG_UNCAL]). No processor reads them, and at 500 Hz they are close to half of
     * a log's records, so processing passes them as [skipTypes][read] and halves its memory.
     */
    val UNCALIBRATED_TYPES: Set<Int> = setOf(LogFormat.T_ACCEL_UNCAL, LogFormat.T_GYRO_UNCAL, LogFormat.T_MAG_UNCAL)

    /**
     * Reads [file]; records whose type is in [skipTypes] are left out of the log (their lists stay
     * empty). The file is walked twice: the first pass only counts records per type, so the second
     * can give every sample column its final size up front. A long log then costs exactly its
     * size in memory, with no growth copies and no trim copy at the end; the second pass reads
     * what the first has just pulled into the page cache.
     */
    fun read(file: File, skipTypes: Set<Int> = emptySet()): RawLog {
        val counts = file.inputStream().use { countByType(it) }
        return file.inputStream().use { read(it, skipTypes, counts) }
    }

    /** Reads a stream, which cannot be rewound, so the columns grow as records arrive. */
    fun read(input: InputStream, skipTypes: Set<Int> = emptySet()): RawLog = read(input, skipTypes, null)

    private fun read(input: InputStream, skipTypes: Set<Int>, expectedCounts: IntArray?): RawLog {
        val builder = RawLog.Builder(expectedCounts)
        val summary = forEachRecord(input, skipTypes) { builder.add(it) }
        return builder.build(truncated = summary.truncated, unknownRecords = summary.unknownRecords)
    }

    /**
     * Streams every record to [consumer] without holding the whole log in memory. Records whose
     * type is in [skipTypes] are read past without being decoded or counted.
     * @throws LogFormatException if the header is not a valid IMUL header.
     */
    fun forEachRecord(input: InputStream, skipTypes: Set<Int> = emptySet(), consumer: (LogRecord) -> Unit): Summary {
        var records = 0L
        var unknown = 0
        val truncated = scan(input, readPayload = true) { type, payload, len ->
            if (type in skipTypes) return@scan
            val b = ByteBuffer.wrap(payload, 0, len).order(ByteOrder.LITTLE_ENDIAN)
            val record = try {
                decode(type, b, len)
            } catch (e: RuntimeException) {
                // A corrupt payload of a known type: skip it rather than lose the whole log.
                unknown++
                null
            }
            if (record == null) {
                if (type !in KNOWN_TYPES) unknown++
            } else {
                consumer(record)
                records++
            }
        }
        return Summary(records, unknown, truncated)
    }

    /**
     * Number of records of each type, indexed by the type byte, without decoding anything. A
     * truncated final record is counted; a reader that follows simply ends up one short of it.
     * @throws LogFormatException if the header is not a valid IMUL header.
     */
    fun countByType(input: InputStream): IntArray {
        val counts = IntArray(256)
        scan(input, readPayload = false) { type, _, _ -> counts[type]++ }
        return counts
    }

    /**
     * Checks the file header, then calls [onRecord] for every complete record with its type and,
     * when [readPayload], its payload bytes (valid up to len; the array is reused). Returns true
     * when the stream ended inside a record.
     */
    private inline fun scan(
        input: InputStream,
        readPayload: Boolean,
        onRecord: (type: Int, payload: ByteArray, len: Int) -> Unit,
    ): Boolean {
        val ins = if (input is BufferedInputStream) input else BufferedInputStream(input, 1 shl 16)
        val header = ByteArray(LogFormat.HEADER_SIZE)
        if (readFully(ins, header, header.size) != header.size) throw LogFormatException("file shorter than header")
        val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { hb.get(it) }.toString(Charsets.US_ASCII)
        if (magic != LogFormat.MAGIC) throw LogFormatException("bad magic '$magic'")
        val version = hb.short.toInt()
        if (version > LogFormat.VERSION || version < 1) throw LogFormatException("unsupported format version $version")

        val rh = ByteArray(LogFormat.RECORD_HEADER_SIZE)
        var payload = ByteArray(if (readPayload) 1 shl 12 else 0)
        while (true) {
            val got = readFully(ins, rh, rh.size)
            if (got != rh.size) {
                // Zero bytes left is a clean end; a partial header is a truncated tail.
                return got > 0
            }
            val type = rh[0].toInt() and 0xFF
            val len = ByteBuffer.wrap(rh, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (len < 0) throw LogFormatException("negative record length")
            if (readPayload) {
                if (payload.size < len) payload = ByteArray(Integer.highestOneBit(len) shl 1)
                if (readFully(ins, payload, len) != len) return true
            } else if (!skipFully(ins, len)) {
                return true
            }
            onRecord(type, payload, len)
        }
    }

    private val KNOWN_TYPES = setOf(
        LogFormat.T_ACCEL, LogFormat.T_GYRO, LogFormat.T_MAG, LogFormat.T_BARO, LogFormat.T_GAME_ROT,
        LogFormat.T_ROT_VEC, LogFormat.T_STEP, LogFormat.T_ACCEL_UNCAL, LogFormat.T_GYRO_UNCAL,
        LogFormat.T_MAG_UNCAL, LogFormat.T_POSE, LogFormat.T_POINT_CLOUD, LogFormat.T_KEYFRAME,
        LogFormat.T_ANNOTATION, LogFormat.T_EVENT, LogFormat.T_META,
    )

    private fun decode(type: Int, b: ByteBuffer, len: Int): LogRecord? = when (type) {
        LogFormat.T_ACCEL -> AccelSample(b.long, b.float, b.float, b.float)
        LogFormat.T_GYRO -> GyroSample(b.long, b.float, b.float, b.float)
        LogFormat.T_MAG -> MagSample(b.long, b.float, b.float, b.float)
        LogFormat.T_BARO -> BaroSample(b.long, b.float)
        LogFormat.T_GAME_ROT -> RotationSample(b.long, b.float, b.float, b.float, b.float, b.float, RotationSource.GAME)
        LogFormat.T_ROT_VEC -> RotationSample(b.long, b.float, b.float, b.float, b.float, b.float, RotationSource.FUSED)
        LogFormat.T_STEP -> StepSample(b.long)
        LogFormat.T_ACCEL_UNCAL -> AccelUncalSample(b.long, b.float, b.float, b.float, b.float, b.float, b.float)
        LogFormat.T_GYRO_UNCAL -> GyroUncalSample(b.long, b.float, b.float, b.float, b.float, b.float, b.float)
        LogFormat.T_MAG_UNCAL -> MagUncalSample(b.long, b.float, b.float, b.float, b.float, b.float, b.float)
        LogFormat.T_POSE -> PoseSample(
            tNs = b.long, frameTimestampNs = b.long,
            tx = b.float, ty = b.float, tz = b.float,
            qx = b.float, qy = b.float, qz = b.float, qw = b.float,
            tracking = enumOrLast(TrackingState.entries, b.get().toInt() and 0xFF),
            failureReason = b.get().toInt() and 0xFF,
        )
        LogFormat.T_POINT_CLOUD -> {
            val t = b.long
            val n = b.int
            if (n < 0 || n * 16 > len - 12) throw IllegalStateException("bad point count $n")
            val arr = FloatArray(n * 4)
            for (i in arr.indices) arr[i] = b.float
            PointCloudSample(t, arr)
        }
        LogFormat.T_KEYFRAME -> {
            val t = b.long
            val tx = b.float; val ty = b.float; val tz = b.float
            val qx = b.float; val qy = b.float; val qz = b.float; val qw = b.float
            KeyframeSample(t, readString(b, b.short.toInt() and 0xFFFF), tx, ty, tz, qx, qy, qz, qw)
        }
        LogFormat.T_ANNOTATION -> {
            val t = b.long
            val kind = enumOrLast(AnnotationKind.entries, b.get().toInt() and 0xFF)
            AnnotationRecord(t, kind, readString(b, b.short.toInt() and 0xFFFF))
        }
        LogFormat.T_EVENT -> EventRecord(b.long, enumOrLast(EventKind.entries, b.get().toInt() and 0xFF))
        LogFormat.T_META -> MetaRecord(readString(b, b.int))
        else -> null
    }

    private fun <E : Enum<E>> enumOrLast(entries: List<E>, ordinal: Int): E =
        if (ordinal in entries.indices) entries[ordinal] else entries.last()

    private fun readString(b: ByteBuffer, len: Int): String {
        if (len < 0 || len > b.remaining()) throw IllegalStateException("bad string length $len")
        val bytes = ByteArray(len)
        b.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    /** Skips [len] bytes; false when the stream ended first. */
    private fun skipFully(ins: InputStream, len: Int): Boolean {
        var left = len.toLong()
        while (left > 0) {
            val n = ins.skip(left)
            if (n <= 0) {
                // skip() may return 0 before EOF; a read tells the two apart.
                if (ins.read() < 0) return false
                left--
            } else {
                left -= n
            }
        }
        return true
    }

    /** Reads up to [len] bytes into [dst]; returns how many were obtained (less than [len] at EOF). */
    private fun readFully(ins: InputStream, dst: ByteArray, len: Int): Int {
        var off = 0
        while (off < len) {
            val n = try {
                ins.read(dst, off, len - off)
            } catch (e: EOFException) {
                -1
            }
            if (n < 0) break
            off += n
        }
        return off
    }
}
