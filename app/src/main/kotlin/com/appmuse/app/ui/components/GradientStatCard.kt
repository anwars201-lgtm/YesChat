package com.appmuse.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.appmuse.app.ui.theme.AppShapes
import com.appmuse.app.ui.theme.AppSpacing

/**
 * A stat card on the brand gradient (default primary→secondary) — an icon chip, a big
 * value, and an optional trend `delta` (e.g. "↑ 12%"). Use it where a flat StatCard would
 * feel thin (a dashboard's headline metric). The value can be a plain `value` string OR a
 * `valueContent` slot so an `AnimatedCounter` drops straight in and inherits the right text
 * style (we provide the headline style + the onPrimary content color to the slot). All text
 * uses the onPrimary role so it reads on the gradient — never hardcode white. Tap-enabled
 * via `onTap`.
 */
@Composable
fun GradientStatCard(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    value: String? = null,
    delta: String? = null,
    onTap: (() -> Unit)? = null,
    valueContent: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val container = modifier
        .fillMaxWidth()
        .clip(AppShapes.large)
        .background(Brush.linearGradient(listOf(cs.primary, cs.secondary)))
        .let { if (onTap != null) it.clickable(onClick = onTap) else it }
        .padding(AppSpacing.base)

    Column(container) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).background(cs.onPrimary.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = cs.onPrimary)
            }
            Spacer(Modifier.width(AppSpacing.sm))
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = cs.onPrimary.copy(alpha = 0.9f),
            )
        }

        Spacer(Modifier.height(AppSpacing.md))

        // The big value — either a plain string or the supplied slot (so AnimatedCounter
        // fits). The slot inherits the headline style + onPrimary content color.
        CompositionLocalProvider(LocalContentColor provides cs.onPrimary) {
            ProvideTextStyle(
                MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            ) {
                when {
                    valueContent != null -> valueContent()
                    value != null -> Text(value, color = cs.onPrimary)
                    else -> Unit
                }
            }
        }

        if (delta != null) {
            Spacer(Modifier.height(AppSpacing.xs))
            Text(
                delta,
                style = MaterialTheme.typography.labelLarge,
                color = cs.onPrimary.copy(alpha = 0.85f),
            )
        }
    }
}
