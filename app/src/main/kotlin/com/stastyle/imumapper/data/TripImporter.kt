package com.stastyle.imumapper.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.stastyle.imumapper.data.db.TripEntity
import com.stastyle.imumapper.data.db.TripStatus
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.LogMeta
import com.stastyle.imumapper.pipeline.core.TripMode
import com.stastyle.imumapper.pipeline.log.LogReader
import com.stastyle.imumapper.pipeline.log.RawLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Outcome of an import: the new trip and what came with it. */
data class TripImport(val tripId: Long, val name: String, val resultCount: Int, val photoCount: Int)

/**
 * Creates a trip from a ZIP written by [TripExporter] or from a bare `.imul` log, picked with
 * `ACTION_OPEN_DOCUMENT`. Everything is unpacked into `cache/import/` first and only then moved into
 * the trip directory, so a bad file never leaves a half-filled trip behind.
 */
class TripImporter(
    context: Context,
    private val trips: TripRepository,
    private val files: TripFiles,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext: Context = context.applicationContext

    /**
     * Imports [uri]. Throws [IOException] when the content is neither a trip ZIP nor an IMUL log or
     * cannot be read.
     */
    suspend fun importFrom(uri: Uri): TripImport = withContext(ioDispatcher) {
        val displayName = queryDisplayName(uri)
        val staging = File(files.importDir(), "import-${System.nanoTime()}")
        try {
            val extracted = openInput(uri).use { input -> extract(input, staging) }
            val rawLog = extracted.rawLog ?: throw IOException("The archive holds no raw.imul log")
            // Only the meta, the time span and "is there anything in it" are needed here.
            val log = LogReader.read(rawLog, LogReader.UNCALIBRATED_TYPES)
            if (log.totalRecords == 0) throw IOException("The log is empty")
            val manifest = extracted.manifest
            val name = importedName(manifest, displayName, log.meta)
            val entity = buildEntity(manifest, name, log, rawLog)
            val tripId = trips.createTrip(entity)
            try {
                moveIntoTrip(tripId, extracted)
                val imported = insertResults(tripId, extracted)
                trips.updateTrip(
                    (trips.getTrip(tripId) ?: entity).copy(
                        id = tripId,
                        status = if (imported > 0) TripStatus.PROCESSED else TripStatus.RECORDED,
                        latestRunId = latestRunId(tripId, imported),
                        lastError = null,
                    ),
                )
                TripImport(tripId, name, imported, extracted.photos.size)
            } catch (e: Exception) {
                trips.deleteTrip(tripId)
                throw e
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun openInput(uri: Uri): InputStream =
        appContext.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("Cannot open $uri")

    private fun extract(input: InputStream, staging: File): ExtractedTrip {
        val buffered = BufferedInputStream(input)
        buffered.mark(SNIFF_BYTES)
        val header = ByteArray(SNIFF_BYTES)
        var read = 0
        while (read < SNIFF_BYTES) {
            val n = buffered.read(header, read, SNIFF_BYTES - read)
            if (n < 0) break
            read += n
        }
        buffered.reset()
        return when (TripArchive.detectKind(header.copyOf(read))) {
            ArchiveKind.ZIP -> TripArchive.extractZip(buffered, staging)
            ArchiveKind.RAW_LOG -> TripArchive.extractRawLog(buffered, staging)
            ArchiveKind.UNKNOWN -> throw IOException("Not a trip archive or IMUL log")
        }
    }

    private fun buildEntity(manifest: TripManifest?, name: String, log: RawLog, rawLog: File): TripEntity {
        val meta = log.meta
        val base = manifest?.toTripEntity() ?: TripEntity(
            name = name,
            mode = meta?.mode ?: TripMode.POCKET,
            carryPosition = meta?.carryPosition ?: CarryPosition.HAND,
            startedAtEpochMs = meta?.startedAtEpochMs ?: System.currentTimeMillis(),
            durationS = log.durationS,
        )
        // The row is created RECORDED with no run; results are attached after their files are in place.
        return base.copy(
            id = 0,
            name = name,
            status = TripStatus.RECORDED,
            rawLogSizeBytes = rawLog.length(),
            latestRunId = null,
            lastError = null,
            durationS = base.durationS ?: log.durationS,
        )
    }

    private fun moveIntoTrip(tripId: Long, extracted: ExtractedTrip) {
        extracted.rawLog?.let { move(it, files.rawLog(tripId)) }
        for (f in extracted.results) move(f, File(files.resultsDir(tripId), f.name))
        for (f in extracted.photos) move(f, File(files.photosDir(tripId), f.name))
    }

    /** Inserts the manifest's result rows whose files were present; returns how many. */
    private suspend fun insertResults(tripId: Long, extracted: ExtractedTrip): Int {
        val manifest = extracted.manifest ?: return 0
        val present = extracted.results.map { it.name }.toSet()
        var count = 0
        for (r in manifest.results.sortedBy { it.runId }) {
            if (r.fileName !in present) continue
            trips.addResult(r.toEntity(tripId))
            count++
        }
        return count
    }

    private suspend fun latestRunId(tripId: Long, imported: Int): Int? =
        if (imported == 0) null else trips.listResults(tripId).maxOfOrNull { it.runId }

    private fun importedName(manifest: TripManifest?, displayName: String?, meta: LogMeta?): String {
        manifest?.name?.takeIf { it.isNotBlank() }?.let { return "$it (imported)" }
        val fromFile = displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
        if (fromFile != null) return fromFile
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .format(Date(meta?.startedAtEpochMs ?: System.currentTimeMillis()))
        return "Imported $stamp"
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
    }.getOrNull()

    private fun move(from: File, to: File) {
        to.parentFile?.mkdirs()
        if (to.exists()) to.delete()
        if (!from.renameTo(to)) {
            // Cache and files may sit on different mounts on some devices; fall back to a copy.
            from.copyTo(to, overwrite = true)
            from.delete()
        }
    }

    private companion object {
        const val SNIFF_BYTES = 8
    }
}
