package com.appmuse.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.appmuse.app.model.User
import com.appmuse.app.ui.components.AppCard
import com.appmuse.app.ui.components.HeroHeader
import com.appmuse.app.ui.components.StatCard
import com.appmuse.app.ui.theme.AppSpacing

/**
 * Home — the first screen after auth, and the REFERENCE for the feature-screen look:
 * a brand HeroHeader, a Row of two StatCards (the second on the secondary accent so
 * the palette shows twice), then an AppCard. The agent replaces the body with the
 * real primary screen for the chosen archetype, but keeps the shape:
 *   - data in via parameters (hoisted from a ViewModel StateFlow in AppNav),
 *   - LoadingView / ErrorView(onRetry) / EmptyState for the three async states,
 *   - HeroHeader / StatCard / AppCard from ui.components, spacing from AppSpacing,
 *     text styles from MaterialTheme.typography (never hardcoded colors or sizes).
 */
@Composable
fun HomeScreen(user: User, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        HeroHeader(
            title = "Welcome${user.name?.let { ", $it" } ?: ""}",
            subtitle = "Here's what's happening today",
        )
        Column(modifier = Modifier.padding(AppSpacing.base)) {
            // Two highlight metrics — swap the values + labels for real data from a
            // ViewModel (item count, unread, streak…). Give the second card the
            // secondary accent so both palette colors appear above the fold.
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                StatCard(
                    value = "12",
                    label = "Today",
                    icon = Icons.Outlined.Insights,
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    value = "48",
                    label = "This week",
                    icon = Icons.Outlined.CheckCircle,
                    accent = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(AppSpacing.base))
            AppCard {
                Text("Your app starts here", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(AppSpacing.sm))
                Text(
                    "Replace this screen with your primary feature — a list, a feed, a dashboard. " +
                        "Bind it to a ViewModel and render Loading / Empty / Error / Success.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
