package com.appmuse.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.appmuse.app.ui.theme.AppSpacing

// Phrase-book chrome. Single-language builds translate these in place;
// multi-locale builds rewrite them to per-locale resource reads. Default
// parameter expressions of @Composable functions evaluate in composable
// scope, so both forms are legal there.
private const val CHROME_SOMETHING_WENT_WRONG = "Terjadi kesalahan"
private const val CHROME_TRY_AGAIN = "Coba lagi"
private const val CHROME_NOTHING_HERE_YET = "Belum ada data"

/**
 * Reusable Loading / Error / Empty states — every feature screen should cover all
 * three (loading, error-with-retry, empty) like the Flutter template does. They are
 * NOT bare Text: an empty/error screen is a designed moment (a tinted icon chip + a
 * title + a one-line explanation + an optional action), so the app never shows a sad
 * blank screen. All colors come from the theme; tweak copy/icons per screen via the
 * params. Keep the icon chip + title + description rhythm when you adapt them.
 */

@Composable
fun LoadingView(modifier: Modifier = Modifier, message: String? = null) {
    Column(
        modifier = modifier.fillMaxSize().padding(AppSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        if (message != null) {
            Spacer(Modifier.height(AppSpacing.md))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun ErrorView(
    message: String,
    onRetry: (() -> Unit)? = null,
    title: String = CHROME_SOMETHING_WENT_WRONG,
    icon: ImageVector = Icons.Outlined.ErrorOutline,
    modifier: Modifier = Modifier,
) {
    StateScaffold(
        icon = icon,
        title = title,
        description = message,
        tint = MaterialTheme.colorScheme.error,
        actionLabel = if (onRetry != null) CHROME_TRY_AGAIN else null,
        onAction = onRetry,
        modifier = modifier,
    )
}

@Composable
fun EmptyState(
    message: String,
    title: String = CHROME_NOTHING_HERE_YET,
    icon: ImageVector = Icons.Outlined.Inbox,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    StateScaffold(
        icon = icon,
        title = title,
        description = message,
        tint = MaterialTheme.colorScheme.primary,
        actionLabel = actionLabel,
        onAction = onAction,
        modifier = modifier,
    )
}

/**
 * Shared centered layout for the empty/error moments — a soft tinted icon chip, a
 * bold title, a muted one-liner, and an optional primary action. Private on purpose:
 * screens use ErrorView/EmptyState; this just keeps their look identical.
 */
@Composable
private fun StateScaffold(
    icon: ImageVector,
    title: String,
    description: String?,
    tint: Color,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(AppSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(72.dp).background(tint.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(AppSpacing.base))
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        if (description != null) {
            Spacer(Modifier.height(AppSpacing.xs))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp),
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(AppSpacing.lg))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
