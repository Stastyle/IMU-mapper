package com.stastyle.imumapper.ui.viewer

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stastyle.imumapper.pipeline.survey.LegMeasure
import java.time.ZoneId
import kotlin.math.max

/**
 * Survey mode's panel under the plan: what is selected and its numbers, the scrubber that moves the
 * cursor along the path, and the actions. It only renders [survey]; every change goes back through
 * the callbacks, so the rules stay in SurveyController where they are tested.
 */
@Composable
fun SurveyPanel(
    survey: SurveyUi,
    startedAtEpochMs: Long?,
    onClear: () -> Unit,
    onCursor: (distanceM: Double) -> Unit,
    onStep: (delta: Int) -> Unit,
    onAddStation: () -> Unit,
    onMoveHere: () -> Unit,
    onShowLegs: () -> Unit,
    onSetAzimuth: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = survey.state
    val readOnly = state.readOnly
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 3.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            SelectionRow(survey.readout, canClear = state.selection != SurveySelection.None, onClear = onClear)
            SelectionReadout(survey.readout, survey.magnetic)
            if (readOnly) {
                Text(
                    "Read-only: nothing is saved",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Scrubber(survey, onCursor, onStep)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val timeline = survey.geometry.timeline
                Text(
                    SurveyFormat.cursorLabel(
                        startedAtEpochMs,
                        state.cursorNs - timeline.startNs,
                        timeline.distanceAt(state.cursorNs),
                        ZoneId.systemDefault(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onMoveHere,
                    enabled = !readOnly && SurveyController.movableStationId(state) != null,
                ) { Text("Move here") }
                OutlinedButton(onClick = onAddStation, enabled = !readOnly) { Text("+ Station") }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onShowLegs, enabled = survey.legs.isNotEmpty(), modifier = Modifier.weight(1f)) {
                    Text("Legs")
                }
                Button(
                    onClick = onSetAzimuth,
                    enabled = !readOnly && SurveyController.selectionEnds(state, survey.geometry) != null,
                    modifier = Modifier.weight(1f),
                ) { Text("Set azimuth") }
            }
        }
    }
}

@Composable
private fun SelectionRow(readout: SurveyReadout?, canClear: Boolean, onClear: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            readout?.names?.joinToString(" › ") ?: "Tap stations, or the path between them",
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onClear, enabled = canClear) { Text("Clear") }
    }
}

/** The numbers; a long press copies them as one line (SurveyFormat.copyLine) for a note or a message. */
@Composable
private fun SelectionReadout(readout: SurveyReadout?, magnetic: Boolean) {
    val copy = copyLineOf(readout, magnetic)
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(copy) {
                if (copy != null) {
                    detectTapGestures(
                        onLongPress = {
                            clipboard.setText(AnnotatedString(copy))
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                    )
                }
            },
    ) {
        when (readout) {
            null -> Unit
            is SurveyReadout.First -> Text(
                "Tap another station, or the path",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is SurveyReadout.Chain -> {
                val measure = readout.measure
                LegNumbers(measure.straight, magnetic)
                if (measure.hops.size > 1) {
                    Text(
                        "Straight line above · Σ hops ${SurveyFormat.metres(measure.hopLengthSumM)} · " +
                            "Σ path ${SurveyFormat.metres(measure.hopPathSumM)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Column(modifier = Modifier.heightIn(max = HOPS_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
                        measure.hops.forEachIndexed { i, hop ->
                            val from = readout.names.getOrElse(i) { "" }
                            val to = readout.names.getOrElse(i + 1) { "" }
                            Text(
                                "$from › $to: ${SurveyFormat.metres(hop.lengthM)} · " +
                                    "${SurveyFormat.azimuth(hop.azimuthDeg, magnetic)} · " +
                                    SurveyFormat.signedDegrees(hop.slopeDeg),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            is SurveyReadout.Stretch -> {
                LegNumbers(readout.measure.leg, magnetic)
                Text(SurveyFormat.stretchLine(readout.measure, magnetic), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Headline (length, azimuth, slope) and the details line; a slope over a short run is greyed. */
@Composable
private fun LegNumbers(leg: LegMeasure, magnetic: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            SurveyFormat.metres(leg.lengthM),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            SurveyFormat.azimuth(leg.azimuthDeg, magnetic),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            SurveyFormat.slope(leg.slopeDeg),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (leg.slopeUncertain) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = UNCERTAIN_ALPHA)
            } else {
                Color.Unspecified
            },
        )
    }
    Text(
        SurveyFormat.details(leg),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Slider over distance along the path, so standing still takes no room; the arrows step one point. */
@Composable
private fun Scrubber(survey: SurveyUi, onCursor: (Double) -> Unit, onStep: (Int) -> Unit) {
    val timeline = survey.geometry.timeline
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onStep(-1) }) {
            Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous point")
        }
        Slider(
            value = timeline.distanceAt(survey.state.cursorNs).toFloat(),
            onValueChange = { onCursor(it.toDouble()) },
            // A path that never moved still needs a range the slider can draw.
            valueRange = 0f..max(timeline.lengthM, MIN_SCRUB_RANGE_M).toFloat(),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onStep(1) }) {
            Icon(Icons.Filled.ChevronRight, contentDescription = "Next point")
        }
    }
}

private fun copyLineOf(readout: SurveyReadout?, magnetic: Boolean): String? = when (readout) {
    is SurveyReadout.Chain ->
        SurveyFormat.copyLine(readout.names.first(), readout.names.last(), readout.measure.straight, magnetic)
    is SurveyReadout.Stretch ->
        SurveyFormat.copyLine(readout.names.first(), readout.names.last(), readout.measure.leg, magnetic)
    is SurveyReadout.First, null -> null
}

private const val MIN_SCRUB_RANGE_M = 0.01
private const val UNCERTAIN_ALPHA = 0.6f
private val HOPS_MAX_HEIGHT = 96.dp
