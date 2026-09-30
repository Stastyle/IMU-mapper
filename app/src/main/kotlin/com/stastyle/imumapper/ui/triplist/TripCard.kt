package com.stastyle.imumapper.ui.triplist

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.stastyle.imumapper.capture.RecordingController
import com.stastyle.imumapper.data.db.TripStatus
import com.stastyle.imumapper.pipeline.core.TripMode
import com.stastyle.imumapper.render.CanvasPalette
import com.stastyle.imumapper.render.PathProgress
import com.stastyle.imumapper.render.PathThumbnail
import com.stastyle.imumapper.render.canvasPalette
import com.stastyle.imumapper.ui.common.GlassCard
import com.stastyle.imumapper.ui.common.NO_VALUE
import com.stastyle.imumapper.ui.common.StatGrid
import com.stastyle.imumapper.ui.common.StatTileData
import com.stastyle.imumapper.ui.common.StatusPill
import com.stastyle.imumapper.ui.common.StatusTone
import com.stastyle.imumapper.ui.common.formatDistance
import com.stastyle.imumapper.ui.common.formatDuration
import com.stastyle.imumapper.ui.theme.imuColors
import kotlin.math.min

/** Where a card's thumbnail is: being made, drawn, or not available (no run, or it could not be made). */
private sealed interface ThumbnailLoad {
    data object Loading : ThumbnailLoad
    data object None : ThumbnailLoad

    @Immutable
    data class Ready(val thumbnail: PathThumbnail) : ThumbnailLoad
}

/**
 * One trip: its path thumbnail, name and actions button, date and mode, status, and the run's duration, distance and
 * steps. Tapping opens the trip (or the running recording); a long press or the actions button opens the actions
 * sheet. The border is strong while the trip is recording or busy.
 *
 * The thumbnail is asked for only while the card is composed, so only visible cards make one; [cachedThumbnail] gives
 * a card scrolled back into view its thumbnail on the first frame.
 */
@Composable
internal fun TripCard(
    item: TripListItem,
    busy: Boolean,
    onOpen: () -> Unit,
    onActions: () -> Unit,
    onShowError: () -> Unit,
    cachedThumbnail: (tripId: Long, runId: Int) -> PathThumbnail?,
    loadThumbnail: suspend (tripId: Long, runId: Int) -> PathThumbnail?,
    modifier: Modifier = Modifier,
) {
    val runId = item.runId
    // Keyed on the run: a re-process shows the old path until the new one is ready rather than a blank tile.
    val thumbnail by produceState(initialThumbnail(item.id, runId, cachedThumbnail), item.id, runId) {
        value = if (runId == null) ThumbnailLoad.None else loadedThumbnail(loadThumbnail(item.id, runId))
    }
    val recording = item.trip.status == TripStatus.RECORDING
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        highlighted = recording || busy,
        onClick = onOpen,
        onLongClick = onActions,
        onClickLabel = "Open trip",
        onLongClickLabel = "More actions",
    ) {
        Row(modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 4.dp)) {
            ThumbnailTile(item.trip.mode, thumbnail, Modifier.padding(top = 4.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                TitleRow(item.trip.name, onActions)
                // The actions button's own padding sets the title row's end; the lines below match the thumbnail's.
                Column(modifier = Modifier.padding(end = 8.dp)) {
                    MetaRow(item)
                    StatusLine(item.trip.status, busy, onShowError)
                    if (item.endedUnexpectedly) EndedUnexpectedlyLine()
                }
            }
        }
        StatGrid(
            tiles = remember(item.durationS, item.distanceM, item.steps) { statTiles(item) },
            maxColumns = 3,
            framed = false,
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 12.dp),
        )
    }
}

private fun initialThumbnail(
    tripId: Long,
    runId: Int?,
    cached: (tripId: Long, runId: Int) -> PathThumbnail?,
): ThumbnailLoad =
    if (runId == null) ThumbnailLoad.None else cached(tripId, runId)?.let(::loadedThumbnail) ?: ThumbnailLoad.Loading

/** A path with no vertices draws nothing, so it gets the mode icon like a trip without a run. */
private fun loadedThumbnail(thumbnail: PathThumbnail?): ThumbnailLoad =
    if (thumbnail != null && thumbnail.size > 0) ThumbnailLoad.Ready(thumbnail) else ThumbnailLoad.None

/** Duration, distance and steps from one run, per [TripListItem.of]; a dash when unknown. */
private fun statTiles(item: TripListItem): List<StatTileData> = listOf(
    StatTileData(label = "Duration", value = formatDuration(item.durationS), icon = Icons.Filled.Timer),
    StatTileData(label = "Distance", value = formatDistance(item.distanceM), icon = Icons.Filled.Route),
    StatTileData(
        label = "Steps",
        value = item.steps?.toString() ?: NO_VALUE,
        icon = Icons.AutoMirrored.Filled.DirectionsWalk,
    ),
)

@Composable
private fun TitleRow(name: String, onActions: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            name,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onActions) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = "More actions for $name",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "Sep 29, 2026 10:47 PM · Pocket": separate texts, so the date may shorten but the mode never does. */
@Composable
private fun MetaRow(item: TripListItem) {
    val style = MaterialTheme.typography.bodySmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            TripFormat.date(item.trip.startedAtEpochMs),
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(" · ", style = style, color = color, maxLines = 1, softWrap = false)
        Text(TripFormat.modeLabel(item.trip.mode), style = style, color = color, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun StatusLine(status: TripStatus, busy: Boolean, onShowError: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(top = 6.dp)
            .heightIn(min = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier
                    .size(20.dp)
                    .semantics { contentDescription = "Working on this trip" },
            )
            return@Row
        }
        when (status) {
            TripStatus.RECORDING -> StatusPill("Recording", StatusTone.Warning, leading = { PulsingDot() })
            TripStatus.RECORDED -> StatusPill("Recorded", StatusTone.Neutral)
            TripStatus.PROCESSED -> StatusPill("Processed", StatusTone.Info)
            TripStatus.FAILED -> StatusPill(
                "Failed",
                StatusTone.Error,
                onClick = onShowError,
                onClickLabel = "Show error",
                leading = {
                    Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(14.dp))
                },
            )
        }
    }
}

/** Decorative: the pill already says "Recording". Drawn in the pill's text colour. */
@Composable
private fun PulsingDot() {
    val transition = rememberInfiniteTransition(label = "recording dot")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 800), RepeatMode.Reverse),
        label = "recording dot alpha",
    )
    val color = LocalContentColor.current
    Box(
        modifier = Modifier
            .size(8.dp)
            // Read in the layer, so the pulse redraws the dot without recomposing the card.
            .graphicsLayer { this.alpha = alpha }
            .background(color, CircleShape),
    )
}

@Composable
private fun EndedUnexpectedlyLine() {
    val color = MaterialTheme.colorScheme.tertiary
    Row(modifier = Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            RecordingController.ENDED_UNEXPECTEDLY_NOTE,
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}

/**
 * The 72 dp tile: the path, the mode icon without one, or an empty tile while it loads. Decorative for TalkBack. A
 * small map in the viewer's canvas colours, bordered so a light tile still reads as a panel on a white card.
 */
@Composable
private fun ThumbnailTile(mode: TripMode, load: ThumbnailLoad, modifier: Modifier = Modifier) {
    val shape = MaterialTheme.shapes.small
    val palette = canvasPalette()
    Box(
        modifier = modifier
            .size(72.dp)
            .clip(shape)
            .background(Color(palette.background))
            .border(1.dp, MaterialTheme.imuColors.cardBorder, shape),
        contentAlignment = Alignment.Center,
    ) {
        when (load) {
            is ThumbnailLoad.Ready ->
                // The drawn map is never mirrored, whatever the language (north up, east right).
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    PathThumbnailCanvas(load.thumbnail, palette, Modifier.fillMaxSize().padding(10.dp))
                }
            ThumbnailLoad.None -> Icon(
                TripFormat.modeIcon(mode),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(32.dp),
            )
            ThumbnailLoad.Loading -> Unit
        }
    }
}

/**
 * The thumbnail's segments in [palette]'s PROGRESS colours, each coloured by its first vertex as the viewer does, with
 * the green start and red end dots on top. The unit square is fitted into the canvas, centred.
 */
@Composable
private fun PathThumbnailCanvas(thumbnail: PathThumbnail, palette: CanvasPalette, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val side = min(size.width, size.height)
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        fun at(i: Int) = Offset(left + thumbnail.x[i] * side, top + thumbnail.y[i] * side)
        val stroke = 2.5.dp.toPx()
        for (i in 0 until thumbnail.size - 1) {
            drawLine(
                color = Color(PathProgress.color(thumbnail.progress[i].toDouble(), palette)),
                start = at(i),
                end = at(i + 1),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
        val dot = 3.5.dp.toPx()
        drawCircle(Color(palette.start), radius = dot, center = at(0))
        drawCircle(Color(palette.end), radius = dot, center = at(thumbnail.size - 1))
    }
}
