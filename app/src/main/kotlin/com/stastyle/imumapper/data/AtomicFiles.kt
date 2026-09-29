package com.stastyle.imumapper.data

import java.io.File
import java.io.FileOutputStream

/**
 * Writes through a temp file and a rename, so a crash or a reader sees the old file or the new one
 * rather than half of one: run results, survey.json and the survey CSV.
 */
object AtomicFiles {
    /**
     * Writes [text] (UTF-8) to "<target>.tmp", syncs it to the disk, then renames it over [target]. When
     * the rename fails it falls back to writing [target] directly, synced the same way, and removes the
     * temp file.
     *
     * The sync comes before the rename because a file system may make the rename durable before the
     * data (f2fs, which Samsung phones use for /data, can): after a power cut or a forced reset in the
     * seconds after a write, the file would then read as zeros. android.util.AtomicFile syncs for the
     * same reason.
     */
    fun writeText(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
        writeSynced(tmp, text)
        if (!tmp.renameTo(target)) {
            // Rename can fail across some file systems, and on Windows (where unit tests may run)
            // whenever the target exists; a plain write is the fallback.
            writeSynced(target, text)
            tmp.delete()
        }
    }

    private fun writeSynced(file: File, text: String) {
        FileOutputStream(file).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
    }

    private const val TMP_SUFFIX = ".tmp"
}
