package com.appmuse.app.ui.components

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.appmuse.app.ui.theme.AppElevation
import com.appmuse.app.ui.theme.AppMotion
import com.appmuse.app.ui.theme.AppShapes
import com.appmuse.app.ui.theme.AppSpacing
import kotlinx.coroutines.delay

/**
 * A transient full-screen success confirmation — a dimmed scrim behind a centered rounded
 * card with a checkmark that scales in + a short message, then auto-dismisses after a hold.
 * It is DECLARATIVE: render it in your tree and drive it with `visible`; it raises
 * `onDismiss` after `holdMillis` so you can flip your state back to false (no imperative
 * "show a dialog" call). Colors come from the theme (primary checkmark, surface card).
 * Reduced motion (OS "Remove animations") shows the card and check already at full scale.
 */
@Composable
fun SuccessOverlay(
    visible: Boolean,
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    holdMillis: Long = 1400L,
) {
    val cs = MaterialTheme.colorScheme
    val reduceMotion = rememberOverlayReducedMotion()

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(if (reduceMotion) 0 else AppMotion.quick)),
        exit = fadeOut(tween(if (reduceMotion) 0 else AppMotion.quick)),
        modifier = modifier,
    ) {
        // Dimmed scrim over the whole screen.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(cs.scrim.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center,
        ) {
            // Checkmark scales in once the overlay becomes visible; hold then auto-dismiss.
            val checkScale = remember(visible) { Animatable(if (reduceMotion) 1f else 0.4f) }
            LaunchedEffect(visible) {
                if (!visible) return@LaunchedEffect
                if (!reduceMotion) {
                    checkScale.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(AppMotion.standard, easing = AppMotion.emphasized),
                    )
                }
                delay(holdMillis)
                onDismiss()
            }

            Surface(
                shape = AppShapes.extraLarge,
                color = cs.surface,
                tonalElevation = AppElevation.high,
                shadowElevation = AppElevation.high,
                modifier = Modifier.widthIn(min = 200.dp, max = 320.dp),
            ) {
                Column(
                    modifier = Modifier.padding(AppSpacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .scale(checkScale.value)
                            .background(cs.primary.copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = cs.primary,
                            modifier = Modifier.size(48.dp),
                        )
                    }
                    Spacer(Modifier.height(AppSpacing.base))
                    Text(
                        message,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.onSurface,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * Reads the OS "Remove animations" / reduce-motion setting via
 * `Settings.Global.ANIMATOR_DURATION_SCALE` (0 == animations off). When on, the card +
 * checkmark render at full scale immediately. File-local copy keeps this self-contained.
 */
@Composable
private fun rememberOverlayReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}
