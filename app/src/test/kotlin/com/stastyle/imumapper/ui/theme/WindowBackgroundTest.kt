package com.stastyle.imumapper.ui.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The window background has two sources: `window_background` in the resources, which the platform shows
 * before Compose draws (the splash, a cold start), and the palette, which `MainActivity` sets when the theme
 * changes while the app runs. They must be the same colour, or a theme change would leave a different
 * background showing through the screen transitions than a fresh start does.
 */
class WindowBackgroundTest {

    @Test
    fun lightResourceMatchesTheLightPalette() {
        assertEquals(hex(ThemePalettes.Light.background), windowBackground("values"))
    }

    @Test
    fun nightResourceMatchesTheDarkPalette() {
        assertEquals(hex(ThemePalettes.Dark.background), windowBackground("values-night"))
    }

    private fun windowBackground(folder: String): String {
        // Unit tests run in the module's folder.
        val file = File("src/main/res/$folder/colors.xml")
        assertTrue(file.isFile, "missing ${file.absolutePath}")
        val match = Regex("""<color name="window_background">(#[0-9A-Fa-f]{6})</color>""").find(file.readText())
        return requireNotNull(match) { "no window_background in $file" }.groupValues[1].uppercase()
    }

    private fun hex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)
}
