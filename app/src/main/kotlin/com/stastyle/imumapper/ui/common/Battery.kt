package com.stastyle.imumapper.ui.common

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BatteryUnknown
import androidx.compose.material.icons.filled.Battery0Bar
import androidx.compose.material.icons.filled.Battery1Bar
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.material.icons.filled.Battery3Bar
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.Battery6Bar
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.stastyle.imumapper.ui.theme.imuColors

/** The battery as the system last broadcast it. */
@Immutable
data class BatteryState(val percent: Int, val charging: Boolean)

/**
 * The battery level and charging state, following `ACTION_BATTERY_CHANGED` while this is composed;
 * null until the first broadcast or when the broadcast carries no level.
 */
@Composable
fun rememberBatteryState(): BatteryState? {
    val context = LocalContext.current.applicationContext
    var state by remember { mutableStateOf<BatteryState?>(null) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                state = intent.toBatteryState()
            }
        }
        // The broadcast is sticky: registering returns the last one at once, so the level shows
        // without waiting for the next change. A system broadcast still reaches a non-exported receiver.
        val last = ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        if (last != null) state = last.toBatteryState()
        onDispose { context.unregisterReceiver(receiver) }
    }
    return state
}

private fun Intent.toBatteryState(): BatteryState? {
    val percent = BatteryFormat.percent(
        level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
        scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1),
    ) ?: return null
    val status = getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    return BatteryState(percent, charging)
}

/** The Material glyph for a [BatteryBucket]. */
fun batteryIcon(bucket: BatteryBucket): ImageVector = when (bucket) {
    BatteryBucket.Bar0 -> Icons.Filled.Battery0Bar
    BatteryBucket.Bar1 -> Icons.Filled.Battery1Bar
    BatteryBucket.Bar2 -> Icons.Filled.Battery2Bar
    BatteryBucket.Bar3 -> Icons.Filled.Battery3Bar
    BatteryBucket.Bar4 -> Icons.Filled.Battery4Bar
    BatteryBucket.Bar5 -> Icons.Filled.Battery5Bar
    BatteryBucket.Bar6 -> Icons.Filled.Battery6Bar
    BatteryBucket.Full -> Icons.Filled.BatteryFull
    BatteryBucket.Charging -> Icons.Filled.BatteryChargingFull
}

/**
 * Battery icon and "92 %", read by TalkBack as one phrase. Low is drawn in the warning colour,
 * charging in the success colour; null shows an unknown-battery icon and a dash.
 */
@Composable
fun BatteryIndicator(state: BatteryState?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.imuColors
    val tint = when {
        state == null -> MaterialTheme.colorScheme.onSurfaceVariant
        state.charging -> colors.success
        BatteryFormat.isLow(state.percent, state.charging) -> colors.warning
        else -> MaterialTheme.colorScheme.onSurface
    }
    val icon = if (state == null) {
        Icons.AutoMirrored.Filled.BatteryUnknown
    } else {
        batteryIcon(BatteryFormat.bucket(state.percent, state.charging))
    }
    val text = if (state == null) NO_VALUE else BatteryFormat.levelText(state.percent)
    Row(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = BatteryFormat.spoken(state?.percent, state?.charging == true)
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Ltr),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
