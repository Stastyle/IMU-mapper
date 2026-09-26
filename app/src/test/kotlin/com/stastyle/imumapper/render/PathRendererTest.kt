package com.stastyle.imumapper.render

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The label placement rule. drawText throws when the text origin lies past the right or bottom
 * canvas edge (its layout box would have a negative size), which took the viewer down as soon
 * as a drag carried an axis letter there.
 */
class PathRendererTest {

    private val w = 1080f
    private val h = 1900f

    @Test
    fun labelWellInsideIsOffsetFromItsAnchor() {
        assertEquals(Offset(104f, 192f), PathRenderer.labelOrigin(100f, 200f, w, h))
    }

    @Test
    fun anchorWithinTheOffsetOfTheRightEdgeIsNotDrawn() {
        // The anchor is on the canvas, but the text would start 2 px past its right edge.
        assertNull(PathRenderer.labelOrigin(w - 2f, 200f, w, h))
        assertNull(PathRenderer.labelOrigin(w - 4f, 200f, w, h))
        assertEquals(Offset(w - 1f, 192f), PathRenderer.labelOrigin(w - 5f, 200f, w, h))
    }

    @Test
    fun anchorAtOrBelowTheBottomEdgeIsNotDrawn() {
        assertNull(PathRenderer.labelOrigin(100f, h + 8f, w, h))
        assertEquals(Offset(104f, h - 1f), PathRenderer.labelOrigin(100f, h + 7f, w, h))
    }

    @Test
    fun labelsSlightlyOffTheLeftAndTopStillShowTheirTail() {
        assertEquals(Offset(-146f, -38f), PathRenderer.labelOrigin(-150f, -30f, w, h))
        assertNull(PathRenderer.labelOrigin(-300f, 100f, w, h))
        assertNull(PathRenderer.labelOrigin(100f, -100f, w, h))
    }

    @Test
    fun nonFiniteCoordinatesAreNotDrawn() {
        assertNull(PathRenderer.labelOrigin(Float.NaN, 100f, w, h))
        assertNull(PathRenderer.labelOrigin(100f, Float.NaN, w, h))
        assertNull(PathRenderer.labelOrigin(Float.POSITIVE_INFINITY, 100f, w, h))
        assertNull(PathRenderer.labelOrigin(100f, Float.NEGATIVE_INFINITY, w, h))
    }
}
