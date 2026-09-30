package com.stastyle.imumapper.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.os.Build

/**
 * Hands the Appearance setting to the platform (API 31 and later), which applies it at once and keeps it
 * for the app, so that from then on even a cold start's window background, splash screen and
 * `values-night` resources match the theme before Compose draws. Without it a phone in light mode with the
 * app set to Dark would flash a light window at every start. API 30 has no per-app night mode; Compose
 * alone follows the setting there.
 */
internal object PlatformNightMode {

    fun apply(context: Context, mode: ThemeMode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val manager = context.getSystemService(UiModeManager::class.java) ?: return
        manager.setApplicationNightMode(
            when (mode) {
                // AUTO clears the app's own mode, so the app follows the system setting again.
                ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
                ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
            },
        )
    }
}
