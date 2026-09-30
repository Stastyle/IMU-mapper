package com.stastyle.imumapper.calibration

import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.render.PathProgress
import com.stastyle.imumapper.ui.calibration.CalibrationMath
import com.stastyle.imumapper.ui.calibration.Fmt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How the calibration path preview splits its distance ramp into drawable runs. */
class PreviewRunsTest {

    /** A 5 m square walked back to the start, one point every 10 cm, like the square test's result. */
    private val square: List<Vec3> = buildList {
        val corners = listOf(Vec3(0.0, 0.0, 0.0), Vec3(5.0, 0.0, 0.0), Vec3(5.0, 5.0, 0.0), Vec3(0.0, 5.0, 0.0))
        for (side in 0 until 4) {
            val a = corners[side]
            val b = corners[(side + 1) % 4]
            for (k in 0 until 50) add(a + (b - a) * (k / 50.0))
        }
        add(corners[0])
    }

    @Test
    fun runsCoverEverySegmentOnceInOrder() {
        val fractions = PathProgress.fractions(square)
        val starts = CalibrationMath.progressRuns(fractions)
        assertEquals(0, starts.first())
        for (k in 1 until starts.size) assertTrue(starts[k] > starts[k - 1])
        // Every segment belongs to exactly one run: from its start up to the next run's start.
        assertTrue(starts.last() < square.size - 1)
    }

    @Test
    fun oneRunPerColourStepAtMost() {
        val fractions = PathProgress.fractions(square)
        val starts = CalibrationMath.progressRuns(fractions)
        assertEquals(CalibrationMath.PREVIEW_COLOR_STEPS, starts.size)
        assertEquals(4, CalibrationMath.progressRuns(fractions, steps = 4).size)
        assertContentEquals(intArrayOf(0), CalibrationMath.progressRuns(fractions, steps = 1))
    }

    @Test
    fun segmentsOfARunShareItsColourStep() {
        val fractions = PathProgress.fractions(square)
        val steps = 8
        val starts = CalibrationMath.progressRuns(fractions, steps)
        for (r in starts.indices) {
            val end = if (r + 1 < starts.size) starts[r + 1] else square.size - 1
            val step = (fractions[starts[r]] * steps).toInt()
            for (i in starts[r] until end) assertEquals(step, (fractions[i] * steps).toInt().coerceAtMost(steps - 1))
        }
    }

    @Test
    fun shortAndBrokenInput() {
        assertContentEquals(IntArray(0), CalibrationMath.progressRuns(DoubleArray(0)))
        assertContentEquals(intArrayOf(0), CalibrationMath.progressRuns(doubleArrayOf(0.0)))
        assertContentEquals(intArrayOf(0), CalibrationMath.progressRuns(doubleArrayOf(0.0, 1.0)))
        // A NaN fraction stays in the first colour instead of throwing.
        assertContentEquals(intArrayOf(0, 1), CalibrationMath.progressRuns(doubleArrayOf(Double.NaN, 0.5, 1.0), 2))
    }
}

/** The spacing the preview's grid chip names, for the ground the canvas shows. */
class PreviewGridTest {

    // The square test's preview on the phone: about 330 x 180 dp at 2.8 px per dp, 16 dp padding.
    private val width = 924.0
    private val height = 504.0
    private val padding = 45.0
    private val minGap = 11.0

    private fun spacing(points: List<Vec3>): Double? {
        val bounds = CalibrationMath.planBounds(points)!!
        val t = CalibrationMath.fitPlan(bounds, width, height, padding)
        return CalibrationMath.previewGridSpacing(t, width, height, minGap)
    }

    @Test
    fun squareTestGetsTheOneMetreGrid() {
        val square = listOf(Vec3(0.0, 0.0, 0.0), Vec3(5.0, 0.0, 0.0), Vec3(5.0, 5.0, 0.0), Vec3(0.0, 5.0, 0.0))
        assertEquals(1.0, spacing(square))
    }

    @Test
    fun longThinWalkWidensTheGridForTheGroundShown() {
        // 60 m north fills the height; the canvas then shows about 134 m across, too much for 1 m lines.
        assertEquals(2.0, spacing(listOf(Vec3(0.0, 0.0, 0.0), Vec3(0.0, 60.0, 0.0))))
    }

    @Test
    fun noGridWhenEvenTheWidestSpacingWouldCrowd() {
        // 10 km across: 50 m lines would be 4 px apart, so no grid and no chip.
        assertEquals(null, spacing(listOf(Vec3(0.0, 0.0, 0.0), Vec3(10_000.0, 0.0, 0.0))))
    }
}

/** How a label / value row shares its width when both do not fit on one line. */
class ValueRowWidthTest {

    private fun valueWidth(label: Int, value: Int) =
        CalibrationMath.valueRowValueWidth(availablePx = 900, gapPx = 30, labelPx = label, valuePx = value)

    @Test
    fun bothFitSoTheValueGetsWhatItWants() {
        assertEquals(200, valueWidth(label = 300, value = 200))
        assertEquals(570, valueWidth(label = 300, value = 570))
    }

    @Test
    fun aLongLabelWrapsBesideAShortValue() {
        // Tuning's "Closure (expected 0 m (ends at the start))" at a large font: the value stays whole.
        assertEquals(180, valueWidth(label = 1_200, value = 180))
    }

    @Test
    fun aLongValueWrapsBesideAShortLabel() {
        // A trip's notes next to "Notes": the label keeps its width and the value takes the rest.
        assertEquals(870 - 150, valueWidth(label = 150, value = 2_000))
    }

    @Test
    fun twoLongSidesShareTheRow() {
        assertEquals(435, valueWidth(label = 1_000, value = 2_000))
    }

    @Test
    fun noRoomGivesNothingRatherThanANegativeWidth() {
        assertEquals(0, CalibrationMath.valueRowValueWidth(availablePx = 10, gapPx = 30, labelPx = 50, valuePx = 50))
    }
}

/** The Calibrate header's subtitle, which names only saved values. */
class CalibrationHeaderTest {

    @Test
    fun namesCarryAndFixedStride() {
        val config = PipelineConfig(strideLengthM = 0.74)
        assertEquals("Carry: Pocket · stride 0.74 m", Fmt.headerLine(config, CarryPosition.POCKET))
        assertEquals("Carry: Hand · stride 0.70 m", Fmt.headerLine(PipelineConfig(), CarryPosition.HAND))
    }

    @Test
    fun weinbergNamesNoSingleStride() {
        // With a gain every step has its own length, so the saved fixed stride would be the wrong number.
        val line = Fmt.headerLine(PipelineConfig(strideLengthM = 0.74, weinbergK = 0.52), CarryPosition.CHEST)
        assertEquals("Carry: Chest · Weinberg stride", line)
    }
}
