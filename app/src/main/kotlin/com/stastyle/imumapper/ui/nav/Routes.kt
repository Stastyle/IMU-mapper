package com.stastyle.imumapper.ui.nav

import com.stastyle.imumapper.pipeline.core.TripMode

/**
 * Navigation routes. Keep every screen reachable from here so the graph stays in one place.
 *
 * [TRIPS], [NEW_TRIP], [CALIBRATION] and [SETTINGS] are the bottom-bar tabs; the others are pushed on
 * top of a tab and show no bar.
 */
object Routes {
    const val TRIPS = "trips"

    /** The Record tab: picks the mode of a new trip, or returns to the one being recorded. */
    const val NEW_TRIP = "new-trip"

    const val ARG_MODE = "mode"
    const val RECORD = "record/{$ARG_MODE}"
    fun record(mode: TripMode) = "record/${mode.name}"

    const val ARG_TRIP_ID = "tripId"
    const val VIEWER = "viewer/{$ARG_TRIP_ID}"
    fun viewer(tripId: Long) = "viewer/$tripId"

    const val CALIBRATION = "calibration"

    const val TUNING = "tuning"

    const val DEBUG = "debug?$ARG_TRIP_ID={$ARG_TRIP_ID}"
    fun debug(tripId: Long? = null) = if (tripId == null) "debug" else "debug?$ARG_TRIP_ID=$tripId"

    const val SETTINGS = "settings"
}
