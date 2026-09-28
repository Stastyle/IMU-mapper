package com.stastyle.imumapper.ui.calibration

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stastyle.imumapper.BuildConfig
import com.stastyle.imumapper.capture.CompassFeed
import com.stastyle.imumapper.capture.CompassReading
import com.stastyle.imumapper.capture.CompassStatus
import com.stastyle.imumapper.capture.SensorLogger
import com.stastyle.imumapper.data.CalibrationRepository
import com.stastyle.imumapper.data.HeadingOffsetReset
import com.stastyle.imumapper.data.TripFiles
import com.stastyle.imumapper.data.TripRepository
import com.stastyle.imumapper.data.db.TripEntity
import com.stastyle.imumapper.data.db.TripStatus
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.EventKind
import com.stastyle.imumapper.pipeline.core.EventRecord
import com.stastyle.imumapper.pipeline.core.HeadingAxisMode
import com.stastyle.imumapper.pipeline.core.LogMeta
import com.stastyle.imumapper.pipeline.core.PathResult
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.TripMode
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.log.LogReader
import com.stastyle.imumapper.pipeline.log.LogWriter
import com.stastyle.imumapper.pipeline.log.RawLog
import com.stastyle.imumapper.pipeline.pdr.PdrProcessor
import com.stastyle.imumapper.pipeline.pdr.PdrSolver
import com.stastyle.imumapper.pipeline.vio.VioProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/** The four guided flows that record a short log and derive one calibration value from it. */
enum class FlowKind(val title: String) {
    STILL("Still bias"),
    STRIDE("Stride walk"),
    HEADING("Heading offset"),
    SQUARE("Square test"),
}

sealed interface FlowResult {
    data class StillBias(
        val gyroBias: Vec3,
        val gyroNoiseRadS: Double,
        val accelNoiseMs2: Double,
        val gyroSamples: Int,
        val accelSamples: Int,
        /** True when the gyro noise says the phone was not actually still. */
        val moved: Boolean,
    ) : FlowResult

    data class Stride(
        val distanceM: Double,
        val steps: Int,
        val strideM: Double,
        /** Fitted Weinberg gain, null when no step had a usable acceleration swing. */
        val weinbergK: Double?,
    ) : FlowResult

    data class Heading(
        val offsetRad: Double,
        /** Device axis the solver measured the heading on; saved with the offset so trips reuse it. */
        val axis: HeadingAxisMode,
        /** Direction the path ended in with the current offset, radians clockwise from north. */
        val endDirectionRad: Double,
        val walkedM: Double,
        val steps: Int,
    ) : FlowResult

    data class Square(
        val closureM: Double,
        val closurePct: Double?,
        val distanceM: Double,
        val steps: Int,
        /** Path points in ENU metres for the top-down preview. */
        val points: List<Vec3>,
    ) : FlowResult
}

sealed interface FlowPhase {
    data object Idle : FlowPhase

    /**
     * The heading walk's compass step: the sensors run without a log until north settles and the user
     * starts walking.
     */
    data class Compass(val reading: CompassReading) : FlowPhase

    /** Sensors are being recorded. [remainingS] counts down for the timed still flow, null for walks. */
    data class Running(val elapsedS: Int, val remainingS: Int?, val steps: Int) : FlowPhase

    data object Computing : FlowPhase

    data class Done(val result: FlowResult) : FlowPhase

    data class Failed(val message: String) : FlowPhase
}

/** Outcome of running both processors over one recorded trip. Either processor may fail on its own. */
data class VioComparison(
    val tripName: String,
    val comparison: CalibrationMath.Comparison?,
    val pdrSteps: Int?,
    val vioFraction: Double?,
    val pdrError: String?,
    val vioError: String?,
)

sealed interface VioPhase {
    data object Idle : VioPhase
    data object Running : VioPhase
    data class Done(val result: VioComparison) : VioPhase
    data class Failed(val message: String) : VioPhase
}

data class CalibrationUiState(
    val config: PipelineConfig = PipelineConfig(),
    val carry: CarryPosition = CarryPosition.HAND,
    val phases: Map<FlowKind, FlowPhase> = FlowKind.entries.associateWith { FlowPhase.Idle },
    val strideDistanceText: String = "20",
    /** Trips recorded in a camera mode, the only ones that can hold ARCore poses. */
    val vioTrips: List<TripEntity> = emptyList(),
    val vioTripId: Long? = null,
    val vioPhase: VioPhase = VioPhase.Idle,
    /** Transient confirmation for a snackbar. */
    val message: String? = null,
) {
    fun phase(kind: FlowKind): FlowPhase = phases[kind] ?: FlowPhase.Idle

    /** The flow currently waiting for the compass, recording or computing, if any; only one runs at a time. */
    val activeFlow: FlowKind?
        get() = phases.entries.firstOrNull {
            it.value is FlowPhase.Compass || it.value is FlowPhase.Running || it.value is FlowPhase.Computing
        }?.key

    /** A flow runs the sensors: it records, or waits for the compass before recording. */
    val sensorsRunning: Boolean get() = phases.values.any { it is FlowPhase.Running || it is FlowPhase.Compass }
}

/**
 * Runs the calibration flows: each one records a short log through its own [SensorLogger] into a
 * temporary file, reads it back and runs the PDR pipeline on it, exactly as a real trip would be
 * processed. Only the derived value is kept; the file is deleted afterwards.
 *
 * The heading walk first waits for the compass, as a recording does ([FlowPhase.Compass]): its north is
 * taken from the first second of the log, and a fused vector registered cold would still be settling
 * then, so the saved offset would absorb that error and turn every trip by it. The logger runs without
 * a writer until north locks and the user starts walking; the log's writer is then attached to the
 * running logger, which keeps the registrations and so the settled fusion.
 */
class CalibrationViewModel(
    private val appContext: Context,
    private val calibration: CalibrationRepository,
    private val trips: TripRepository,
    private val files: TripFiles,
    /** Records the one-time [HeadingOffsetReset] as done; a saved heading offset makes it moot. */
    private val markHeadingOffsetResetDone: suspend () -> Unit,
) : ViewModel() {

    private val _ui = MutableStateFlow(CalibrationUiState())
    val ui: StateFlow<CalibrationUiState> = _ui.asStateFlow()

    private class Session(val kind: FlowKind, val file: File, val writer: LogWriter, val distanceM: Double?) {
        var ticker: Job? = null
        var closed = false
    }

    /** Created on first use: registering ten sensors at the fastest rate is not free. */
    private var logger: SensorLogger? = null
    private var session: Session? = null

    /** Flow whose log file is being opened on the IO dispatcher; [session] is still null then. */
    private var opening: FlowKind? = null

    /** Flow in its compass step ([FlowPhase.Compass]); [compassJob] publishes the compass meanwhile. */
    private var compassFlow: FlowKind? = null
    private var compassJob: Job? = null

    /**
     * Stops the sensors and closes the writer of the previous session. SensorLogger.stop() runs on
     * the IO dispatcher, so a new start has to wait for it or the late stop would unregister the
     * listeners the new session just registered.
     */
    private var closeJob: Job? = null

    /** Deletes logs left behind by a flow whose process died; a new flow waits for it. */
    private val pruneJob: Job = viewModelScope.launch(Dispatchers.IO) { pruneStaleLogs() }

    init {
        viewModelScope.launch {
            calibration.observeConfig().collect { c -> _ui.update { it.copy(config = c) } }
        }
        viewModelScope.launch {
            calibration.observeCarryPosition().collect { c -> _ui.update { it.copy(carry = c) } }
        }
        viewModelScope.launch {
            trips.observeTrips().collect { all ->
                val candidates = all.filter { it.mode != TripMode.POCKET && it.status != TripStatus.RECORDING }
                _ui.update { s ->
                    val keep = s.vioTripId?.takeIf { id -> candidates.any { it.id == id } }
                    s.copy(vioTrips = candidates, vioTripId = keep ?: candidates.firstOrNull()?.id)
                }
            }
        }
    }

    fun setStrideDistance(text: String) {
        _ui.update { it.copy(strideDistanceText = text) }
    }

    fun selectVioTrip(tripId: Long) {
        _ui.update { it.copy(vioTripId = tripId, vioPhase = VioPhase.Idle) }
    }

    fun dismissMessage() {
        _ui.update { it.copy(message = null) }
    }

    fun discard(kind: FlowKind) {
        if (_ui.value.activeFlow == kind) return
        setPhase(kind, FlowPhase.Idle)
    }

    /**
     * Starts [kind]. The heading walk waits for the compass first and records from [confirmCompass];
     * the others record at once. The still flow stops itself after [STILL_SECONDS]; walks stop on [stop].
     */
    fun start(kind: FlowKind) {
        if (session != null || opening != null || compassFlow != null) {
            _ui.update { it.copy(message = "Finish the running flow first") }
            return
        }
        val distance = if (kind == FlowKind.STRIDE) parseDistance() else null
        if (kind == FlowKind.STRIDE && distance == null) {
            setPhase(kind, FlowPhase.Failed("Enter the walked distance in metres (for example 20)"))
            return
        }
        val sensorLogger = logger ?: SensorLogger(appContext).also { logger = it }
        if (kind == FlowKind.HEADING) enterCompass(kind, sensorLogger) else record(kind, sensorLogger, distance)
    }

    /** "Start walking" in the compass dialog: once north is locked, the heading walk's log starts. */
    fun confirmCompass() {
        val kind = compassFlow ?: return
        val phase = _ui.value.phase(kind) as? FlowPhase.Compass ?: return
        if (phase.reading.status != CompassStatus.LOCKED) return
        val sensorLogger = logger ?: return
        leaveCompass()
        record(kind, sensorLogger, null)
    }

    /**
     * Runs the logger without a writer and publishes the compass until [confirmCompass] or
     * [cancelActive]. It waits for the previous flow's stop first, which would otherwise unregister the
     * listeners registered here.
     */
    private fun enterCompass(kind: FlowKind, sensorLogger: SensorLogger) {
        compassFlow = kind
        setPhase(kind, FlowPhase.Compass(CompassReading(CompassStatus.WAITING)))
        compassJob = viewModelScope.launch {
            closeJob?.join()
            sensorLogger.start(null)
            CompassFeed.readings(sensorLogger).collect { r ->
                if (compassFlow == kind) setPhase(kind, FlowPhase.Compass(r))
            }
        }
    }

    /** Stops publishing the compass; the sensors keep running for the walk or for the caller to stop. */
    private fun leaveCompass() {
        compassJob?.cancel()
        compassJob = null
        compassFlow = null
    }

    /** Opens the flow's log and attaches it to the logger, which starts it or, after a compass step, keeps it. */
    private fun record(kind: FlowKind, sensorLogger: SensorLogger, distance: Double?) {
        opening = kind
        setPhase(kind, FlowPhase.Running(0, if (kind == FlowKind.STILL) STILL_SECONDS else null, 0))
        viewModelScope.launch {
            // Assigned inside the IO block: withContext throws CancellationException after the block
            // completed when the scope is cancelled meanwhile, and the open writer must not be lost.
            var opened: Session? = null
            try {
                pruneJob.join()
                closeJob?.join()
                withContext(Dispatchers.IO) { opened = openSession(kind, sensorLogger, distance) }
            } catch (e: CancellationException) {
                opened?.let { withContext(NonCancellable + Dispatchers.IO) { discardUnstarted(it) } }
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "calibration log could not be opened", e)
                if (opening == kind) {
                    opening = null
                    setPhase(kind, FlowPhase.Failed("Could not open a log file: " + describe(e)))
                    // A heading walk's sensors run since its compass step, and no session will stop them.
                    stopSensors()
                }
                return@launch
            }
            val s = opened ?: return@launch
            if (opening != kind) {
                // cancelActive() ran while the file was being opened; it already marked the flow.
                withContext(NonCancellable + Dispatchers.IO) { discardUnstarted(s) }
                return@launch
            }
            opening = null
            session = s
            sensorLogger.start(s.writer)
            s.ticker = viewModelScope.launch { tick(s, sensorLogger) }
        }
    }

    /** Ends a walk flow and computes its result. */
    fun stop(kind: FlowKind) {
        val s = session ?: return
        if (s.kind != kind) return
        finish(s)
    }

    /**
     * Aborts whatever runs (Cancel in the compass dialog, screen left, app stopped). A compass step goes
     * back to Idle, since nothing was recorded yet; a recording is marked interrupted. Safe to call when
     * nothing runs.
     */
    fun cancelActive() {
        val waiting = compassFlow
        if (waiting != null) {
            leaveCompass()
            setPhase(waiting, FlowPhase.Idle)
            stopSensors()
            return
        }
        val pending = opening
        if (pending != null) {
            // The log is still being opened: record() sees the cleared intent and discards the file. The
            // sensors may be running already, from a compass step.
            opening = null
            setPhase(pending, FlowPhase.Failed("Interrupted before it finished"))
            stopSensors()
            return
        }
        val s = session ?: return
        session = null
        s.ticker?.cancel()
        setPhase(s.kind, FlowPhase.Failed("Interrupted before it finished"))
        closeJob = viewModelScope.launch(Dispatchers.IO) { closeSession(s) }
    }

    /** Stops a logger that runs without a session; the next start waits for it like for a session's close. */
    private fun stopSensors() {
        val sensorLogger = logger ?: return
        val previous = closeJob
        closeJob = viewModelScope.launch(Dispatchers.IO) {
            previous?.join()
            try {
                sensorLogger.stop()
            } catch (e: Exception) {
                Log.w(TAG, "sensor logger stop failed", e)
            }
        }
    }

    fun save(kind: FlowKind) {
        val done = _ui.value.phase(kind) as? FlowPhase.Done ?: return
        val current = _ui.value.config
        val result = done.result
        val change: Pair<PipelineConfig, String> = when (result) {
            is FlowResult.StillBias -> Pair(
                current.copy(gyroBias = result.gyroBias),
                "Still bias: gyro " + Fmt.vec3(result.gyroBias) + " rad/s",
            )
            is FlowResult.Stride -> Pair(
                current.copy(strideLengthM = result.strideM, weinbergK = result.weinbergK ?: current.weinbergK),
                "Stride walk: " + result.steps + " steps over " + Fmt.metres(result.distanceM),
            )
            is FlowResult.Heading -> Pair(
                current.copy(headingOffsetRad = result.offsetRad, headingAxis = result.axis),
                "Heading offset " + Fmt.degrees(result.offsetRad) + " on the " + Fmt.axis(result.axis) +
                    " axis (" + Fmt.carry(_ui.value.carry) + ")",
            )
            // Nothing to change for the square test: it is the baseline number, kept as a note.
            is FlowResult.Square -> Pair(
                current,
                "Square test: closure " + Fmt.metres(result.closureM) + " (" +
                    Fmt.percent(result.closurePct) + " of " + Fmt.metres(result.distanceM) + ")",
            )
        }
        val newConfig = change.first
        val note = change.second
        viewModelScope.launch {
            val outcome = runCatching {
                HeadingOffsetReset.saveCalibration(
                    calibration = calibration,
                    config = newConfig,
                    notes = note,
                    markDone = markHeadingOffsetResetDone,
                    offsetMeasured = result is FlowResult.Heading,
                )
            }
            outcome.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            outcome.getOrNull()?.let { Log.w(TAG, "heading offset reset not marked done", it) }
            outcome.fold(
                onSuccess = {
                    setPhase(kind, FlowPhase.Idle)
                    _ui.update { it.copy(message = kind.title + " saved") }
                },
                onFailure = { e -> _ui.update { it.copy(message = "Save failed: " + describe(e)) } },
            )
        }
    }

    fun runVioComparison() {
        val tripId = _ui.value.vioTripId ?: return
        if (_ui.value.vioPhase is VioPhase.Running) return
        _ui.update { it.copy(vioPhase = VioPhase.Running) }
        viewModelScope.launch {
            val outcome = runCatching {
                val trip = trips.getTrip(tripId) ?: throw IllegalStateException("Trip not found")
                val log = withContext(Dispatchers.IO) {
                    LogReader.read(files.rawLog(tripId), LogReader.UNCALIBRATED_TYPES)
                }
                if (!log.hasVio) throw IllegalStateException("This trip has no ARCore tracking samples")
                val config = _ui.value.config
                withContext(Dispatchers.Default) { compareProcessors(trip.name, log, config) }
            }
            val error = outcome.exceptionOrNull()
            if (error is CancellationException) throw error
            _ui.update { s ->
                s.copy(vioPhase = outcome.fold({ VioPhase.Done(it) }, { VioPhase.Failed(describe(it)) }))
            }
        }
    }

    override fun onCleared() {
        val s = session
        session = null
        opening = null
        leaveCompass()
        s?.ticker?.cancel()
        // viewModelScope is already cancelled here, so the cleanup has to be synchronous.
        if (s != null) closeSession(s)
        logger?.release()
        logger = null
    }

    // --- recording ---

    private suspend fun tick(s: Session, logger: SensorLogger) {
        var elapsed = 0
        while (true) {
            delay(1_000L)
            elapsed++
            val steps = logger.stats.value.stepCount
            if (s.kind == FlowKind.STILL) {
                val remaining = STILL_SECONDS - elapsed
                if (remaining <= 0) break
                setPhase(s.kind, FlowPhase.Running(elapsed, remaining, steps))
            } else {
                setPhase(s.kind, FlowPhase.Running(elapsed, null, steps))
            }
        }
        finish(s)
    }

    /**
     * Every flow deletes its own log when it ends, but a process killed mid-walk leaves a multi-megabyte
     * file behind. Runs once per ViewModel, before the first flow opens its file.
     */
    private fun pruneStaleLogs() {
        val stale = calibrationDir().listFiles() ?: return
        for (f in stale) {
            if (f.isFile && !f.delete()) Log.w(TAG, "could not delete stale calibration log " + f.name)
        }
    }

    private fun calibrationDir(): File = File(appContext.cacheDir, "calibration").also { it.mkdirs() }

    private fun openSession(kind: FlowKind, logger: SensorLogger, distanceM: Double?): Session {
        val dir = calibrationDir()
        val file = File(dir, kind.name.lowercase() + "-" + System.currentTimeMillis() + ".imul")
        val writer = LogWriter(FileOutputStream(file))
        try {
            writer.writeMeta(
                LogMeta(
                    appVersion = BuildConfig.VERSION_NAME,
                    deviceModel = Build.MODEL,
                    androidSdk = Build.VERSION.SDK_INT,
                    mode = TripMode.POCKET,
                    carryPosition = _ui.value.carry,
                    startedAtEpochMs = System.currentTimeMillis(),
                    sensorPeriodsUs = logger.sensorPeriodsUs(),
                    config = _ui.value.config,
                    notes = "calibration: " + kind.name,
                ),
            )
            writer.write(EventRecord(SystemClock.elapsedRealtimeNanos(), EventKind.START))
        } catch (e: Exception) {
            writer.close()
            file.delete()
            throw e
        }
        return Session(kind, file, writer, distanceM)
    }

    private fun finish(s: Session) {
        if (session !== s) return
        session = null
        s.ticker?.cancel()
        setPhase(s.kind, FlowPhase.Computing)
        val config = _ui.value.config
        // Closing is its own job so that a flow started right after this one waits for the sensors to
        // stop, without also waiting for the computation. async keeps a write failure for await(), and
        // ATOMIC makes the writer close even when the ViewModel is cleared before the job gets a thread.
        val closing = viewModelScope.async(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
            try {
                s.writer.write(EventRecord(SystemClock.elapsedRealtimeNanos(), EventKind.STOP))
            } finally {
                closeSession(s, delete = false)
            }
        }
        closeJob = closing
        viewModelScope.launch {
            try {
                closing.await()
                val log = withContext(Dispatchers.IO) { LogReader.read(s.file, LogReader.UNCALIBRATED_TYPES) }
                val result = withContext(Dispatchers.Default) { compute(s, log, config) }
                setPhase(s.kind, FlowPhase.Done(result))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Throwable, not Exception: a pipeline TODO() surfaces as NotImplementedError.
                Log.w(TAG, "calibration flow " + s.kind + " failed", e)
                setPhase(s.kind, FlowPhase.Failed(describe(e)))
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { s.file.delete() }
            }
        }
    }

    /** Stops the sensors and closes the writer. Idempotent; safe from any thread. */
    private fun closeSession(s: Session, delete: Boolean = true) {
        synchronized(s) {
            if (s.closed) return
            s.closed = true
        }
        try {
            logger?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "sensor logger stop failed", e)
        }
        try {
            s.writer.close()
        } catch (e: Exception) {
            Log.w(TAG, "log writer close failed", e)
        }
        if (delete) s.file.delete()
    }

    /** Drops a session whose sensors never started: only the writer and the file exist. */
    private fun discardUnstarted(s: Session) {
        synchronized(s) {
            if (s.closed) return
            s.closed = true
        }
        try {
            s.writer.close()
        } catch (e: Exception) {
            Log.w(TAG, "log writer close failed", e)
        }
        s.file.delete()
    }

    // --- computation (pure; runs on Dispatchers.Default) ---

    private fun compute(s: Session, log: RawLog, config: PipelineConfig): FlowResult = when (s.kind) {
        FlowKind.STILL -> computeStill(log)
        FlowKind.STRIDE -> computeStride(log, config, s.distanceM ?: throw IllegalStateException("No distance"))
        FlowKind.HEADING -> computeHeading(log, config)
        FlowKind.SQUARE -> computeSquare(log, config)
    }

    private fun computeStill(log: RawLog): FlowResult.StillBias {
        // Skip the first second: the phone is usually still settling after the tap on Start.
        val fromNs = log.firstTimestampNs + SETTLE_NS
        val gyro = log.gyro.filter { it.tNs >= fromNs }.map { Vec3.of(it.x, it.y, it.z) }
        val accel = log.accel.filter { it.tNs >= fromNs }.map { Vec3.of(it.x, it.y, it.z) }
        if (gyro.size < MIN_STILL_SAMPLES) throw IllegalStateException("Too few gyroscope samples (" + gyro.size + ")")
        val bias = CalibrationMath.mean(gyro) ?: throw IllegalStateException("No gyroscope samples")
        val gyroNoise = CalibrationMath.noiseRms(gyro) ?: 0.0
        val accelNoise = CalibrationMath.magnitudeStdDev(accel) ?: 0.0
        return FlowResult.StillBias(
            gyroBias = bias,
            gyroNoiseRadS = gyroNoise,
            accelNoiseMs2 = accelNoise,
            gyroSamples = gyro.size,
            accelSamples = accel.size,
            moved = gyroNoise > CalibrationMath.STILL_GYRO_RMS_LIMIT,
        )
    }

    private fun computeStride(log: RawLog, config: PipelineConfig, distanceM: Double): FlowResult.Stride {
        val result = PdrProcessor().process(log, config)
        val steps = result.stats.stepCount
        val stride = CalibrationMath.strideLengthM(distanceM, steps)
            ?: throw IllegalStateException("No steps were detected; walk a little further")
        // The solver exposes the per-step vertical swing the Weinberg model is fitted on.
        val swings = PdrSolver().prepare(log, config).steps.swing
        val k = CalibrationMath.fitWeinbergK(distanceM, swings, stride)
        return FlowResult.Stride(distanceM, steps, stride, k)
    }

    private fun computeHeading(log: RawLog, config: PipelineConfig): FlowResult.Heading {
        // The saved axis belongs to the saved offset; the walk picks its own and saves it with the result.
        // North always comes from the compass here, whatever the Settings switch says: the offset turns
        // the walk onto magnetic north, and against the gyro's own north it would hold an arbitrary angle.
        val walkConfig = config.copy(headingAxis = HeadingAxisMode.AUTO, northFromCompass = true)
        val result = PdrProcessor().process(log, walkConfig)
        val problem = CalibrationMath.northProblem(result.diagnostics)
        if (problem != null) {
            throw IllegalStateException(
                "The compass gave no north during the walk (" + problem + "). Calibrate outdoors, away " +
                    "from metal, cars and electronics.",
            )
        }
        val moved = CalibrationMath.compassMovedDeg(result.diagnostics)
        if (moved != null) {
            throw IllegalStateException(
                "The compass moved by " + Fmt.num(abs(moved), 1) + "° during the walk: calibrate away from metal.",
            )
        }
        val end = result.points.lastOrNull()?.p ?: throw IllegalStateException("The pipeline produced no path")
        val offset = CalibrationMath.headingOffsetFromEnd(end, config.headingOffsetRad)
            ?: throw IllegalStateException("Walk further: the path ended under one metre from the start")
        return FlowResult.Heading(
            offsetRad = offset,
            axis = CalibrationMath.headingAxisFromDiagnostics(result.diagnostics),
            endDirectionRad = CalibrationMath.directionRad(end),
            walkedM = result.stats.distanceM,
            steps = result.stats.stepCount,
        )
    }

    private fun computeSquare(log: RawLog, config: PipelineConfig): FlowResult.Square {
        val result = PdrProcessor().process(log, config)
        val points = result.points.map { it.p }
        val closure = CalibrationMath.closureErrorM(points)
            ?: throw IllegalStateException("The pipeline produced no path")
        return FlowResult.Square(
            closureM = closure,
            closurePct = CalibrationMath.closurePercent(closure, result.stats.distanceM),
            distanceM = result.stats.distanceM,
            steps = result.stats.stepCount,
            points = points,
        )
    }

    private fun compareProcessors(tripName: String, log: RawLog, config: PipelineConfig): VioComparison {
        // runCatching catches Throwable: VioProcessor may still be a TODO() stub (NotImplementedError).
        val pdr: Result<PathResult> = runCatching { PdrProcessor().process(log, config) }
        val vio: Result<PathResult> = runCatching { VioProcessor().process(log, config) }
        val p = pdr.getOrNull()
        val v = vio.getOrNull()
        val comparison = if (p != null && v != null) {
            CalibrationMath.compare(p.points.map { it.p }, p.stats.distanceM, v.points.map { it.p }, v.stats.distanceM)
        } else {
            null
        }
        return VioComparison(
            tripName = tripName,
            comparison = comparison,
            pdrSteps = p?.stats?.stepCount,
            vioFraction = v?.stats?.vioFraction,
            pdrError = pdr.exceptionOrNull()?.let { describe(it) },
            vioError = vio.exceptionOrNull()?.let { describe(it) },
        )
    }

    // --- helpers ---

    private fun parseDistance(): Double? =
        _ui.value.strideDistanceText.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 && it < 1000.0 }

    private fun setPhase(kind: FlowKind, phase: FlowPhase) {
        _ui.update { it.copy(phases = it.phases + (kind to phase)) }
    }

    private fun describe(e: Throwable): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

    companion object {
        private const val TAG = "CalibrationVM"
        const val STILL_SECONDS = 10
        private const val SETTLE_NS = 1_000_000_000L
        private const val MIN_STILL_SAMPLES = 100
    }
}
