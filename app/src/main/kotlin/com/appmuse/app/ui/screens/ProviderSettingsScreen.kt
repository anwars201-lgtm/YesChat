// APPBUILDER-NATIVE-CAPABILITY:provider_settings.manage_profiles
// APPBUILDER-NATIVE-CAPABILITY:provider_settings.fetch_models
// APPBUILDER-NATIVE-CAPABILITY:provider_settings.test_connection
// APPBUILDER-NATIVE-CAPABILITY:navigation.provider_settings
package com.appmuse.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appmuse.app.R
import com.appmuse.app.model.ProviderProfile
import com.appmuse.app.ui.ProviderSettingsUiState
import com.appmuse.app.ui.ProviderSettingsViewModel
import com.appmuse.app.ui.components.EmptyState
import com.appmuse.app.ui.components.ErrorView
import com.appmuse.app.ui.components.SkeletonList
import com.appmuse.app.ui.theme.AppSpacing

@Composable
fun ProviderSettingsScreen(vm: ProviderSettingsViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    DisposableEffect(vm) { onDispose { vm.cancelRequest() } }
    when (val s = state) {
        ProviderSettingsUiState.Loading -> SkeletonList()
        is ProviderSettingsUiState.Error -> Column(Modifier.fillMaxSize()) {
            ErrorView(s.message, title = "Pengaturan belum dapat dibuka", modifier = Modifier.weight(1f))
            Button(onClick = vm::load, modifier = Modifier.align(Alignment.CenterHorizontally).padding(AppSpacing.base)) {
                Text("Coba lagi")
            }
        }
        is ProviderSettingsUiState.Success -> {
            val busy = s.action != null
            BackHandler(enabled = s.editor != null) { if (!busy) vm.closeEditor() }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(AppSpacing.base),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
            ) {
                item {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) {
                        Column(Modifier.fillMaxWidth().padding(AppSpacing.base), verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                                Box(
                                    Modifier.size(AppSpacing.xxl).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                }
                                Text(
                                    stringResource(R.string.appmuse_feature_provider_settings_title),
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            Text(stringResource(R.string.appmuse_privacy_api_key), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
                s.error?.let { error -> item { SettingsFeedback(error, error = true) } }
                s.notice?.let { notice -> item { SettingsFeedback(notice) } }
                if (s.editor == null) {
                    item {
                        Text(stringResource(R.string.appmuse_settings_manage_help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item {
                        Button(onClick = { vm.manageProfiles() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.appmuse_action_settings_manage_profiles))
                        }
                        Text("Tambahkan profil baru atau edit profil di bawah.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (s.action != null) item { SettingsProgress(s.action) }
                    if (s.archive.profiles.isEmpty()) {
                        item {
                            EmptyState(
                                title = stringResource(R.string.appmuse_profiles_empty_title),
                                message = stringResource(R.string.appmuse_profiles_empty_body),
                                icon = Icons.Outlined.Dns,
                                actionLabel = "Tambah profil",
                                onAction = { vm.manageProfiles() },
                            )
                        }
                    } else {
                        items(s.archive.profiles, key = { it.id }) { profile ->
                            ProviderProfileRow(
                                profile = profile,
                                active = profile.id == s.archive.selectedProfileId,
                                busy = busy,
                                onEdit = { vm.manageProfiles(profile.id) },
                                onSelect = { vm.selectProfile(profile.id) },
                                onDelete = { vm.requestDelete(profile.id) },
                            )
                        }
                    }
                    item { Text(stringResource(R.string.appmuse_privacy_local_only), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    item {
                        TextButton(onClick = vm::closeEditor, enabled = !busy) {
                            Text(stringResource(R.string.appmuse_feature_provider_settings_nav))
                        }
                        Text(
                            if (s.archive.profiles.any { it.id == s.editor.id }) "Edit profil penyedia" else "Tambah profil penyedia",
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    item { ProviderEditor(s, vm) }
                }
            }
            s.pendingDelete?.let { profile ->
                AlertDialog(
                    onDismissRequest = { if (!busy) vm.requestDelete(null) },
                    title = { Text(stringResource(R.string.appmuse_profiles_delete_confirmation)) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                            Text("Profil “${profile.name}” dan API Key-nya dihapus dari perangkat. Percakapan tidak ikut dihapus.")
                            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    },
                    confirmButton = { TextButton(onClick = vm::deleteProfile, enabled = !busy) { Text("Hapus", color = MaterialTheme.colorScheme.error) } },
                    dismissButton = { TextButton(onClick = { vm.requestDelete(null) }, enabled = !busy) { Text("Batal") } },
                )
            }
        }
    }
}

@Composable
private fun ProviderProfileRow(
    profile: ProviderProfile,
    active: Boolean,
    busy: Boolean,
    onEdit: () -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(AppSpacing.md), verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                Box(Modifier.size(AppSpacing.xxl).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(if (active) Icons.Outlined.CheckCircle else Icons.Outlined.Dns, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Column(Modifier.weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium)
                    Text(profile.model, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDelete, enabled = !busy) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "Hapus profil ${profile.name}", tint = MaterialTheme.colorScheme.error)
                }
            }
            Text(profile.baseUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                if (active) Text("Profil aktif", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                else TextButton(onClick = onSelect, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Gunakan profil") }
                TextButton(onClick = onEdit, enabled = !busy) { Text("Edit profil") }
            }
        }
    }
}

@Composable
private fun ProviderEditor(s: ProviderSettingsUiState.Success, vm: ProviderSettingsViewModel) {
    val profile = s.editor ?: return
    val busy = s.action != null
    var showKey by remember(profile.id) { mutableStateOf(false) }
    var showModels by rememberSaveable(profile.id) { mutableStateOf(false) }
    var modelQuery by rememberSaveable(profile.id) { mutableStateOf("") }
    ElevatedCard(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AppSpacing.base), verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
            Text(stringResource(R.string.appmuse_settings_protocol_help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingsField(profile.name, { vm.edit("name", it) }, stringResource(R.string.appmuse_settings_profile_name), s.fieldErrors["name"], !busy)
            SettingsField(
                profile.baseUrl, { vm.edit("baseUrl", it) }, stringResource(R.string.appmuse_settings_base_url), s.fieldErrors["baseUrl"], !busy,
                help = "Contoh: https://api.openai.com/v1. Gunakan alamat API, bukan alamat situs web.",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            )
            OutlinedTextField(
                value = profile.apiKey,
                onValueChange = { vm.edit("apiKey", it) },
                label = { Text(stringResource(R.string.appmuse_settings_api_key)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !busy,
                isError = s.fieldErrors["apiKey"] != null,
                supportingText = { s.fieldErrors["apiKey"]?.let { Text(it) } },
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (showKey) "Sembunyikan API Key" else "Tampilkan API Key")
                    }
                },
            )
            Text(stringResource(R.string.appmuse_settings_model_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = vm::fetchModels, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.appmuse_action_settings_fetch_models))
            }
            if (s.action == "models") {
                SettingsProgress("models")
                TextButton(onClick = vm::cancelRequest) { Text("Batalkan permintaan") }
            }
            s.modelsError?.let { error ->
                SettingsFeedback(error + " Anda tetap dapat memasukkan nama model secara manual.", error = true)
                TextButton(onClick = vm::fetchModels, enabled = !busy) { Text("Coba ambil model lagi") }
            }
            s.models?.let { models ->
                if (models.isEmpty()) {
                    Text("Penyedia tidak mengembalikan model. Masukkan nama model secara manual.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    OutlinedButton(onClick = { modelQuery = ""; showModels = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Pilih dari ${models.size} model")
                    }
                }
            }
            SettingsField(
                profile.model, { vm.edit("model", it) }, stringResource(R.string.appmuse_settings_model), s.fieldErrors["model"], !busy,
                help = stringResource(R.string.appmuse_settings_manual_model),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            )
            Text(stringResource(R.string.appmuse_settings_reasoning_effort), style = MaterialTheme.typography.titleMedium)
            val options = listOf(
                "default" to stringResource(R.string.appmuse_settings_reasoning_default),
                "low" to stringResource(R.string.appmuse_settings_reasoning_low),
                "medium" to stringResource(R.string.appmuse_settings_reasoning_medium),
                "high" to stringResource(R.string.appmuse_settings_reasoning_high),
            )
            Column {
                options.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                        pair.forEach { (value, label) ->
                            FilterChip(
                                selected = profile.reasoningEffort == value,
                                onClick = { vm.edit("reasoningEffort", value) },
                                label = { Text(label) },
                                enabled = !busy,
                                modifier = Modifier.weight(1f).sizeInTouchTarget(),
                            )
                        }
                    }
                }
            }
            Text(stringResource(R.string.appmuse_settings_reasoning_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = profile.systemPrompt,
                onValueChange = { vm.edit("systemPrompt", it) },
                label = { Text(stringResource(R.string.appmuse_settings_system_prompt)) },
                enabled = !busy,
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.appmuse_privacy_context), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Uji koneksi mengirim pesan singkat dan instruksi sistem ke penyedia. Biaya token mungkin berlaku dari penyedia Anda.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = vm::testConnection, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.appmuse_action_settings_test_connection))
            }
            if (s.action == "test") {
                SettingsProgress("test")
                TextButton(onClick = vm::cancelRequest) { Text("Batalkan permintaan") }
            }
            if (s.action == "save") SettingsProgress("save")
            Button(onClick = vm::saveProfile, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Simpan profil") }
            TextButton(onClick = vm::closeEditor, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Batal") }
        }
    }
    if (showModels && !s.models.isNullOrEmpty()) {
        AlertDialog(
            onDismissRequest = { showModels = false },
            title = { Text("Pilih model") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                    OutlinedTextField(value = modelQuery, onValueChange = { modelQuery = it }, label = { Text("Cari model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    val matches = s.models.filter { it.contains(modelQuery.trim(), ignoreCase = true) }
                    if (matches.isEmpty()) Text("Tidak ada model yang cocok. Ubah pencarian atau masukkan nama model secara manual.")
                    LazyColumn {
                        items(matches, key = { it }) { model ->
                            TextButton(onClick = { vm.edit("model", model); showModels = false }, modifier = Modifier.fillMaxWidth()) {
                                Text(model, Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showModels = false }) { Text("Tutup") } },
        )
    }
}

private fun Modifier.sizeInTouchTarget(): Modifier = this.then(Modifier.padding(vertical = AppSpacing.xs))

@Composable
private fun SettingsField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    error: String?,
    enabled: Boolean,
    help: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        isError = error != null,
        keyboardOptions = keyboardOptions,
        supportingText = if (error != null || help != null) ({ Text(error ?: help.orEmpty()) }) else null,
    )
}

@Composable
private fun SettingsFeedback(message: String, error: Boolean = false) {
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(message, Modifier.fillMaxWidth().padding(AppSpacing.md), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SettingsProgress(action: String) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
        Text(
            when (action) {
                "models" -> "Mengambil daftar model…"
                "test" -> "Menguji koneksi dan pengaturan model…"
                "delete" -> "Menghapus profil…"
                else -> "Menyimpan perubahan…"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
