package com.stastyle.imumapper.ui.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How many stat tiles share a row. */
class StatColumnsTest {

    @Test
    fun takesTheMostColumnsThatFit() {
        // 3 × 100 + 2 × 12 = 324.
        assertEquals(3, statColumns(availableDp = 324f, gapDp = 12f, neededDp = 100f, maxColumns = 3))
        assertEquals(3, statColumns(availableDp = 400f, gapDp = 12f, neededDp = 100f, maxColumns = 3))
        assertEquals(2, statColumns(availableDp = 323f, gapDp = 12f, neededDp = 100f, maxColumns = 3))
        assertEquals(4, statColumns(availableDp = 440f, gapDp = 8f, neededDp = 100f, maxColumns = 4))
    }

    @Test
    fun theGapsCount() {
        assertEquals(3, statColumns(availableDp = 300f, gapDp = 0f, neededDp = 100f, maxColumns = 3))
        assertEquals(2, statColumns(availableDp = 300f, gapDp = 1f, neededDp = 100f, maxColumns = 3))
    }

    @Test
    fun neverMoreThanTheMaximum() {
        assertEquals(3, statColumns(availableDp = 10_000f, gapDp = 8f, neededDp = 50f, maxColumns = 3))
        assertEquals(4, statColumns(availableDp = Float.POSITIVE_INFINITY, gapDp = 8f, neededDp = 50f, maxColumns = 4))
    }

    @Test
    fun alwaysAtLeastOneColumn() {
        // A tile wider than the row still gets a row of its own.
        assertEquals(1, statColumns(availableDp = 80f, gapDp = 8f, neededDp = 100f, maxColumns = 3))
        assertEquals(1, statColumns(availableDp = 0f, gapDp = 8f, neededDp = 100f, maxColumns = 3))
        assertEquals(1, statColumns(availableDp = 400f, gapDp = 8f, neededDp = 100f, maxColumns = 1))
        assertEquals(1, statColumns(availableDp = 400f, gapDp = 8f, neededDp = 100f, maxColumns = 0))
        assertEquals(1, statColumns(availableDp = Float.NaN, gapDp = 8f, neededDp = 100f, maxColumns = 3))
        assertEquals(1, statColumns(availableDp = 400f, gapDp = 8f, neededDp = Float.NaN, maxColumns = 3))
    }

    @Test
    fun largerTextDropsToFewerColumns() {
        // The same 352 dp row: tiles measured at font scale 1.0 fit three, at 1.3 only two.
        val available = 352f - 2 * 16f
        assertEquals(3, statColumns(available, gapDp = 8f, neededDp = 96f, maxColumns = 3))
        assertEquals(2, statColumns(available, gapDp = 8f, neededDp = 125f, maxColumns = 3))
    }

    @Test
    fun balancingSpreadsTilesOverTheSameNumberOfRows() {
        assertEquals(2, balancedColumns(columns = 3, count = 4))
        assertEquals(3, balancedColumns(columns = 4, count = 5))
        assertEquals(3, balancedColumns(columns = 4, count = 6))
        assertEquals(3, balancedColumns(columns = 3, count = 6))
        assertEquals(4, balancedColumns(columns = 4, count = 4))
        assertEquals(2, balancedColumns(columns = 2, count = 3))
    }

    @Test
    fun balancingNeverExceedsTheTilesOrTheColumns() {
        assertEquals(3, balancedColumns(columns = 4, count = 3))
        assertEquals(1, balancedColumns(columns = 3, count = 1))
        assertEquals(1, balancedColumns(columns = 1, count = 6))
        assertEquals(1, balancedColumns(columns = 3, count = 0))
        for (columns in 1..6) {
            for (count in 1..20) {
                val balanced = balancedColumns(columns, count)
                val rows = (count + columns - 1) / columns
                assertTrue(balanced in 1..columns, "columns=$columns count=$count gave $balanced")
                assertEquals(rows, (count + balanced - 1) / balanced, "rows for columns=$columns count=$count")
            }
        }
    }
}
