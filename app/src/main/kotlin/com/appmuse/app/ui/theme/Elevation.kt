package com.appmuse.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Elevation scale — the depth ramp for cards/sheets/headers (the Kotlin mirror of
 * the Flutter template's shadow tokens). Pass these to `CardDefaults.cardElevation`
 * / `ElevatedCard` etc. so surfaces share one consistent depth language instead of
 * ad-hoc dp values. `tonal` is the resting card level; raise to `raised` on press
 * or for a featured surface.
 */
object AppElevation {
    val flat = 0.dp
    val tonal = 1.dp
    val low = 2.dp
    val raised = 4.dp
    val high = 8.dp
}
