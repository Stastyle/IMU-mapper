package com.stastyle.imumapper

import android.app.Application
import android.content.Context
import com.stastyle.imumapper.capture.RecordingController
import com.stastyle.imumapper.update.UpdateManager

class ImuMapperApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        // Creating the controller runs the stale-trip sweep on its own background scope, so a trip left
        // in RECORDING by a killed process is closed out on every launch, whichever screen opens first.
        RecordingController.get(this)
        UpdateManager.get(this).autoCheckIfDue()
    }

    companion object {
        fun from(context: Context): ImuMapperApp = context.applicationContext as ImuMapperApp
        fun container(context: Context): AppContainer = from(context).container
    }
}
