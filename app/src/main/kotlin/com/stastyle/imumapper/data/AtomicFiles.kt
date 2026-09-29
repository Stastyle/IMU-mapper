package com.stastyle.imumapper.data

import java.io.File

/**
 * Writes through a temp file and a rename, so a crash or a reader sees the old file or the new one
 * rather than half of one: run results, survey.json and the survey CSV.
 */
object AtomicFiles {
    /**
     * Writes [text] (UTF-8) to "<target>.tmp", then renames it over [target]. When the rename fails it
     * falls back to writing [target] directly and removes the temp file.
     */
    fun writeText(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            // Rename can fail across some file systems, and on Windows (where unit tests may run)
            // whenever the target exists; a plain write is the fallback.
            target.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
    }

    private const val TMP_SUFFIX = ".tmp"
}
