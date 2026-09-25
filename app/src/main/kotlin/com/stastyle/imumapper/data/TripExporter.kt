package com.stastyle.imumapper.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.stastyle.imumapper.BuildConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A finished export: the ZIP in the cache, its content URI and an intent that opens the share sheet. */
data class TripExport(val zip: File, val uri: Uri, val shareIntent: Intent)

/**
 * Packs one trip (raw log, every result, photos, `trip.json`) into a ZIP under `cache/export/` and
 * hands it to other apps through the `${applicationId}.fileprovider` authority declared in the
 * manifest (the `cache` path covers the export directory).
 */
class TripExporter(
    context: Context,
    private val trips: TripRepository,
    private val files: TripFiles,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext: Context = context.applicationContext

    /**
     * Builds the archive for [tripId]. Throws [IllegalArgumentException] when the trip does not exist
     * and [java.io.IOException] when the archive cannot be written.
     */
    suspend fun export(tripId: Long): TripExport {
        val trip = trips.getTrip(tripId) ?: throw IllegalArgumentException("Trip $tripId does not exist")
        val results = trips.listResults(tripId)
        val zip = withContext(ioDispatcher) {
            files.pruneExports(EXPORT_MAX_AGE_MS)
            val target = File(files.exportDir(), exportFileName(trip.name, trip.startedAtEpochMs, tripId))
            val tmp = File(target.parentFile, target.name + ".part")
            try {
                tmp.outputStream().use { out ->
                    TripArchive.write(
                        trip = trip,
                        results = results,
                        tripDir = files.tripDir(tripId),
                        out = out,
                        appVersion = BuildConfig.VERSION_NAME,
                    )
                }
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
            target
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
         * `<name>-<yyyyMMdd-HHmm>-<id>.zip` with the name reduced to file-system-safe ASCII, so the
         * receiver sees something meaningful and two trips never collide.
         */
        fun exportFileName(tripName: String, startedAtEpochMs: Long, tripId: Long): String {
            val safe = tripName.map { if (it.isLetterOrDigit()) it else '_' }
                .joinToString("")
                .trim('_')
                .take(40)
                .ifEmpty { "trip" }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(startedAtEpochMs))
            return "$safe-$stamp-$tripId.${TripArchive.ZIP_EXTENSION}"
        }
    }
}
