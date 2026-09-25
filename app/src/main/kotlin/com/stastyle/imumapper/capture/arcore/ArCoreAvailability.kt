package com.stastyle.imumapper.capture.arcore

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether ARCore (Google Play Services for AR) can be used on this device right now. */
sealed interface ArAvailability {
    /** The query is still running; ARCore answers asynchronously the first time. */
    data object Checking : ArAvailability

    /** ARCore is installed and current: a session can be created. */
    data object Ready : ArAvailability

    /** The device is capable but ARCore is missing or too old; [ArCoreAvailability.requestInstall] fixes it. */
    data class NeedsInstall(val detail: String) : ArAvailability

    /** The Play Store install flow was started; it completes when the activity resumes. */
    data object InstallRequested : ArAvailability

    /** This device cannot run ARCore at all. */
    data class Unsupported(val detail: String) : ArAvailability

    /** The availability check itself failed (for example offline with ARCore not installed). */
    data class Error(val message: String) : ArAvailability
}

/**
 * Wraps `ArCoreApk.checkAvailability` and `requestInstall` as a state the UI can show. All methods are
 * main-thread only; `checkAvailability` may answer "still checking" and is polled until it settles.
 */
class ArCoreAvailability(context: Context) {

    private val appContext: Context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow<ArAvailability>(ArAvailability.Checking)
    val state: StateFlow<ArAvailability> = _state.asStateFlow()
    private var attempts = 0
    private val pollRunnable = Runnable { poll() }

    /** Starts (or restarts) the availability query. Safe to call on every resume. */
    fun check() {
        handler.removeCallbacks(pollRunnable)
        attempts = 0
        poll()
    }

    /**
     * Starts the Play Store install of ARCore, or finishes one started earlier ([userRequested] false
     * when called from onResume after [ArAvailability.InstallRequested]).
     */
    fun requestInstall(activity: Activity, userRequested: Boolean) {
        try {
            val next: ArAvailability? = when (ArCoreApk.getInstance().requestInstall(activity, userRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> ArAvailability.InstallRequested
                ArCoreApk.InstallStatus.INSTALLED -> ArAvailability.Ready
                else -> null
            }
            if (next != null) _state.value = next else check()
        } catch (e: UnavailableUserDeclinedInstallationException) {
            _state.value = ArAvailability.NeedsInstall("ARCore install was declined")
        } catch (e: UnavailableDeviceNotCompatibleException) {
            _state.value = ArAvailability.Unsupported("This device does not support ARCore")
        } catch (e: Exception) {
            Log.w(TAG, "requestInstall failed", e)
            val detail = e.message ?: e.javaClass.simpleName
            _state.value = ArAvailability.Error("Could not start the ARCore install: $detail")
        }
    }

    private fun poll() {
        val availability = try {
            ArCoreApk.getInstance().checkAvailability(appContext)
        } catch (e: Exception) {
            Log.w(TAG, "checkAvailability failed", e)
            _state.value = ArAvailability.Error("Could not check ARCore: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        when (availability) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED -> _state.value = ArAvailability.Ready
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> setNeedsInstall("ARCore is not installed")
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> setNeedsInstall("ARCore needs an update")
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE ->
                _state.value = ArAvailability.Unsupported("This device does not support ARCore")
            else -> {
                // UNKNOWN_CHECKING / UNKNOWN_TIMED_OUT / UNKNOWN_ERROR: the answer is not settled yet.
                if (attempts < MAX_ATTEMPTS) {
                    attempts++
                    _state.value = ArAvailability.Checking
                    handler.postDelayed(pollRunnable, POLL_MS)
                } else {
                    _state.value = ArAvailability.Error("Could not determine ARCore availability ($availability)")
                }
            }
        }
    }

    private fun setNeedsInstall(detail: String) {
        // Keep "install requested" while the Play Store flow is in flight so the UI does not flicker.
        if (_state.value !is ArAvailability.InstallRequested) _state.value = ArAvailability.NeedsInstall(detail)
    }

    private companion object {
        const val TAG = "ArCoreAvailability"
        const val POLL_MS = 200L
        const val MAX_ATTEMPTS = 25
    }
}
