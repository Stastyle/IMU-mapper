package com.stastyle.imumapper.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.stastyle.imumapper.AppContainer
import com.stastyle.imumapper.ImuMapperApp

/** The app-wide dependency container, for screens and view-model factories. */
@Composable
fun appContainer(): AppContainer = ImuMapperApp.container(LocalContext.current)

/**
 * The activity behind a Compose context, unwrapping the context wrappers around it; null when there is
 * none. ARCore's install flow needs it, and the screens ask it whether a stop is only a recreation.
 */
fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
