package com.stastyle.imumapper.ui.common

/*
 * Column choice for StatGrid, kept pure so it is unit-tested without Compose. Widths are in dp.
 */

/**
 * The largest column count from [maxColumns] down to 2 whose tiles, each [neededDp] wide with
 * [gapDp] between them, fit in [availableDp]; otherwise 1. An unbounded width gives [maxColumns],
 * and NaN input gives 1.
 */
fun statColumns(availableDp: Float, gapDp: Float, neededDp: Float, maxColumns: Int): Int {
    for (n in maxColumns downTo 2) {
        if (n * neededDp + (n - 1) * gapDp <= availableDp) return n
    }
    return 1
}

/**
 * Spreads [count] tiles evenly over the rows that [columns] would need: 4 tiles in 3 columns
 * become 2 × 2 rather than 3 + 1, and 5 in 4 become 3 + 2. It never adds a row and never returns
 * more columns than tiles, so the result still fits wherever [columns] did.
 */
fun balancedColumns(columns: Int, count: Int): Int {
    if (columns <= 1 || count <= 1) return 1
    val rows = (count + columns - 1) / columns
    return (count + rows - 1) / rows
}
