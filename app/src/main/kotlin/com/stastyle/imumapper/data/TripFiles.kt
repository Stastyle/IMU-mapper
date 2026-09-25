package com.stastyle.imumapper.data

import android.content.Context
import java.io.File

/**
 * On-disk layout, all under the app's private files directory:
 *
 * ```
 * files/trips/<tripId>/raw.imul            raw sensor log (LogFormat)
 * files/trips/<tripId>/results/run-<n>.json PathResult per processing run
 * files/trips/<tripId>/photos/<name>.jpg    keyframes
 * cache/export/                             ZIPs for the share sheet
 * cache/import/                             staging area while a ZIP or log is imported
 * cache/updates/                            downloaded APKs
 * ```
 *
 * The primary constructor takes plain directories so unit tests can point it at a temp folder;
 * the app uses the [Context] constructor.
 */
class TripFiles(private val filesDir: File, private val cacheDir: File) {

    constructor(context: Context) : this(context.applicationContext.filesDir, context.applicationContext.cacheDir)

    val root: File get() = File(filesDir, "trips")

    fun tripDir(tripId: Long): File = File(root, tripId.toString()).also { it.mkdirs() }

    fun rawLog(tripId: Long): File = File(tripDir(tripId), RAW_LOG_NAME)

    fun resultsDir(tripId: Long): File = File(tripDir(tripId), RESULTS_DIR_NAME).also { it.mkdirs() }

    fun resultFile(tripId: Long, runId: Int): File = File(resultsDir(tripId), resultFileName(runId))

    fun resultFileName(runId: Int): String = "run-$runId.json"

    fun photosDir(tripId: Long): File = File(tripDir(tripId), PHOTOS_DIR_NAME).also { it.mkdirs() }

    fun exportDir(): File = File(cacheDir, "export").also { it.mkdirs() }

    fun importDir(): File = File(cacheDir, "import").also { it.mkdirs() }

    fun updatesDir(): File = File(cacheDir, "updates").also { it.mkdirs() }

    fun deleteTrip(tripId: Long) {
        File(root, tripId.toString()).deleteRecursively()
    }

    /**
     * Removes export ZIPs older than [maxAgeMs]. Exports live in the cache so the system may also
     * reclaim them; this just keeps the share sheet from accumulating one file per tap.
     */
    fun pruneExports(maxAgeMs: Long, nowMs: Long = System.currentTimeMillis()) {
        val files = exportDir().listFiles() ?: return
        for (f in files) {
            if (f.isFile && nowMs - f.lastModified() > maxAgeMs) f.delete()
        }
    }

    companion object {
        const val RAW_LOG_NAME = "raw.imul"
        const val RESULTS_DIR_NAME = "results"
        const val PHOTOS_DIR_NAME = "photos"
    }
}
