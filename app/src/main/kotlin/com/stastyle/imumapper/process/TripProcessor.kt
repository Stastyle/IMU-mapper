package com.stastyle.imumapper.process

import com.stastyle.imumapper.data.CalibrationRepository
import com.stastyle.imumapper.data.TripFiles
import com.stastyle.imumapper.data.TripRepository
import com.stastyle.imumapper.data.db.PathResultEntity
import com.stastyle.imumapper.data.db.TripStatus
import com.stastyle.imumapper.pipeline.core.PathResult
import com.stastyle.imumapper.pipeline.core.PathStats
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.Processor
import com.stastyle.imumapper.pipeline.log.LogReader
import com.stastyle.imumapper.pipeline.log.RawLog
import com.stastyle.imumapper.pipeline.pdr.PdrProcessor
import com.stastyle.imumapper.pipeline.vio.VioProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs the pipeline over a recorded trip and stores the result as a new run.
 * Implemented by the app-data work item.
 */
interface TripProcessor {
    /**
     * Processes [tripId] with [config] (or the saved calibration when null), writes
     * `results/run-<n>.json`, inserts the [PathResultEntity] and updates the trip row.
     * The default processor picks VIO when the log has ARCore tracking, PDR otherwise.
     *
     * @throws IllegalArgumentException when the trip does not exist.
     * @throws IllegalStateException when the trip is still being recorded.
     * @throws java.io.IOException when the raw log is missing or unreadable.
     */
    suspend fun process(tripId: Long, config: PipelineConfig? = null, label: String = ""): PathResultEntity

    /**
     * Same as [process] but runs the given [processor] instead of the default one, for "PDR only" and
     * "VIO only" runs from the debug and calibration screens. The stored [PathResultEntity.label]
     * defaults to "PDR" or "VIO" for the pipeline's own processors, so a comparison of both on the same
     * log is labelled without the caller doing anything.
     *
     * The default body throws so test doubles of this interface written before it existed keep
     * compiling; [DefaultTripProcessor] overrides it.
     */
    suspend fun processWith(
        tripId: Long,
        processor: Processor,
        config: PipelineConfig? = null,
        label: String = "",
    ): PathResultEntity = throw UnsupportedOperationException("processWith is not supported by ${javaClass.name}")
}

/**
 * Reads the raw log on [ioDispatcher], runs the processor on [computeDispatcher], then writes the result
 * file before touching the database, so a crash between the two leaves an orphan file, never a row
 * without a file. Runs on the same trip are serialised by a per-trip mutex; runs on different trips
 * may overlap.
 *
 * [json] should be the container's encoder so stored `statsJson`/`configJson` match what the rest of
 * the app writes; the default is equivalent.
 */
class DefaultTripProcessor(
    private val trips: TripRepository,
    private val calibration: CalibrationRepository,
    private val files: TripFiles,
    private val processor: Processor,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) : TripProcessor {

    private val locks = ConcurrentHashMap<Long, Mutex>()

    override suspend fun process(tripId: Long, config: PipelineConfig?, label: String): PathResultEntity =
        processWith(tripId, processor, config, label)

    override suspend fun processWith(
        tripId: Long,
        processor: Processor,
        config: PipelineConfig?,
        label: String,
    ): PathResultEntity = lockFor(tripId).withLock {
        val trip = trips.getTrip(tripId) ?: throw IllegalArgumentException("Trip $tripId does not exist")
        // The recorder owns the row while it writes; marking it FAILED here would clobber its status,
        // and the log is not complete anyway.
        check(trip.status != TripStatus.RECORDING) { "Trip $tripId is still being recorded" }
        try {
            val rawFile = files.rawLog(tripId)
            val log = withContext(ioDispatcher) { readLog(rawFile) }
            val effectiveConfig = config ?: calibration.getConfig()
            val result = withContext(computeDispatcher) { processor.process(log, effectiveConfig) }
            val runId = trips.nextRunId(tripId)
            val resultFile = files.resultFile(tripId, runId)
            withContext(ioDispatcher) { writeAtomically(resultFile, result.toJson()) }
            val entity = PathResultEntity(
                tripId = tripId,
                runId = runId,
                pipelineVersion = result.pipelineVersion,
                createdAtEpochMs = clock(),
                fileName = resultFile.name,
                statsJson = json.encodeToString(PathStats.serializer(), result.stats),
                configJson = json.encodeToString(PipelineConfig.serializer(), result.config),
                label = label.ifBlank { defaultLabel(processor, log) },
            )
            trips.addResult(entity)
            // Column-scoped write: a rename or note edit that landed while the pipeline ran is kept.
            trips.markProcessed(tripId, runId, result.stats.distanceM, result.stats.durationS)
            entity
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            // Reached once the frames holding the log have unwound, so the heap is free again and the
            // row can be written. The trip is marked FAILED with a message that says what happened:
            // the viewer shows it instead of re-running the same processing on every open, which is
            // what took the whole app down before (see ViewerViewModel.maybeProcess).
            val message = outOfMemoryMessage(files.rawLog(tripId))
            trips.markFailed(tripId, message)
            throw IllegalStateException(message, e)
        } catch (e: Exception) {
            trips.markFailed(tripId, describe(e))
            throw e
        }
    }

    private fun lockFor(tripId: Long): Mutex = locks.getOrPut(tripId) { Mutex() }

    private fun readLog(rawFile: File): RawLog {
        if (!rawFile.isFile) throw FileNotFoundException("Raw log missing: ${rawFile.name}")
        // The processors read the calibrated streams only; the uncalibrated ones are close to half
        // of a log's records and would sit in memory for nothing.
        val log = LogReader.read(rawFile, LogReader.UNCALIBRATED_TYPES)
        if (log.totalRecords == 0) throw IllegalStateException("Raw log is empty")
        return log
    }

    private fun outOfMemoryMessage(rawFile: File): String {
        val mb = rawFile.length() / 1_000_000.0
        return String.format(Locale.US, "Not enough memory to process this trip (raw log %.0f MB)", mb)
    }

    /** Write to a sibling temp file then rename so a half-written result never carries a run name. */
    private fun writeAtomically(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            // Rename can fail across some file systems; a plain copy is the fallback.
            target.writeText(text)
            tmp.delete()
        }
    }

    private fun defaultLabel(processor: Processor, log: RawLog): String = when (processor) {
        is PdrProcessor -> LABEL_PDR
        is VioProcessor -> LABEL_VIO
        else -> if (log.hasVio) LABEL_VIO else LABEL_PDR
    }

    private fun describe(e: Exception): String {
        val message = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        return message.take(MAX_ERROR_LENGTH)
    }

    companion object {
        const val LABEL_PDR = "PDR"
        const val LABEL_VIO = "VIO"
        private const val MAX_ERROR_LENGTH = 500
    }
}

/** Convenience for callers that only have the stored entity: loads the path JSON. */
fun TripFiles.readResult(entity: PathResultEntity): PathResult =
    PathResult.fromJson(File(resultsDir(entity.tripId), entity.fileName).readText())
