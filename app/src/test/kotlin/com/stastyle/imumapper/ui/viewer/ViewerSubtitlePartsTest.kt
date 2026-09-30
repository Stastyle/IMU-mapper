package com.stastyle.imumapper.ui.viewer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** The top bar's second line, split into the date, the run label and "raw" that are laid out separately. */
class ViewerSubtitlePartsTest {

    private val date = "Sep 29, 2026, 9:47 AM"
    private val run = "Run 3 · v12 · PDR"

    @Test
    fun outsideSurveyModeTheDateLeadsTheRunAndRaw() {
        val parts = viewerSubtitleParts(surveyMode = false, date = date, run = run, raw = true)
        assertEquals(SubtitleParts(date, run, raw = true), parts)
        assertEquals("$date · $run · raw", parts?.text)
        assertEquals(SubtitleParts(date, run, raw = false), viewerSubtitleParts(false, date, run, raw = false))
    }

    @Test
    fun withoutTheDateThePartsSayWhatViewerSubtitleSays() {
        for (surveyMode in listOf(false, true)) {
            for (shownRun in listOf(run, null)) {
                for (raw in listOf(false, true)) {
                    // viewerSubtitle only says "raw" beside a run or the survey label, as the parts do.
                    val expected = viewerSubtitle(surveyMode, shownRun, raw && (surveyMode || shownRun != null))
                    val parts = viewerSubtitleParts(surveyMode, date = null, run = shownRun, raw = raw)
                    assertEquals(expected, parts?.text, "survey=$surveyMode run=$shownRun raw=$raw")
                }
            }
        }
    }

    @Test
    fun surveyModeLeavesTheDateOutForItsActions() {
        val parts = viewerSubtitleParts(surveyMode = true, date = date, run = run, raw = true)
        assertEquals(SubtitleParts(date = null, label = "Survey (beta)", raw = true), parts)
    }

    @Test
    fun beforeTheFirstRunOnlyTheDateShows() {
        val parts = viewerSubtitleParts(surveyMode = false, date = date, run = null, raw = true)
        assertEquals(SubtitleParts(date, label = null, raw = false), parts)
        assertFalse(parts!!.raw)
        assertEquals(date, parts.text)
        assertNull(viewerSubtitleParts(surveyMode = false, date = null, run = null, raw = false))
    }
}
