package com.stastyle.imumapper.ui.viewer

import com.stastyle.imumapper.data.sampleResult
import kotlin.test.Test
import kotlin.test.assertEquals

/** The line under the Raw path switch, in the View menu and in Survey mode's menu. */
class RawPathHintTest {

    private val oldRun = sampleResult().copy(pipelineVersion = ViewerUiState.RAW_POINTS_VERSION - 1)
    private val newRun = sampleResult().copy(pipelineVersion = ViewerUiState.RAW_POINTS_VERSION)

    @Test
    fun aRunWithoutAStoredRawPathSaysToReprocessInBothMenus() {
        for (survey in listOf(false, true)) {
            for (show in listOf(false, true)) {
                val ui = ViewerUiState(result = oldRun, showRaw = show, surveyMode = survey)
                assertEquals("Re-process this run to store its raw path", rawPathHint(ui), "survey=$survey show=$show")
            }
        }
    }

    @Test
    fun aRunThatNothingCorrectedSaysSoOnceTheSwitchIsOnInBothMenus() {
        // rawResult stays null when the run stores no raw path distinct from the corrected one, so
        // the switch reads ON while the path, stations and totals stay the same.
        for (survey in listOf(false, true)) {
            val ui = ViewerUiState(result = newRun, showRaw = true, rawResult = null, surveyMode = survey)
            assertEquals("Nothing was corrected in this run", rawPathHint(ui), "survey=$survey")
        }
    }

    @Test
    fun theViewMenuSaysTheCorrectedPathIsDimmed() {
        val off = ViewerUiState(result = newRun, showRaw = false)
        val on = ViewerUiState(result = newRun, showRaw = true, rawResult = newRun.copy(points = newRun.points.reversed()))
        for (ui in listOf(off, on)) {
            assertEquals("Before loop closure and smoothing; the corrected path is dimmed", rawPathHint(ui))
        }
    }

    @Test
    fun surveyModeDoesNotPromiseADimmedPathItDoesNotDraw() {
        val off = ViewerUiState(result = newRun, showRaw = false, surveyMode = true)
        val on = ViewerUiState(
            result = newRun,
            showRaw = true,
            rawResult = newRun.copy(points = newRun.points.reversed()),
            surveyMode = true,
        )
        for (ui in listOf(off, on)) {
            assertEquals(null, ui.sceneOverlay)
            assertEquals("Before loop closure and smoothing; stations follow by time", rawPathHint(ui))
        }
    }

    @Test
    fun noResultYetIsNotReportedAsNothingCorrected() {
        val ui = ViewerUiState(result = null, showRaw = true)
        assertEquals("Before loop closure and smoothing; the corrected path is dimmed", rawPathHint(ui))
    }
}
