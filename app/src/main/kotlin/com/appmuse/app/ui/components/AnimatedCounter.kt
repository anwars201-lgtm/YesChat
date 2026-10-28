package com.appmuse.app.ui.components

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import com.appmuse.app.ui.theme.AppMotion
import java.text.NumberFormat

/**
 * A number that counts up from `from` (default 0) to `value` once on mount, then settles —
 * the small flourish that makes a dashboard metric feel alive. The displayed number is
 * locale-formatted (thousands separators per the device locale) with optional `decimals`,
 * `prefix`, and `suffix`. It inherits its text style + color from the enclosing context
 * (LocalTextStyle / LocalContentColor), so dropping it into a `GradientStatCard.valueContent`
 * slot picks up that card's headline/onPrimary styling automatically. Reduced motion (OS
 * "Remove animations") skips the tween and renders the final value immediately.
 */
@Composable
fun AnimatedCounter(
    value: Double,
    modifier: Modifier = Modifier,
    from: Double = 0.0,
    decimals: Int = 0,
    prefix: String = "",
    suffix: String = "",
    durationMillis: Int = AppMotion.slow,
) {
    val reduceMotion = rememberCounterReducedMotion()

    // Locale-aware formatter (thousands separators + fixed fraction digits).
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale, decimals) {
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = decimals
            maximumFractionDigits = decimals
        }
    }

    val animated = remember(value) { Animatable(if (reduceMotion) value.toFloat() else from.toFloat()) }
    LaunchedEffect(value, reduceMotion) {
        if (reduceMotion) {
            animated.snapTo(value.toFloat())
        } else {
            animated.animateTo(
                targetValue = value.toFloat(),
                animationSpec = tween(durationMillis = durationMillis.coerceIn(0, AppMotion.quick), easing = AppMotion.standardEasing),
            )
        }
    }

    // Re-format only when the live animated value changes; affixes applied per-frame.
    val display by remember(formatter, prefix, suffix) {
        derivedStateOf { prefix + formatter.format(animated.value.toDouble()) + suffix }
    }

    Text(
        text = display,
        modifier = modifier,
        style = LocalTextStyle.current,
        color = LocalContentColor.current,
        textAlign = TextAlign.Start,
    )
}

/**
 * Reads the OS "Remove animations" / reduce-motion setting via
 * `Settings.Global.ANIMATOR_DURATION_SCALE` (0 == animations off). When on, the counter
 * jumps straight to the final value. File-local copy keeps this primitive self-contained.
 */
@Composable
private fun rememberCounterReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}
