package com.stastyle.imumapper.ui.viewer

import com.stastyle.imumapper.capture.RecordingController
import com.stastyle.imumapper.data.db.PathResultEntity
import com.stastyle.imumapper.data.db.TripEntity
import com.stastyle.imumapper.data.db.TripStatus
import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.PathStats
import com.stastyle.imumapper.pipeline.pdr.OrientationEstimator
import com.stastyle.imumapper.render.ClimbTotals
import com.stastyle.imumapper.render.ElevationProfile
import com.stastyle.imumapper.render.PathProgress
import com.stastyle.imumapper.render.PathScene
import com.stastyle.imumapper.render.SceneModel
import com.stastyle.imumapper.render.SceneOptions
import com.stastyle.imumapper.ui.calibration.CalibrationMath
import com.stastyle.imumapper.ui.common.NO_VALUE
import com.stastyle.imumapper.ui.common.formatDistance
import com.stastyle.imumapper.ui.common.formatDuration
import com.stastyle.imumapper.ui.common.formatHeight
import com.stastyle.imumapper.ui.triplist.TripFormat
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToLong

/*
 * The viewer's texts and the small decisions behind them. Pure Kotlin, no Android or Compose, so
 * they are unit-tested on the JVM; the composables only lay them out.
 */

/** The viewer's tabs, in the order the segmented control shows them. */
internal enum class ViewerTab(val label: String) {
    PATH("Path"),
    THREE_D("3D"),
    GRAPH("Graph"),
    DETAILS("Details"),
}

/**
 * The top bar's second line: the run shown, and "raw" when the raw path is. Survey mode says
 * "Survey (beta)" in place of the run, which its menu checks, since the north chip, Undo, the ruler
 * and the menu leave the title too little room for both.
 */
internal fun viewerSubtitle(surveyMode: Boolean, run: String?, raw: Boolean): String? = when {
    surveyMode -> if (raw) "Survey (beta) · raw" else "Survey (beta)"
    run == null -> null
    raw -> "$run · raw"
    else -> run
}

/**
 * [viewerSubtitle] split into the parts the top bar draws as separate texts, led by the trip's
 * [date]. The date gives way first, then [label]; [raw] is never cut, since a cut "raw" would hide
 * which path is on screen. [text] is the whole line, for TalkBack.
 */
internal data class SubtitleParts(val date: String?, val label: String?, val raw: Boolean) {
    val text: String get() = listOfNotNull(date, label, RAW.takeIf { raw }).joinToString(SEPARATOR)

    companion object {
        const val SEPARATOR = " · "
        const val RAW = "raw"
    }
}

/**
 * The parts of the subtitle, or null when there is nothing to say. Survey mode leaves the date
 * out: its four actions leave the title room for the mode and "raw" only. "raw" needs a label,
 * because the raw path is always a run's.
 */
internal fun viewerSubtitleParts(surveyMode: Boolean, date: String?, run: String?, raw: Boolean): SubtitleParts? {
    val label = if (surveyMode) SURVEY_LABEL else run
    val shownDate = date.takeUnless { surveyMode }
    if (label == null && shownDate == null) return null
    return SubtitleParts(shownDate, label, raw && label != null)
}

private const val SURVEY_LABEL = "Survey (beta)"

/**
 * The line under the Raw path switch. It says why the switch changes nothing when it cannot (the run
 * predates stored raw paths, or loop closure and smoothing moved no point), and otherwise what raw
 * means. Survey mode draws no dimmed corrected path ([ViewerUiState.sceneOverlay]), so it says that
 * its stations, placed by time, follow the raw path instead.
 */
internal fun rawPathHint(ui: ViewerUiState): String = when {
    ui.rawUnavailable -> "Re-process this run to store its raw path"
    ui.showRaw && ui.rawResult == null && ui.result != null -> "Nothing was corrected in this run"
    ui.surveyMode -> "Before loop closure and smoothing; stations follow by time"
    else -> "Before loop closure and smoothing; the corrected path is dimmed"
}

/** "Run 3 · v12", plus the run's label ("Run 3 · v12 · PDR") when it has one. */
internal fun runLabel(run: PathResultEntity): String {
    val base = "Run ${run.runId} · v${run.pipelineVersion}"
    return if (run.label.isBlank()) base else "$base · ${run.label}"
}

/**
 * The canvas's grid chip ("5 m grid"): null without a scene or with the grid off. The spacing is the
 * one [PathScene.build] drew, taken from the same extent (the scene's bounds include an overlay run).
 */
internal fun gridChipText(scene: SceneModel?, options: SceneOptions): String? {
    if (scene == null || !options.showGrid) return null
    val size = scene.bounds.size
    val spacing = PathScene.gridSpacing(max(size.x, size.y))
    val number = if (spacing == Math.rint(spacing)) {
        spacing.toLong().toString()
    } else {
        String.format(Locale.US, "%.1f", spacing)
    }
    return "$number m grid"
}

/**
 * Whether a run's diagnostics record a pause. PDR writes "pauses", VIO "pauseCount" and, for the steps
 * it filled tracking gaps with, "pdr.pauses"; each is present only when the log had a pause. The
 * duration is the log's span, pauses included, so the tile says so when there were any.
 */
internal fun recordsPauses(diagnostics: Map<String, String>): Boolean =
    PAUSE_KEYS.any { key -> (diagnostics[key]?.trim()?.toIntOrNull() ?: 0) > 0 }

private val PAUSE_KEYS = listOf("pauses", "pauseCount", "pdr.pauses")

/**
 * A number for a stat tile: its [value], an optional [detail] line, and [spoken] when TalkBack needs
 * other words than the two lines (such as "from -0.7 m to +5.0 m" for "-0.7 … +5.0").
 */
internal data class StatText(val value: String, val detail: String? = null, val spoken: String? = null)

/** The Trip Summary's and the Graph tab's numbers, all from the result on screen (raw or corrected). */
internal object ViewerStats {
    /** Climb and descent count legs of at least this much, so walking bob and barometer noise are not climb. */
    const val CLIMB_DEAD_BAND_M = 0.5

    fun distance(stats: PathStats): StatText = StatText(formatDistance(stats.distanceM))

    fun duration(stats: PathStats, diagnostics: Map<String, String>): StatText =
        StatText(formatDuration(stats.durationS), "incl. pauses".takeIf { recordsPauses(diagnostics) })

    fun steps(stats: PathStats): StatText = StatText(if (stats.stepCount >= 0) stats.stepCount.toString() else NO_VALUE)

    /** The height range, with the lowest and highest point relative to the start under it. */
    fun vertical(stats: PathStats): StatText {
        if (!stats.minZ.isFinite() || !stats.maxZ.isFinite()) return StatText(NO_VALUE)
        val range = formatDistance(stats.maxZ - stats.minZ)
        return StatText(
            value = range,
            detail = "${SurveyFormat.signedMetres(stats.minZ)} … ${SurveyFormat.signedMetres(stats.maxZ)}",
            spoken = "$range, from ${formatHeight(stats.minZ)} to ${formatHeight(stats.maxZ)}",
        )
    }

    /** The end-to-start error before loop closure, to the centimetre, and its share of the distance. */
    fun closure(stats: PathStats): StatText {
        val error = stats.closureErrorM
        if (error == null || !error.isFinite() || error < 0.0) return StatText(NO_VALUE, "no loop marked")
        val value = String.format(Locale.US, "%.2f m", error)
        val share = if (stats.distanceM > 0.0 && stats.distanceM.isFinite()) {
            String.format(Locale.US, "%.1f %% of distance", error / stats.distanceM * 100.0)
        } else {
            null
        }
        return StatText(value, share)
    }

    fun vio(stats: PathStats): StatText {
        val fraction = stats.vioFraction
        if (!fraction.isFinite()) return StatText(NO_VALUE)
        return StatText("${(fraction * 100.0).roundToLong()} %", "of points")
    }

    fun min(stats: PathStats): StatText = StatText(formatHeight(stats.minZ))

    fun max(stats: PathStats): StatText = StatText(formatHeight(stats.maxZ))

    /** The last point's height against the first's; the path starts at 0, so this is where it ended up. */
    fun netChange(points: List<PathPoint>): StatText {
        if (points.isEmpty()) return StatText(NO_VALUE)
        return StatText(formatHeight(points.last().p.z - points.first().p.z))
    }

    fun climb(totals: ClimbTotals): StatText = StatText(formatDistance(totals.climbM), "rises ≥ 0.5 m")

    fun descent(totals: ClimbTotals): StatText = StatText(formatDistance(totals.descentM), "drops ≥ 0.5 m")
}

/**
 * Whether the elevation chart has something to draw: two points or more, spread along some length. A standing
 * recording would give a single sample at distance 0.
 */
internal fun showsElevation(pointCount: Int, profile: ElevationProfile): Boolean =
    pointCount >= 2 && profile.size >= 2 && profile.totalM >= PathProgress.MIN_LENGTH_M

/** A y-axis label of the elevation chart: "6 m", "0 m", "-1.5 m", never "-0 m". */
internal fun axisLabel(metres: Double): String {
    if (!metres.isFinite()) return NO_VALUE
    val tenths = (metres * 10.0).roundToLong()
    return if (tenths % 10L == 0L) {
        "${tenths / 10L} m"
    } else {
        String.format(Locale.US, "%.1f m", tenths / 10.0)
    }
}

/** A raw log's size: "850 kB", "140.2 MB", "1.25 GB" (decimal units, as Android shows files); [NO_VALUE] for none. */
internal fun fileSize(bytes: Long): String = when {
    bytes <= 0L -> NO_VALUE
    bytes < 1_000_000L -> "${max(1L, (bytes / 1000.0).roundToLong())} kB"
    bytes < 1_000_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1e6)
    else -> String.format(Locale.US, "%.2f GB", bytes / 1e9)
}

/**
 * Where +Y of the shown result points: "Magnetic", or "Relative (reason)"; "Not recorded" for a result from
 * before the diagnostic existed, which may well be magnetic; null without a result.
 */
internal fun northText(diagnostics: Map<String, String>?): String? {
    if (diagnostics == null) return null
    if (!diagnostics.containsKey(OrientationEstimator.NORTH_REFERENCE)) return "Not recorded"
    val problem = CalibrationMath.northProblem(diagnostics) ?: return "Magnetic"
    return "Relative ($problem)"
}

/** One line of the Details tab's trip facts. */
internal data class TripFact(val label: String, val value: String)

/**
 * The Details tab's trip facts. [endedUnexpectedly] is true for a trip the recorder did not finish
 * ([RecordingController.ENDED_UNEXPECTEDLY_NOTE]); the screen then shows the warning.
 */
internal data class TripFacts(val rows: List<TripFact>, val endedUnexpectedly: Boolean)

/**
 * Mode, carry, start, end, raw log size and north for [trip]. A trip that ended unexpectedly has an
 * end time that is when the app noticed, not when recording stopped, so the end is "unknown" and,
 * when a run gives the log's length [runDurationS], "Last data" is the start plus that length. [date]
 * formats an epoch time (TripFormat.date on screen), so tests do not depend on the phone's locale.
 */
internal fun tripFacts(trip: TripEntity, runDurationS: Double?, north: String?, date: (Long) -> String): TripFacts {
    val unexpected = trip.notes == RecordingController.ENDED_UNEXPECTEDLY_NOTE
    val rows = ArrayList<TripFact>()
    rows += TripFact("Mode", TripFormat.modeLabel(trip.mode))
    rows += TripFact("Carried in", TripFormat.carryLabel(trip.carryPosition))
    rows += TripFact("Started", date(trip.startedAtEpochMs))
    if (unexpected) {
        rows += TripFact("Ended", "unknown")
        if (runDurationS != null && runDurationS.isFinite() && runDurationS >= 0.0) {
            rows += TripFact("Last data", "≈ " + date(trip.startedAtEpochMs + (runDurationS * 1000.0).roundToLong()))
        }
    } else {
        val ended = trip.endedAtEpochMs?.let(date)
            ?: if (trip.status == TripStatus.RECORDING) "still recording" else NO_VALUE
        rows += TripFact("Ended", ended)
    }
    rows += TripFact("Raw log", fileSize(trip.rawLogSizeBytes))
    rows += TripFact("North", north ?: NO_VALUE)
    return TripFacts(rows, unexpected)
}
