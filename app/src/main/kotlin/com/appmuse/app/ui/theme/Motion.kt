package com.appmuse.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/**
 * Motion scale — durations (ms) + easings for entrance/transition animations (the
 * Kotlin mirror of the Flutter template's motion tokens). Use with
 * `tween(AppMotion.standard, easing = AppMotion.emphasized)` in
 * `animate*AsState`/`AnimatedVisibility` so motion feels consistent and intentional,
 * never gratuitous. Routine motion, including legacy standard/slow tokens, is
 * capped at 200 ms. Continuous loading shimmer has a separate calm cycle.
 */
object AppMotion {
    const val micro = 120
    const val quick = 200
    const val standard = quick
    const val slow = quick
    const val shimmerCycle = 1040

    /** Standard decelerate — most entrances. */
    val standardEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Emphasized — hero/featured transitions. */
    val emphasized: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
}
