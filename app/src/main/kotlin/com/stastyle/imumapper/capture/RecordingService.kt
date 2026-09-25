package com.stastyle.imumapper.capture

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.stastyle.imumapper.MainActivity
import com.stastyle.imumapper.Notifications
import com.stastyle.imumapper.R
import com.stastyle.imumapper.pipeline.core.EventKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps sensor logging alive while the screen is off (pocket mode).
 *
 * The service owns nothing about the recording itself; [RecordingController] does. Here we hold a
 * partial wake lock, log screen on/off events, keep the notification's elapsed time fresh, and
 * forward the notification's Stop action to the controller. The service type is "health", which
 * requires the ACTIVITY_RECOGNITION runtime permission to be granted before [start] is called.
 */
open class RecordingService : LifecycleService() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var updater: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val tripId = intent.getLongExtra(EXTRA_TRIP_ID, -1L)
                val controller = RecordingController.get(this)
                try {
                    startForeground(
                        Notifications.ID_RECORDING,
                        buildNotification(controller.state.value),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH,
                    )
                } catch (e: Exception) {
                    // Missing permission or background-start restriction: the in-process recording goes
                    // on while the screen is on; only screen-off survival is lost.
                    Log.e(TAG, "startForeground failed", e)
                    stopSelf()
                    return START_NOT_STICKY
                }
                onStartRecording(tripId)
            }
            ACTION_STOP -> onStopRecording()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Called once the service is in the foreground for [tripId]. */
    protected open fun onStartRecording(tripId: Long) {
        val controller = RecordingController.get(this)
        // currentTripId is set under the controller's lock before the start intent is sent, so it is
        // reliable even when this runs before the controller has published its Recording state.
        if (controller.currentTripId != tripId) {
            // Nobody in this process is recording that trip: the process died and we were restarted, or
            // a stale start intent arrived. Close the trip out and leave.
            lifecycleScope.launch {
                runCatching { controller.finalizeOrphanedTrip(tripId) }
                    .onFailure { Log.w(TAG, "orphan finalize failed", it) }
                finish()
            }
            return
        }
        acquireWakeLock()
        registerScreenReceiver(controller)
        updater?.cancel()
        updater = lifecycleScope.launch {
            while (isActive) {
                val state = controller.state.value
                if (state is RecordingState.Idle) {
                    // Stopped from the screen; the controller also sends ACTION_STOP, but do not wait for it.
                    finish()
                    break
                }
                notify(state)
                delay(NOTIFICATION_PERIOD_MS)
            }
        }
    }

    /** Notification Stop action, or the controller telling us the recording ended. */
    protected open fun onStopRecording() {
        lifecycleScope.launch {
            runCatching { RecordingController.get(this@RecordingService).stop() }
                .onFailure { Log.w(TAG, "stop failed", it) }
            finish()
        }
    }

    override fun onDestroy() {
        updater?.cancel()
        updater = null
        unregisterScreenReceiver()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun finish() {
        updater?.cancel()
        updater = null
        unregisterScreenReceiver()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        // A timeout is a safety net against a leaked lock; trips are minutes, not hours.
        wl.acquire(WAKE_LOCK_TIMEOUT_MS)
        wakeLock = wl
    }

    private fun releaseWakeLock() {
        val wl = wakeLock ?: return
        wakeLock = null
        if (wl.isHeld) {
            runCatching { wl.release() }.onFailure { Log.w(TAG, "wake lock release failed", it) }
        }
    }

    private fun registerScreenReceiver(controller: RecordingController) {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> controller.writeEvent(EventKind.SCREEN_OFF)
                    Intent.ACTION_SCREEN_ON -> controller.writeEvent(EventKind.SCREEN_ON)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        // System broadcasts reach a non-exported receiver; the flag only keeps other apps out.
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenReceiver = receiver
    }

    private fun unregisterScreenReceiver() {
        val receiver = screenReceiver ?: return
        screenReceiver = null
        runCatching { unregisterReceiver(receiver) }
    }

    private fun notify(state: RecordingState) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(Notifications.ID_RECORDING, buildNotification(state)) }
            .onFailure { Log.w(TAG, "notify failed", it) }
    }

    private fun buildNotification(state: RecordingState): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (state) {
            is RecordingState.Recording -> {
                val elapsed = formatElapsed(state.elapsedNs)
                val steps = "${state.stepCount} steps"
                if (state.paused) "Paused at $elapsed · $steps" else "$elapsed · $steps · ${modeLabel(state.mode)}"
            }
            is RecordingState.Stopping -> "Saving…"
            is RecordingState.Idle -> getString(R.string.notification_recording_text)
        }
        return NotificationCompat.Builder(this, Notifications.CHANNEL_RECORDING)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(getString(R.string.notification_recording_title))
            .setContentText(text)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.notification_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val WAKE_LOCK_TAG = "imumapper:recording"
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1000L
        private const val NOTIFICATION_PERIOD_MS = 3000L

        const val ACTION_START = "com.stastyle.imumapper.action.START_RECORDING"
        const val ACTION_STOP = "com.stastyle.imumapper.action.STOP_RECORDING"
        const val EXTRA_TRIP_ID = "tripId"

        fun start(context: Context, tripId: Long) {
            val intent = Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TRIP_ID, tripId)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
        }
    }
}
