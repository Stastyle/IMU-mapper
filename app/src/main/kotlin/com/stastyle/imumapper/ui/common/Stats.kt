package com.stastyle.imumapper.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One tile of a [StatGrid]. [icon] and [valueIcon] are decorative; a drawable resource can be
 * passed as `ImageVector.vectorResource(R.drawable.ic_magnet)`. [contentDescription], when set,
 * replaces everything TalkBack would read for the tile. [valueColor] tints the value (and
 * [valueIcon]), for a state such as "OK" or "Stalled"; unspecified means `onSurface`.
 */
@Immutable
data class StatTileData(
    val label: String,
    val value: String,
    val icon: ImageVector? = null,
    val detail: String? = null,
    val contentDescription: String? = null,
    val valueColor: Color = Color.Unspecified,
    val valueIcon: ImageVector? = null,
)

// The tile's inner layout, shared by StatTile and StatGrid's measuring so the two never disagree.
private val IconSize = 18.dp
private val IconGap = 6.dp
private val ValueIconGap = 4.dp
private val FramePaddingH = 12.dp
private val FramePaddingV = 10.dp
private const val SPOKEN_NO_VALUE = "not available"

private object StatStyles {
    val label: TextStyle @Composable get() = MaterialTheme.typography.labelMedium
    val value: TextStyle
        @Composable get() = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Ltr)
    // A detail is often signs and digits only ("-0.7 … +5.0"), which has no letters to take a direction
    // from; ContentOrLtr keeps it in reading order on Hebrew phones.
    val detail: TextStyle
        @Composable get() = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.ContentOrLtr)
}

/**
 * A labelled number: the icon inline with the label on the first line, the value below it (left to
 * right, one line, never ellipsized; [StatGrid] makes the columns wide enough), and an optional
 * [detail] below that, which may wrap. TalkBack reads the tile as one item, and a [NO_VALUE] value
 * as "not available". [framed] draws the tile's own rounded panel; leave it off inside a card that
 * already separates its stats.
 */
@Composable
fun StatTile(
    icon: ImageVector?,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    contentDescription: String? = null,
    valueColor: Color = Color.Unspecified,
    valueIcon: ImageVector? = null,
    framed: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val frame = if (framed) {
        val shape = MaterialTheme.shapes.small
        Modifier
            .background(scheme.surfaceContainerHigh.copy(alpha = 0.7f), shape)
            .border(1.dp, scheme.outlineVariant, shape)
            .padding(horizontal = FramePaddingH, vertical = FramePaddingV)
    } else {
        Modifier
    }
    val tileSemantics = if (contentDescription != null) {
        Modifier.clearAndSetSemantics { this.contentDescription = contentDescription }
    } else {
        Modifier.semantics(mergeDescendants = true) {}
    }
    val valueTint = valueColor.takeOrElse { scheme.onSurface }
    Column(modifier = modifier.then(tileSemantics).then(frame)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = scheme.secondary, modifier = Modifier.size(IconSize))
                Spacer(Modifier.size(IconGap))
            }
            Text(label, style = StatStyles.label, color = scheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (valueIcon != null) {
                Icon(valueIcon, contentDescription = null, tint = valueTint, modifier = Modifier.size(IconSize))
                Spacer(Modifier.size(ValueIconGap))
            }
            Text(
                value,
                style = StatStyles.value,
                color = valueTint,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
                modifier = if (value == NO_VALUE) {
                    Modifier.semantics { this.contentDescription = SPOKEN_NO_VALUE }
                } else {
                    Modifier
                },
            )
        }
        if (detail != null) {
            Text(detail, style = StatStyles.detail, color = scheme.onSurfaceVariant)
        }
    }
}

/** [StatTile] from its data. */
@Composable
fun StatTile(data: StatTileData, modifier: Modifier = Modifier, framed: Boolean = true) {
    StatTile(
        icon = data.icon,
        label = data.label,
        value = data.value,
        modifier = modifier,
        detail = data.detail,
        contentDescription = data.contentDescription,
        valueColor = data.valueColor,
        valueIcon = data.valueIcon,
        framed = framed,
    )
}

/**
 * [tiles] in equal columns: as many as fit, up to [maxColumns], found by measuring every label,
 * value and detail word in the tile's own text styles (so large and nonlinearly scaled fonts drop
 * to fewer columns instead of clipping), then spread evenly over the rows that needs. Tiles in a
 * row share its height.
 */
@Composable
fun StatGrid(
    tiles: List<StatTileData>,
    modifier: Modifier = Modifier,
    maxColumns: Int = 3,
    framed: Boolean = true,
    gap: Dp = 8.dp,
) {
    if (tiles.isEmpty()) return
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val density = LocalDensity.current
    val labelStyle = StatStyles.label
    val valueStyle = StatStyles.value
    val detailStyle = StatStyles.detail
    val neededDp = remember(tiles, framed, measurer, density, labelStyle, valueStyle, detailStyle) {
        fun widthDp(text: String, style: TextStyle): Float = with(density) {
            measurer.measure(text, style, maxLines = 1, softWrap = false).size.width.toDp().value
        }
        val frame = if (framed) (FramePaddingH * 2 + 2.dp).value else 0f
        val iconDp = (IconSize + IconGap).value
        val valueIconDp = (IconSize + ValueIconGap).value
        tiles.maxOf { tile ->
            val label = widthDp(tile.label, labelStyle) + if (tile.icon != null) iconDp else 0f
            val value = widthDp(tile.value, valueStyle) + if (tile.valueIcon != null) valueIconDp else 0f
            // A detail may wrap between words but must not split one, such as "+5.0".
            val detail = tile.detail?.split(' ')?.maxOfOrNull { widthDp(it, detailStyle) } ?: 0f
            maxOf(label, value, detail) + frame
        }
    }
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val available = if (constraints.hasBoundedWidth) maxWidth.value else Float.POSITIVE_INFINITY
        val columns = balancedColumns(statColumns(available, gap.value, neededDp, maxColumns), tiles.size)
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            tiles.chunked(columns).forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    row.forEach { tile ->
                        StatTile(
                            data = tile,
                            framed = framed,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                    // Keep the last row's tiles as wide as the others.
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
