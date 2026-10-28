package com.appmuse.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.appmuse.app.ui.theme.AppSpacing

/**
 * The brand hero band that opens a primary screen — a palette gradient with a
 * rounded bottom, an optional eyebrow/subtitle, and a big title in onPrimary. This
 * is the single biggest "looks designed" signal; put it at the top of the home/
 * primary screen instead of a bare Text title. Colors are 100% from the palette
 * (primary→secondary), so it follows the onboarding color automatically.
 */
@Composable
fun HeroHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
            .background(Brush.linearGradient(listOf(cs.primary, cs.secondary)))
            .padding(horizontal = AppSpacing.base, vertical = AppSpacing.lg),
    ) {
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.titleMedium,
                color = cs.onPrimary.copy(alpha = 0.85f),
            )
        }
        Text(title, style = MaterialTheme.typography.headlineMedium, color = cs.onPrimary)
    }
}
