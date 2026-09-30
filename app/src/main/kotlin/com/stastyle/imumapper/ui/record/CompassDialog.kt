package com.stastyle.imumapper.ui.record

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.stastyle.imumapper.capture.CompassLock
import com.stastyle.imumapper.capture.CompassReading
import com.stastyle.imumapper.capture.CompassStatus
import com.stastyle.imumapper.ui.common.BrandButton
import com.stastyle.imumapper.ui.theme.imuColors
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The "Calibrating compass" popup shown before a recording and before the heading calibration walk: a
 * live compass dial, what the user should do right now, and [startLabel] once north is locked. Back and
 * Cancel call [onCancel]. [hint] says how to hold the phone. With [onSkip] set, "Start anyway" appears
 * while [canSkip] is true, for when the compass never locks; without it there is no way past the lock.
 */
@Composable
fun CompassDialog(
    reading: CompassReading?,
    hint: String,
    startLabel: String,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    canSkip: Boolean = false,
    onSkip: (() -> Unit)? = null,
) {
    val status = reading?.status ?: CompassStatus.WAITING
    val locked = status == CompassStatus.LOCKED
    val view = LocalView.current
    LaunchedEffect(locked) {
        if (locked) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }
    Dialog(
        onDismissRequest = onCancel,
        // A stray tap beside the dialog must not throw away the wait; Back and Cancel are explicit.
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false),
    ) {
        // The dialog colour and the card border of the navy theme; the dial's own colours come from the scheme.
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.imuColors.cardBorder),
        ) {
            // Scrolls so Start and Cancel stay reachable when the window is short (split screen).
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AnimatedContent(targetState = locked, label = "compass title") { isLocked ->
                    Text(
                        text = if (isLocked) "North found" else "Calibrating compass",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                CompassDial(reading = reading, modifier = Modifier.size(200.dp))
                AnimatedContent(targetState = status, label = "compass status") { s ->
                    Text(
                        text = statusText(s),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                val detail = detailText(reading)
                if (detail != null) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                AnimatedVisibility(visible = locked) {
                    BrandButton(onClick = onStart, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Text(startLabel, style = MaterialTheme.typography.titleMedium)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    if (onSkip != null) {
                        AnimatedVisibility(visible = canSkip) {
                            Column(horizontalAlignment = Alignment.End) {
                                TextButton(onClick = onSkip) { Text("Start anyway") }
                                Text(
                                    text = "North may be off by several degrees",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.padding(end = 12.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compass card that turns so its N points at magnetic north relative to the phone's top edge (the
 * fixed mark at the top), with a ring that fills while north settles and turns solid when it locks.
 */
@Composable
private fun CompassDial(reading: CompassReading?, modifier: Modifier = Modifier) {
    val status = reading?.status ?: CompassStatus.WAITING
    val locked = status == CompassStatus.LOCKED
    val heading = reading?.headingDeg
    val scheme = MaterialTheme.colorScheme

    // The card turns by minus the heading. The angle is kept unwrapped so a heading going from 359 to 1
    // degree turns the card two degrees through north, not 358 the long way round.
    val cardAngle = remember { Animatable(-(heading ?: 0.0).toFloat()) }
    LaunchedEffect(heading) {
        if (heading != null) {
            val target = CompassLock.unwrapDegrees(-cardAngle.targetValue.toDouble(), heading)
            cardAngle.animateTo(
                -target.toFloat(),
                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
            )
        }
    }
    val progress by animateFloatAsState(reading?.progress ?: 0f, label = "compass progress")
    val accent by animateColorAsState(if (locked) scheme.secondary else scheme.primary, label = "compass accent")
    val face by animateColorAsState(
        if (locked) scheme.secondaryContainer else scheme.surfaceContainerHighest,
        label = "compass face",
    )
    val track = scheme.outlineVariant
    val tickColor = scheme.onSurfaceVariant
    val onFace = if (locked) scheme.onSecondaryContainer else scheme.onSurface

    val measurer = rememberTextMeasurer()
    val letterStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
    val letters = remember(measurer, letterStyle) {
        listOf(0.0 to "N", 90.0 to "E", 180.0 to "S", 270.0 to "W").map { (bearing, text) ->
            bearing to measurer.measure(text, letterStyle)
        }
    }

    val description = dialDescription(reading)
    Box(modifier = modifier.semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val ringStroke = 6.dp.toPx()
            val radius = size.minDimension / 2f - 12.dp.toPx()
            drawCircle(face, radius = radius)
            drawCircle(track, radius = radius, style = Stroke(ringStroke))
            val sweep = if (locked) 360f else 360f * progress
            if (sweep > 0f) {
                drawArc(
                    color = accent,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(2f * radius, 2f * radius),
                    style = Stroke(ringStroke, cap = StrokeCap.Round),
                )
            }

            // Fixed mark above the ring: the phone's top edge (or camera, when upright).
            val mark = Path().apply {
                moveTo(center.x, center.y - radius - 2.dp.toPx())
                lineTo(center.x - 7.dp.toPx(), center.y - radius - 12.dp.toPx())
                lineTo(center.x + 7.dp.toPx(), center.y - radius - 12.dp.toPx())
                close()
            }
            drawPath(mark, onFace)

            val tickOuter = radius - 8.dp.toPx()
            rotate(degrees = cardAngle.value, pivot = center) {
                for (i in 0 until 72) {
                    val major = i % 6 == 0
                    val a = Math.toRadians(i * 5.0)
                    val inner = tickOuter - (if (major) 12.dp else 6.dp).toPx()
                    val sx = sin(a).toFloat()
                    val cy = -cos(a).toFloat()
                    drawLine(
                        color = tickColor,
                        start = Offset(center.x + inner * sx, center.y + inner * cy),
                        end = Offset(center.x + tickOuter * sx, center.y + tickOuter * cy),
                        strokeWidth = (if (major) 2.dp else 1.dp).toPx(),
                    )
                }
                // North pointer on the card, just inside the ticks.
                val tip = tickOuter - 14.dp.toPx()
                val pointer = Path().apply {
                    moveTo(center.x, center.y - tip)
                    lineTo(center.x - 7.dp.toPx(), center.y - tip + 12.dp.toPx())
                    lineTo(center.x + 7.dp.toPx(), center.y - tip + 12.dp.toPx())
                    close()
                }
                drawPath(pointer, accent)
            }

            // Letters are placed on the turning card but drawn upright so they stay readable. Their
            // origins lie well inside the canvas, so the text layout never runs past an edge.
            val letterRadius = tickOuter - 34.dp.toPx()
            for ((bearing, layout) in letters) {
                val a = Math.toRadians(bearing + cardAngle.value)
                val cx = center.x + letterRadius * sin(a).toFloat()
                val cy = center.y - letterRadius * cos(a).toFloat()
                drawText(
                    textLayoutResult = layout,
                    color = if (bearing == 0.0) accent else tickColor,
                    topLeft = Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedVisibility(visible = locked) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = accent, modifier = Modifier.size(24.dp))
            }
            Text(
                text = heading?.let { headingText(it) } ?: "--",
                // Left to right in Hebrew too, or the degree sign lands in front of the number.
                style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Ltr),
                color = onFace,
            )
            if (heading != null) {
                Text(
                    text = cardinal(heading),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun statusText(status: CompassStatus): String = when (status) {
    CompassStatus.WAITING -> "Starting the compass..."
    CompassStatus.CALIBRATE -> "Wave the phone in a figure 8 a few times"
    CompassStatus.INTERFERENCE -> "Magnetic interference: step away from metal, cars and electronics"
    CompassStatus.SETTLING -> "Hold the phone still..."
    CompassStatus.LOCKED -> "North is set. Start when you are ready."
}

/** "Accuracy ±4° · Field 47 µT", leaving out what the sensors do not report. */
private fun detailText(reading: CompassReading?): String? {
    if (reading == null) return null
    val parts = ArrayList<String>()
    reading.accuracyDeg?.let { parts.add("Accuracy ±" + it.roundToInt() + "°") }
    reading.fieldUt?.let { parts.add("Field " + it.roundToInt() + " µT") }
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}

private fun headingText(deg: Double): String = String.format(Locale.US, "%d°", deg.roundToInt() % 360)

private val CARDINALS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

private val CARDINAL_WORDS =
    listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")

private fun cardinalIndex(deg: Double): Int = ((deg / 45.0).roundToInt() % 8 + 8) % 8

private fun cardinal(deg: Double): String = CARDINALS[cardinalIndex(deg)]

private fun dialDescription(reading: CompassReading?): String {
    val heading = reading?.headingDeg ?: return "Compass, waiting for a reading"
    val base = "Compass: the phone points " + heading.roundToInt() % 360 + " degrees, " +
        CARDINAL_WORDS[cardinalIndex(heading)]
    return if (reading.status == CompassStatus.LOCKED) "$base. North is set." else base
}
