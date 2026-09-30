package com.stastyle.imumapper.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Photo
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.stastyle.imumapper.render.MarkerKind
import com.stastyle.imumapper.render.SceneMarker
import com.stastyle.imumapper.ui.common.GlassCard
import com.stastyle.imumapper.ui.common.formatDuration
import java.util.Locale

/**
 * The tapped marker: its colour, title, time and position, and for a keyframe its photo. It sits under the
 * Path tab's canvas and over the bottom of the 3D tab's.
 */
@Composable
fun MarkerCard(marker: SceneMarker, onOpenPhoto: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    // Over the 3D canvas a tap between the card's texts must not reach the map under it, as it did not
    // through the Card this replaced; an empty pointer handler takes the hit.
    GlassCard(modifier = modifier.fillMaxWidth().pointerInput(Unit) {}) {
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
                // A mark from before the path's first point has a negative time; it reads as the start.
                Text(
                    "${formatDuration(marker.elapsedS.coerceAtLeast(0.0))} · $position",
                    style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
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
