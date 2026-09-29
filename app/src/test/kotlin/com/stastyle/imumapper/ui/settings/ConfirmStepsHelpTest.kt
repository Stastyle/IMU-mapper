package com.stastyle.imumapper.ui.settings

import com.stastyle.imumapper.pipeline.core.PipelineConfig
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The help text under the Settings step count, which names the saved held-out height limit. */
class ConfirmStepsHelpTest {

    @Test
    fun namesTheSavedLimitNotTheDefault() {
        assertContains(confirmStepsHelp(PipelineConfig().baroMaxHeldM), "up to 1.5 m of its height for good")
        val edited = confirmStepsHelp(2.0)
        assertContains(edited, "up to 2 m of its height for good")
        assertFalse("1.5 m" in edited)
        assertContains(confirmStepsHelp(0.75), "up to 0.75 m")
    }

    @Test
    fun namesNoNumberWhileLoading() {
        val loading = confirmStepsHelp(null)
        assertContains(loading, "up to a limit set in the Debug editor")
        assertFalse(Regex("""\d m\b""").containsMatchIn(loading))
    }

    @Test
    fun gentleSlopesLoseHeightRatherThanComeThroughLate() {
        for (text in listOf(confirmStepsHelp(null), confirmStepsHelp(1.5))) {
            assertFalse("late" in text)
            assertContains(text, "only height beyond that comes through")
        }
    }

    @Test
    fun zeroLimitSaysEveryChangeComesThrough() {
        for (m in listOf(0.0, -1.0)) {
            val text = confirmStepsHelp(m)
            assertContains(text, "every change comes through")
            assertFalse("gentle slope" in text)
        }
    }

    @Test
    fun namesTheDefaultStepCount() {
        assertTrue("(default ${PipelineConfig().baroConfirmSteps})" in confirmStepsHelp(1.5))
    }

    @Test
    fun claimsNoJumpPassRates() {
        // Sudden pressure jumps are held out at any step count, so the text must not say fewer steps let them through.
        val text = confirmStepsHelp(1.5)
        assertFalse("about half at 3" in text)
        assertContains(text, "Fewer steps keep short stairs")
    }
}
