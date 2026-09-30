package com.stastyle.imumapper.data

import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.render.PathThumbnail
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * The path thumbnails of the trip cards, one per (trip, run). Runs are never overwritten, so a thumbnail made once
 * stays right for its run.
 *
 * [get] looks in memory, then in the sidecar `thumb-run-<n>.json` in the trip folder, and only then decodes the run
 * file. A run file can be several megabytes, and decoding one per card on every launch is how the app used to run out
 * of memory, so the decode reads the points alone, one run at a time, and its result is written to the sidecar for the
 * next launch. The sidecar sits in the trip folder rather than under `results/`, so the exporter, which takes only the
 * JSON files under `results/`, never ships it, and it goes when the trip's folder is deleted.
 *
 * A card may ask for a trip that is being deleted, so nothing here creates a folder: every path is built from
 * [TripFiles.root] without the `mkdirs` helpers, and the sidecar is written with a plain temp file and rename that fail
 * when the folder is gone. A failure (no folder, no run file, a corrupt run, too little memory) is remembered for the
 * session, so a card scrolled back into view does not repeat it.
 *
 * No Android API, so the class runs in the JVM unit tests.
 */
class TripThumbnails internal constructor(
    private val files: TripFiles,
    private val trips: TripRepository,
    /** The app's Json: unknown keys are ignored, which is what lets the decode skip everything but the points. */
    private val json: Json,
    private val ioDispatcher: CoroutineDispatcher,
    /** Runs between a run's decode and its sidecar write; tests delete the trip there. */
    private val beforeWrite: suspend (tripId: Long) -> Unit,
) {
    constructor(
        files: TripFiles,
        trips: TripRepository,
        json: Json,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(files, trips, json, ioDispatcher, {})

    // A thumbnail is a few hundred floats, so even hundreds of trips fit without an eviction policy.
    private val memory = ConcurrentHashMap<RunKey, PathThumbnail>()
    private val failed: MutableSet<RunKey> = ConcurrentHashMap.newKeySet()

    /** One decode at a time: two multi-megabyte runs decoding side by side is what runs out of memory. */
    private val mutex = Mutex()

    /** The thumbnail when it is already in memory, without I/O, so a card coming back into view draws it at once. */
    fun cached(tripId: Long, runId: Int): PathThumbnail? = memory[RunKey(tripId, runId)]

    /**
     * The thumbnail of run [runId] of trip [tripId], or null when it cannot be made. A path without points gives an
     * empty thumbnail, not null.
     */
    suspend fun get(tripId: Long, runId: Int): PathThumbnail? {
        val key = RunKey(tripId, runId)
        memory[key]?.let { return it }
        if (key in failed) return null
        return withContext(ioDispatcher) {
            mutex.withLock {
                // Another card may have made it, or failed at it, while this one waited for the lock.
                memory[key] ?: if (key in failed) null else load(key)
            }
        }
    }

    /** The sidecar of a run. Internal so tests can corrupt or age it. */
    internal fun sidecarFile(tripId: Long, runId: Int): File =
        File(tripFolder(tripId), "$SIDECAR_PREFIX$runId$SIDECAR_SUFFIX")

    private suspend fun load(key: RunKey): PathThumbnail? {
        readSidecar(key)?.let { stored ->
            memory[key] = stored
            return stored
        }
        val decoded = try {
            decode(key)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            // The decoded points are unreachable once the error unwinds, so the app carries on.
            null
        }
        if (decoded == null) {
            failed += key
            return null
        }
        // Cached before the write, so a card that leaves the screen during the write does not decode the run again.
        memory[key] = decoded.thumbnail
        beforeWrite(key.tripId)
        writeSidecar(key, decoded)
        return decoded.thumbnail
    }

    /** The stored thumbnail, or null when there is none or it is unreadable, from another format or another run. */
    private fun readSidecar(key: RunKey): PathThumbnail? = runCatching {
        val file = sidecarFile(key.tripId, key.runId)
        if (!file.isFile) return null
        val sidecar = json.decodeFromString(Sidecar.serializer(), file.readText())
        if (sidecar.formatVersion != FORMAT_VERSION) return null
        // A run file of another length is another run under the same number: a trip id used again after the database
        // was wiped left this folder behind. Without the run file (deleted, or never there) the sidecar is all we have.
        val run = runFile(key)
        if (run.isFile && run.length() != sidecar.sourceLength) return null
        // The constructor rejects arrays of different lengths, which only a damaged file has.
        PathThumbnail(sidecar.x, sidecar.y, sidecar.progress)
    }.getOrNull()

    /** The thumbnail made from the run file, or null without one. */
    @OptIn(ExperimentalSerializationApi::class)
    private fun decode(key: RunKey): Decoded? {
        val run = runFile(key)
        if (!run.isFile) return null
        val sourceLength = run.length()
        // Streamed and points only: the point cloud, raw points, keyframes and diagnostics are skipped, not built.
        val points = FileInputStream(run).use { json.decodeFromStream(PointsOnly.serializer(), it).points }
        return Decoded(PathThumbnail.build(points), sourceLength)
    }

    /**
     * Writes the sidecar through a temp file and a rename, neither of which creates a folder: when the trip was deleted
     * during the decode the write throws and leaves nothing behind. Not synced to the disk, because a sidecar lost or
     * garbled by a power cut is only made again. A failed write still leaves the thumbnail in memory.
     */
    private suspend fun writeSidecar(key: RunKey, decoded: Decoded) {
        val thumbnail = decoded.thumbnail
        val sidecar = Sidecar(FORMAT_VERSION, decoded.sourceLength, thumbnail.x, thumbnail.y, thumbnail.progress)
        val target = sidecarFile(key.tripId, key.runId)
        val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
        val written = try {
            FileOutputStream(tmp).use { it.write(json.encodeToString(Sidecar.serializer(), sidecar).toByteArray()) }
            // Windows, where the unit tests run, does not rename over an existing file.
            tmp.renameTo(target) || (target.delete() && tmp.renameTo(target))
        } catch (e: Exception) {
            false
        }
        if (!written) {
            tmp.delete()
            return
        }
        // The trip may have been deleted after the decode, with this file landing in its folder while the folder was
        // being removed. Its row goes before its files, so a missing row means the sidecar must go too, and the folder
        // with it once empty (delete() leaves a folder that still holds anything). The deletion also removes the card,
        // which cancels the caller, so the check must not be skipped then; a check that fails counts as a missing row,
        // since a lost sidecar is only made again.
        val rowExists = try {
            withContext(NonCancellable) { trips.getTrip(key.tripId) != null }
        } catch (e: Exception) {
            false
        }
        if (!rowExists) {
            target.delete()
            target.parentFile?.delete()
        }
    }

    private fun tripFolder(tripId: Long): File = File(files.root, tripId.toString())

    private fun runFile(key: RunKey): File =
        File(File(tripFolder(key.tripId), TripFiles.RESULTS_DIR_NAME), files.resultFileName(key.runId))

    private data class RunKey(val tripId: Long, val runId: Int)

    private class Decoded(val thumbnail: PathThumbnail, val sourceLength: Long)

    internal companion object {
        /** Bump when the stored thumbnail changes (its vertices, colours or layout), so old sidecars are made again. */
        const val FORMAT_VERSION = 1
        const val SIDECAR_PREFIX = "thumb-run-"
        const val SIDECAR_SUFFIX = ".json"
        private const val TMP_SUFFIX = ".tmp"
    }
}

/** The one part of a `PathResult` a thumbnail needs; with unknown keys ignored, the decoder skips the rest. */
@Serializable
private class PointsOnly(val points: List<PathPoint>)

/** The sidecar file: [PathThumbnail]'s arrays, and what they were made from. */
@Serializable
private class Sidecar(
    val formatVersion: Int,
    /** Length in bytes of the run file the thumbnail was made from. */
    val sourceLength: Long,
    val x: FloatArray,
    val y: FloatArray,
    val progress: FloatArray,
)
