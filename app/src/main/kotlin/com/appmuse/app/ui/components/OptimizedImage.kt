package com.appmuse.app.ui.components

import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Shape
import com.appmuse.app.ui.theme.AppMotion
import com.appmuse.app.ui.theme.AppShapes

/**
 * A graceful image slot — rounded corners, an optional aspect ratio, and a designed
 * fallback so an empty/failed image is never a raw broken box. When `painter` is null it
 * shows a shimmering placeholder tinted by the theme (the loading look) behind a soft icon;
 * supply a `painter` (e.g. `painterResource(...)`) to show the actual image cropped to fill.
 *
 * NOTE: the template has no async-network-image dependency (Coil) yet, so this primitive
 * does NOT fetch a URL itself — the placeholder/shimmer is the null/empty state, and the
 * caller passes an already-resolved Painter. Wire it to Coil's AsyncImage (with this same
 * shimmer as the `loading`/`error` slot) once `io.coil-kt:coil-compose` is added to
 * build.gradle.kts. Reduced motion (OS "Remove animations") freezes the shimmer to a static
 * tint.
 */
@Composable
fun OptimizedImage(
    painter: Painter?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    aspectRatio: Float? = null,
    shape: Shape = AppShapes.medium,
    fallbackIcon: ImageVector = Icons.Outlined.Image,
) {
    val base = modifier
        .let { if (aspectRatio != null) it.aspectRatio(aspectRatio) else it }
        .clip(shape)

    Box(modifier = base, contentAlignment = Alignment.Center) {
        if (painter != null) {
            ComposeImage(
                painter = painter,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            // Placeholder: shimmer fill + a soft fallback icon so the empty state reads as
            // intentional rather than broken.
            Box(Modifier.fillMaxSize().background(rememberImageShimmerBrush()))
            Icon(
                fallbackIcon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

/**
 * Animated shimmer brush for the loading/empty fill — a diagonal sweep of the onSurface role
 * between a low-alpha base and a brighter highlight. Frozen to a flat gradient under reduced
 * motion. File-local so this primitive stays self-contained.
 */
@Composable
private fun rememberImageShimmerBrush(): Brush {
    val cs = MaterialTheme.colorScheme
    val base = cs.onSurface.copy(alpha = 0.06f)
    val highlight = cs.onSurface.copy(alpha = 0.16f)
    val colors = listOf(base, highlight, base)

    if (rememberImageReducedMotion()) {
        return Brush.linearGradient(colors)
    }

    val transition = rememberInfiniteTransition(label = "image-shimmer")
    val translate by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = AppMotion.slow * 2, easing = AppMotion.standardEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "image-shimmer-translate",
    )
    return Brush.linearGradient(
        colors = colors,
        start = Offset(translate - 300f, translate - 300f),
        end = Offset(translate, translate),
    )
}

/**
 * Reads the OS "Remove animations" / reduce-motion setting via
 * `Settings.Global.ANIMATOR_DURATION_SCALE` (0 == animations off). When on, the shimmer is a
 * static tint. File-local copy keeps this primitive self-contained.
 */
@Composable
private fun rememberImageReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}
