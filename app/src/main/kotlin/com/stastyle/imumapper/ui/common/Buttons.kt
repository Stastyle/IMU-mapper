package com.stastyle.imumapper.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stastyle.imumapper.ui.theme.imuColors

/**
 * The filled pill button, in [brandFill][com.stastyle.imumapper.ui.theme.ImuColors.brandFill] with
 * white content and M3's disabled colours. It replaces every filled `Button` except the red Stop
 * pill and error-filled buttons.
 */
@Composable
fun BrandButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = MaterialTheme.imuColors
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = colors.brandFill, contentColor = colors.onBrandFill),
        contentPadding = contentPadding,
        content = content,
    )
}

/**
 * A choice chip: a pill, brand-filled when selected, and outlined (in `outline`, which reads as a
 * boundary at 3:1) when not. The chip keeps M3's 48 dp touch target.
 */
@Composable
fun BrandFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = MaterialTheme.imuColors
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon?.let { icon ->
            @Composable { Icon(icon, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        },
        shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = colors.cardFill,
            labelColor = scheme.onSurface,
            iconColor = scheme.onSurfaceVariant,
            selectedContainerColor = colors.brandFill,
            selectedLabelColor = colors.onBrandFill,
            selectedLeadingIconColor = colors.onBrandFill,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = scheme.outline,
            selectedBorderColor = Color.Transparent,
        ),
    )
}

/**
 * A translucent dark circle of [size] with a thin border and a centred [icon], for the canvas and
 * recording controls. The touch target is at least 48 dp even when the circle is smaller, and
 * TalkBack reads [contentDescription] as a button.
 */
@Composable
fun RoundIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    enabled: Boolean = true,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val iconSize = if (size >= 56.dp) 28.dp else 20.dp
    RoundButtonFrame(onClick, contentDescription, modifier, size, enabled, contentColor) { tint ->
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** [RoundIconButton] showing a short [text] ("3D", "2D") instead of an icon. */
@Composable
fun RoundIconButton(
    onClick: () -> Unit,
    text: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    enabled: Boolean = true,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    RoundButtonFrame(onClick, contentDescription, modifier, size, enabled, contentColor) { tint ->
        Text(
            text,
            color = tint,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.SemiBold,
                textDirection = TextDirection.Ltr,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun RoundButtonFrame(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier,
    size: Dp,
    enabled: Boolean,
    contentColor: Color,
    content: @Composable (tint: Color) -> Unit,
) {
    val colors = MaterialTheme.imuColors
    val interaction = remember { MutableInteractionSource() }
    val tint = if (enabled) contentColor else contentColor.copy(alpha = 0.38f)
    // The outer box is the touch target; the ripple stays on the circle so the feedback matches it.
    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            // Replaces the text variant's own "3D" so TalkBack reads only the description.
            .clearAndSetSemantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .indication(interaction, ripple())
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f))
                .border(1.dp, colors.cardBorderStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            content(tint)
        }
    }
}

/**
 * A small read-only pill over the canvas ("5 m grid"). It is a plain Box, not a Surface, so taps and
 * drags pass through it to the canvas below.
 */
@Composable
fun GridScaleChip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.8f))
            .border(1.dp, MaterialTheme.imuColors.cardBorder, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Ltr),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
