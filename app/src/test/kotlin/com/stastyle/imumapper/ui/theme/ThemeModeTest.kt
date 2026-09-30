package com.stastyle.imumapper.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Settings → Appearance: how a stored name reads back, and which theme each choice draws. */
class ThemeModeTest {

    @Test
    fun storedNamesReadBack() {
        for (mode in ThemeMode.entries) assertEquals(mode, ThemeMode.parse(mode.name))
    }

    @Test
    fun missingOrUnknownNamesReadAsSystem() {
        for (name in listOf(null, "", "dark", "Dark", " DARK", "SEPIA")) {
            assertEquals(ThemeMode.SYSTEM, ThemeMode.parse(name), "name $name")
        }
    }

    @Test
    fun namesAreTheStoredFormat() {
        // Stored by name: renaming an entry would silently reset every phone's choice to System.
        assertEquals(listOf("SYSTEM", "LIGHT", "DARK"), ThemeMode.entries.map { it.name })
    }

    @Test
    fun systemFollowsThePhoneAndTheOthersDoNot() {
        assertTrue(ThemeMode.SYSTEM.isDark(systemDark = true))
        assertFalse(ThemeMode.SYSTEM.isDark(systemDark = false))
        for (systemDark in listOf(true, false)) {
            assertFalse(ThemeMode.LIGHT.isDark(systemDark))
            assertTrue(ThemeMode.DARK.isDark(systemDark))
        }
    }
}
