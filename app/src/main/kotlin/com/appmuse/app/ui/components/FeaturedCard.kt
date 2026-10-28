package com.appmuse.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.appmuse.app.ui.theme.AppElevation
import com.appmuse.app.ui.theme.AppShapes
import com.appmuse.app.ui.theme.AppSpacing

/**
 * The full-width hero metric card — the single biggest "this app was designed" signal at
 * the top of a primary screen. A title + an optional big primaryValue (with a small
 * secondaryLabel unit) + a subtitle + an optional leading icon + an optional cta slot,
 * laid over the brand primary→secondary gradient (default) OR a background `image` with a
 * top-to-bottom dark scrim for legibility. Every text/icon uses the onPrimary role so it
 * stays readable on the gradient or the darkened image — never hardcode white. Tap-enabled
 * when `onTap` is set. `image` takes a Painter (a local/drawable painter — the template has
 * no async-image dependency yet), so the default brand gradient is the common path.
 */
@Composable
fun FeaturedCard(
    title: String,
    modifier: Modifier = Modifier,
    primaryValue: String? = null,
    secondaryLabel: String? = null,
    subtitle: String? = null,
    icon: ImageVector? = null,
    image: Painter? = null,
    height: Dp = 200.dp,
    onTap: (() -> Unit)? = null,
    cta: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val cardModifier = modifier
        .fillMaxWidth()
        .height(height)
        .let { if (onTap != null) it.clickable(onClick = onTap) else it }

    ElevatedCard(
        modifier = cardModifier,
        shape = AppShapes.large,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = AppElevation.raised),
    ) {
        Box(Modifier.fillMaxSize()) {
            // Background layer — either the brand gradient or the image + legibility scrim.
            if (image != null) {
                Image(
                    painter = image,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(AppShapes.large),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(cs.scrim.copy(alpha = 0.15f), cs.scrim.copy(alpha = 0.65f)),
                            ),
                        ),
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.linearGradient(listOf(cs.primary, cs.secondary))),
                )
            }

            // Foreground content — top row (icon + title), the big value, then subtitle + cta.
            Column(
                modifier = Modifier.fillMaxSize().padding(AppSpacing.lg),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .background(cs.onPrimary.copy(alpha = 0.16f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(icon, contentDescription = null, tint = cs.onPrimary)
                        }
                        Spacer(Modifier.width(AppSpacing.sm))
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = cs.onPrimary.copy(alpha = 0.9f),
                    )
                }

                if (primaryValue != null) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            primaryValue,
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = cs.onPrimary,
                        )
                        if (secondaryLabel != null) {
                            Spacer(Modifier.width(AppSpacing.xs))
                            Text(
                                secondaryLabel,
                                style = MaterialTheme.typography.titleMedium,
                                color = cs.onPrimary.copy(alpha = 0.85f),
                                modifier = Modifier.padding(bottom = AppSpacing.xs),
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onPrimary.copy(alpha = 0.85f),
                        )
                    } else {
                        Spacer(Modifier.width(0.dp))
                    }
                    if (cta != null) cta()
                }
            }
        }
    }
}
