package com.stastyle.imumapper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object Notifications {
    const val CHANNEL_RECORDING = "recording"
    const val ID_RECORDING = 1001

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val recording = NotificationChannel(
            CHANNEL_RECORDING,
            context.getString(R.string.notification_channel_recording),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_recording_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(recording)
    }
}
