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
 * first large step to its last.
 *
 * Everything is measured on the unsmoothed height averaged over each step's interval (from the
 * step before, or the trip start, to the step). A low-passed height would spread a pressure jump
 * over as many steps as its settling time and let it pass as a climb, and it would carry a jump
 * just before or after a climb into it. The change of a step is its interval's mean minus the one
 * before; a climb's steps take their changes, every other step takes none, so a climb adds the
 * height between the interval before it and its last interval. A step interval that touches a
 * pause gives no change and ends every run, so nothing climbed during a pause reaches the path.
 *
 * Held-out changes are gone for good, so the path does not return to the barometer by itself.
 * When pressure wobble passes some of its swings as climbs and holds out others, the path would
 * drift further with every swing. A run right after a climb that brings the height at least half
 * way back is therefore taken as well: a wobble then passes whole, as it would without the filter,
 * and a real climb followed by a short way back down keeps both.
 *
 * Limits: a pressure jump in the direction of a climb, right before or after it, joins the climb;
 * wobble slower than about 4 s passes; a slope gentle enough that noise hides its steps is mostly
 * lost, and a climb of fewer steps than `minSteps` is lost entirely.
 */
class Climbs private constructor(
    private val intervalStartNs: LongArray,
    private val stepNs: LongArray,
    private val changeM: DoubleArray,
    private val firsts: IntArray,
    private val lasts: IntArray,
    /**
     * Runs held out that would have moved the height by [HELD_REPORT_M] or more: pressure changes
     * on flat ground, or climbs with too few steps. Smaller runs are barometer noise.
     */
    val heldRuns: Int,
    /** Total height change of the [heldRuns], metres, all taken as positive. */
    val heldM: Double,
) {
    /** Number of climbs. */
    val size: Int get() = firsts.size

    /** First step of climb [k]; the path starts to change height after the step before it. */
    fun firstStep(k: Int): Int = firsts[k]

    /** Last step of climb [k]. */
    fun lastStep(k: Int): Int = lasts[k]

    /**
     * Height change step [i] takes into the path when the path starts at [fromNs]: its share of the
     * climb, scaled down when [fromNs] cuts into its interval, as a VIO gap does.
     */
    fun changeAfter(i: Int, fromNs: Long): Double {
        val c = changeM[i]
        if (c == 0.0) return 0.0
        val lo = intervalStartNs[i]
        val hi = stepNs[i]
        if (fromNs <= lo || hi <= lo) return c
        if (fromNs >= hi) return 0.0
        return c * ((hi - fromNs).toDouble() / (hi - lo).toDouble())
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
            // The last run of minClimbM or more before the current one, and whether it was a climb.
            var previous: Run? = null
            var previousClimbed = false
            for (r in runs) {
                val climb = r.large >= minSteps
                // A run right after a climb that brings the height at least half way back is taken
                // too, so the two cancel. Otherwise pressure wobble, whose swings pass as climbs or not
                // by chance, would leave every climb it passed and drift the path away.
                val back = !climb && previousClimbed && previous != null && r.dir == -previous.dir &&
                    abs(r.total) >= 0.5 * abs(previous.taken(change)) &&
                    r.first - previous.lastTaken <= minSteps && !pausedBetween(paused, previous.lastTaken, r.first)
                if (climb || back) {
                    for (k in r.firstTaken..r.lastTaken) taken[k] = change[k]
                    firsts.add(r.firstTaken)
                    lasts.add(r.lastTaken)
                } else if (abs(r.total) >= HELD_REPORT_M) {
                    held++
                    heldM += abs(r.total)
                }
                if (abs(r.total) >= minClimbM) {
                    previous = r
                    previousClimbed = climb || back
                }
            }
            return Climbs(from, t, taken, firsts.toIntArray(), lasts.toIntArray(), held, heldM)
        }

        /** True when a step interval after [a] up to [b] touches a pause. */
        private fun pausedBetween(paused: BooleanArray, a: Int, b: Int): Boolean {
            for (k in a + 1..b) if (paused[k]) return true
            return false
        }
    }
}
