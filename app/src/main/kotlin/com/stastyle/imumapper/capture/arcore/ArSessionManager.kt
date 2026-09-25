package com.stastyle.imumapper.capture.arcore

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.stastyle.imumapper.capture.RecordingController
import com.stastyle.imumapper.pipeline.core.EventKind
import com.stastyle.imumapper.pipeline.core.TrackingState
import com.stastyle.imumapper.pipeline.core.TripMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class ArStatus { CLOSED, PAUSED, RUNNING, FAILED }

/** What the ARCore session is doing, for the recording screen overlay. */
data class ArSessionState(
    val status: ArStatus = ArStatus.CLOSED,
    val tracking: TrackingState = TrackingState.STOPPED,
    /** Why tracking is not running, in plain words; empty while tracking. */
    val trackingDetail: String = "",
    val torchSupported: Boolean = false,
    val torchOn: Boolean = false,
    /** Photos saved so far (illuminated mode). */
    val keyframeCount: Int = 0,
    /** Set with [ArStatus.FAILED]; cleared by [ArSessionManager.close]. */
    val error: String? = null,
)

/**
 * Owns one ARCore [Session] for a recording: creation and configuration, pause/resume, torch, and the
 * per-frame update on the GL thread (through [onGlFrame]). Records go to [controller]. Every method is
 * safe to call from any thread; the session calls themselves are serialised by one lock so a pause from
 * the main thread never overlaps an update on the GL thread.
 *
 * Lifecycle: [open] once per trip, then [resume] / [pause] following the activity, then [close].
 * Errors never throw out; they set [ArSessionState.error] with [ArStatus.FAILED].
 */
class ArSessionManager(context: Context, private val controller: RecordingController) {

    private val appContext: Context = context.applicationContext
    private val lock = Any()
    private var session: Session? = null
    private var resumed = false
    private var logger: ArFrameLogger? = null
    private var saver: KeyframeSaver? = null
    private var writeExecutor: ExecutorService? = null
    private var textureName = -1
    private var textureDirty = false
    private var geometry: DisplayGeometry? = null
    private var geometryDirty = false
    private var torchOn = false
    private var torchSupported = false
    private var openedPhotosDir: File? = null

    private val _state = MutableStateFlow(ArSessionState())
    val state: StateFlow<ArSessionState> = _state.asStateFlow()

    val isOpen: Boolean get() = synchronized(lock) { session != null }

    /**
     * Creates and configures the session for [mode]; keyframes of illuminated mode go to [photosDir].
     * Returns true when a session exists afterwards (also when it was already open). Takes ~100 ms.
     */
    fun open(mode: TripMode, photosDir: File, displayRotationDegrees: Int): Boolean = synchronized(lock) {
        if (session != null) {
            if (photosDir == openedPhotosDir) return true
            // A different trip: the old session's map and photo directory must not leak into it.
            close()
        }
        val s = try {
            Session(appContext)
        } catch (e: UnavailableArcoreNotInstalledException) {
            return fail("ARCore is not installed", e)
        } catch (e: UnavailableApkTooOldException) {
            return fail("ARCore needs an update", e)
        } catch (e: UnavailableSdkTooOldException) {
            return fail("This app needs an update for the installed ARCore", e)
        } catch (e: UnavailableDeviceNotCompatibleException) {
            return fail("This device does not support ARCore", e)
        } catch (e: SecurityException) {
            return fail("Camera permission is missing", e)
        } catch (e: Exception) {
            return fail("Could not start ARCore: ${e.message ?: e.javaClass.simpleName}", e)
        }

        val cameraId = try {
            s.cameraConfig.cameraId
        } catch (e: Exception) {
            Log.w(TAG, "camera id unavailable", e)
            null
        }
        val characteristics = cameraId?.let { cameraCharacteristics(it) }
        torchSupported = characteristics?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        torchOn = mode == TripMode.FLASHLIGHT && torchSupported

        val config = Config(s)
        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        config.focusMode = Config.FocusMode.AUTO
        config.planeFindingMode = Config.PlaneFindingMode.DISABLED
        config.lightEstimationMode = Config.LightEstimationMode.DISABLED
        config.depthMode = Config.DepthMode.DISABLED
        config.flashMode = if (torchOn) Config.FlashMode.TORCH else Config.FlashMode.OFF
        try {
            s.configure(config)
        } catch (e: Exception) {
            runCatching { s.close() }
            return fail("ARCore rejected the configuration: ${e.message ?: e.javaClass.simpleName}", e)
        }

        val keyframeSaver = if (mode == TripMode.ILLUMINATED) {
            val sensorOrientation = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            KeyframeSaver(photosDir, exifOrientationFor(jpegRotationDegrees(sensorOrientation, displayRotationDegrees)))
        } else {
            null
        }
        // A trip can outlive this session (screen re-entered, Retry, activity recreated): keyframe numbering
        // continues after the photos already on disk so none of them is overwritten.
        val existingKeyframes =
            if (keyframeSaver != null) existingKeyframeIndices(photosDir.list()?.asList() ?: emptyList()) else emptyList()
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "ar-log") }
        logger = ArFrameLogger(
            controller, executor, keyframeSaver, ::onTracking, ::onKeyframeSaved,
            lastKeyframeIndex = existingKeyframes.maxOrNull() ?: 0,
            keyframesOnDisk = existingKeyframes.size,
        )
        saver = keyframeSaver
        writeExecutor = executor
        session = s
        openedPhotosDir = photosDir
        resumed = false
        textureDirty = true
        geometryDirty = true
        _state.value = ArSessionState(
            status = ArStatus.PAUSED,
            torchSupported = torchSupported,
            torchOn = torchOn,
            keyframeCount = existingKeyframes.size,
        )
        true
    }

    /** Starts the camera. Returns false (with [ArSessionState.error] set) when the camera is not available. */
    fun resume(): Boolean = synchronized(lock) {
        val s = session ?: return false
        if (resumed) return true
        try {
            s.resume()
        } catch (e: CameraNotAvailableException) {
            return fail("Camera not available. Close other camera apps and try again.", e)
        } catch (e: Exception) {
            return fail("Could not resume ARCore: ${e.message ?: e.javaClass.simpleName}", e)
        }
        resumed = true
        // The torch physically follows the camera, so the log marks it on at every resume.
        if (torchOn) controller.writeEvent(EventKind.TORCH_ON)
        _state.update { it.copy(status = ArStatus.RUNNING, error = null) }
        true
    }

    /** Stops the camera (and torch); the session and its tracking map are kept for [resume]. */
    fun pause() {
        synchronized(lock) {
            val s = session ?: return
            if (!resumed) return
            resumed = false
            runCatching { s.pause() }.onFailure { Log.w(TAG, "pause failed", it) }
            // The camera is off: close the tracking run in the log rather than leaving it open-ended.
            logger?.onSessionStopped()
            if (torchOn) controller.writeEvent(EventKind.TORCH_OFF)
            _state.update {
                if (it.status == ArStatus.FAILED) {
                    it
                } else {
                    it.copy(status = ArStatus.PAUSED, tracking = TrackingState.STOPPED, trackingDetail = "")
                }
            }
        }
    }

    /** Releases the session and the worker threads; queued log writes still complete. */
    fun close() {
        synchronized(lock) {
            pause()
            session?.let { s -> runCatching { s.close() }.onFailure { Log.w(TAG, "close failed", it) } }
            session = null
            openedPhotosDir = null
            logger = null
            saver?.shutdown()
            saver = null
            writeExecutor?.shutdown()
            writeExecutor = null
            resumed = false
            torchOn = false
            torchSupported = false
            _state.value = ArSessionState()
        }
    }

    /** Switches the camera torch; a no-op when the camera has no flash. */
    fun setTorch(on: Boolean) {
        synchronized(lock) {
            val s = session ?: return
            if (!torchSupported || on == torchOn) return
            val config = s.config
            config.flashMode = if (on) Config.FlashMode.TORCH else Config.FlashMode.OFF
            try {
                s.configure(config)
            } catch (e: Exception) {
                Log.w(TAG, "torch change rejected", e)
                return
            }
            torchOn = on
            if (resumed) controller.writeEvent(if (on) EventKind.TORCH_ON else EventKind.TORCH_OFF)
            _state.update { it.copy(torchOn = on) }
        }
    }

    /**
     * GL thread, once per drawn frame. Applies a pending camera texture name and display geometry, runs
     * `Session.update()` and the logging, and returns the frame to draw; null when nothing is running.
     */
    fun onGlFrame(textureName: Int, geometry: DisplayGeometry?): Frame? = synchronized(lock) {
        if (geometry != null) {
            this.geometry = geometry
            geometryDirty = true
        }
        if (textureName != this.textureName) {
            this.textureName = textureName
            textureDirty = true
        }
        val s = session ?: return null
        if (!resumed) return null
        try {
            if (textureDirty && this.textureName >= 0) {
                s.setCameraTextureName(this.textureName)
                textureDirty = false
            }
            val g = this.geometry
            if (geometryDirty && g != null && g.width > 0 && g.height > 0) {
                s.setDisplayGeometry(g.rotation, g.width, g.height)
                geometryDirty = false
            }
            val frame = s.update()
            logger?.onFrame(frame)
            frame
        } catch (e: CameraNotAvailableException) {
            stopAfterFailure(s)
            fail("Camera not available. Close other camera apps and try again.", e)
            null
        } catch (e: Exception) {
            stopAfterFailure(s)
            fail("ARCore stopped: ${e.message ?: e.javaClass.simpleName}", e)
            null
        }
    }

    /**
     * Under [lock]. Leaves the session paused after a GL-thread failure; [pause] would skip it later, so the
     * log's tracking run is closed here (Retry then opens a fresh session, which the log must not mistake
     * for a resume of this one).
     */
    private fun stopAfterFailure(s: Session) {
        resumed = false
        runCatching { s.pause() }
        logger?.onSessionStopped()
    }

    private fun onTracking(tracking: TrackingState, reason: TrackingFailureReason) {
        _state.update { it.copy(tracking = tracking, trackingDetail = describe(tracking, reason)) }
    }

    private fun onKeyframeSaved(count: Int) {
        _state.update { it.copy(keyframeCount = count) }
    }

    private fun fail(message: String, cause: Exception): Boolean {
        Log.e(TAG, message, cause)
        _state.update { it.copy(status = ArStatus.FAILED, error = message) }
        return false
    }

    private fun cameraCharacteristics(cameraId: String): CameraCharacteristics? = try {
        val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        manager?.getCameraCharacteristics(cameraId)
    } catch (e: Exception) {
        Log.w(TAG, "camera characteristics unavailable", e)
        null
    }

    private fun describe(tracking: TrackingState, reason: TrackingFailureReason): String {
        if (tracking == TrackingState.TRACKING) return ""
        return when (reason) {
            TrackingFailureReason.NONE -> if (tracking == TrackingState.PAUSED) "Initialising" else "Stopped"
            TrackingFailureReason.BAD_STATE -> "Tracking lost, recovering"
            TrackingFailureReason.INSUFFICIENT_LIGHT -> "Too dark"
            TrackingFailureReason.EXCESSIVE_MOTION -> "Moving too fast"
            TrackingFailureReason.INSUFFICIENT_FEATURES -> "Not enough visual detail"
            TrackingFailureReason.CAMERA_UNAVAILABLE -> "Camera unavailable"
            else -> reason.name.lowercase().replace('_', ' ')
        }
    }

    private companion object {
        const val TAG = "ArSessionManager"
    }
}
