package com.stastyle.imumapper.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.stastyle.imumapper.pipeline.core.TripMode
import com.stastyle.imumapper.ui.calibration.CalibrationScreen
import com.stastyle.imumapper.ui.debug.DebugScreen
import com.stastyle.imumapper.ui.record.RecordScreen
import com.stastyle.imumapper.ui.settings.SettingsScreen
import com.stastyle.imumapper.ui.settings.UpdateBanner
import com.stastyle.imumapper.ui.triplist.TripListScreen
import com.stastyle.imumapper.ui.viewer.ViewerScreen

@Composable
fun AppNavGraph() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.TRIPS) {
        composable(Routes.TRIPS) {
            TripListScreen(
                onNewTrip = { mode -> nav.navigate(Routes.record(mode)) },
                onOpenTrip = { id -> nav.navigate(Routes.viewer(id)) },
                onOpenCalibration = { nav.navigate(Routes.CALIBRATION) },
                onOpenDebug = { nav.navigate(Routes.debug()) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                banner = { UpdateBanner() },
            )
        }
        composable(
            Routes.RECORD,
            arguments = listOf(navArgument(Routes.ARG_MODE) { type = NavType.StringType }),
        ) { entry ->
            val mode = entry.arguments?.getString(Routes.ARG_MODE)
                ?.let { runCatching { TripMode.valueOf(it) }.getOrNull() } ?: TripMode.POCKET
            RecordScreen(
                mode = mode,
                onFinished = { tripId ->
                    nav.navigate(Routes.viewer(tripId)) {
                        popUpTo(Routes.TRIPS)
                    }
                },
                onCancelled = { nav.popBackStack() },
            )
        }
        composable(
            Routes.VIEWER,
            arguments = listOf(navArgument(Routes.ARG_TRIP_ID) { type = NavType.LongType }),
        ) { entry ->
            val tripId = entry.arguments?.getLong(Routes.ARG_TRIP_ID) ?: -1L
            ViewerScreen(
                tripId = tripId,
                onBack = { nav.popBackStack() },
                onOpenDebug = { id -> nav.navigate(Routes.debug(id)) },
            )
        }
        composable(Routes.CALIBRATION) {
            CalibrationScreen(onBack = { nav.popBackStack() })
        }
        composable(
            Routes.DEBUG,
            arguments = listOf(
                navArgument(Routes.ARG_TRIP_ID) {
                    type = NavType.LongType
                    defaultValue = -1L
                },
            ),
        ) { entry ->
            val tripId = entry.arguments?.getLong(Routes.ARG_TRIP_ID)?.takeIf { it >= 0 }
            DebugScreen(tripId = tripId, onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}
