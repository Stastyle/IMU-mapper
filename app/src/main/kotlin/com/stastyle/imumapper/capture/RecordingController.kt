package com.stastyle.imumapper.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.stastyle.imumapper.BuildConfig
import com.stastyle.imumapper.ImuMapperApp
import com.stastyle.imumapper.data.db.TripEntity
import com.stastyle.imumapper.data.db.TripStatus
import com.stastyle.imumapper.pipeline.core.AnnotationKind
import com.stastyle.imumapper.pipeline.core.AnnotationRecord
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.EventKind
import com.stastyle.imumapper.pipeline.core.EventRecord
import com.stastyle.imumapper.pipeline.core.LogMeta
import com.stastyle.imumapper.pipeline.core.LogRecord
import com.stastyle.imumapper.pipeline.core.TripMode
import com.stastyle.imumapper.pipeline.log.LogWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the recorder is doing right now. */
sealed interface RecordingState {
    data object Idle : RecordingState

    data class Recording(
        val tripId: Long,
        val mode: TripMode,
        val carryPosition: CarryPosition,
        /** Elapsed-realtime nanoseconds when START was written; the time base of every record. */
        val startedNs: Long,
        /** Recording time so far, not counting pauses. */
        val elapsedNs: Long,
        val paused: Boolean,
        val stepCount: Int,
        val annotationCount: Int,
        /** Where the ARCore keyframe photos of this trip go. */
        val photosDir: File,
    ) : RecordingState

    data object Stopping : RecordingState
}

/**
 * Process-wide owner of the current recording. Screens, the foreground service and the ARCore session
 * all talk to the same instance from [get]. Lives as long as the process so a recording survives the
 * recording screen being left and re-entered.
 *
 * Threading: [start] and [stop] are serialised by a mutex and run in the controller's own scope, so a
 * caller that is cancelled mid-way (a ViewModel being cleared) never leaves the recorder half stopped.
 * [write], [annotate], [pause] and [resume] may be called from any thread.
 */
class RecordingController private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val container = ImuMapperApp.container(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    /** The IMU logger. Only one exists per process; calibration flows create their own [SensorLogger]. */
    val sensorLogger: SensorLogger = SensorLogger(appContext)

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    /** Guards [writer], [session] and [stopping]. Never hold it across a suspend point. */
    private val lock = Any()
    private var writer: LogWriter? = null
    private var session: Session? = null
    private var stopping = false
    private var ticker: Job? = null

    /** Mutable bookkeeping for one recording; read and written under [lock]. */
    private class Session(
        val tripId: Long,
        val mode: TripMode,
        val carryPosition: CarryPosition,
        val startedNs: Long,
        val startedAtEpochMs: Long,
        val photosDir: File,
    ) {
        var paused = false
        var pauseStartNs = 0L
        var pausedAccumNs = 0L
        var annotationCount = 0
        var serviceStarted = false

        fun activeElapsedNs(nowNs: Long): Long {
            val pausedNow = if (paused) nowNs - pauseStartNs else 0L
            return nowNs - startedNs - pausedAccumNs - pausedNow
        }
    }

    val isRecording: Boolean
        get() = synchronized(lock) { session != null && !stopping }

    /** Trip being recorded, or null. */
    val currentTripId: Long?
        get() = synchronized(lock) { session?.tripId }

    init {
        // A trip left in RECORDING status means the process died mid-recording; whatever was flushed
        // to raw.imul is the log. Runs under the mutex so it cannot race a start() that follows.
        scope.launch {
            mutex.withLock {
                runCatching { recoverStaleTrips() }.onFailure { Log.w(TAG, "stale trip recovery failed", it) }
            }
        }
    }

    /**
     * Creates the trip, opens its raw log, starts the foreground service and the sensors.
     * Returns the new trip id. Throws if a recording is already running or the trip cannot be created.
     */
    suspend fun start(mode: TripMode, carryPosition: CarryPosition): Long =
        scope.async { mutex.withLock { startLocked(mode, carryPosition) } }.await()

    /**
     * Writes STOP, closes the log, marks the trip RECORDED and stops the service.
     * Returns the trip id, or null when nothing was recording.
     */
    suspend fun stop(): Long? = scope.async { mutex.withLock { stopLocked() } }.await()

    /** Appends a record from another producer (ARCore poses, keyframes). Dropped when not recording. */
    fun write(record: LogRecord): Boolean = synchronized(lock) {
        val w = writer ?: return false
        if (stopping) return false
        try {
            w.write(record)
            true
        } catch (e: Exception) {
            Log.w(TAG, "write failed", e)
            false
        }
    }

    fun writeEvent(kind: EventKind): Boolean = write(EventRecord(nowNs(), kind))

    /** Places an annotation at the current time. Returns false when not recording. */
    fun annotate(kind: AnnotationKind, note: String = ""): Boolean {
        val ok = synchronized(lock) {
            val s = session
            if (s == null || stopping) {
                false
            } else {
                val written = write(AnnotationRecord(nowNs(), kind, note))
                if (written) s.annotationCount++
                written
            }
        }
        if (ok) publish()
        return ok
    }

    /** Volume-key shortcut: a WAYPOINT with haptic feedback. Returns true when the key was consumed. */
    fun onVolumeKey(): Boolean {
        if (!isRecording) return false
        val ok = annotate(AnnotationKind.WAYPOINT)
        if (ok) haptic()
        return ok
    }

    fun pause() {
        val changed = synchronized(lock) {
            val s = session
            if (s == null || stopping || s.paused) {
                false
            } else {
                s.paused = true
                s.pauseStartNs = nowNs()
                write(EventRecord(s.pauseStartNs, EventKind.PAUSE))
                true
            }
        }
        if (changed) publish()
    }

    fun resume() {
        val changed = synchronized(lock) {
            val s = session
            if (s == null || stopping || !s.paused) {
                false
            } else {
                val now = nowNs()
                s.pausedAccumNs += now - s.pauseStartNs
                s.paused = false
                write(EventRecord(now, EventKind.RESUME))
                true
            }
        }
        if (changed) publish()
    }

    /**
     * Marks a trip nobody is recording as RECORDED. Used by the service when it is started for a trip
     * after the process died, and by the stale-trip sweep at startup.
     */
    suspend fun finalizeOrphanedTrip(tripId: Long) {
        if (currentTripId == tripId) return
        val trip = container.tripRepository.getTrip(tripId) ?: return
        if (trip.status != TripStatus.RECORDING) return
        val file = container.tripFiles.rawLog(tripId)
        container.tripRepository.updateTrip(
            trip.copy(
                endedAtEpochMs = trip.endedAtEpochMs ?: System.currentTimeMillis(),
                status = TripStatus.RECORDED,
                rawLogSizeBytes = file.length(),
                notes = if (trip.notes.isBlank()) "Recording ended unexpectedly" else trip.notes,
            ),
        )
    }

    private suspend fun recoverStaleTrips() {
        val trips = container.tripRepository.observeTrips().first()
        for (trip in trips) {
            if (trip.status == TripStatus.RECORDING) finalizeOrphanedTrip(trip.id)
        }
    }

    private suspend fun startLocked(mode: TripMode, carryPosition: CarryPosition): Long {
        check(synchronized(lock) { session == null }) { "A recording is already running" }
        val startedAtEpochMs = System.currentTimeMillis()
        val config = container.calibrationRepository.getConfig()
        val tripId = container.tripRepository.createTrip(
            TripEntity(
                name = tripName(mode, startedAtEpochMs),
                mode = mode,
                carryPosition = carryPosition,
                startedAtEpochMs = startedAtEpochMs,
                status = TripStatus.RECORDING,
            ),
        )
        val rawLog = container.tripFiles.rawLog(tripId)
        val photosDir = container.tripFiles.photosDir(tripId)
        val w = withContext(Dispatchers.IO) { LogWriter(FileOutputStream(rawLog)) }
        val startedNs = nowNs()
        try {
            w.writeMeta(
                LogMeta(
                    appVersion = appVersion(),
                    deviceModel = Build.MODEL,
                    androidSdk = Build.VERSION.SDK_INT,
                    mode = mode,
                    carryPosition = carryPosition,
                    startedAtEpochMs = startedAtEpochMs,
                    sensorPeriodsUs = sensorLogger.sensorPeriodsUs(),
                    config = config,
                ),
            )
            w.write(EventRecord(startedNs, EventKind.START))
        } catch (e: Exception) {
            runCatching { w.close() }
            throw e
        }
        val s = Session(tripId, mode, carryPosition, startedNs, startedAtEpochMs, photosDir)
        synchronized(lock) {
            writer = w
            session = s
            stopping = false
        }
        sensorLogger.start(w)
        s.serviceStarted = startService(tripId)
        publish()
        ticker = scope.launch {
            while (isActive) {
                publish()
                delay(TICK_MS)
            }
        }
        return tripId
    }

    private suspend fun stopLocked(): Long? {
        val s = synchronized(lock) {
            val current = session
            if (current != null) stopping = true
            current
        } ?: return null
        _state.value = RecordingState.Stopping
        ticker?.cancel()
        ticker = null
        // The logger thread must let go of the writer before it is closed; stop() blocks briefly.
        withContext(Dispatchers.IO) { sensorLogger.stop() }
        val stopNs = nowNs()
        val elapsedNs = synchronized(lock) {
            val w = writer
            if (w != null) {
                runCatching { w.write(EventRecord(stopNs, EventKind.STOP)) }
                    .onFailure { Log.w(TAG, "STOP not written", it) }
                runCatching { w.close() }.onFailure { Log.w(TAG, "close failed", it) }
            }
            writer = null
            session = null
            s.activeElapsedNs(stopNs)
        }
        try {
            val trip = container.tripRepository.getTrip(s.tripId)
            if (trip != null) {
                container.tripRepository.updateTrip(
                    trip.copy(
                        endedAtEpochMs = System.currentTimeMillis(),
                        status = TripStatus.RECORDED,
                        rawLogSizeBytes = container.tripFiles.rawLog(s.tripId).length(),
                        durationS = elapsedNs / 1e9,
                    ),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "trip update failed", e)
        }
        if (s.serviceStarted) {
            runCatching { RecordingService.stop(appContext) }.onFailure { Log.w(TAG, "service stop failed", it) }
        }
        synchronized(lock) { stopping = false }
        _state.value = RecordingState.Idle
        return s.tripId
    }

    private fun publish() {
        val next = synchronized(lock) {
            val s = session
            if (s == null || stopping) {
                null
            } else {
                val now = nowNs()
                RecordingState.Recording(
                    tripId = s.tripId,
                    mode = s.mode,
                    carryPosition = s.carryPosition,
                    startedNs = s.startedNs,
                    elapsedNs = s.activeElapsedNs(now),
                    paused = s.paused,
                    stepCount = sensorLogger.stats.value.stepCount,
                    annotationCount = s.annotationCount,
                    photosDir = s.photosDir,
                )
            }
        }
        if (next != null) _state.value = next
    }

    /** Starts the foreground service; false when the health permission is missing (recording continues). */
    private fun startService(tripId: Long): Boolean {
        val granted = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.w(TAG, "ACTIVITY_RECOGNITION not granted; recording without the foreground service")
            return false
        }
        return try {
            RecordingService.start(appContext, tripId)
            true
        } catch (e: Exception) {
            Log.w(TAG, "foreground service did not start", e)
            false
        }
    }

    private fun haptic() {
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                appContext.getSystemService(Vibrator::class.java)
            }
            vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } catch (e: Exception) {
            Log.w(TAG, "haptic failed", e)
        }
    }

    private fun appVersion(): String {
        val fromPackage = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        }.getOrNull()
        return fromPackage ?: BuildConfig.VERSION_NAME
    }

    private fun tripName(mode: TripMode, epochMs: Long): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
        return "${modeLabel(mode)} $stamp"
    }

    private fun nowNs(): Long = SystemClock.elapsedRealtimeNanos()

    companion object {
        private const val TAG = "RecordingController"
        private const val TICK_MS = 250L

        @Volatile
        private var instance: RecordingController? = null

        fun get(context: Context): RecordingController {
            val existing = instance
            if (existing != null) return existing
            synchronized(this) {
                val again = instance
                if (again != null) return again
                val created = RecordingController(context.applicationContext)
                instance = created
                return created
            }
        }
    }
}
