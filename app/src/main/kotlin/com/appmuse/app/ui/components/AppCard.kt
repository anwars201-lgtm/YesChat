package com.appmuse.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.appmuse.app.ui.theme.AppElevation
import com.appmuse.app.ui.theme.AppShapes
import com.appmuse.app.ui.theme.AppSpacing

/**
 * The standard content card — an ElevatedCard with the app's large corner radius,
 * resting elevation, and consistent inner padding. Use this instead of rolling a
 * flat `Surface`/`Box` per screen so every card shares one depth + shape language.
 * Colors come from the theme (ElevatedCard uses colorScheme.surfaceContainer*).
 */
@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = AppShapes.large,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = AppElevation.low),
    ) {
        Column(Modifier.padding(AppSpacing.base), content = content)
    }
}
