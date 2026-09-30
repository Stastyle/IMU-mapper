package com.stastyle.imumapper.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.stastyle.imumapper.BuildConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A finished export: the ZIP in the cache, its content URI and an intent that opens the share sheet. */
data class TripExport(val zip: File, val uri: Uri, val shareIntent: Intent)

/**
 * Packs one trip (raw log, every result, photos, `survey.json` when present, `trip.json`) into a ZIP
 * under `cache/export/` and hands it to other apps through the `${applicationId}.fileprovider`
 * authority declared in the manifest (the `cache` path covers the export directory).
 */
class TripExporter(
    context: Context,
    private val trips: TripRepository,
    private val files: TripFiles,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext: Context = context.applicationContext

    /**
     * One export at a time. The trip list's Export and the viewer's Share both use this exporter and name
     * a trip's ZIP the same way, and a user can start both on one trip (the card stays tappable while it
     * exports, and an export keeps writing after the viewer that started it is closed). Waiting here
     * means the second one writes its ZIP after the first is finished rather than beside it.
     */
    private val exportLock = Mutex()

    /**
     * Builds the archive for [tripId]. Throws [IllegalArgumentException] when the trip does not exist
     * and [java.io.IOException] when the archive cannot be written.
     */
    suspend fun export(tripId: Long): TripExport {
        val trip = trips.getTrip(tripId) ?: throw IllegalArgumentException("Trip $tripId does not exist")
        val results = trips.listResults(tripId)
        // withContext does not return before its block does, even for a cancelled caller, so the lock is
        // held until the blocking write and the rename are over.
        val zip = exportLock.withLock {
            withContext(ioDispatcher) {
                files.pruneExports(EXPORT_MAX_AGE_MS)
                val target = File(files.exportDir(), exportFileName(trip.name, trip.startedAtEpochMs, tripId))
                replaceFile(target) { out ->
                    TripArchive.write(
                        trip = trip,
                        results = results,
                        tripDir = files.tripDir(tripId),
                        out = out,
                        appVersion = BuildConfig.VERSION_NAME,
                    )
                }
                target
            }
        }
        val uri = FileProvider.getUriForFile(appContext, authority(appContext), zip)
        return TripExport(zip, uri, shareIntent(uri, trip.name))
    }

    private fun shareIntent(uri: Uri, title: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_ZIP
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "IMU Mapper trip: $title")
            // Some receivers only honour the grant when the URI is also in the clip data.
            clipData = ClipData.newRawUri(title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Export trip").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    companion object {
        const val MIME_ZIP = "application/zip"
        private const val EXPORT_MAX_AGE_MS = 24L * 60L * 60L * 1000L

        fun authority(context: Context): String = "${context.packageName}.fileprovider"

        /**
         * Writes [target] through [write] into a temp file of its own, "<name>-<random>.part" beside it, and
         * then renames that over [target]. Two writes to one target never share a file, a failed write
         * leaves the old [target] as it was, and a crash leaves only a stale .part, which
         * [TripFiles.pruneExports] removes with the old exports.
         *
         * There is no delete before the rename: on Android rename(2) replaces [target] in one step, so a
         * share sheet holding its path always finds a whole file, and a receiver that already opened the
         * old one keeps reading it. The copy is the fallback where rename cannot replace a file (on
         * Windows, where unit tests may run, it fails whenever [target] exists).
         */
        internal fun replaceFile(target: File, write: (OutputStream) -> Unit) {
            val tmp = File.createTempFile(target.nameWithoutExtension + "-", PART_SUFFIX, target.parentFile)
            try {
                tmp.outputStream().use(write)
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
        }

        private const val PART_SUFFIX = ".part"

        /**
         * `<name>-<yyyyMMdd-HHmm>-<id>.zip` with the name made file-system safe by [ExportNames.safeStem],
         * so the receiver sees something meaningful and two trips never collide.
         */
        fun exportFileName(tripName: String, startedAtEpochMs: Long, tripId: Long): String {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(startedAtEpochMs))
            return "${ExportNames.safeStem(tripName)}-$stamp-$tripId.${TripArchive.ZIP_EXTENSION}"
        }
    }
}
