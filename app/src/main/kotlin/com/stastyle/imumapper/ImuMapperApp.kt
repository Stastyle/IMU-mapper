package com.stastyle.imumapper

import android.app.Application
import android.content.Context

class ImuMapperApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
    }

    companion object {
        fun from(context: Context): ImuMapperApp = context.applicationContext as ImuMapperApp
        fun container(context: Context): AppContainer = from(context).container
    }
}
