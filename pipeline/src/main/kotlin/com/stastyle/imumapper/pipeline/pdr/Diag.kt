package com.stastyle.imumapper.pipeline.pdr

import java.util.Locale

/** Locale-independent number formatting for the diagnostics map (Turkish locales would print commas). */
object Diag {
    fun num(x: Double, decimals: Int = 3): String = String.format(Locale.US, "%." + decimals + "f", x)
}
