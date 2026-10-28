package com.appmuse.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.appmuse.app.R
import com.appmuse.app.config.AppConfig
import com.appmuse.app.model.User
import com.appmuse.app.ui.components.AppCard
import com.appmuse.app.ui.theme.AppSpacing

// Phrase-book chrome. Single-language builds translate these in place;
// multi-locale builds rewrite them to per-locale resource reads.
private const val CHROME_ACCOUNT = "Profil"
private const val CHROME_MANAGE_PROFILE = "Kelola profil dan preferensi Anda di sini."
private const val CHROME_LOG_OUT = "Keluar"

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
        Box(
            modifier = Modifier.size(AppSpacing.xxl * 2).background(cs.secondaryContainer, MaterialTheme.shapes.extraLarge),
            contentAlignment = Alignment.Center,
        ) {
            if (AppConfig.authEnabled) {
                Text(initialOf(user), style = MaterialTheme.typography.displaySmall, color = cs.onSecondaryContainer)
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.Chat,
                    contentDescription = null,
                    modifier = Modifier.size(AppSpacing.xxl),
                    tint = cs.onSecondaryContainer,
                )
            }
        }
        Spacer(Modifier.height(AppSpacing.base))
        Text(
            if (AppConfig.authEnabled) user.name ?: CHROME_ACCOUNT else stringResource(R.string.appmuse_app_name),
            style = MaterialTheme.typography.headlineSmall,
        )
        if (AppConfig.authEnabled && user.email.isNotEmpty()) {
            Spacer(Modifier.height(AppSpacing.xs))
            Text(
                user.email,
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(AppSpacing.xl))
        AppCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
            ) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = cs.onPrimaryContainer)
                Text(
                    if (AppConfig.authEnabled) CHROME_ACCOUNT else stringResource(R.string.appmuse_settings_api_key),
                    style = MaterialTheme.typography.titleLarge,
                    color = cs.onPrimaryContainer,
                )
            }
            Spacer(Modifier.height(AppSpacing.sm))
            Text(
                if (AppConfig.authEnabled) CHROME_MANAGE_PROFILE else stringResource(R.string.appmuse_privacy_api_key),
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onPrimaryContainer,
            )
            if (AppConfig.authEnabled) {
                Spacer(Modifier.height(AppSpacing.lg))
                OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                    Text(CHROME_LOG_OUT)
                }
            }
        }
        if (!AppConfig.authEnabled) {
            Spacer(Modifier.height(AppSpacing.lg))
            Text(
                stringResource(R.string.appmuse_privacy_local_only),
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

private fun initialOf(user: User): String {
    val source = user.name?.takeIf { it.isNotBlank() } ?: user.email
    return source.trim().firstOrNull()?.uppercase() ?: "?"
}
