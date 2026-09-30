package com.stastyle.imumapper

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stastyle.imumapper.capture.RecordingController
import com.stastyle.imumapper.ui.nav.AppNavGraph
import com.stastyle.imumapper.ui.theme.ImuMapperTheme
import com.stastyle.imumapper.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val themeSetting = ImuMapperApp.from(this).themeMode
        // Until the setting has been read it counts as System. On API 31 and later the platform already
        // applies the stored choice to this activity's configuration (PlatformNightMode), so "the system's"
        // dark flag is the chosen one and even this first guess matches.
        val systemDark =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        applySystemBars(dark = (themeSetting.value ?: ThemeMode.SYSTEM).isDark(systemDark))
        setContent {
            val setting by themeSetting.collectAsStateWithLifecycle()
            val dark = (setting ?: ThemeMode.SYSTEM).isDark(isSystemInDarkTheme())
            DisposableEffect(dark) {
                applySystemBars(dark)
                onDispose {}
            }
            ImuMapperTheme(dark = dark) {
                AppNavGraph()
            }
        }
    }

    /**
     * Transparent bars with icons that suit the app's theme, not the phone's: with the app set to Dark on a
     * phone in light mode the icons must stay light. `SystemBarStyle.auto` would follow the phone and, in
     * three-button navigation, also lay the system's own scrim behind the buttons, which the dark theme has
     * never had.
     */
    private fun applySystemBars(dark: Boolean) {
        val style = if (dark) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    /** Volume keys mark a waypoint while recording, so a point can be logged without looking. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isVolume = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (isVolume) {
            val controller = RecordingController.get(this)
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && controller.onVolumeKey()) return true
            // Swallow the matching ACTION_UP and repeats too, so the volume panel does not pop up mid-trip.
            if (controller.isRecording) return true
        }
        return super.dispatchKeyEvent(event)
    }
}
