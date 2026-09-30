package com.stastyle.imumapper.ui.newtrip

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stastyle.imumapper.capture.RecordingController
import com.stastyle.imumapper.capture.RecordingState
import com.stastyle.imumapper.pipeline.core.TripMode
import com.stastyle.imumapper.ui.common.AppScaffold
import com.stastyle.imumapper.ui.common.BatteryFormat
import com.stastyle.imumapper.ui.common.BatteryIndicator
import com.stastyle.imumapper.ui.common.BatteryState
import com.stastyle.imumapper.ui.common.BrandButton
import com.stastyle.imumapper.ui.common.GlassCard
import com.stastyle.imumapper.ui.common.ScreenHeader
import com.stastyle.imumapper.ui.common.SectionHeader
import com.stastyle.imumapper.ui.common.appContainer
import com.stastyle.imumapper.ui.common.formatClock
import com.stastyle.imumapper.ui.common.rememberBatteryState
import com.stastyle.imumapper.ui.theme.imuColors
import com.stastyle.imumapper.ui.triplist.TripFormat

/**
 * The Record tab: picks the capture mode of a new trip and hands it to the recording screen, whose
 * setup step takes the carry position and starts the recording. While a recording runs the page only
 * returns to it ([onReturnToRecording] with the running mode), and while one is being saved it offers
 * nothing. [bottomBar] is the slot for the tab bar.
 */
@Composable
fun NewTripScreen(
    onContinue: (TripMode) -> Unit,
    onReturnToRecording: (TripMode) -> Unit,
    bottomBar: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val container = appContainer()
    val recorder = remember(context) { RecordingController.get(context) }
    val vm: NewTripViewModel = viewModel {
        NewTripViewModel(
            recording = recorder.state,
            defaultTripMode = container.updateManager.preferences.defaultTripMode,
        )
    }
    val ui by vm.ui.collectAsStateWithLifecycle()

    AppScaffold(
        topBar = { ScreenHeader("New Trip", subtitle = "Record motion with your IMU") },
        bottomBar = bottomBar,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val state = ui) {
                is NewTripUiState.Recording -> RecordingCard(
                    recording = state.recording,
                    onReturn = { onReturnToRecording(state.recording.mode) },
                )
                NewTripUiState.Stopping -> StoppingCard()
                is NewTripUiState.Choose -> ModeChoice(
                    mode = state.mode,
                    onSelect = vm::selectMode,
                    onContinue = onContinue,
                )
            }
        }
    }
}

/** The only thing on the page while a recording runs, so a second one cannot start in another mode. */
@Composable
private fun RecordingCard(recording: RecordingState.Recording, onReturn: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth(), highlighted = true) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                // Title, mode and clock are one item for TalkBack, like the line they read as.
                modifier = Modifier.semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                IconTile(TripFormat.modeIcon(recording.mode), emphasized = true)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Recording in progress",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    // Separate texts, so the clock keeps its left-to-right order on a right-to-left phone.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val quiet = MaterialTheme.colorScheme.onSurfaceVariant
                        val body = MaterialTheme.typography.bodyMedium
                        Text(TripFormat.modeLabel(recording.mode), style = body, color = quiet)
                        Separator(body)
                        Text(
                            formatClock(recording.elapsedNs),
                            style = body.copy(textDirection = TextDirection.Ltr, fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (recording.paused) {
                            Separator(body)
                            Text("Paused", style = body, color = quiet)
                        }
                    }
                }
            }
            Text(
                "A new trip can start once this one is stopped.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BrandButton(onClick = onReturn, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text("Return to recording")
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun StoppingCard() {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            Text("Saving the last trip…", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** [mode] is null while the Settings default loads; no option is selected and Continue waits. */
@Composable
private fun ModeChoice(mode: TripMode?, onSelect: (TripMode) -> Unit, onContinue: (TripMode) -> Unit) {
    SectionHeader(title = "Capture mode")
    Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (option in TripMode.entries) {
            ModeCard(mode = option, selected = option == mode, onSelect = { onSelect(option) })
        }
    }
    Text(
        "Where the phone is carried is chosen on the next step.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    DeviceCard(battery = rememberBatteryState())
    BrandButton(
        onClick = { if (mode != null) onContinue(mode) },
        enabled = mode != null,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
    ) {
        Text("Continue", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
    }
}

/** One radio option as a card: the whole card selects it, and the strong border marks the choice. */
@Composable
private fun ModeCard(mode: TripMode, selected: Boolean, onSelect: () -> Unit) {
    GlassCard(
        highlighted = selected,
        modifier = Modifier
            .fillMaxWidth()
            // Clipped first, so the ripple keeps the card's rounded corners.
            .clip(MaterialTheme.shapes.medium)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IconTile(TripFormat.modeIcon(mode), emphasized = selected)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(TripFormat.modeLabel(mode), style = MaterialTheme.typography.titleMedium)
                Text(
                    TripFormat.modeExplanation(mode),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Drawn only; the card carries the radio semantics.
            RadioButton(selected = selected, onClick = null)
        }
    }
}

/** The phone the trip is recorded on, with its battery, since a recording keeps every sensor busy. */
@Composable
private fun DeviceCard(battery: BatteryState?) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                IconTile(Icons.Filled.PhoneAndroid, emphasized = false)
                Column(modifier = Modifier.weight(1f)) {
                    Text("Device", style = MaterialTheme.typography.titleMedium)
                    Text(
                        deviceName(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BatteryIndicator(battery)
            }
            if (battery != null && BatteryFormat.isLow(battery.percent, battery.charging)) {
                val warning = MaterialTheme.imuColors.warning
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Filled.BatteryAlert,
                        contentDescription = null,
                        tint = warning,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        "Battery low: charge the phone before a long walk. Recording keeps the sensors at their " +
                            "fastest rate.",
                        style = MaterialTheme.typography.bodySmall,
                        color = warning,
                    )
                }
            }
        }
    }
}

/** The " · " between parts of a line; silent, so TalkBack does not read "middle dot". */
@Composable
private fun Separator(style: TextStyle) {
    Text(
        " · ",
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clearAndSetSemantics {},
    )
}

/** A rounded square behind an icon; [emphasized] fills it in the brand blue, for the chosen mode. */
@Composable
private fun IconTile(icon: ImageVector, emphasized: Boolean) {
    val colors = MaterialTheme.imuColors
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (emphasized) colors.brandFill else MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (emphasized) colors.onBrandFill else MaterialTheme.colorScheme.secondary,
        )
    }
}

/** "Samsung SM-S948B": what the system reports, only the maker capitalised. */
private fun deviceName(): String =
    Build.MANUFACTURER.replaceFirstChar { it.titlecase() } + " " + Build.MODEL
