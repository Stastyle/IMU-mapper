package com.stastyle.imumapper.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stastyle.imumapper.BuildConfig
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.TripMode
import com.stastyle.imumapper.ui.common.appContainer
import com.stastyle.imumapper.ui.debug.ConfigDraft
import com.stastyle.imumapper.update.ReleaseInfo
import com.stastyle.imumapper.update.ReleaseNotes
import com.stastyle.imumapper.update.UpdateManager
import com.stastyle.imumapper.update.UpdateState
import kotlin.math.roundToInt

/**
 * Carry position, default trip mode, north from compass, steps to confirm a height change, check for
 * updates, about.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = appContainer()
    val vm: SettingsViewModel = viewModel {
        SettingsViewModel(calibration = container.calibrationRepository, updates = UpdateManager.get(context))
    }
    val carryPosition by vm.carryPosition.collectAsStateWithLifecycle()
    val tripMode by vm.defaultTripMode.collectAsStateWithLifecycle()
    val northFromCompass by vm.northFromCompass.collectAsStateWithLifecycle()
    val confirmSteps by vm.baroConfirmSteps.collectAsStateWithLifecycle()
    val maxHeldM by vm.baroMaxHeldM.collectAsStateWithLifecycle()
    val updateState by vm.updateState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RecordingSection(
                carryPosition = carryPosition,
                onCarryPosition = vm::setCarryPosition,
                tripMode = tripMode,
                onTripMode = vm::setDefaultTripMode,
                northFromCompass = northFromCompass,
                onNorthFromCompass = vm::setNorthFromCompass,
            )
            ProcessingSection(
                confirmSteps = confirmSteps,
                maxHeldM = maxHeldM,
                onConfirmSteps = vm::setBaroConfirmSteps,
            )
            UpdatesSection(
                state = updateState,
                installedVersion = vm.installedVersion,
                unavailableReason = vm.updatesUnavailableReason,
                releasesPageUrl = vm.releasesPageUrl,
                onCheck = vm::checkForUpdates,
                onDownload = vm::downloadUpdate,
                onCancel = vm::cancelDownload,
                onInstall = vm::installUpdate,
                onRetry = vm::retryUpdate,
                onDismiss = vm::dismissUpdate,
                onOpenUrl = { url -> openUrl(context, url) },
            )
            AboutSection(installedVersion = vm.installedVersion, onOpenUrl = { url -> openUrl(context, url) })
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChoiceChips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (option in options) {
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
            )
        }
    }
}

@Composable
private fun RecordingSection(
    carryPosition: CarryPosition,
    onCarryPosition: (CarryPosition) -> Unit,
    tripMode: TripMode,
    onTripMode: (TripMode) -> Unit,
    northFromCompass: Boolean,
    onNorthFromCompass: (Boolean) -> Unit,
) {
    SectionCard(title = "Recording") {
        Text("Carry position", style = MaterialTheme.typography.labelLarge)
        Text(
            "Where the phone is while you walk. The stride calibration is tied to it; the heading offset " +
                "belongs to the pose the phone is in when a recording starts.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChoiceChips(
            options = CarryPosition.entries,
            selected = carryPosition,
            label = ::carryPositionLabel,
            onSelect = onCarryPosition,
        )
        HorizontalDivider()
        Text("Default trip mode", style = MaterialTheme.typography.labelLarge)
        Text(
            "Preselected when starting a new trip.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChoiceChips(
            options = TripMode.entries,
            selected = tripMode,
            label = ::tripModeLabel,
            onSelect = onTripMode,
        )
        HorizontalDivider()
        // The whole row toggles, so the target is large and TalkBack reads label and state together.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = northFromCompass, role = Role.Switch, onValueChange = onNorthFromCompass),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text("North from compass", style = MaterialTheme.typography.labelLarge)
                Text(
                    "North on the map is taken from the compass before each recording. " +
                        "Off keeps the path in the gyroscope's own frame, which differs per trip. " +
                        "Also applies when trips are re-processed, since processing uses the current calibration.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = northFromCompass, onCheckedChange = null)
        }
    }
}

@Composable
private fun ProcessingSection(confirmSteps: Int?, maxHeldM: Double?, onConfirmSteps: (Int) -> Unit) {
    SectionCard(title = "Processing") {
        Text("Steps to confirm a height change", style = MaterialTheme.typography.labelLarge)
        Text(
            confirmStepsHelp(maxHeldM),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StepCountStepper(steps = confirmSteps, onSteps = onConfirmSteps)
    }
}

/**
 * The help text under the step count. [maxHeldM] is the saved [PipelineConfig.baroMaxHeldM], which the
 * Debug editor ("Max held-out height") or an applied tuning proposal sets; while it is null, before the
 * calibration has loaded, the text names no number. At 0 or below the path follows the barometer, so the
 * filter holds nothing out.
 */
internal fun confirmStepsHelp(maxHeldM: Double?): String {
    val slopes = when {
        maxHeldM == null ->
            "A gentle slope can lose part of its height for good, up to a limit set in the Debug editor; " +
                "only height beyond that comes through."
        maxHeldM <= 0.0 ->
            "The height limit in the Debug editor is 0 m, though, so every change comes through."
        else ->
            "A gentle slope can lose up to ${ConfigDraft.num(maxHeldM)} m of its height for good, the limit " +
                "set in the Debug editor; only height beyond that comes through."
    }
    return "Applies to barometer height (all of a Pocket trip, elsewhere only where camera tracking was " +
        "lost), also when trips are re-processed. A height change counts only if it keeps going one way for " +
        "about this many steps (default $DEFAULT_CONFIRM_STEPS), as on stairs; pressure jumps on flat ground " +
        "do not. Fewer steps keep short stairs and more of a shallow slope; more steps hold out more of the " +
        "slower pressure changes wind can cause. Off takes every change. " + slopes
}

/**
 * Minus, the value (0 shown as "Off") and plus. [steps] is null until the saved calibration has loaded;
 * until then the value is a placeholder and both buttons are disabled. Plus stops at [MAX_CONFIRM_STEPS];
 * a larger value set in the Debug editor is still shown and counts down with minus.
 */
@Composable
private fun StepCountStepper(steps: Int?, onSteps: (Int) -> Unit) {
    val shown = when {
        steps == null -> "…"
        steps <= 0 -> "Off"
        else -> steps.toString()
    }
    val spoken = when {
        steps == null -> "Loading"
        steps <= 0 -> "Off"
        steps == 1 -> "1 step"
        else -> "$steps steps"
    }
    // Set by the first button press, so TalkBack reads each new value then but not the saved value
    // arriving while the screen opens.
    var pressed by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = {
                if (steps != null) {
                    pressed = true
                    onSteps(steps - 1)
                }
            },
            enabled = steps != null && steps > 0,
        ) {
            Icon(Icons.Filled.Remove, contentDescription = "Fewer steps")
        }
        Text(
            shown,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(min = 56.dp)
                .semantics {
                    contentDescription = spoken
                    if (pressed) liveRegion = LiveRegionMode.Polite
                },
        )
        IconButton(
            onClick = {
                if (steps != null) {
                    pressed = true
                    onSteps(steps + 1)
                }
            },
            enabled = steps != null && steps < MAX_CONFIRM_STEPS,
        ) {
            Icon(Icons.Filled.Add, contentDescription = "More steps")
        }
    }
}

@Composable
private fun UpdatesSection(
    state: UpdateState,
    installedVersion: String,
    unavailableReason: String?,
    releasesPageUrl: String,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    SectionCard(title = "Updates") {
        Text("Installed version: $installedVersion", style = MaterialTheme.typography.bodyMedium)
        when {
            unavailableReason != null -> Text(
                unavailableReason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> UpdateControls(
                state = state,
                onCheck = onCheck,
                onDownload = onDownload,
                onCancel = onCancel,
                onInstall = onInstall,
                onRetry = onRetry,
                onDismiss = onDismiss,
            )
        }
        val releaseUrl = releaseOf(state)?.htmlUrl?.takeIf { it.isNotBlank() } ?: releasesPageUrl
        TextButton(onClick = { onOpenUrl(releaseUrl) }) { Text("Open release page") }
    }
}

@Composable
private fun UpdateControls(
    state: UpdateState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        UpdateState.Idle -> Button(onClick = onCheck) { Text("Check for updates") }
        UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text("Checking GitHub for a newer release…")
        }
        is UpdateState.UpToDate -> {
            Text("You have the latest version.")
            OutlinedButton(onClick = onCheck) { Text("Check again") }
        }
        is UpdateState.Available -> AvailableBlock(state.release, onDownload = onDownload, onDismiss = onDismiss)
        is UpdateState.Downloading -> {
            val percent = (state.progress * 100f).roundToInt().coerceIn(0, 100)
            Text("Downloading version ${state.release.version}… $percent %")
            LinearProgressIndicator(
                progress = { state.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
        is UpdateState.ReadyToInstall -> {
            Text("Version ${state.release.version} is downloaded and verified.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onInstall) { Text("Install") }
                TextButton(onClick = onDismiss) { Text("Later") }
            }
        }
        is UpdateState.NeedsInstallPermission -> {
            Text(
                "Android needs your permission first: allow \"Install unknown apps\" for IMU Mapper on the " +
                    "settings page that opened, then come back and tap Install again.",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onInstall) { Text("Install") }
                TextButton(onClick = onDismiss) { Text("Later") }
            }
        }
        is UpdateState.Error -> {
            Text(state.message, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetry) { Text("Retry") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun AvailableBlock(release: ReleaseInfo, onDownload: () -> Unit, onDismiss: () -> Unit) {
    Text("Version ${release.version} is available", style = MaterialTheme.typography.titleSmall)
    val published = release.publishedAt.substringBefore('T')
    val size = release.apkSizeBytes
    val meta = buildString {
        if (published.isNotBlank()) append("Published $published")
        if (size > 0) {
            if (isNotEmpty()) append(" · ")
            append("%.1f MB".format(size / 1_048_576.0))
        }
    }
    if (meta.isNotEmpty()) {
        Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val notes = ReleaseNotes.plainText(release.notes)
    if (notes.isNotEmpty()) {
        Text(notes, style = MaterialTheme.typography.bodyMedium)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onDownload) { Text("Update") }
        TextButton(onClick = onDismiss) { Text("Later") }
    }
}

@Composable
private fun AboutSection(installedVersion: String, onOpenUrl: (String) -> Unit) {
    val repoUrl = "https://github.com/${BuildConfig.GITHUB_REPO}"
    SectionCard(title = "About") {
        Text("IMU Mapper $installedVersion", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Records a walk with the phone's sensors and shows the route as a 3D path.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { onOpenUrl(repoUrl) }) { Text("github.com/${BuildConfig.GITHUB_REPO}") }
        Text(
            "Open source; the licence is in the repository. Updates are downloaded from its GitHub Releases.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()
        Text(
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} " +
                "(API ${Build.VERSION.SDK_INT})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun releaseOf(state: UpdateState): ReleaseInfo? = when (state) {
    is UpdateState.Available -> state.release
    is UpdateState.Downloading -> state.release
    is UpdateState.ReadyToInstall -> state.release
    is UpdateState.NeedsInstallPermission -> state.release
    is UpdateState.Error -> state.release
    else -> null
}

/** Highest count the Settings stepper offers; the Debug editor takes up to ConfigSchema's 50. */
private const val MAX_CONFIRM_STEPS = 20

private val DEFAULT_CONFIRM_STEPS = PipelineConfig().baroConfirmSteps

private fun carryPositionLabel(position: CarryPosition): String = when (position) {
    CarryPosition.HAND -> "Hand"
    CarryPosition.POCKET -> "Pocket"
    CarryPosition.CHEST -> "Chest"
    CarryPosition.HELMET -> "Helmet"
}

private fun tripModeLabel(mode: TripMode): String = when (mode) {
    TripMode.POCKET -> "Pocket"
    TripMode.FLASHLIGHT -> "Flashlight"
    TripMode.ILLUMINATED -> "Illuminated"
}

/** Opens [url] in the browser; a missing browser is reported nowhere because there is nothing to do about it. */
private fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // No browser installed: the link simply does nothing.
    }
}
