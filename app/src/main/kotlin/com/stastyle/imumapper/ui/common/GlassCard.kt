package com.stastyle.imumapper.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.stastyle.imumapper.ui.theme.imuColors

/** The colouring of a [GlassCard]. */
enum class CardTone {
    /** The translucent navy card. */
    Normal,

    /** A blue card for something to act on, such as the update banner. */
    Accent,

    /** A red card for a failure the user should read. */
    Error,
}

/**
 * The app's card: a translucent fill with a 1 dp border in the medium shape, replacing M3 `Card`.
 * Like `Card` it adds no padding, so `Card { Column(Modifier.padding(16.dp)) { … } }` becomes
 * `GlassCard { Column(Modifier.padding(16.dp)) { … } }`. [highlighted] draws the strong border (a
 * trip that is recording or busy). With [onClick] the whole card is clickable, and [onLongClick]
 * and the two labels, which TalkBack reads as the actions, apply too; without [onClick] they are
 * ignored, so a card is never long-press-only.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    tone: CardTone = CardTone.Normal,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    onLongClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = MaterialTheme.imuColors
    val shape = MaterialTheme.shapes.medium
    val fill = when (tone) {
        CardTone.Normal -> colors.cardFill
        CardTone.Accent -> scheme.primaryContainer.copy(alpha = 0.88f)
        CardTone.Error -> scheme.errorContainer.copy(alpha = 0.88f)
    }
    val border = when {
        tone == CardTone.Error -> scheme.error.copy(alpha = 0.6f)
        highlighted || tone == CardTone.Accent -> colors.cardBorderStrong
        else -> colors.cardBorder
    }
    val contentColor = when (tone) {
        CardTone.Normal -> scheme.onSurface
        CardTone.Accent -> scheme.onPrimaryContainer
        CardTone.Error -> scheme.onErrorContainer
    }
    val click = if (onClick == null) {
        Modifier
    } else {
        Modifier.combinedClickable(
            onClickLabel = onClickLabel,
            onLongClickLabel = onLongClickLabel,
            onLongClick = onLongClick,
            onClick = onClick,
        )
    }
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Column(
            modifier = modifier
                .clip(shape)
                .background(fill)
                .border(1.dp, border, shape)
                .then(click),
            content = content,
        )
    }
}
