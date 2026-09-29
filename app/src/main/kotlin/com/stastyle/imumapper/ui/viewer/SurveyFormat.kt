package com.stastyle.imumapper.ui.viewer

import com.stastyle.imumapper.pipeline.survey.CompassReference
import com.stastyle.imumapper.pipeline.survey.Detail
import com.stastyle.imumapper.pipeline.survey.LegMeasure
import com.stastyle.imumapper.pipeline.survey.LegTotals
import com.stastyle.imumapper.pipeline.survey.NorthSolution
import com.stastyle.imumapper.pipeline.survey.ReferenceFit
import com.stastyle.imumapper.pipeline.survey.ReferenceLine
import com.stastyle.imumapper.pipeline.survey.StretchMeasure
import com.stastyle.imumapper.pipeline.survey.SurveyCsv
import com.stastyle.imumapper.pipeline.survey.TraverseLeg
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Display strings for Survey mode. Numbers use Locale.US, so a copied line pastes into a spreadsheet
 * the same on every phone; a value that cannot be measured shows [DASH]. Metres and rotations are
 * rounded before formatting, so "-0.0" never appears.
 */
object SurveyFormat {
    const val DASH: String = "—"

    /** Shown by the North sheet when NorthSolution.disagree. */
    const val DISAGREE: String = "References disagree: drift during the walk, or a misread bearing"

    private const val SEPARATOR = " · "
    private const val CURVED_MARK = "⌒"
    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)

    /** M only when north is known to be magnetic; an unknown north is never shown as magnetic. */
    fun suffix(magnetic: Boolean): String = if (magnetic) "M" else "R"

    fun metres(m: Double): String = String.format(Locale.US, "%.1f m", tenths(m))

    /** Height changes carry their sign even at zero, so up and down read alike. */
    fun signedMetres(m: Double): String = String.format(Locale.US, "%+.1f", tenths(m))

    /** Three digits like a compass card; 359.6 rounds to 000, not 360. */
    fun azimuth(deg: Double?, magnetic: Boolean): String {
        val whole = wholeAzimuth(deg) ?: return DASH
        return String.format(Locale.US, "%03d° %s", whole, suffix(magnetic))
    }

    /** The arrow reads at a glance; a level leg gets none. */
    fun slope(deg: Double): String {
        val whole = Math.round(deg)
        return when {
            whole > 0 -> "▲ +$whole°"
            whole < 0 -> "▼ $whole°"
            else -> "0°"
        }
    }

    fun signedDegrees(deg: Double): String = String.format(Locale.US, "%+d°", Math.round(deg))

    fun grade(pct: Double?): String = if (pct == null || !pct.isFinite()) DASH else "${Math.round(pct)} %"

    /** North rotations in tenths, the steppers' finest step being half a degree. */
    fun rotation(deg: Double): String = String.format(Locale.US, "%+.1f°", tenths(deg))

    /** The readout's second line; the curve mark says the path strays from the chord. */
    fun details(leg: LegMeasure): String = listOf(
        "horiz ${number(leg.horizontalM)}",
        "Δh ${signedMetres(leg.heightChangeM)}",
        grade(leg.gradePct),
        "path ${pathNumber(leg)}",
    ).joinToString(SEPARATOR)

    /** Stretch selections only: the passage direction and how straight the path is. */
    fun stretchLine(measure: StretchMeasure, magnetic: Boolean): String {
        val straight = measure.leg.straightness?.let { String.format(Locale.US, "%.2f", it) } ?: DASH
        return "fitted ${azimuth(measure.fittedAzimuthDeg, magnetic)}${SEPARATOR}straight $straight"
    }

    /** The line a long press on the readout copies, readable on its own in a note or a message. */
    fun copyLine(from: String, to: String, leg: LegMeasure, magnetic: Boolean): String =
        "$from → $to: ${metres(leg.lengthM)}, horiz ${metres(leg.horizontalM)}, " +
            "${azimuth(leg.azimuthDeg, magnetic)}, ${signedDegrees(leg.slopeDeg)} " +
            "(Δh ${signedMetres(leg.heightChangeM)} m), path ${metres(leg.pathM)}"

    /** The top bar's north chip: the rotation in use and how many compass readings set it. */
    fun northChip(north: NorthSolution, magnetic: Boolean): String {
        val chip = "N ${rotation(north.rotationDeg)} ${suffix(magnetic)}"
        return when (val used = north.usedCount) {
            0 -> chip
            1 -> "${chip}${SEPARATOR}1 ref"
            else -> "${chip}${SEPARATOR}$used refs"
        }
    }

    /**
     * The scrubber's label: the clock time at the cursor when the trip's start is known (it matches
     * a note taken underground), else the time since the path's start.
     */
    fun cursorLabel(startedAtEpochMs: Long?, elapsedNs: Long, distanceM: Double, zone: ZoneId): String {
        val time = if (startedAtEpochMs == null) {
            formatDuration(elapsedNs / 1e9)
        } else {
            CLOCK.withZone(zone).format(Instant.ofEpochMilli(startedAtEpochMs + elapsedNs / 1_000_000L))
        }
        return "$time$SEPARATOR${metres(distanceM)}"
    }

    /** The share sheet's text: which trip, run and north a CSV was measured on. */
    fun shareText(tripName: String, runId: Int, raw: Boolean, north: NorthSolution): String =
        "$tripName · Run $runId${if (raw) " (raw path)" else ""}, ${SurveyCsv.correctionText(north)}"

    /** The Legs sheet's title: which north the azimuths are from, readable however narrow the header. */
    fun legsTitle(magnetic: Boolean): String =
        "Legs · azimuths from ${if (magnetic) "magnetic" else "relative"} north (${suffix(magnetic)})"

    /** The header carries the suffix so every azimuth cell stays short. */
    fun tableHeader(magnetic: Boolean): List<String> =
        listOf("From", "To", "Length", "Azimuth ${suffix(magnetic)}", "Slope", "Δh", "Path")

    fun tableRow(leg: TraverseLeg): List<String> {
        val m = leg.measure
        val azimuth = wholeAzimuth(m.azimuthDeg)?.let { String.format(Locale.US, "%03d°", it) } ?: DASH
        return listOf(
            leg.from.name,
            leg.to.name,
            number(m.lengthM),
            azimuth,
            signedDegrees(m.slopeDeg),
            signedMetres(m.heightChangeM),
            pathNumber(m),
        )
    }

    /** Height changes sum to the net climb; azimuth and slope have no meaningful sum. */
    fun totalsRow(totals: LegTotals): List<String> =
        listOf("Total", "", number(totals.lengthM), "", "", signedMetres(totals.heightChangeM), number(totals.pathM))

    /** A typed angle: a comma works as the decimal point (the keypad offers one), and NaN is refused. */
    fun parseDegrees(text: String): Double? =
        text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

    /** The Detail dialog's names, with the RDP tolerance so the choice is concrete. */
    fun detailLabel(detail: Detail): String = when (detail) {
        Detail.COARSE -> "Coarse (1 m)"
        Detail.NORMAL -> "Normal (0.5 m)"
        Detail.FINE -> "Fine (0.25 m)"
    }

    fun corners(count: Int): String = if (count == 1) "1 corner" else "$count corners"

    /**
     * One compass reading in the North sheet. Bearings come from a hand compass, so they are always
     * magnetic; a reading whose chord is too short on this run stays in the file but is not used.
     */
    fun reference(reference: CompassReference, fit: ReferenceFit?): String {
        val back = if (reference.backBearing) " back-bearing" else ""
        val bearing = azimuth(reference.bearingDeg, magnetic = true) + back
        val line = when (reference.line) {
            ReferenceLine.CHORD -> "point to point"
            ReferenceLine.FITTED -> "passage"
        }
        val residual = fit?.residualDeg?.let { "residual ${rotation(it)}" } ?: "not used: too short"
        return listOf(bearing, line, residual).joinToString(SEPARATOR)
    }

    /** Rounded to 0.1 first, and + 0.0 turns a negative zero positive. */
    private fun tenths(value: Double): Double = Math.round(value * 10.0) / 10.0 + 0.0

    private fun number(m: Double): String = String.format(Locale.US, "%.1f", tenths(m))

    private fun pathNumber(leg: LegMeasure): String = number(leg.pathM) + if (leg.curved) CURVED_MARK else ""

    private fun wholeAzimuth(deg: Double?): Long? =
        if (deg == null || !deg.isFinite()) null else Math.floorMod(Math.round(deg), 360L)
}
