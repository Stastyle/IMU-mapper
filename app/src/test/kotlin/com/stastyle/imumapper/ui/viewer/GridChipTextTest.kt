package com.stastyle.imumapper.ui.viewer

import com.stastyle.imumapper.SurveyFixtures
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.render.Bounds
import com.stastyle.imumapper.render.PathScene
import com.stastyle.imumapper.render.SceneModel
import com.stastyle.imumapper.render.SceneOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The canvas's "n m grid" chip, which replaced the label the scene used to draw on the floor. */
class GridChipTextTest {

    /** A scene with nothing in it but [bounds], which is all the chip reads. */
    private fun sceneOf(sizeX: Double, sizeY: Double, sizeZ: Double = 0.0): SceneModel = SceneModel(
        bounds = Bounds(Vec3(-1.0, -2.0, 0.0), Vec3(-1.0 + sizeX, -2.0 + sizeY, sizeZ)),
        lineCount = 0,
        lineCoords = DoubleArray(0),
        lineColors = IntArray(0),
        lineWidths = FloatArray(0),
        cloudCount = 0,
        cloudCoords = DoubleArray(0),
        cloudColor = 0,
        markers = emptyList(),
        labels = emptyList(),
    )

    @Test
    fun noSceneOrNoGridMeansNoChip() {
        assertNull(gridChipText(null, SceneOptions()))
        assertNull(gridChipText(sceneOf(10.0, 10.0), SceneOptions(showGrid = false)))
    }

    @Test
    fun theSpacingFollowsTheLongerSideOfThePlan() {
        val grid = SceneOptions(showGrid = true)
        assertEquals("1 m grid", gridChipText(sceneOf(15.0, 10.0), grid))
        assertEquals("1 m grid", gridChipText(sceneOf(60.0, 1.0), grid))
        assertEquals("2 m grid", gridChipText(sceneOf(100.0, 20.0), grid))
        // The north-south extent counts as much as the east-west one.
        assertEquals("5 m grid", gridChipText(sceneOf(10.0, 300.0), grid))
        assertEquals("10 m grid", gridChipText(sceneOf(800.0, 5.0), grid))
        assertEquals("50 m grid", gridChipText(sceneOf(2000.0, 5.0), grid))
    }

    @Test
    fun heightDoesNotChooseTheFloorGrid() {
        // A tall shaft over a small floor still gets the small floor's grid, as the scene draws it.
        assertEquals("1 m grid", gridChipText(sceneOf(5.0, 5.0, sizeZ = 500.0), SceneOptions()))
    }

    @Test
    fun aBuiltSceneGivesTheSpacingItDrew() {
        // The L walk spans 5 m by 10 m.
        val scene = PathScene.build(SurveyFixtures.lWalk(), SceneOptions())
        assertEquals("1 m grid", gridChipText(scene, SceneOptions()))
    }
}
