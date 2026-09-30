package com.stastyle.imumapper.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stastyle.imumapper.ui.common.BrandButton
import com.stastyle.imumapper.ui.common.CardTone
import com.stastyle.imumapper.ui.common.GlassCard
import com.stastyle.imumapper.update.UpdateManager
import com.stastyle.imumapper.update.UpdateState

/**
 * "Version X available" strip for the trip list, wired to the process-wide [UpdateManager].
 * Renders nothing unless there is something for the user to act on. It also follows a download
 * started from its own Update button through to Install, so the user is not sent to Settings to
 * finish what they started here.
 */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val manager = remember(context) { UpdateManager.get(context) }
    val state by manager.state.collectAsStateWithLifecycle()
    UpdateBanner(
        state = state,
        onUpdate = manager::download,
        onInstall = manager::install,
        onLater = manager::dismiss,
        modifier = modifier,
    )
}

/**
 * Stateless variant: shows for Available, Downloading, ReadyToInstall and NeedsInstallPermission, as
 * a blue card with the update icon. It pads itself (16 dp at the sides, 8 dp above and below), so the
 * trip list places it like its own cards.
 */
@Composable
fun UpdateBanner(
    state: UpdateState,
    onUpdate: () -> Unit,
    onInstall: () -> Unit,
    onLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val release = when (state) {
        is UpdateState.Available -> state.release
        is UpdateState.Downloading -> state.release
        is UpdateState.ReadyToInstall -> state.release
        is UpdateState.NeedsInstallPermission -> state.release
        else -> return
    }
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        tone = CardTone.Accent,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Filled.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state) {
                    is UpdateState.Downloading -> {
                        Text("Downloading version ${release.version}…", style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(
                            progress = { state.progress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                        )
                    }
                    is UpdateState.ReadyToInstall -> {
                        Text(
                            "Version ${release.version} is ready to install",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Actions(primary = "Install", onPrimary = onInstall, onLater = onLater)
                    }
                    is UpdateState.NeedsInstallPermission -> {
                        Text(
                            "Allow \"Install unknown apps\" for IMU Mapper, then tap Install again",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Actions(primary = "Install", onPrimary = onInstall, onLater = onLater)
                    }
                    else -> {
                        Text("Version ${release.version} available", style = MaterialTheme.typography.bodyMedium)
                        Actions(primary = "Update", onPrimary = onUpdate, onLater = onLater)
                    }
                }
            }
        }
    }
}

@Composable
private fun Actions(primary: String, onPrimary: () -> Unit, onLater: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BrandButton(onClick = onPrimary) { Text(primary) }
        // The card's own text colour, which reads better on the blue card than the primary blue.
        TextButton(
            onClick = onLater,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
        ) { Text("Later") }
    }
}
