package com.appmuse.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.appmuse.app.R
import com.appmuse.app.ui.ChatModelsUiState
import com.appmuse.app.ui.theme.AppSpacing

@Composable
fun ReasoningDialog(selected: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.appmuse_settings_reasoning_effort)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                Text("Berlaku untuk percakapan ini. Default tidak mengirim parameter reasoning_effort.", style = MaterialTheme.typography.bodySmall)
                listOf("default", "low", "medium", "high").forEach { value ->
                    TextButton(onClick = { onSelect(value) }, modifier = Modifier.fillMaxWidth().heightIn(min = AppSpacing.xxl)) {
                        Text((if (value == selected) "✓ " else "") + reasoningLabel(value))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } },
    )
}

@Composable
fun ModelDialog(
    selected: String,
    state: ChatModelsUiState,
    onRetry: () -> Unit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var manual by rememberSaveable(selected) { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ganti model") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                Text("Pilih model dari penyedia profil aktif atau masukkan namanya secara manual.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = manual,
                    onValueChange = { manual = it },
                    label = { Text(stringResource(R.string.appmuse_settings_model)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = manual.trim().length > 200,
                )
                Column(Modifier.fillMaxWidth().heightIn(max = AppSpacing.xxl * 4)) {
                    when (state) {
                        ChatModelsUiState.Loading -> SkeletonList(count = 2)
                        is ChatModelsUiState.Error -> {
                            Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = onRetry) { Text("Coba lagi") }
                        }
                        is ChatModelsUiState.Success -> {
                            if (state.models.isEmpty()) {
                                Text("Daftar model kosong. Masukkan nama model secara manual.", style = MaterialTheme.typography.bodyMedium)
                            } else LazyColumn {
                                items(state.models, key = { it }) { model ->
                                    TextButton(
                                        onClick = { onSelect(model) },
                                        modifier = Modifier.fillMaxWidth().heightIn(min = AppSpacing.xxl),
                                    ) {
                                        Text((if (model == selected) "✓ " else "") + model)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(manual) }, enabled = manual.isNotBlank() && manual.trim().length <= 200) { Text("Gunakan model") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}
