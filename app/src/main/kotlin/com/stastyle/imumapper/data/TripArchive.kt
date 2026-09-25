package com.stastyle.imumapper.data

import com.stastyle.imumapper.data.db.PathResultEntity
import com.stastyle.imumapper.data.db.TripEntity
import com.stastyle.imumapper.data.db.TripStatus
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.TripMode
import com.stastyle.imumapper.pipeline.log.LogFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * `trip.json` inside an export ZIP: the trip row plus one entry per stored run, so another phone can
 * rebuild the database rows without re-processing. Everything else in the archive is a plain copy of
 * the trip directory.
 */
@Serializable
data class TripManifest(
    val formatVersion: Int = TripArchive.MANIFEST_VERSION,
    val exportedAtEpochMs: Long,
    val appVersion: String = "",
    val name: String,
    val mode: TripMode,
    val carryPosition: CarryPosition,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long? = null,
    val status: TripStatus = TripStatus.RECORDED,
    val rawLogSizeBytes: Long = 0,
    val latestRunId: Int? = null,
    val distanceM: Double? = null,
    val durationS: Double? = null,
    val notes: String = "",
    val lastError: String? = null,
    val results: List<ResultManifest> = emptyList(),
) {
    fun toTripEntity(): TripEntity = TripEntity(
        name = name,
        mode = mode,
        carryPosition = carryPosition,
        startedAtEpochMs = startedAtEpochMs,
        endedAtEpochMs = endedAtEpochMs,
        status = status,
        rawLogSizeBytes = rawLogSizeBytes,
        latestRunId = latestRunId,
        distanceM = distanceM,
        durationS = durationS,
        notes = notes,
        lastError = lastError,
    )

    companion object {
        fun of(
            trip: TripEntity,
            results: List<PathResultEntity>,
            exportedAtEpochMs: Long,
            appVersion: String = "",
        ): TripManifest = TripManifest(
            exportedAtEpochMs = exportedAtEpochMs,
            appVersion = appVersion,
            name = trip.name,
            mode = trip.mode,
            carryPosition = trip.carryPosition,
            startedAtEpochMs = trip.startedAtEpochMs,
            endedAtEpochMs = trip.endedAtEpochMs,
            status = trip.status,
            rawLogSizeBytes = trip.rawLogSizeBytes,
            latestRunId = trip.latestRunId,
            distanceM = trip.distanceM,
            durationS = trip.durationS,
            notes = trip.notes,
            lastError = trip.lastError,
            results = results.map { ResultManifest.of(it) },
        )
    }
}

@Serializable
data class ResultManifest(
    val runId: Int,
    val pipelineVersion: Int,
    val createdAtEpochMs: Long,
    val fileName: String,
    val statsJson: String,
    val configJson: String,
    val label: String = "",
) {
    fun toEntity(tripId: Long): PathResultEntity = PathResultEntity(
        tripId = tripId,
        runId = runId,
        pipelineVersion = pipelineVersion,
        createdAtEpochMs = createdAtEpochMs,
        fileName = fileName,
        statsJson = statsJson,
        configJson = configJson,
        label = label,
    )

    companion object {
        fun of(e: PathResultEntity) = ResultManifest(
            runId = e.runId,
            pipelineVersion = e.pipelineVersion,
            createdAtEpochMs = e.createdAtEpochMs,
            fileName = e.fileName,
            statsJson = e.statsJson,
            configJson = e.configJson,
            label = e.label,
        )
    }
}

/** What an input picked for import turned out to be, from its first bytes. */
enum class ArchiveKind { ZIP, RAW_LOG, UNKNOWN }

/** Files unpacked from an archive into a staging directory, before any database row exists. */
data class ExtractedTrip(
    val manifest: TripManifest?,
    val rawLog: File?,
    /** `results/run-<n>.json` files, in archive order. */
    val results: List<File>,
    val photos: List<File>,
)

/**
 * ZIP layout shared by [TripExporter] and [TripImporter]. Pure JVM so the layout is unit-tested
 * without Android:
 *
 * ```
 * trip.json              TripManifest
 * raw.imul               the raw log
 * results/run-<n>.json   PathResult per run
 * photos/<name>          keyframes
 * ```
 */
object TripArchive {
    const val MANIFEST_VERSION = 1
    const val MANIFEST_NAME = "trip.json"
    const val ZIP_EXTENSION = "zip"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    /**
     * Writes the archive for [trip] to [out]. Only files that exist are added, so a trip without
     * results or photos still exports. [tripDir] is `files/trips/<id>`.
     */
    fun write(
        trip: TripEntity,
        results: List<PathResultEntity>,
        tripDir: File,
        out: OutputStream,
        exportedAtEpochMs: Long = System.currentTimeMillis(),
        appVersion: String = "",
    ) {
        val manifest = TripManifest.of(trip, results, exportedAtEpochMs, appVersion)
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_NAME))
            zip.write(json.encodeToString(TripManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            val raw = File(tripDir, TripFiles.RAW_LOG_NAME)
            if (raw.isFile) addFile(zip, TripFiles.RAW_LOG_NAME, raw)

            val resultsDir = File(tripDir, TripFiles.RESULTS_DIR_NAME)
            for (f in sortedFiles(resultsDir)) {
                if (f.extension == "json") addFile(zip, "${TripFiles.RESULTS_DIR_NAME}/${f.name}", f)
            }

            val photosDir = File(tripDir, TripFiles.PHOTOS_DIR_NAME)
            for (f in sortedFiles(photosDir)) addFile(zip, "${TripFiles.PHOTOS_DIR_NAME}/${f.name}", f)
        }
    }

    fun encodeManifest(manifest: TripManifest): String = json.encodeToString(TripManifest.serializer(), manifest)

    fun decodeManifest(text: String): TripManifest = json.decodeFromString(TripManifest.serializer(), text)

    /** Sniffs the first bytes of an input. The stream must support mark/reset or be a fresh stream. */
    fun detectKind(header: ByteArray): ArchiveKind = when {
        header.size >= 4 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte() &&
            header[2] == 3.toByte() && header[3] == 4.toByte() -> ArchiveKind.ZIP
        header.size >= 4 && String(header, 0, 4, Charsets.US_ASCII) == LogFormat.MAGIC -> ArchiveKind.RAW_LOG
        else -> ArchiveKind.UNKNOWN
    }

    /**
     * Unpacks a trip ZIP into [stagingDir] (created if needed). Entry names outside the known layout
     * are ignored; names that try to escape the directory are rejected.
     */
    fun extractZip(input: InputStream, stagingDir: File): ExtractedTrip {
        stagingDir.mkdirs()
        var manifest: TripManifest? = null
        var rawLog: File? = null
        val results = ArrayList<File>()
        val photos = ArrayList<File>()
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val name = entry.name.trimStart('/')
                val parts = name.split('/')
                if (parts.any { it == ".." || it.isEmpty() }) throw IOException("Unsafe entry name: ${entry.name}")
                when {
                    name == MANIFEST_NAME -> manifest = decodeManifest(zip.readBytes().toString(Charsets.UTF_8))
                    name == TripFiles.RAW_LOG_NAME -> rawLog = copyEntry(zip, File(stagingDir, TripFiles.RAW_LOG_NAME))
                    parts.size == 2 && parts[0] == TripFiles.RESULTS_DIR_NAME && parts[1].endsWith(".json") ->
                        results += copyEntry(zip, File(File(stagingDir, TripFiles.RESULTS_DIR_NAME), parts[1]))
                    parts.size == 2 && parts[0] == TripFiles.PHOTOS_DIR_NAME ->
                        photos += copyEntry(zip, File(File(stagingDir, TripFiles.PHOTOS_DIR_NAME), parts[1]))
                    // Anything else (macOS resource forks, future additions) is not a trip file.
                }
                zip.closeEntry()
            }
        }
        return ExtractedTrip(manifest, rawLog, results, photos)
    }

    /** Copies a bare `.imul` stream into [stagingDir] as `raw.imul`. */
    fun extractRawLog(input: InputStream, stagingDir: File): ExtractedTrip {
        stagingDir.mkdirs()
        val target = File(stagingDir, TripFiles.RAW_LOG_NAME)
        target.outputStream().use { out -> input.copyTo(out) }
        return ExtractedTrip(manifest = null, rawLog = target, results = emptyList(), photos = emptyList())
    }

    /** Result run number from a `run-<n>.json` name, null when the name is not one. */
    fun runIdOf(fileName: String): Int? {
        if (!fileName.startsWith("run-") || !fileName.endsWith(".json")) return null
        return fileName.removePrefix("run-").removeSuffix(".json").toIntOrNull()
    }

    private fun addFile(zip: ZipOutputStream, entryName: String, file: File) {
        val entry = ZipEntry(entryName)
        entry.time = file.lastModified()
        zip.putNextEntry(entry)
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun copyEntry(zip: ZipInputStream, target: File): File {
        target.parentFile?.mkdirs()
        target.outputStream().use { out -> zip.copyTo(out) }
        return target
    }

    private fun sortedFiles(dir: File): List<File> =
        (dir.listFiles() ?: emptyArray()).filter { it.isFile }.sortedBy { it.name }
}
