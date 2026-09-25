package com.stastyle.imumapper.pipeline.core

/**
 * The [PAUSE, RESUME) intervals of a log, from its [EventRecord]s. The recorder keeps logging
 * sensors, poses and steps while paused, so the pipeline is the one that leaves them out: PDR
 * uses no step and no barometric change inside a pause, and the VIO fuser holds the position.
 * A PAUSE that is never resumed lasts to the end of time; a RESUME without a PAUSE and a second
 * PAUSE inside a pause are ignored. Intervals are sorted and disjoint.
 */
class PauseIntervals private constructor(private val starts: LongArray, private val ends: LongArray) {

    val size: Int get() = starts.size
    val isEmpty: Boolean get() = starts.isEmpty()

    fun startNs(i: Int): Long = starts[i]
    fun endNs(i: Int): Long = ends[i]

    /** True when [tNs] lies inside a pause (start inclusive, end exclusive). */
    fun contains(tNs: Long): Boolean {
        val i = lastStartAtOrBefore(tNs)
        return i >= 0 && tNs < ends[i]
    }

    /** Total paused time inside [fromNs, toNs], 0 for an empty or backwards range. */
    fun coveredNs(fromNs: Long, toNs: Long): Long {
        if (toNs <= fromNs) return 0L
        var covered = 0L
        for (i in starts.indices) {
            val a = maxOf(fromNs, starts[i])
            val b = minOf(toNs, ends[i])
            if (b > a) covered += b - a
        }
        return covered
    }

    /**
     * Calls [block] with each maximal sub-range of [fromNs, toNs] that lies outside every pause,
     * in time order. Used to integrate a signal over a range while skipping the paused parts.
     */
    inline fun forEachUnpaused(fromNs: Long, toNs: Long, block: (Long, Long) -> Unit) {
        if (toNs <= fromNs) return
        var cursor = fromNs
        for (i in 0 until size) {
            val s = startNs(i)
            val e = endNs(i)
            if (e <= cursor) continue
            if (s >= toNs) break
            if (s > cursor) block(cursor, s)
            cursor = maxOf(cursor, e)
            if (cursor >= toNs) return
        }
        if (cursor < toNs) block(cursor, toNs)
    }

    private fun lastStartAtOrBefore(tNs: Long): Int {
        var lo = 0
        var hi = starts.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= tNs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    companion object {
        val NONE: PauseIntervals = PauseIntervals(LongArray(0), LongArray(0))

        fun of(events: List<EventRecord>): PauseIntervals {
            val starts = ArrayList<Long>()
            val ends = ArrayList<Long>()
            var open = Long.MIN_VALUE
            var paused = false
            for (e in events.sortedBy { it.tNs }) {
                when (e.kind) {
                    EventKind.PAUSE -> if (!paused) {
                        open = e.tNs
                        paused = true
                    }
                    EventKind.RESUME -> if (paused) {
                        if (e.tNs > open) {
                            starts.add(open)
                            ends.add(e.tNs)
                        }
                        paused = false
                    }
                    else -> {}
                }
            }
            if (paused) {
                starts.add(open)
                ends.add(Long.MAX_VALUE)
            }
            if (starts.isEmpty()) return NONE
            return PauseIntervals(starts.toLongArray(), ends.toLongArray())
        }
    }
}
