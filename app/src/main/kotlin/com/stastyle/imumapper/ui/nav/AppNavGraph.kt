package com.stastyle.imumapper.ui.nav

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.stastyle.imumapper.capture.RecordingController
import com.stastyle.imumapper.pipeline.core.TripMode
import com.stastyle.imumapper.ui.calibration.CalibrationScreen
import com.stastyle.imumapper.ui.debug.DebugScreen
import com.stastyle.imumapper.ui.newtrip.NewTripScreen
import com.stastyle.imumapper.ui.record.RecordScreen
import com.stastyle.imumapper.ui.settings.SettingsScreen
import com.stastyle.imumapper.ui.settings.UpdateBanner
import com.stastyle.imumapper.ui.triplist.TripListScreen
import com.stastyle.imumapper.ui.tuning.TuningScreen
import com.stastyle.imumapper.ui.viewer.ViewerScreen
import com.stastyle.imumapper.update.UpdateManager

/**
 * The app's screens. Trips is the start destination, so the recording notification, which only brings
 * the activity forward, lands in the app as before.
 *
 * The four tabs pass [AppBottomBar] into their own scaffolds; pushed screens get none. Every push is
 * guarded against a double tap twice over: the tap is dropped unless the screen it came from is resumed
 * (it stops being so once the transition away from it starts), and the push is single-top. Back taps
 * are dropped the same way, because a second pop would also pop the start destination and leave a
 * blank screen. Two record entries would both adopt the running recording and both process it when it
 * stops.
 */
@Composable
fun AppNavGraph() {
    val nav = rememberNavController()
    val context = LocalContext.current
    val recorder = remember(context) { RecordingController.get(context) }
    val updates = remember(context) { UpdateManager.get(context) }
    val tabBar: @Composable (route: String) -> Unit = { route ->
        val recording by recorder.state.collectAsStateWithLifecycle()
        val update by updates.state.collectAsStateWithLifecycle()
        AppBottomBar(
            currentRoute = route,
            recording = recordingBadge(recording),
            updateAvailable = updateBadge(update),
            onSelect = nav::navigateToTab,
        )
    }

    // The running recording's own route, whatever mode the screen that asked for it showed; nothing when
    // it ended in between.
    fun openRecording() {
        recordRouteFor(recorder.state.value)?.let(nav::push)
    }

    NavHost(
        navController = nav,
        startDestination = Routes.TRIPS,
        enterTransition = { fadeIn(tween(FADE_MS)) },
        exitTransition = { fadeOut(tween(FADE_MS)) },
    ) {
        composable(Routes.TRIPS) {
            TripListScreen(
                onOpenTrip = dropUnlessResumedWith { id: Long -> nav.push(Routes.viewer(id)) },
                onOpenRecording = dropUnlessResumedWith { _: TripMode -> openRecording() },
                onNewTrip = { nav.navigateToTab(Routes.NEW_TRIP) },
                onOpenDebug = dropUnlessResumed { nav.push(Routes.debug()) },
                banner = { UpdateBanner() },
                bottomBar = { tabBar(Routes.TRIPS) },
            )
        }
        composable(Routes.NEW_TRIP) {
            NewTripScreen(
                // A recording that started meanwhile is joined rather than set up a second time.
                onContinue = dropUnlessResumedWith { mode: TripMode ->
                    nav.push(recordRouteFor(recorder.state.value) ?: Routes.record(mode))
                },
                onReturnToRecording = dropUnlessResumedWith { _: TripMode -> openRecording() },
                bottomBar = { tabBar(Routes.NEW_TRIP) },
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
                // Not guarded: processing may end while the app is in the background, and the viewer must
                // still replace the record screen.
                onFinished = { tripId ->
                    nav.navigate(Routes.viewer(tripId)) {
                        popUpTo(Routes.TRIPS)
                    }
                },
                onCancelled = dropUnlessResumed { nav.popBackStack() },
            )
        }
        composable(
            Routes.VIEWER,
            arguments = listOf(navArgument(Routes.ARG_TRIP_ID) { type = NavType.LongType }),
        ) { entry ->
            val tripId = entry.arguments?.getLong(Routes.ARG_TRIP_ID) ?: -1L
            ViewerScreen(
                tripId = tripId,
                onBack = dropUnlessResumed { nav.popBackStack() },
                onOpenDebug = dropUnlessResumedWith { id: Long -> nav.push(Routes.debug(id)) },
            )
        }
        composable(Routes.CALIBRATION) {
            CalibrationScreen(
                onBack = null,
                onOpenTuning = dropUnlessResumed { nav.push(Routes.TUNING) },
                bottomBar = { tabBar(Routes.CALIBRATION) },
            )
        }
        composable(Routes.TUNING) {
            TuningScreen(onBack = dropUnlessResumed { nav.popBackStack() })
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
            DebugScreen(tripId = tripId, onBack = dropUnlessResumed { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = null,
                onOpenTuning = dropUnlessResumed { nav.push(Routes.TUNING) },
                onOpenDebug = dropUnlessResumed { nav.push(Routes.debug()) },
                bottomBar = { tabBar(Routes.SETTINGS) },
            )
        }
    }
}

/**
 * Opens a tab: the stack of the tab left is saved and that of the tab opened restored, so each tab
 * comes back as it was, and selecting the tab already shown does nothing.
 */
private fun NavController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Pushes [route] unless it is already on top. */
private fun NavController.push(route: String) {
    navigate(route) { launchSingleTop = true }
}

/**
 * [dropUnlessResumed] for a callback that takes an argument: [block] runs only while the destination
 * that composed the callback is resumed.
 */
@Composable
private fun <T> dropUnlessResumedWith(block: (T) -> Unit): (T) -> Unit {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return remember(lifecycle, block) {
        { value -> if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) block(value) }
    }
}

/** Length of the fade between destinations. */
private const val FADE_MS = 150
