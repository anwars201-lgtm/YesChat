package com.appmuse.app.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.ui.res.stringResource
import com.appmuse.app.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.appmuse.app.model.ChatAttachment
import com.appmuse.app.ui.theme.AppSpacing

@Composable
fun reasoningLabel(value: String?): String = stringResource(when (value) {
    "low" -> R.string.appmuse_settings_reasoning_low
    "medium" -> R.string.appmuse_settings_reasoning_medium
    "high" -> R.string.appmuse_settings_reasoning_high
    else -> R.string.appmuse_settings_reasoning_default
})

@Composable
fun ChatComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    busy: Boolean,
    streaming: Boolean,
    model: String?,
    reasoning: String?,
    attachments: List<ChatAttachment>,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onReasoning: () -> Unit,
    onModel: () -> Unit,
    onAttach: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = AppSpacing.xs,
    ) {
        Column(Modifier.padding(AppSpacing.md), verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            if (attachments.isNotEmpty()) Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
            ) {
                attachments.forEach { attachment ->
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(attachment.name.take(24), Modifier.padding(start = AppSpacing.md), style = MaterialTheme.typography.labelMedium)
                            IconButton(onClick = { onRemoveAttachment(attachment.id) }, enabled = !busy) {
                                Icon(Icons.Outlined.Close, contentDescription = "Hapus lampiran ${attachment.name}")
                            }
                        }
                    }
                }
            }
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                placeholder = { Text("Tulis pesan…") },
                label = { Text("Pesan") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 5,
                shape = MaterialTheme.shapes.large,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                IconButton(onClick = onAttach, enabled = !busy, modifier = Modifier.size(AppSpacing.xxl)) {
                    Icon(Icons.Outlined.AttachFile, contentDescription = "Lampirkan berkas", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(
                    onClick = onReasoning,
                    enabled = !busy && model != null,
                    modifier = Modifier.size(AppSpacing.xxl),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                ) {
                    Icon(Icons.Outlined.Psychology, contentDescription = "Tingkat penalaran: ${reasoningLabel(reasoning)}")
                }
                AssistChip(
                    onClick = onModel,
                    enabled = !busy && model != null,
                    modifier = Modifier.weight(1f).heightIn(min = AppSpacing.xxl),
                    label = { Text(model ?: "Ganti model", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium) },
                    leadingIcon = { Icon(Icons.Outlined.SwapHoriz, contentDescription = "Ganti model") },
                )
                FilledIconButton(
                    onClick = if (streaming) onStop else onSend,
                    modifier = Modifier.size(AppSpacing.xxl),
                    enabled = streaming || (!busy && model != null && (draft.isNotBlank() || attachments.isNotEmpty())),
                ) {
                    Icon(
                        if (streaming) Icons.Outlined.StopCircle else Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(if (streaming) R.string.appmuse_action_chat_stop else R.string.appmuse_action_chat_send),
                    )
                }
            }
            Text(
                if (busy && !streaming) "Memproses…" else "Penalaran: ${reasoningLabel(reasoning)} · Tersimpan di perangkat",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

