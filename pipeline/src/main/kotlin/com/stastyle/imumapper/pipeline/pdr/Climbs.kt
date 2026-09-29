package com.stastyle.imumapper.pipeline.pdr

import com.stastyle.imumapper.pipeline.core.PauseIntervals
import kotlin.math.abs

/**
 * The barometric height change each step takes into the path when
 * [com.stastyle.imumapper.pipeline.core.PipelineConfig.baroConfirmSteps] is on.
 *
 * Indoors the pressure also changes on flat ground: rooms, corridors and stairwells are held at
 * different pressures, doors send pulses through a building and wind shakes it, and each pascal
 * reads as 8 cm of height. Such changes come and go within a few steps, while stairs and slopes
 * keep the height moving the same way step after step. So the height changes only in climbs: runs
 * of consecutive steps that all move it the same way, of which at least `minSteps` move it by
 * `minStepM` or more. The smaller steps of a run neither count nor break it, because on a gentle
 * slope the barometer noise pushes some steps under `minStepM`; a climb spans the run from its
 * first large step to its last. A run shaped like a jump, with [JUMP_SHARE] of it in two
 * consecutive changes (and any run of two steps or fewer), is no climb however many large steps
 * noise gives it: stairs spread their height over every step.
 *
 * Everything is measured on the unsmoothed height averaged over each step's interval (from the
 * step before, or the trip start, to the step). A low-passed height would spread a pressure jump
 * over as many steps as its settling time and let it pass as a climb, and it would carry a jump
 * just before or after a climb into it. The change of a step is its interval's mean minus the one
 * before; a climb's steps take their changes, every other step takes none, so a climb adds the
 * height between the interval before it and its last interval. A step interval that touches a
 * pause gives no change and ends every run, so nothing climbed during a pause reaches the path.
 * A jump inside one step interval shows as two large changes, and a flight of stairs as about two
 * more changes than it has stairs.
 *
 * Held-out changes do not come back by themselves, which lets the path drift from the barometer in
 * two ways, and each has a remedy:
 * - Pressure wobble passes some swings as climbs and holds out others. A run right after a climb
 *   that brings the height at least half way back is therefore taken as well, unless it is a jump
 *   the climb was not: most of it in two consecutive changes, the larger of them at least twice
 *   the climb's typical step. So a door zone right after a flight stays out, while a way back at
 *   the pace of its climb is taken, and so is the way out of a zone that slipped in as a climb. A
 *   wobble then mostly passes whole, as it would without the filter, and a real climb followed by
 *   a short way back down keeps both.
 * - Wobble slower than a climb, and a slope too gentle for its steps to count, still pile up held
 *   changes. The path is therefore kept within `maxHeldM` of the barometer's own height (the step
 *   means with the paused changes left out): what is held beyond that comes through. The band is
 *   centred where a path starts, so a VIO gap does not inherit what PDR held out while ARCore was
 *   tracking.
 *
 * Limits: a pressure jump in the direction of a climb, right before or after it, joins the climb;
 * a pressure change spread over a second or more can pass as a climb or a way back; wobble slower
 * than about 4 s passes in part; the path stays within `maxHeldM` of the barometer, so a gentle
 * slope loses up to that much, or up to twice it on the way back up a ramp walked down, and once
 * held-out height has used the band up, pressure changes the same way pass; a change spread over
 * fewer than `minSteps` large changes comes through only beyond `maxHeldM` or as a way back.
 */
class Climbs private constructor(
    private val intervalStartNs: LongArray,
    private val stepNs: LongArray,
    /** Height change of the barometer at each step, metres: 0 across a pause and at the first step. */
    private val change: DoubleArray,
    /** The part of [change] the climbs take. */
    private val taken: DoubleArray,
    private val maxHeldM: Double,
    private val firsts: IntArray,
    private val lasts: IntArray,
    /**
     * Runs held out that would have moved the height by [HELD_REPORT_M] or more: pressure changes
     * on flat ground, or climbs with too few steps. Smaller runs are barometer noise.
     */
    val heldRuns: Int,
    /** Sizes of the [heldRuns] added up, metres, up and down alike: not a net height. */
    val heldM: Double,
    /** Height the `maxHeldM` limit let through, metres, up and down alike. */
    val limitM: Double,
) {
    /** Number of climbs, ways back included. */
    val size: Int get() = firsts.size

    /** First step of climb [k], at least 1; the path starts to change height after the step before it. */
    fun firstStep(k: Int): Int = firsts[k]

    /** Last step of climb [k]. */
    fun lastStep(k: Int): Int = lasts[k]

    /**
     * Height change of the path at steps [first] until [last] when it starts at [fromNs]: the
     * climbs, kept within `maxHeldM` of the barometer's own height from there on. A step whose
     * interval [fromNs] cuts into, as a VIO gap does, takes its share of the interval.
     */
    fun changes(first: Int, last: Int, fromNs: Long): DoubleArray {
        val out = DoubleArray(maxOf(0, last - first))
        var offset = 0.0 // path height minus the barometer's, since fromNs
        for (i in first until last) {
            val f = share(i, fromNs)
            val next = (offset + (taken[i] - change[i]) * f).coerceIn(-maxHeldM, maxHeldM)
            out[i - first] = change[i] * f + next - offset
            offset = next
        }
        return out
    }

    private fun share(i: Int, fromNs: Long): Double {
        val lo = intervalStartNs[i]
        val hi = stepNs[i]
        if (fromNs <= lo || hi <= lo) return 1.0
        if (fromNs >= hi) return 0.0
        return (hi - fromNs).toDouble() / (hi - lo).toDouble()
    }

    /**
     * Steps [first]..[last] that all move the height in direction [dir]; [large] of them by
     * `minStepM` or more. A climb takes [firstTaken]..[lastTaken], from its first large step to its
     * last: small steps at the ends are mostly noise that happens to lean the same way.
     */
    private class Run(
        val first: Int,
        val last: Int,
        val dir: Int,
        val large: Int,
        val total: Double,
        val firstTaken: Int,
        val lastTaken: Int,
    ) {
        fun taken(change: DoubleArray): Double {
            var sum = 0.0
            for (k in firstTaken..lastTaken) sum += change[k]
            return sum
        }

        /**
         * True when two consecutive changes carry [JUMP_SHARE] or more of the run, as a pressure jump
         * does, and for any run of two steps or fewer.
         */
        fun jumpShaped(change: DoubleArray): Boolean {
            if (last - first < 2) return true
            var most = 0.0
            for (k in first until last) most = maxOf(most, abs(change[k] + change[k + 1]))
            return most >= JUMP_SHARE * abs(total)
        }

        /** True when the run is [jumpShaped] and its largest change is [JUMP_STEP_RATIO] times [typicalM] or more. */
        fun isJumpAgainst(change: DoubleArray, typicalM: Double): Boolean {
            if (!jumpShaped(change)) return false
            var largest = 0.0
            for (k in first..last) largest = maxOf(largest, abs(change[k]))
            return largest >= JUMP_STEP_RATIO * typicalM
        }

        /** Median size of the taken changes, metres: the pace of a climb. */
        fun typicalStep(change: DoubleArray): Double {
            val sizes = DoubleArray(lastTaken - firstTaken + 1) { abs(change[firstTaken + it]) }
            sizes.sort()
            val m = sizes.size / 2
            return if (sizes.size % 2 == 1) sizes[m] else (sizes[m - 1] + sizes[m]) / 2
        }

        companion object {
            fun of(first: Int, last: Int, dir: Int, change: DoubleArray, minStepM: Double): Run {
                var large = 0
                var firstLarge = -1
                var lastLarge = -1
                var total = 0.0
                for (k in first..last) {
                    if (abs(change[k]) >= minStepM) {
                        large++
                        if (firstLarge < 0) firstLarge = k
                        lastLarge = k
                    }
                    total += change[k]
                }
                return if (large > 0) {
                    Run(first, last, dir, large, total, firstLarge, lastLarge)
                } else {
                    Run(first, last, dir, 0, total, first, last)
                }
            }
        }
    }

    companion object {
        /** Smallest height change of a held-out run that [heldRuns] counts, metres. */
        const val HELD_REPORT_M: Double = 0.25

        /** Share of a run in two consecutive changes from which it is shaped like a jump. */
        const val JUMP_SHARE: Double = 0.75

        /** How many times a climb's typical step a jump's largest change must be. */
        const val JUMP_STEP_RATIO: Double = 2.0

        /**
         * Finds the climbs of [steps] in the unsmoothed height [raw]. [tripStartNs] opens the
         * interval of the first step. [minSteps] must be at least 1.
         */
        fun detect(
            raw: AltitudeTrack,
            steps: DetectedSteps,
            pauses: PauseIntervals,
            tripStartNs: Long,
            minSteps: Int,
            minStepM: Double,
            maxHeldM: Double,
        ): Climbs {
            require(minSteps >= 1) { "minSteps must be at least 1: $minSteps" }
            val n = steps.size
            val t = steps.tNs
            val from = LongArray(n) { i -> if (i == 0) minOf(tripStartNs, t[0]) else t[i - 1] }
            val mean = DoubleArray(n)
            val paused = BooleanArray(n)
            for (i in 0 until n) {
                mean[i] = raw.meanOver(from[i], t[i])
                paused[i] = pauses.coveredNs(from[i], t[i]) > 0L
            }
            // Change and direction from the interval before each step to its own: +1 up, -1 down,
            // 0 across a pause. The first step has no interval before it.
            val change = DoubleArray(n)
            val dir = IntArray(n)
            for (i in 1 until n) {
                if (paused[i] || paused[i - 1]) continue
                change[i] = mean[i] - mean[i - 1]
                dir[i] = if (change[i] > 0.0) 1 else if (change[i] < 0.0) -1 else 0
            }

            // Runs: consecutive steps that all move the height the same way.
            val runs = ArrayList<Run>()
            var first = 1
            while (first < n) {
                if (dir[first] == 0) {
                    first++
                    continue
                }
                var last = first
                while (last + 1 < n && dir[last + 1] == dir[first]) last++
                runs.add(Run.of(first, last, dir[first], change, minStepM))
                first = last + 1
            }

            val taken = DoubleArray(n)
            val firsts = ArrayList<Int>()
            val lasts = ArrayList<Int>()
            var held = 0
            var heldM = 0.0
            val minClimbM = minSteps * minStepM
            // The last taken run, or run of minClimbM or more, and whether it was taken.
            var previous: Run? = null
            var previousTaken = false
            for (r in runs) {
                val climb = r.large >= minSteps && !r.jumpShaped(change)
                val back = !climb && previousTaken && previous != null && r.dir == -previous.dir &&
                    abs(r.total) >= 0.5 * abs(previous.taken(change)) &&
                    r.first - previous.lastTaken <= minSteps &&
                    !pausedBetween(paused, previous.lastTaken, r.first) &&
                    (previous.jumpShaped(change) || !r.isJumpAgainst(change, previous.typicalStep(change)))
                if (climb || back) {
                    for (k in r.firstTaken..r.lastTaken) taken[k] = change[k]
                    firsts.add(r.firstTaken)
                    lasts.add(r.lastTaken)
                    previous = r
                    previousTaken = true
                } else {
                    if (abs(r.total) >= HELD_REPORT_M) {
                        held++
                        heldM += abs(r.total)
                    }
                    if (abs(r.total) >= minClimbM) {
                        previous = r
                        previousTaken = false
                    }
                }
            }

            // What the limit lets through over the whole trip.
            var offset = 0.0
            var limitM = 0.0
            for (i in 0 until n) {
                val free = offset + taken[i] - change[i]
                val next = free.coerceIn(-maxHeldM, maxHeldM)
                limitM += abs(next - free)
                offset = next
            }
            return Climbs(
                from, t, change, taken, maxHeldM, firsts.toIntArray(), lasts.toIntArray(), held, heldM, limitM,
            )
        }

        /** True when a step interval after [a] up to [b] touches a pause. */
        private fun pausedBetween(paused: BooleanArray, a: Int, b: Int): Boolean {
            for (k in a + 1..b) if (paused[k]) return true
            return false
        }
    }
}
