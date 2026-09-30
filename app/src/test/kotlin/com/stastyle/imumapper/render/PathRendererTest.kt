package com.stastyle.imumapper.render

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The label placement rule. drawText throws when the text origin lies past the right or bottom
 * canvas edge (its layout box would have a negative size), which took the viewer down as soon
 * as a drag carried an axis letter there. Also where the Start and End labels go, so that on a nearly closed loop
 * neither prints over the other marker or its label.
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
    fun anExplicitOffsetReplacesTheDefaultOne() {
        assertEquals(Offset(130f, 190f), PathRenderer.labelOrigin(100f, 200f, w, h, dx = 30f, dy = -10f))
        // The edge rule applies to the offset origin: an anchor well inside can still push its text past the edge.
        assertNull(PathRenderer.labelOrigin(w - 20f, 200f, w, h, dx = 30f, dy = 0f))
    }

    @Test
    fun endLabelsMergeWithinTheThreshold() {
        // A closed loop: both markers on the same pixel.
        assertTrue(PathRenderer.mergeEndLabels(500f, 500f, 500f, 500f, 24f))
        assertTrue(PathRenderer.mergeEndLabels(500f, 500f, 510f, 510f, 24f))
        // Exactly at the threshold still merges; a pixel past it does not.
        assertTrue(PathRenderer.mergeEndLabels(500f, 500f, 524f, 500f, 24f))
        assertTrue(PathRenderer.mergeEndLabels(500f, 500f, 500f, 476f, 24f))
        assertFalse(PathRenderer.mergeEndLabels(500f, 500f, 525f, 500f, 24f))
        // Distance, not per axis: 20 px across and 20 px down is 28 px apart.
        assertFalse(PathRenderer.mergeEndLabels(500f, 500f, 520f, 520f, 24f))
        assertFalse(PathRenderer.mergeEndLabels(0f, 0f, 1000f, 1000f, 24f))
    }

    @Test
    fun aNonFiniteEndPositionNeverMerges() {
        assertFalse(PathRenderer.mergeEndLabels(Float.NaN, 500f, 500f, 500f, 24f))
        assertFalse(PathRenderer.mergeEndLabels(500f, 500f, 500f, Float.NaN, 24f))
        assertFalse(PathRenderer.mergeEndLabels(Float.POSITIVE_INFINITY, 500f, Float.POSITIVE_INFINITY, 500f, 24f))
    }

    // End labels at density 1, as drawn: 12 sp semibold "Start" is about 26 px wide and "End" about 20, both 16 tall.
    // An unselected marker reaches 7 px and its label starts 11 px right of it; a selected one reaches 15 (the
    // selection ring) and its label starts at 19.
    private val startW = 26f
    private val endW = 20f
    private val labelH = 16f
    private val reach = 7f
    private val clear = 11f
    private val selectedReach = 15f
    private val selectedClear = 19f

    /** By default the "Start" label against the End marker and label, both labels right of their unselected markers. */
    private fun collides(
        selfX: Float,
        selfY: Float,
        otherX: Float,
        otherY: Float,
        selfW: Float = startW,
        otherW: Float = endW,
        selfDx: Float = clear,
        otherDx: Float = clear,
        otherReach: Float = reach,
    ) = PathRenderer.endLabelCollides(selfX, selfY, selfDx, selfW, labelH, otherX, otherY, otherReach, otherDx, otherW)

    private fun placement(
        endX: Float,
        endY: Float,
        startSelected: Boolean = false,
        fontScale: Float = 1f,
    ) = PathRenderer.endLabelPlacement(
        startX = 500f,
        startY = 500f,
        startReach = if (startSelected) selectedReach else reach,
        startClear = if (startSelected) selectedClear else clear,
        startW = startW * fontScale,
        endX = endX,
        endY = endY,
        endReach = reach,
        endClear = clear,
        endW = endW * fontScale,
        labelH = labelH * fontScale,
        mergePx = 24f,
    )

    @Test
    fun aStartLabelCoversAnEndMarkerLevelWithItOnTheRight() {
        // A loop ending 30 px east of its start: "Start" runs from 511 to 537, across the End dot at 523..537.
        assertTrue(collides(500f, 500f, 530f, 500f))
        // "End" is clear of the Start dot and of "Start", so only the left marker's label is in the way.
        assertFalse(collides(530f, 500f, 500f, 500f, selfW = endW, otherW = startW))
    }

    @Test
    fun anEndLabelCoversAStartMarkerLevelWithItOnTheRight() {
        // The mirror: End 30 px west of Start, and "End" (481..501) covers the Start dot (493..507).
        assertTrue(collides(470f, 500f, 500f, 500f, selfW = endW, otherW = startW))
        assertFalse(collides(500f, 500f, 470f, 500f))
    }

    @Test
    fun aMarkerDirectlyBelowOrFarToTheSideDoesNotCollide() {
        // 30 px straight down, each label is right of both dots and above or below the other label.
        assertFalse(collides(500f, 500f, 500f, 530f))
        assertFalse(collides(500f, 530f, 500f, 500f, selfW = endW, otherW = startW))
        // 60 px to the right, "Start" ends at 537, before the End dot starts at 553.
        assertFalse(collides(500f, 500f, 560f, 500f))
        assertFalse(collides(560f, 500f, 500f, 500f, selfW = endW, otherW = startW))
    }

    @Test
    fun aLabelCoveringOnlyTheOtherLabelCollides() {
        // End 60 px to the right with its label moved left of it (529..549): clear of the End dot, but over "Start".
        assertTrue(collides(500f, 500f, 560f, 500f, otherDx = -(clear + endW)))
    }

    @Test
    fun aLabelMovedLeftOfItsMarkerClearsTheOtherOne() {
        // The level pair 30 px apart again, with "Start" drawn left of its marker (463..489).
        assertFalse(collides(500f, 500f, 530f, 500f, selfDx = -(clear + startW)))
        assertFalse(collides(530f, 500f, 500f, 500f, selfW = endW, otherW = startW, otherDx = -(clear + startW)))
    }

    @Test
    fun aSelectedOtherMarkerWidensTheBand() {
        // 46 px to the right, "Start" (511..537) stops short of the End dot (539..553), but not of its ring (531..561).
        assertFalse(collides(500f, 500f, 546f, 500f))
        assertTrue(collides(500f, 500f, 546f, 500f, otherReach = selectedReach))
    }

    @Test
    fun aNonFiniteCoordinateNeverCollides() {
        assertFalse(collides(Float.NaN, 500f, 530f, 500f))
        assertFalse(collides(500f, Float.NaN, 530f, 500f))
        assertFalse(collides(500f, 500f, Float.NaN, 500f))
        assertFalse(collides(500f, 500f, 530f, Float.NaN))
        assertFalse(collides(Float.POSITIVE_INFINITY, 500f, Float.POSITIVE_INFINITY, 500f))
    }

    @Test
    fun endLabelsStayRightOfTheirMarkersWhenNothingCollides() {
        assertEquals(PathRenderer.EndLabelPlacement.RIGHT, placement(560f, 500f))
        assertEquals(PathRenderer.EndLabelPlacement.RIGHT, placement(500f, 530f))
        assertEquals(PathRenderer.EndLabelPlacement.RIGHT, placement(440f, 500f))
    }

    @Test
    fun theLeftMarkersLabelMovesLeftWhenItWouldCoverTheOther() {
        assertEquals(PathRenderer.EndLabelPlacement.START_LEFT, placement(530f, 500f))
        assertEquals(PathRenderer.EndLabelPlacement.END_LEFT, placement(470f, 500f))
        // On a slant just past the 24 px merge, 22 px across and 12 px down: "Start" covers the End dot and "End".
        assertEquals(PathRenderer.EndLabelPlacement.START_LEFT, placement(522f, 512f))
    }

    @Test
    fun nearlyCoincidentMarkersStillMerge() {
        assertEquals(PathRenderer.EndLabelPlacement.MERGED, placement(500f, 500f))
        assertEquals(PathRenderer.EndLabelPlacement.MERGED, placement(524f, 500f))
    }

    @Test
    fun aLabelSqueezedFromBothSidesMerges() {
        // Start selected at twice the font size, End 25 px straight below it: on the right "Start" runs into "End",
        // and "End" reaches into Start's selection ring whichever side "Start" takes, so the two share one label.
        assertEquals(PathRenderer.EndLabelPlacement.MERGED, placement(500f, 525f, startSelected = true, fontScale = 2f))
    }

    @Test
    fun aNonFiniteEndMarkerLeavesBothLabelsRight() {
        // labelOrigin then drops the one that cannot be placed.
        assertEquals(PathRenderer.EndLabelPlacement.RIGHT, placement(Float.NaN, 500f))
        assertEquals(PathRenderer.EndLabelPlacement.RIGHT, placement(500f, Float.NaN))
    }

    @Test
    fun nonFiniteCoordinatesAreNotDrawn() {
        assertNull(PathRenderer.labelOrigin(Float.NaN, 100f, w, h))
        assertNull(PathRenderer.labelOrigin(100f, Float.NaN, w, h))
        assertNull(PathRenderer.labelOrigin(Float.POSITIVE_INFINITY, 100f, w, h))
        assertNull(PathRenderer.labelOrigin(100f, Float.NEGATIVE_INFINITY, w, h))
    }
}
