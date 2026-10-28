package com.appmuse.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.appmuse.app.ui.theme.AppShapes
import com.appmuse.app.ui.theme.AppSpacing

/**
 * A compact metric/highlight card — a tinted accent icon chip + a big bold value +
 * a caption. Pair two in a Row under the hero on a dashboard. `accent` defaults to
 * the brand primary; pass colorScheme.secondary for a second card so the palette
 * shows twice. All colors from the theme.
 */
@Composable
fun StatCard(
    value: String,
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    accent: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
) {
    val cs = MaterialTheme.colorScheme
    ElevatedCard(modifier = modifier, shape = AppShapes.large) {
        Row(Modifier.padding(AppSpacing.md), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).background(accent.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent)
            }
            Spacer(Modifier.width(AppSpacing.sm))
            Column {
                Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(label, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
        }
    }
}
