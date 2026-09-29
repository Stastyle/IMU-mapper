package com.stastyle.imumapper.pipeline.survey

import com.stastyle.imumapper.pipeline.core.Vec3
import java.util.Locale

/** The survey's CSV table and the sentence that says how north was corrected. */
object SurveyCsv {
    /** Excel needs the byte-order mark to read UTF-8 (Hebrew station names). */
    const val BOM: String = "\uFEFF"
    const val HEADER: String = "from,to,from_s,to_s,length_m,horizontal_m,height_change_m,azimuth_deg,north," +
        "slope_deg,grade_pct,path_m,curved,to_east_m,to_north_m,to_up_m"

    /** RFC 4180 line ends, which Excel and LibreOffice both expect. */
    const val EOL: String = "\r\n"

    /** The whole file: BOM + HEADER + EOL, then one row + EOL per leg; a value that cannot be measured is empty. */
    fun text(legs: List<TraverseLeg>, startNs: Long, origin: Vec3, magnetic: Boolean): String {
        val out = StringBuilder()
        out.append(BOM).append(HEADER).append(EOL)
        for (leg in legs) {
            val m = leg.measure
            val cells = listOf(
                field(leg.from.name),
                field(leg.to.name),
                fixed((leg.from.tNs - startNs) / 1e9, 1),
                fixed((leg.to.tNs - startNs) / 1e9, 1),
                fixed(m.lengthM, 2),
                fixed(m.horizontalM, 2),
                fixed(m.heightChangeM, 2),
                m.azimuthDeg?.let(::azimuth) ?: "",
                if (magnetic) "M" else "R",
                fixed(m.slopeDeg, 1),
                m.gradePct?.let { fixed(it, 1) } ?: "",
                fixed(m.pathM, 2),
                if (m.curved) "yes" else "no",
                fixed(m.b.x - origin.x, 2),
                fixed(m.b.y - origin.y, 2),
                fixed(m.b.z - origin.z, 2),
            )
            out.append(cells.joinToString(",")).append(EOL)
        }
        return out.toString()
    }

    /**
     * A value that starts with = + - @ tab or CR gets a leading ' so Excel does not read it as a formula
     * ("- squeeze" showed #NAME?, "=HYPERLINK(...)" became a live link, "+5" lost its sign). Excel shows
     * the apostrophe when it opens a CSV (tested); that is the price of keeping the name. Then RFC 4180
     * quoting: wrapped in quotes, quotes doubled, when the value holds , " CR or LF.
     */
    fun field(value: String): String {
        val safe = if (value.isNotEmpty() && value[0] in FORMULA_START) "'$value" else value
        return if (safe.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) {
            "\"" + safe.replace("\"", "\"\"") + "\""
        } else {
            safe
        }
    }

    /** First characters that make a spreadsheet read a cell as a formula (the OWASP CSV injection list). */
    private const val FORMULA_START = "=+-@\t\r"

    /** Locale.US fixed-point with [decimals] places; "-0.00" becomes "0.00". */
    fun fixed(value: Double, decimals: Int): String {
        val text = String.format(Locale.US, "%.${decimals}f", value)
        // A small negative rounds to "-0.00", which reads as a direction where there is none.
        return if (text.startsWith("-") && text.all { it == '-' || it == '0' || it == '.' }) text.substring(1) else text
    }

    /**
     * "north +4.0° from 2 compass readings", "north +4.0° from 1 compass reading", "north -1.5° set by
     * hand", "north as recorded".
     */
    fun correctionText(solution: NorthSolution): String {
        // Rounded first so -0.04 reads "+0.0", not "-0.0".
        val deg = Math.round(solution.rotationDeg * 10.0) / 10.0 + 0.0
        return when (solution.source) {
            NorthSource.REFERENCES -> {
                val count = solution.usedCount
                val plural = if (count == 1) "" else "s"
                String.format(Locale.US, "north %+.1f° from %d compass reading%s", deg, count, plural)
            }
            NorthSource.MANUAL -> String.format(Locale.US, "north %+.1f° set by hand", deg)
            NorthSource.NONE -> "north as recorded"
        }
    }

    /** One decimal, and a value that rounds to 360.0 is north: "0.0". */
    private fun azimuth(deg: Double): String = fixed(deg, 1).let { if (it == "360.0") "0.0" else it }
}
