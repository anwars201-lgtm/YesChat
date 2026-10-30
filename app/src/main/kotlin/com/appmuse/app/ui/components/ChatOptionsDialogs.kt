package com.appmuse.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.appmuse.app.R
import com.appmuse.app.model.ProviderProfile
import com.appmuse.app.ui.ChatModelsUiState
import com.appmuse.app.ui.theme.AppSpacing

/** Reasoning picker for the chat screen, rising from the composer's reasoning
 *  pill. Applies to this conversation only (a per-conversation override of the
 *  active behaviour's reasoning effort). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReasoningSheet(selected: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.base, vertical = AppSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        ) {
            Text(stringResource(R.string.appmuse_settings_reasoning_effort), style = MaterialTheme.typography.titleLarge)
            Text("Berlaku untuk percakapan ini. Default tidak mengirim parameter reasoning_effort.", style = MaterialTheme.typography.bodySmall)
            listOf("default", "low", "medium", "high").forEach { value ->
                TextButton(onClick = { onSelect(value) }, modifier = Modifier.fillMaxWidth().heightIn(min = AppSpacing.xxl)) {
                    Text((if (value == selected) "✓ " else "") + reasoningLabel(value))
                }
            }
        }
    }
}

/** Provider + model picker for the chat screen, rising from the composer pill.
 *  Providers and behaviours are independent layers: switching the provider here
 *  rebinds this conversation to that provider (keeping the active behaviour),
 *  and the model list below always belongs to the selected provider. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderModelSheet(
    providers: List<ProviderProfile>,
    selectedProviderId: String?,
    selectedModel: String,
    modelsState: ChatModelsUiState,
    onSelectProvider: (String) -> Unit,
    onRetryModels: () -> Unit,
    onSelectModel: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var manual by rememberSaveable(selectedProviderId, selectedModel) { mutableStateOf(selectedModel) }
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.base, vertical = AppSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        ) {
            Text("Penyedia & model", style = MaterialTheme.typography.titleLarge)
            Text("Penyedia untuk percakapan ini", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            providers.forEach { provider ->
                TextButton(
                    onClick = { onSelectProvider(provider.id) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = AppSpacing.xxl),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            (if (provider.id == selectedProviderId) "✓ " else "") + provider.name,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(provider.model, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text("Model penyedia aktif", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(
                value = manual,
                onValueChange = { manual = it },
                label = { Text(stringResource(R.string.appmuse_settings_model)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                isError = manual.trim().length > 200,
            )
            Button(
                onClick = { onSelectModel(manual) },
                enabled = manual.isNotBlank() && manual.trim().length <= 200,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Gunakan model") }
            Column(Modifier.fillMaxWidth().heightIn(max = AppSpacing.xxl * 3)) {
                when (modelsState) {
                    ChatModelsUiState.Loading -> SkeletonList(count = 2)
                    is ChatModelsUiState.Error -> {
                        Text(modelsState.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onRetryModels) { Text("Coba lagi") }
                    }
                    is ChatModelsUiState.Success -> {
                        if (modelsState.models.isEmpty()) {
                            Text("Daftar model kosong. Masukkan nama model secara manual.", style = MaterialTheme.typography.bodyMedium)
                        } else LazyColumn {
                            items(modelsState.models, key = { it }) { model ->
                                TextButton(
                                    onClick = { onSelectModel(model) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = AppSpacing.xxl),
                                ) {
                                    Text((if (model == selectedModel) "✓ " else "") + model)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
