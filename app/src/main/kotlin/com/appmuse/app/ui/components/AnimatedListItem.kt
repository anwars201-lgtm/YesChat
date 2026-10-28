package com.appmuse.app.ui.components

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.appmuse.app.ui.theme.AppMotion
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Wraps a list item so the first paint of a list cascades in — a per-index fade + a small
 * upward slide, staggered so each row arrives just after the one above it. The delay is
 * `min(index * perItemStagger, maxTotalDelay)` so a long list doesn't drag (rows past the
 * cap all animate together). Wrap each row's content inside an `itemsIndexed` block. Reduced
 * motion (OS "Remove animations") renders every item at its final position+opacity
 * immediately, with no stagger.
 */
@Composable
fun AnimatedListItem(
    index: Int,
    modifier: Modifier = Modifier,
    perItemStaggerMillis: Int = 40,
    maxTotalDelayMillis: Int = 300,
    content: @Composable () -> Unit,
) {
    val reduceMotion = rememberItemReducedMotion()

    // 0f → 1f progress drives both fade (alpha) and slide ((1-p) * distance).
    val progress = remember(index) { Animatable(if (reduceMotion) 1f else 0f) }
    val slideDistancePx = with(LocalDensity.current) { 12.dp.toPx() }

    LaunchedEffect(index, reduceMotion) {
        if (reduceMotion) {
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        val delayMs = minOf(index * perItemStaggerMillis, maxTotalDelayMillis).toLong()
        delay(delayMs)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(AppMotion.standard, easing = AppMotion.standardEasing),
        )
    }

    val p = progress.value
    val yOffset = ((1f - p) * slideDistancePx).roundToInt()

    Box(
        modifier = modifier
            .offset { IntOffset(x = 0, y = yOffset) }
            .alpha(p),
    ) {
        content()
    }
}

/**
 * Reads the OS "Remove animations" / reduce-motion setting via
 * `Settings.Global.ANIMATOR_DURATION_SCALE` (0 == animations off). When on, items snap to
 * their final state with no stagger. File-local copy keeps this primitive self-contained.
 */
@Composable
private fun rememberItemReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}
