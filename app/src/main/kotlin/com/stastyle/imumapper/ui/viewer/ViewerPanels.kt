package com.stastyle.imumapper.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.stastyle.imumapper.pipeline.core.PathStats
import com.stastyle.imumapper.render.MarkerKind
import com.stastyle.imumapper.render.SceneMarker
import java.util.Locale
import kotlin.math.roundToInt

/** Numbers panel under the canvas: distance, duration, steps, vertical range, closure, VIO share. */
@Composable
fun StatsPanel(stats: PathStats, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 3.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatItem("Distance", formatMetres(stats.distanceM))
                StatItem("Duration", formatDuration(stats.durationS))
                StatItem("Steps", stats.stepCount.toString())
            }
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatItem("Vertical", formatRange(stats.minZ, stats.maxZ))
                StatItem("Closure", formatClosure(stats.closureErrorM, stats.distanceM))
                StatItem("VIO", "${(stats.vioFraction * 100).roundToInt()} %")
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** Card shown above the stats when a marker is tapped. */
@Composable
fun MarkerCard(marker: SceneMarker, onOpenPhoto: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(12.dp).background(Color(marker.color), MaterialTheme.shapes.extraSmall))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(marker.title, style = MaterialTheme.typography.titleSmall)
                val position = String.format(
                    Locale.US, "%.1f E, %.1f N, %.1f up", marker.position.x, marker.position.y, marker.position.z,
                )
                Text("${formatDuration(marker.elapsedS)} · $position", style = MaterialTheme.typography.bodySmall)
                if (marker.detail.isNotBlank() && marker.kind != MarkerKind.KEYFRAME) {
                    Text(marker.detail, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (marker.kind == MarkerKind.KEYFRAME) {
                TextButton(onClick = onOpenPhoto) {
                    Icon(Icons.Filled.Photo, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Photo")
                }
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close")
            }
        }
    }
}

/** Full-width dialog with a keyframe photo, a spinner while it decodes, or the error. */
@Composable
fun PhotoDialog(title: String, bitmap: Bitmap?, loading: Boolean, error: String?, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }
                Box(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        bitmap != null -> Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = title,
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.FillWidth,
                        )
                        loading -> CircularProgressIndicator(modifier = Modifier.padding(32.dp))
                        else -> Text(
                            error ?: "No photo",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            }
        }
    }
}

fun formatMetres(m: Double): String = String.format(Locale.US, "%.1f m", m)

fun formatRange(minZ: Double, maxZ: Double): String =
    String.format(Locale.US, "%.1f m (%+.1f…%+.1f)", maxZ - minZ, minZ, maxZ)

fun formatClosure(errorM: Double?, distanceM: Double): String {
    if (errorM == null) return "—"
    val pct = if (distanceM > 0.0) errorM / distanceM * 100.0 else 0.0
    return String.format(Locale.US, "%.2f m (%.1f %%)", errorM, pct)
}

fun formatDuration(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).roundToInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}
