package com.appmuse.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Palette — the single source of truth for app colors (the Kotlin equivalent of
 * the Flutter template's AppColors). The agent edits THESE values to match the
 * user's chosen design/colorPalette, then everything reads from MaterialTheme.
 * Never hardcode Color(...) in screens — use MaterialTheme.colorScheme.* or add
 * a token here.
 */
object AppColors {
    val primary = Color(0xFF6366F1) // indigo
    val secondary = Color(0xFF8B5CF6) // purple
    val error = Color(0xFFEF4444)
    val onPrimary = Color(0xFFFFFFFF)

    // Dark scheme
    val background = Color(0xFF0F172A)
    val surface = Color(0xFF1E293B)
    val onBackground = Color(0xFFF8FAFC)
    val onSurface = Color(0xFFE2E8F0)
    val surfaceVariant = Color(0xFF273449) // card/field fill on dark
    val onSurfaceVariant = Color(0xFF94A3B8) // labels/hints on dark
    val outline = Color(0xFF475569) // borders on dark

    // Light scheme
    val lightBackground = Color(0xFFF8FAFC)
    val lightSurface = Color(0xFFFFFFFF)
    val lightOnBackground = Color(0xFF0F172A)
    val lightOnSurface = Color(0xFF1E293B)
    val lightSurfaceVariant = Color(0xFFEEF1F6) // card/field fill on light
    val lightOnSurfaceVariant = Color(0xFF64748B) // labels/hints on light
    val lightOutline = Color(0xFFCBD5E1) // borders on light

    // Container + tertiary roles — DERIVED from primary/secondary so recoloring just
    // `primary`/`secondary` cascades to the whole chrome (the nav indicator pill, M3
    // tonal surfaces, tinted accent chips/cards). The agent recolors primary/secondary
    // in Todo 2 and these follow automatically; the on* colors use the near-black/near-
    // white text tones so contrast holds on any palette. surfaceTint is pinned so
    // elevated surfaces tint toward the brand instead of M3's indigo default.
    val tertiary = secondary
    val onTertiary = onPrimary

    // Light scheme containers (light tints of the accent; dark readable text on top)
    val lightPrimaryContainer = lerp(lightSurface, primary, 0.16f)
    val lightOnPrimaryContainer = lightOnBackground
    val lightSecondaryContainer = lerp(lightSurface, secondary, 0.16f)
    val lightOnSecondaryContainer = lightOnBackground

    // Dark scheme containers (deeper tints of the accent; light readable text on top)
    val darkPrimaryContainer = lerp(surface, primary, 0.30f)
    val darkOnPrimaryContainer = onBackground
    val darkSecondaryContainer = lerp(surface, secondary, 0.30f)
    val darkOnSecondaryContainer = onBackground
}

val DarkColors = darkColorScheme(
    primary = AppColors.primary,
    secondary = AppColors.secondary,
    tertiary = AppColors.tertiary,
    background = AppColors.background,
    surface = AppColors.surface,
    surfaceVariant = AppColors.surfaceVariant,
    surfaceTint = AppColors.primary,
    primaryContainer = AppColors.darkPrimaryContainer,
    onPrimaryContainer = AppColors.darkOnPrimaryContainer,
    secondaryContainer = AppColors.darkSecondaryContainer,
    onSecondaryContainer = AppColors.darkOnSecondaryContainer,
    error = AppColors.error,
    onPrimary = AppColors.onPrimary,
    onTertiary = AppColors.onTertiary,
    onBackground = AppColors.onBackground,
    onSurface = AppColors.onSurface,
    onSurfaceVariant = AppColors.onSurfaceVariant,
    outline = AppColors.outline,
)

val LightColors = lightColorScheme(
    primary = AppColors.primary,
    secondary = AppColors.secondary,
    tertiary = AppColors.tertiary,
    background = AppColors.lightBackground,
    surface = AppColors.lightSurface,
    surfaceVariant = AppColors.lightSurfaceVariant,
    surfaceTint = AppColors.primary,
    primaryContainer = AppColors.lightPrimaryContainer,
    onPrimaryContainer = AppColors.lightOnPrimaryContainer,
    secondaryContainer = AppColors.lightSecondaryContainer,
    onSecondaryContainer = AppColors.lightOnSecondaryContainer,
    error = AppColors.error,
    onPrimary = AppColors.onPrimary,
    onTertiary = AppColors.onTertiary,
    onBackground = AppColors.lightOnBackground,
    onSurface = AppColors.lightOnSurface,
    onSurfaceVariant = AppColors.lightOnSurfaceVariant,
    outline = AppColors.lightOutline,
)
