package com.stastyle.imumapper.ui.viewer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The second line of the viewer's top bar. */
class ViewerTitleTest {

    @Test
    fun theViewerShowsTheRunAndWhetherThePathIsRaw() {
        assertEquals("Run 3 · v12", viewerSubtitle(surveyMode = false, run = "Run 3 · v12", raw = false))
        assertEquals("Run 3 · v12 · raw", viewerSubtitle(surveyMode = false, run = "Run 3 · v12", raw = true))
        assertNull(viewerSubtitle(surveyMode = false, run = null, raw = false))
    }

    @Test
    fun surveyModeSaysSurveyBetaAndLeavesTheRunToTheMenu() {
        // The north chip, Undo, the ruler and the menu leave the title little room; the run is
        // checked in the menu, so the line keeps only what the design draws, plus "raw" when on.
        assertEquals("Survey (beta)", viewerSubtitle(surveyMode = true, run = "Run 3 · v12", raw = false))
        assertEquals("Survey (beta) · raw", viewerSubtitle(surveyMode = true, run = "Run 3 · v12", raw = true))
        assertEquals("Survey (beta)", viewerSubtitle(surveyMode = true, run = null, raw = false))
    }
}
