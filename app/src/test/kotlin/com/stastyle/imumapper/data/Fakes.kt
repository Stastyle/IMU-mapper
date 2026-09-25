package com.stastyle.imumapper.data

import com.stastyle.imumapper.data.db.PathResultEntity
import com.stastyle.imumapper.data.db.TripEntity
import com.stastyle.imumapper.pipeline.core.AccelSample
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.EventKind
import com.stastyle.imumapper.pipeline.core.EventRecord
import com.stastyle.imumapper.pipeline.core.LogMeta
import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.PathResult
import com.stastyle.imumapper.pipeline.core.PathStats
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.PoseSample
import com.stastyle.imumapper.pipeline.core.PositionSource
import com.stastyle.imumapper.pipeline.core.Processor
import com.stastyle.imumapper.pipeline.core.TrackingState
import com.stastyle.imumapper.pipeline.core.TripMode
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.log.LogWriter
import com.stastyle.imumapper.pipeline.log.RawLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** In-memory [TripRepository] with the same id and runId rules as the Room one. */
class FakeTripRepository(private val files: TripFiles) : TripRepository {
    private val trips = MutableStateFlow<Map<Long, TripEntity>>(emptyMap())
    private val results = MutableStateFlow<List<PathResultEntity>>(emptyList())
    private var nextId = 1L

    val deletedTripIds = ArrayList<Long>()

    override fun observeTrips(): Flow<List<TripEntity>> =
        trips.map { it.values.sortedByDescending { t -> t.startedAtEpochMs } }

    override fun observeTrip(tripId: Long): Flow<TripEntity?> = trips.map { it[tripId] }

    override suspend fun getTrip(tripId: Long): TripEntity? = trips.value[tripId]

    override suspend fun createTrip(trip: TripEntity): Long {
        val id = nextId++
        trips.value = trips.value + (id to trip.copy(id = id))
        files.tripDir(id)
        return id
    }

    override suspend fun updateTrip(trip: TripEntity) {
        check(trip.id in trips.value) { "unknown trip ${trip.id}" }
        trips.value = trips.value + (trip.id to trip)
    }

    override suspend fun deleteTrip(tripId: Long) {
        deletedTripIds += tripId
        results.value = results.value.filter { it.tripId != tripId }
        trips.value = trips.value - tripId
        files.deleteTrip(tripId)
    }

    override fun observeResults(tripId: Long): Flow<List<PathResultEntity>> =
        results.map { list -> list.filter { it.tripId == tripId }.sortedBy { it.runId } }

    override suspend fun listResults(tripId: Long): List<PathResultEntity> =
        results.value.filter { it.tripId == tripId }.sortedBy { it.runId }

    override suspend fun getResult(tripId: Long, runId: Int): PathResultEntity? =
        results.value.firstOrNull { it.tripId == tripId && it.runId == runId }

    override suspend fun nextRunId(tripId: Long): Int =
        (results.value.filter { it.tripId == tripId }.maxOfOrNull { it.runId } ?: 0) + 1

    override suspend fun addResult(result: PathResultEntity) {
        results.value = results.value.filter { !(it.tripId == result.tripId && it.runId == result.runId) } + result
    }
}

class FakeCalibrationRepository(var config: PipelineConfig = PipelineConfig()) : CalibrationRepository {
    private val carry = MutableStateFlow(CarryPosition.HAND)
    private val configFlow = MutableStateFlow(config)

    override fun observeConfig(): Flow<PipelineConfig> = configFlow
    override suspend fun getConfig(): PipelineConfig = config
    override suspend fun saveConfig(config: PipelineConfig, notes: String) {
        this.config = config
        configFlow.value = config
    }

    override fun observeCarryPosition(): Flow<CarryPosition> = carry
    override suspend fun getCarryPosition(): CarryPosition = carry.value
    override suspend fun saveCarryPosition(position: CarryPosition) {
        carry.value = position
    }
}

/**
 * Returns a canned result, or throws when [failure] is set. [onProcess] runs inside the call so tests
 * can block it to observe concurrency.
 */
class FakeProcessor(
    var result: PathResult = sampleResult(),
    var failure: Throwable? = null,
    var onProcess: (RawLog, PipelineConfig) -> Unit = { _, _ -> },
) : Processor {
    val calls = ArrayList<PipelineConfig>()
    val running = AtomicInteger(0)
    val maxConcurrent = AtomicInteger(0)

    override fun process(log: RawLog, config: PipelineConfig): PathResult {
        val now = running.incrementAndGet()
        maxConcurrent.updateAndGet { maxOf(it, now) }
        try {
            calls += config
            onProcess(log, config)
            failure?.let { throw it }
            return result.copy(config = config)
        } finally {
            running.decrementAndGet()
        }
    }
}

fun sampleResult(distanceM: Double = 12.5, durationS: Double = 30.0): PathResult = PathResult(
    pipelineVersion = 1,
    config = PipelineConfig(),
    points = listOf(
        PathPoint(0L, Vec3.ZERO, PositionSource.PDR, 0.0, 0),
        PathPoint(1_000_000_000L, Vec3(0.0, 0.7, 0.0), PositionSource.PDR, 0.0, 1),
    ),
    stats = PathStats(distanceM = distanceM, durationS = durationS, stepCount = 2, minZ = 0.0, maxZ = 0.0),
    diagnostics = mapOf("note" to "fake"),
)

/** Writes a small but valid raw log: meta, START, a few accel samples, optionally a tracked pose, STOP. */
fun writeSampleLog(file: File, withVio: Boolean = false, startedAtEpochMs: Long = 1_700_000_000_000L) {
    file.parentFile?.mkdirs()
    LogWriter(file.outputStream()).use { w ->
        w.writeMeta(
            LogMeta(
                appVersion = "test",
                deviceModel = "unit",
                androidSdk = 35,
                mode = if (withVio) TripMode.FLASHLIGHT else TripMode.POCKET,
                carryPosition = CarryPosition.POCKET,
                startedAtEpochMs = startedAtEpochMs,
            ),
        )
        w.write(EventRecord(0L, EventKind.START))
        for (i in 0 until 50) {
            val t = i * 10_000_000L
            w.write(AccelSample(t, 0f, 0f, 9.81f))
        }
        if (withVio) {
            w.write(PoseSample(100_000_000L, 100_000_000L, 0f, 0f, 0f, 0f, 0f, 0f, 1f, TrackingState.TRACKING, 0))
        }
        w.write(EventRecord(500_000_000L, EventKind.STOP))
    }
}
