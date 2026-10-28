package com.appmuse.app.ui.components

import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.appmuse.app.ui.theme.AppMotion
import com.appmuse.app.ui.theme.AppShapes
import com.appmuse.app.ui.theme.AppSpacing

/**
 * Skeleton placeholders for the loading moment — an animated shimmer that holds the
 * list's exact shape (avatar circle + two text bars) so the UI never jumps when data
 * arrives. Render `SkeletonList` in a list's loading branch instead of a bare spinner;
 * `SkeletonCard` is the single-row placeholder for one-off cards. The shimmer is a
 * hand-rolled animated Brush (no third-party lib) built from the onSurface role at low
 * alpha, so it follows the palette. Reduced-motion (OS "Remove animations") freezes the
 * sweep to a calm static tint — the layout is identical either way.
 */

@Composable
fun SkeletonList(count: Int = 5, modifier: Modifier = Modifier) {
    val brush = rememberShimmerBrush()
    Column(
        modifier = modifier.fillMaxWidth().padding(AppSpacing.base),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
    ) {
        repeat(count) { SkeletonCard(brush = brush) }
    }
}

@Composable
fun SkeletonCard(modifier: Modifier = Modifier, brush: Brush = rememberShimmerBrush()) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clipBackground()
            .padding(AppSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar circle — the leading slot of a typical list row.
        Box(Modifier.size(48.dp).background(brush, CircleShape))
        Spacer(Modifier.width(AppSpacing.md))
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            // Two text bars mirroring a title + subtitle line.
            Box(Modifier.fillMaxWidth(0.7f).height(14.dp).background(brush, AppShapes.extraSmall))
            Box(Modifier.fillMaxWidth(0.45f).height(12.dp).background(brush, AppShapes.extraSmall))
        }
    }
}

/** A soft surface so the placeholder reads as a card without competing with the shimmer. */
@Composable
private fun Modifier.clipBackground(): Modifier {
    val fill = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    return this.background(fill, AppShapes.large)
}

/**
 * The shared animated shimmer brush — a diagonal sweep of the onSurface role between a
 * low-alpha base and a brighter highlight. The sweep is driven by an infinite transition;
 * when reduced motion is on we return a flat static gradient (no transition armed) so no
 * animation runs at all. File-local so each skeleton in a list shares one phase.
 */
@Composable
internal fun rememberShimmerBrush(): Brush {
    val cs = MaterialTheme.colorScheme
    val base = cs.onSurface.copy(alpha = 0.06f)
    val highlight = cs.onSurface.copy(alpha = 0.16f)
    val colors = listOf(base, highlight, base)

    if (rememberReducedMotion()) {
        // Calm static tint — same colors, no movement.
        return Brush.linearGradient(colors)
    }

    val transition = rememberInfiniteTransition(label = "shimmer")
    val translate by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = AppMotion.slow * 2, easing = AppMotion.standardEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer-translate",
    )
    return Brush.linearGradient(
        colors = colors,
        start = Offset(translate - 300f, translate - 300f),
        end = Offset(translate, translate),
    )
}

/**
 * Reads the OS "Remove animations" / reduce-motion accessibility setting via
 * `Settings.Global.ANIMATOR_DURATION_SCALE` (0 == animations off). Honoured by every
 * entrance/shimmer in this file so the E1 reduced-motion contract holds. Remembered on
 * the context so it is read once per composition.
 */
@Composable
internal fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        scale == 0f
    }
}
