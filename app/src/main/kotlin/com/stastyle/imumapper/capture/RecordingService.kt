package com.stastyle.imumapper.capture

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.stastyle.imumapper.MainActivity
import com.stastyle.imumapper.Notifications
import com.stastyle.imumapper.R

/**
 * Foreground service that keeps sensor logging alive while the screen is off (pocket mode).
 *
 * Skeleton only: the app-capture work item adds the sensor logger, wake lock, annotation
 * handling and state reporting. The service type is "health", which requires the
 * ACTIVITY_RECOGNITION runtime permission to be granted before [start] is called.
 */
open class RecordingService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val tripId = intent.getLongExtra(EXTRA_TRIP_ID, -1L)
                startForeground(
                    Notifications.ID_RECORDING,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH,
                )
                onStartRecording(tripId)
            }
            ACTION_STOP -> {
                onStopRecording()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /** Hook for the capture implementation. */
    protected open fun onStartRecording(tripId: Long) = Unit

    /** Hook for the capture implementation. */
    protected open fun onStopRecording() = Unit

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, Notifications.CHANNEL_RECORDING)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(getString(R.string.notification_recording_title))
            .setContentText(getString(R.string.notification_recording_text))
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.notification_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
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
