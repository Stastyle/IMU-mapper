package com.stastyle.imumapper.ui.theme

/**
 * Settings → Appearance. Stored by [name] under the `theme_mode` preferences key, so a renamed entry
 * would silently reset every phone's choice to [SYSTEM].
 */
enum class ThemeMode {
    /** Follow the phone's dark theme setting, as the app did before the navy redesign; the default. */
    SYSTEM,
    LIGHT,
    DARK,
    ;

    /** Whether the app draws dark, given whether the phone is in dark theme ([systemDark]). */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        /** The stored [name] back as a mode; a missing, empty or unknown name (a newer build's, say) is [SYSTEM]. */
        fun parse(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}
