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
 * cache/updates/                            downloaded APKs
 * ```
 */
class TripFiles(context: Context) {

    private val appContext = context.applicationContext

    val root: File get() = File(appContext.filesDir, "trips")

    fun tripDir(tripId: Long): File = File(root, tripId.toString()).also { it.mkdirs() }

    fun rawLog(tripId: Long): File = File(tripDir(tripId), RAW_LOG_NAME)

    fun resultsDir(tripId: Long): File = File(tripDir(tripId), "results").also { it.mkdirs() }

    fun resultFile(tripId: Long, runId: Int): File = File(resultsDir(tripId), resultFileName(runId))

    fun resultFileName(runId: Int): String = "run-$runId.json"

    fun photosDir(tripId: Long): File = File(tripDir(tripId), "photos").also { it.mkdirs() }

    fun exportDir(): File = File(appContext.cacheDir, "export").also { it.mkdirs() }

    fun updatesDir(): File = File(appContext.cacheDir, "updates").also { it.mkdirs() }

    fun deleteTrip(tripId: Long) {
        File(root, tripId.toString()).deleteRecursively()
    }

    companion object {
        const val RAW_LOG_NAME = "raw.imul"
    }
}
