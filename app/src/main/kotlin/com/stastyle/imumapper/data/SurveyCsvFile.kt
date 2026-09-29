package com.stastyle.imumapper.data

import java.io.File

/** The survey CSV as a file in cache/export, named so a receiver can tell trips, runs and raw paths apart. */
object SurveyCsvFile {
    const val MIME_CSV: String = "text/csv"

    /**
     * "<safeStem>-<tripId>-run<runId>[-raw]-survey.csv". The same trip, run and path always give the
     * same name, so exporting again replaces the file instead of piling up copies in the cache.
     */
    fun fileName(tripName: String, tripId: Long, runId: Int, raw: Boolean): String {
        val rawPart = if (raw) "-raw" else ""
        return "${ExportNames.safeStem(tripName)}-$tripId-run$runId$rawPart-survey.csv"
    }

    /**
     * Writes [text] into [dir] atomically and returns the file. The byte-order mark is part of [text]
     * (SurveyCsv.BOM), and UTF-8 turns it into EF BB BF, which Excel needs to show Hebrew names.
     */
    fun write(dir: File, fileName: String, text: String): File =
        File(dir, fileName).also { AtomicFiles.writeText(it, text) }
}
