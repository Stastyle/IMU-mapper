package com.stastyle.imumapper.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

/** One bottom-bar destination; [route] is a top-level route from [Routes]. */
private class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab(Routes.TRIPS, "Trips", Icons.AutoMirrored.Filled.ViewList),
    Tab(Routes.NEW_TRIP, "Record", Icons.Filled.RadioButtonChecked),
    Tab(Routes.CALIBRATION, "Calibrate", Icons.Filled.Straighten),
    Tab(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
)

/**
 * Bottom navigation between the four top-level screens. Each of them hosts it in its own scaffold's
 * bottomBar slot, so the snackbar and the floating button sit above it and no insets are applied twice;
 * pushed screens have none.
 *
 * [currentRoute] is the tab shown as selected. The Record tab carries a dot while [recordingActive],
 * and Settings while [updateAvailable]; the dot is decorative and TalkBack reads the item's state
 * description instead.
 */
@Composable
fun AppBottomBar(
    currentRoute: String?,
    recordingActive: Boolean,
    updateAvailable: Boolean,
    onSelect: (route: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    NavigationBar(modifier = modifier, containerColor = colors.surfaceContainerLow.copy(alpha = BAR_ALPHA)) {
        for (tab in TABS) {
            val badgeState = when {
                tab.route == Routes.NEW_TRIP && recordingActive -> "Recording in progress"
                tab.route == Routes.SETTINGS && updateAvailable -> "Update available"
                else -> null
            }
            NavigationBarItem(
                selected = tab.route == currentRoute,
                onClick = { onSelect(tab.route) },
                icon = {
                    BadgedBox(badge = { if (badgeState != null) Badge() }) {
                        // The label names the tab, so the icon adds nothing for TalkBack.
                        Icon(tab.icon, contentDescription = null)
                    }
                },
                label = { Text(tab.label) },
                modifier = if (badgeState != null) Modifier.semantics { stateDescription = badgeState } else Modifier,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = colors.secondary,
                    selectedTextColor = colors.secondary,
                    indicatorColor = colors.primaryContainer,
                ),
            )
        }
    }
}

/** The bar lets a little of the page gradient through, like the cards above it. */
private const val BAR_ALPHA = 0.92f
