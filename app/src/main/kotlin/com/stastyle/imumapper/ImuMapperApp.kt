package com.stastyle.imumapper

import android.app.Application
import android.content.Context
import com.stastyle.imumapper.update.UpdateManager

class ImuMapperApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        UpdateManager.get(this).autoCheckIfDue()
    }

    companion object {
        fun from(context: Context): ImuMapperApp = context.applicationContext as ImuMapperApp
        fun container(context: Context): AppContainer = from(context).container
    }
}
