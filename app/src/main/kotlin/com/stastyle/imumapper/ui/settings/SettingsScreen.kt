package com.stastyle.imumapper.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.stastyle.imumapper.ui.common.AppScaffold
import com.stastyle.imumapper.ui.common.AppTopBar
import com.stastyle.imumapper.ui.common.BrandButton
import com.stastyle.imumapper.ui.common.BrandFilterChip
import com.stastyle.imumapper.ui.common.GlassCard
import com.stastyle.imumapper.ui.common.ScreenHeader
import com.stastyle.imumapper.ui.common.SectionHeader
import com.stastyle.imumapper.ui.common.StatusPill
import com.stastyle.imumapper.ui.common.appContainer
import com.stastyle.imumapper.ui.debug.ConfigDraft
import com.stastyle.imumapper.update.ReleaseInfo
import com.stastyle.imumapper.update.ReleaseNotes
import com.stastyle.imumapper.update.UpdateManager
import com.stastyle.imumapper.update.UpdateState
import kotlin.math.roundToInt

/**
 * Carry position, default trip mode, north from compass, steps to confirm a height change, check for
 * updates, the tools (assisted tuning and Debug), about.
 *
 * As the Settings tab it has no back arrow ([onBack] null), shows the large tab header and hosts the tab
 * bar in [bottomBar]; with [onBack] it is a pushed screen with a back arrow instead.
 */
@Composable
fun SettingsScreen(
    onBack: (() -> Unit)?,
    onOpenTuning: () -> Unit = {},
    onOpenDebug: () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
) {
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
    val version = "IMU Mapper ${vm.installedVersion}"

    AppScaffold(
        topBar = {
            if (onBack == null) {
                ScreenHeader("Settings", subtitle = version)
            } else {
                AppTopBar("Settings", subtitle = version, onBack = onBack)
            }
        },
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
            ToolsSection(onOpenTuning = onOpenTuning, onOpenDebug = onOpenDebug)
            AboutSection(installedVersion = vm.installedVersion, onOpenUrl = { url -> openUrl(context, url) })
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** A settings group: a card under a heading with its icon, and an optional [action] (a pill) at its end. */
@Composable
private fun SectionCard(
    title: String,
    icon: ImageVector,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionHeader(title = title, icon = icon, action = action)
            content()
        }
    }
}

/** A setting's name above its explanation. */
@Composable
private fun SettingLabel(title: String, help: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceChips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (option in options) {
            BrandFilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = label(option),
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
    SectionCard(title = "Recording", icon = Icons.AutoMirrored.Filled.DirectionsWalk) {
        SettingLabel(
            "Carry position",
            "Where the phone is while you walk. The stride calibration is tied to it; the heading offset " +
                "belongs to the pose the phone is in when a recording starts.",
        )
        ChoiceChips(
            options = CarryPosition.entries,
            selected = carryPosition,
            label = ::carryPositionLabel,
            onSelect = onCarryPosition,
        )
        HorizontalDivider()
        SettingLabel("Default trip mode", "Preselected when starting a new trip.")
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
                .toggleable(value = northFromCompass, role = Role.Switch, onValueChange = onNorthFromCompass)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingLabel(
                "North from compass",
                "North on the map is taken from the compass before each recording. " +
                    "Off keeps the path in the gyroscope's own frame, which differs per trip. " +
                    "Also applies when trips are re-processed, since processing uses the current calibration.",
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            Switch(checked = northFromCompass, onCheckedChange = null)
        }
    }
}

@Composable
private fun ProcessingSection(confirmSteps: Int?, maxHeldM: Double?, onConfirmSteps: (Int) -> Unit) {
    SectionCard(title = "Processing", icon = Icons.Filled.Tune) {
        SettingLabel("Steps to confirm a height change", confirmStepsHelp(maxHeldM))
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
    val pill = UpdatePill.of(state, unavailableReason)
    SectionCard(
        title = "Updates",
        icon = Icons.Filled.SystemUpdate,
        action = pill?.let { p -> @Composable { StatusPill(p.label, p.tone) } },
    ) {
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
        UpdateState.Idle -> BrandButton(onClick = onCheck) { Text("Check for updates") }
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
                BrandButton(onClick = onInstall) { Text("Install") }
                TextButton(onClick = onDismiss) { Text("Later") }
            }
        }
        is UpdateState.NeedsInstallPermission -> {
            Text(
                "Android needs your permission first: allow \"Install unknown apps\" for IMU Mapper on the " +
                    "settings page that opened, then come back and tap Install again.",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BrandButton(onClick = onInstall) { Text("Install") }
                TextButton(onClick = onDismiss) { Text("Later") }
            }
        }
        is UpdateState.Error -> {
            Text(state.message, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BrandButton(onClick = onRetry) { Text("Retry") }
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
        BrandButton(onClick = onDownload) { Text("Update") }
        TextButton(onClick = onDismiss) { Text("Later") }
    }
}

@Composable
private fun ToolsSection(onOpenTuning: () -> Unit, onOpenDebug: () -> Unit) {
    SectionCard(title = "Tools", icon = Icons.Filled.Build) {
        ToolRow(
            icon = Icons.Filled.AutoFixHigh,
            title = "Assisted tuning",
            detail = "Let a chat model propose thresholds for a walk you describe, and check them before saving.",
            onClick = onOpenTuning,
        )
        HorizontalDivider()
        ToolRow(
            icon = Icons.Filled.BugReport,
            title = "Debug",
            detail = "Live sensors, raw logs, stored runs, re-processing with an edited config, the last crash.",
            onClick = onOpenDebug,
        )
    }
}

/** A whole-row button, so the target is large and TalkBack reads the title and detail together. */
@Composable
private fun ToolRow(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
        Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AboutSection(installedVersion: String, onOpenUrl: (String) -> Unit) {
    val repoUrl = "https://github.com/${BuildConfig.GITHUB_REPO}"
    SectionCard(title = "About", icon = Icons.Filled.Info) {
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
