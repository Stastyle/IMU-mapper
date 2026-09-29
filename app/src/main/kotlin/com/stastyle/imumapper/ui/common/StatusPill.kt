package com.stastyle.imumapper.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stastyle.imumapper.ui.theme.imuColors

/** What a [StatusPill] says about its subject, which picks its colours. */
enum class StatusTone { Info, Success, Warning, Error, Neutral }

/**
 * A small status badge ("Processed", "Failed", "Up to date"). Each tone pairs a dark container with
 * a light text colour that reads at 4.5:1 or better; never bright text on its own container. With
 * [onClick] it becomes a button with a 48 dp touch target around the pill, labelled for TalkBack by
 * [onClickLabel] ("Show error"). [leading] goes before the text, such as a decorative dot.
 */
@Composable
fun StatusPill(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val (container, content) = pillColors(tone)
    if (onClick == null) {
        PillBody(text, container, content, leading, modifier)
        return
    }
    val interaction = remember { MutableInteractionSource() }
    // The click area is the 48 dp box; the ripple stays on the pill so the feedback matches its shape.
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = onClickLabel,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        PillBody(text, container, content, leading, feedback = Modifier.indication(interaction, ripple()))
    }
}

@Composable
private fun pillColors(tone: StatusTone): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    val colors = MaterialTheme.imuColors
    return when (tone) {
        StatusTone.Info -> colors.brandFill to colors.onBrandFill
        StatusTone.Success -> colors.successContainer to colors.success
        StatusTone.Warning -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        StatusTone.Error -> scheme.errorContainer to scheme.onErrorContainer
        StatusTone.Neutral -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
}

@Composable
private fun PillBody(
    text: String,
    container: Color,
    content: Color,
    leading: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    feedback: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .then(feedback)
            .background(container)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (leading != null) CompositionLocalProvider(LocalContentColor provides content, content = leading)
        Text(
            text,
            // labelMedium is 12 sp; the pill never goes below 11 sp.
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = content,
            maxLines = 1,
        )
    }
}
