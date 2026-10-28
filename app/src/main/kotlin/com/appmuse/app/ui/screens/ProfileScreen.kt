package com.appmuse.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.appmuse.app.config.AppConfig
import com.appmuse.app.model.User
import com.appmuse.app.ui.components.AppCard
import com.appmuse.app.ui.theme.AppSpacing

// Phrase-book chrome. Single-language builds translate these in place;
// multi-locale builds rewrite them to per-locale resource reads.
private const val CHROME_ACCOUNT = "Account"
private const val CHROME_MANAGE_PROFILE = "Manage your profile and preferences here."
private const val CHROME_LOG_OUT = "Log out"

/**
 * Profile — shows the signed-in user and the logout action. [onLogout] is hoisted
 * (AppNav passes AuthViewModel::logout) so this screen stays stateless and the
 * ViewModel owns the session. A soft tinted avatar chip + name + email head the
 * screen, then an AppCard groups account actions. The logout button is hidden when
 * auth is disabled (authMode == none) since there is no real session to end.
 */
@Composable
fun ProfileScreen(user: User, onLogout: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AppSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(AppSpacing.base))
        // Avatar — the user's initial in a soft brand-tinted circle (swap for an
        // image once user.avatar is wired). primaryContainer keeps it on-palette
        // without the heavy solid-primary block.
        Box(
            modifier = Modifier.size(96.dp).background(cs.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                initialOf(user),
                style = MaterialTheme.typography.displaySmall,
                color = cs.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(AppSpacing.base))
        Text(user.name ?: CHROME_ACCOUNT, style = MaterialTheme.typography.headlineSmall)
        if (user.email.isNotEmpty()) {
            Spacer(Modifier.height(AppSpacing.xs))
            Text(
                user.email,
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(AppSpacing.xl))
        AppCard {
            Text(CHROME_ACCOUNT, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(AppSpacing.sm))
            Text(
                CHROME_MANAGE_PROFILE,
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
            if (AppConfig.authEnabled) {
                Spacer(Modifier.height(AppSpacing.lg))
                OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                    Text(CHROME_LOG_OUT)
                }
            }
        }
    }
}

private fun initialOf(user: User): String {
    val source = user.name?.takeIf { it.isNotBlank() } ?: user.email
    return source.trim().firstOrNull()?.uppercase() ?: "?"
}
