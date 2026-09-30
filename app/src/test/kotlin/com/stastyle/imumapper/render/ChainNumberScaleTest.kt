package com.stastyle.imumapper.render

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A chain number sits on its station's dot, and the light palette draws both the number and the dot's ring white, so
 * any stroke that reaches the ring vanishes. The number must fit inside the fill, and a number that already fits
 * must keep its size, so the dark theme draws one and two places exactly as it did before the scale existed.
 *
 * Sizes are in dp for the chain number's 11 sp bold at font scale 1 in Roboto: the scale is a ratio, so the
 * screen's density cancels out. The plain JVM cannot measure text, so these are the font's metrics, rounded up.
 */
class ChainNumberScaleTest {

    /** The fill inside the ring of a chain station's dot, as SurveyRenderer draws it. */
    private val fill = SurveyRenderer.SELECTED_RADIUS_DP - SurveyRenderer.SELECTED_RING_DP / 2f

    private val digitAdvance = 6.2f
    private val slashAdvance = 4.4f
    private val lineHeight = 12.9f

    /** How tall the digits' ink is: about 0.71 em. */
    private val digitInk = 7.8f

    private fun width(label: String, fontScale: Float = 1f): Float =
        label.fold(0f) { sum, c -> sum + if (c == '/') slashAdvance else digitAdvance } * fontScale

    private fun scaleOf(label: String, fontScale: Float = 1f): Float =
        chainNumberScale(width(label, fontScale), lineHeight * fontScale, fill)

    /** How far the scaled ink's corners reach from the station's centre. */
    private fun reach(label: String, fontScale: Float = 1f): Float {
        val k = scaleOf(label, fontScale)
        return hypot(k * width(label, fontScale) / 2f, k * digitInk * fontScale / 2f)
    }

    @Test
    fun oneAndTwoPlacesKeepTheirSizeAtTheDefaultFontSize() {
        for (label in listOf("1", "7", "12", "88")) {
            assertEquals(1f, scaleOf(label), "\"$label\"")
            assertTrue(reach(label) < fill, "\"$label\" reaches ${reach(label)} dp of a $fill dp fill")
        }
    }

    @Test
    fun aClosedLoopsTwoPlacesShrinkToStayInsideTheFill() {
        // End on Start after a closed loop carries both places, about 23 dp across: at full size its outer strokes
        // would lie on the ring, which covers 8.75 to 11.25 dp.
        assertTrue(width("1/12") / 2f > fill)
        val k = scaleOf("1/12")
        assertTrue(k < 1f, "\"1/12\" is drawn at $k")
        assertTrue(reach("1/12") <= fill + 1e-4f, "\"1/12\" reaches ${reach("1/12")} dp of a $fill dp fill")
        // Shrunk to fit, not to nothing: it is still a label to read.
        assertTrue(k > 0.6f, "\"1/12\" is drawn at $k")
    }

    @Test
    fun aThirdPlaceOrALargeFontShrinksToFit() {
        for ((label, fontScale) in listOf("123" to 1f, "12" to 1.3f, "1/12" to 1.3f, "7" to 2f)) {
            val k = scaleOf(label, fontScale)
            assertTrue(k < 1f, "\"$label\" at font scale $fontScale is drawn at $k")
            assertTrue(reach(label, fontScale) <= fill + 1e-4f, "\"$label\" at font scale $fontScale")
        }
    }

    @Test
    fun aLabelIsNeverEnlarged() {
        assertEquals(1f, chainNumberScale(1f, 1f, fill))
        assertEquals(1f, chainNumberScale(0f, 0f, fill), "an empty label keeps 1 rather than dividing by 0")
    }
}
