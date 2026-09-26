package com.stastyle.imumapper.pipeline.log

import com.stastyle.imumapper.pipeline.core.BaroSample
import com.stastyle.imumapper.pipeline.core.RotationSample
import com.stastyle.imumapper.pipeline.core.RotationSource
import com.stastyle.imumapper.pipeline.core.StepSample

/*
 * Sample lists stored as columns of primitives.
 *
 * The recorder logs ten sensor streams at up to 500 Hz, so a half-hour walk is several million
 * samples. Held as data class objects in an ArrayList they cost about 45 bytes each on ART, and
 * a long trip then no longer fits the app's heap when it is processed. Here a stream is a
 * LongArray of timestamps plus a FloatArray of values (20 bytes per accelerometer sample) and the
 * data class is created on access, so the rest of the pipeline keeps reading a plain List.
 */

/**
 * Growable timestamp column plus [width] floats per row. Give it the exact count when it is
 * known ([LogReader] counts a file first): the columns are then allocated once and handed to
 * the list as they are, with no growth or trim copies. Not for use after [times] or [values]
 * has been called, since the list may then share the arrays.
 */
internal class ColumnBuilder(private val width: Int, initialCapacity: Int = DEFAULT_CAPACITY) {
    private var times = LongArray(initialCapacity.coerceAtLeast(1))
    private var values = FloatArray(times.size * width)
    var size: Int = 0
        private set

    private fun next(t: Long): Int {
        if (size == times.size) {
            val n = times.size * 2
            times = times.copyOf(n)
            values = values.copyOf(n * width)
        }
        times[size] = t
        return size++ * width
    }

    fun add(t: Long) {
        require(width == 0)
        next(t)
    }

    fun add(t: Long, a: Float) {
        require(width == 1)
        // next() may replace the arrays, so it must run before the array reference is taken.
        val o = next(t)
        values[o] = a
    }

    fun add(t: Long, a: Float, b: Float, c: Float) {
        require(width == 3)
        val o = next(t)
        values[o] = a
        values[o + 1] = b
        values[o + 2] = c
    }

    fun add(t: Long, a: Float, b: Float, c: Float, d: Float, e: Float) {
        require(width == 5)
        val o = next(t)
        values[o] = a
        values[o + 1] = b
        values[o + 2] = c
        values[o + 3] = d
        values[o + 4] = e
    }

    fun add(t: Long, a: Float, b: Float, c: Float, d: Float, e: Float, f: Float) {
        require(width == 6)
        val o = next(t)
        values[o] = a
        values[o + 1] = b
        values[o + 2] = c
        values[o + 3] = d
        values[o + 4] = e
        values[o + 5] = f
    }

    fun times(): LongArray = if (size == times.size) times else times.copyOf(size)
    fun values(): FloatArray = if (size == times.size) values else values.copyOf(size * width)

    companion object {
        const val DEFAULT_CAPACITY = 1024
    }
}

/** Timestamp plus three floats per sample: accelerometer, gyroscope, magnetometer. */
internal class TripletList<T>(
    private val times: LongArray,
    private val values: FloatArray,
    private val make: (Long, Float, Float, Float) -> T,
) : AbstractList<T>(), RandomAccess {
    override val size: Int get() = times.size

    override fun get(index: Int): T {
        val o = index * 3
        return make(times[index], values[o], values[o + 1], values[o + 2])
    }
}

/** Timestamp plus six floats per sample: the uncalibrated streams with their bias estimate. */
internal class SextetList<T>(
    private val times: LongArray,
    private val values: FloatArray,
    private val make: (Long, Float, Float, Float, Float, Float, Float) -> T,
) : AbstractList<T>(), RandomAccess {
    override val size: Int get() = times.size

    override fun get(index: Int): T {
        val o = index * 6
        return make(times[index], values[o], values[o + 1], values[o + 2], values[o + 3], values[o + 4], values[o + 5])
    }
}

internal class BaroList(private val times: LongArray, private val values: FloatArray) :
    AbstractList<BaroSample>(), RandomAccess {
    override val size: Int get() = times.size
    override fun get(index: Int): BaroSample = BaroSample(times[index], values[index])
}

internal class StepList(private val times: LongArray) : AbstractList<StepSample>(), RandomAccess {
    override val size: Int get() = times.size
    override fun get(index: Int): StepSample = StepSample(times[index])
}

/** Rotation vectors: five floats plus the source, kept as its ordinal in a byte column. */
internal class RotationList(
    private val times: LongArray,
    private val values: FloatArray,
    private val sources: ByteArray,
) : AbstractList<RotationSample>(), RandomAccess {
    override val size: Int get() = times.size

    override fun get(index: Int): RotationSample {
        val o = index * 5
        return RotationSample(
            times[index], values[o], values[o + 1], values[o + 2], values[o + 3], values[o + 4],
            RotationSource.entries[sources[index].toInt()],
        )
    }

    class Builder(initialCapacity: Int = ColumnBuilder.DEFAULT_CAPACITY) {
        private val columns = ColumnBuilder(5, initialCapacity)
        private var sources = ByteArray(initialCapacity.coerceAtLeast(1))

        fun add(s: RotationSample) {
            if (columns.size == sources.size) sources = sources.copyOf(sources.size * 2)
            sources[columns.size] = s.source.ordinal.toByte()
            columns.add(s.tNs, s.qx, s.qy, s.qz, s.qw, s.headingAccuracyRad)
        }

        fun build(): RotationList {
            val n = columns.size
            return RotationList(columns.times(), columns.values(), if (n == sources.size) sources else sources.copyOf(n))
        }
    }

    companion object {
        /** The samples of [source] from [all], in order, as a compact list of their own. */
        fun withSource(all: List<RotationSample>, source: RotationSource): RotationList {
            var n = 0
            for (s in all) if (s.source == source) n++
            val b = Builder(n)
            for (s in all) if (s.source == source) b.add(s)
            return b.build()
        }
    }
}
