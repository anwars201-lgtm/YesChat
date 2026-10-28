package com.appmuse.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.appmuse.app.ui.design.ProfileSurface
import com.appmuse.app.ui.theme.AppSpacing

/** A profile-owned elevated shell with a soft indigo inset for grouped content. */
@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    ProfileSurface(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().background(
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.shapes.medium,
            ).padding(AppSpacing.md),
            content = content,
        )
    }
}
