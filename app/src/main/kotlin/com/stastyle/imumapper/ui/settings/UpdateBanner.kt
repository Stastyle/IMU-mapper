package com.stastyle.imumapper.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

/** Stateless variant: shows for Available, Downloading, ReadyToInstall and NeedsInstallPermission. */
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
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (state) {
                is UpdateState.Downloading -> {
                    Text("Downloading version ${release.version}…", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(
                        progress = { state.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is UpdateState.ReadyToInstall -> {
                    Text("Version ${release.version} is ready to install", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onInstall) { Text("Install") }
                        TextButton(onClick = onLater) { Text("Later") }
                    }
                }
                is UpdateState.NeedsInstallPermission -> {
                    Text(
                        "Allow \"Install unknown apps\" for IMU Mapper, then tap Install again",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onInstall) { Text("Install") }
                        TextButton(onClick = onLater) { Text("Later") }
                    }
                }
                else -> {
                    Text("Version ${release.version} available", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onUpdate) { Text("Update") }
                        TextButton(onClick = onLater) { Text("Later") }
                    }
                }
            }
        }
    }
}
