package com.stastyle.imumapper

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stastyle.imumapper.capture.RecordingController
import com.stastyle.imumapper.ui.nav.AppNavGraph
import com.stastyle.imumapper.ui.theme.ImuMapperTheme
import com.stastyle.imumapper.ui.theme.ThemeMode
import com.stastyle.imumapper.ui.theme.ThemePalettes

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val themeSetting = ImuMapperApp.from(this).themeMode
        // Until the setting has been read it counts as System. On API 31 and later the platform already
        // applies the stored choice to this activity's configuration (PlatformNightMode), so "the system's"
        // dark flag is the chosen one and even this first guess matches.
        applyWindowTheme(dark = (themeSetting.value ?: ThemeMode.SYSTEM).isDark(resources.configuration.isNight))
        setContent {
            val setting by themeSetting.collectAsStateWithLifecycle()
            // Read here, above the provider below, so this is the activity's real configuration: the override
            // must not feed back into the choice it is made from.
            val system = LocalConfiguration.current
            val dark = (setting ?: ThemeMode.SYSTEM).isDark(system.isNight)
            DisposableEffect(dark) {
                applyWindowTheme(dark)
                onDispose {}
            }
            val themed = remember(system, dark) { system.withNight(dark) }
            CompositionLocalProvider(LocalConfiguration provides themed) {
                ImuMapperTheme(dark = dark) {
                    AppNavGraph()
                }
            }
        }
    }

    /**
     * Matches the window to the app's theme, not the phone's.
     *
     * The bars stay transparent, with icons that suit the theme: with the app set to Dark on a phone in light
     * mode the icons must stay light. `SystemBarStyle.auto` would follow the phone and, in three-button
     * navigation, also lay the system's own scrim behind the buttons, which the dark theme has never had.
     *
     * The window background shows through while one screen fades into the next. The platform takes it from
     * `window_background` once, when the window is made. A later change of the setting reaches this activity
     * as a configuration change it handles itself, or on API 30 not as a configuration change at all, so
     * without this a switch from Light to Dark would flash the light background through every screen change
     * until the next start, and on API 30 the phone's own mode would pick it.
     *
     * The platform's floating Cut/Copy/Paste toolbar takes its light or dark look from the activity theme's
     * `isLightTheme` each time it opens, and `Theme.ImuMapper`'s parent is dark, so the overlay sets it from
     * the app's theme. `applyStyle` changes the theme in place, so nothing is recreated, and a configuration
     * change rebuilds the theme with the overlay still applied. Compose dialogs copy this theme when they open,
     * so their text fields follow it too. Material3's bottom sheets do not: their window theme has a dark
     * platform parent, which sets the attribute back.
     */
    private fun applyWindowTheme(dark: Boolean) {
        val style = if (dark) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        // The same colours as window_background in values and values-night (WindowBackgroundTest).
        val palette = if (dark) ThemePalettes.Dark else ThemePalettes.Light
        window.setBackgroundDrawable(ColorDrawable(palette.background))
        val platformTheme =
            if (dark) R.style.ThemeOverlay_ImuMapper_PlatformDark else R.style.ThemeOverlay_ImuMapper_PlatformLight
        theme.applyStyle(platformTheme, true)
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

private val Configuration.isNight: Boolean
    get() = (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

/**
 * A copy of this configuration with its night bits set to [night], which [MainActivity] provides as
 * `LocalConfiguration` so that library code asking `isSystemInDarkTheme()` follows Settings → Appearance.
 * Material3's ModalBottomSheet asks it in the composition that opens the sheet and sets the sheet window's
 * bar icons from the answer. API 30 has no per-app night mode, so without this a Light app on a dark phone
 * gets white bar icons over a light sheet, and a Dark app on a light phone dark ones over navy. On API 31+ the
 * two already agree once PlatformNightMode has landed. Only this value changes: resources still resolve
 * against the activity's real configuration.
 */
private fun Configuration.withNight(night: Boolean): Configuration = Configuration(this).apply {
    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
        (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
}
