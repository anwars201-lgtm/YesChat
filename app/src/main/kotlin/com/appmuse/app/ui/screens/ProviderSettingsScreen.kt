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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.outlined.Psychology
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appmuse.app.R
import com.appmuse.app.model.BehaviorProfile
import com.appmuse.app.model.ProviderConfig
import com.appmuse.app.ui.ProviderSettingsUiState
import com.appmuse.app.ui.ProviderSettingsViewModel
import com.appmuse.app.ui.components.EmptyState
import com.appmuse.app.ui.components.ErrorView
import com.appmuse.app.ui.components.SkeletonList
import com.appmuse.app.ui.components.reasoningLabel
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
            BackHandler(enabled = s.providerEditor != null || s.behaviorEditor != null) { if (!busy) vm.closeEditor() }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(AppSpacing.base),
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
                when {
                    s.providerEditor != null -> {
                        item {
                            TextButton(onClick = vm::closeEditor, enabled = !busy) {
                                Text(stringResource(R.string.appmuse_feature_provider_settings_nav))
                            }
                            Text(
                                if (s.archive.providers.any { it.id == s.providerEditor.id }) "Edit penyedia" else "Tambah penyedia",
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                        item { ProviderEditorForm(s, vm) }
                    }
                    s.behaviorEditor != null -> {
                        item {
                            TextButton(onClick = vm::closeEditor, enabled = !busy) {
                                Text(stringResource(R.string.appmuse_feature_provider_settings_nav))
                            }
                            Text(
                                if (s.archive.behaviors.any { it.id == s.behaviorEditor.id }) "Edit perilaku" else "Tambah perilaku",
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                        item { BehaviorEditorForm(s, vm) }
                    }
                    else -> {
                        item {
                            Text("Penyedia", style = MaterialTheme.typography.titleLarge)
                            Text("Koneksi dan model. API Key tersimpan terenkripsi di perangkat.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        item {
                            Button(onClick = { vm.manageProvider() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Text("Tambah penyedia")
                            }
                        }
                        if (s.action != null) item { SettingsProgress(s.action) }
                        if (s.archive.providers.isEmpty()) {
                            item {
                                EmptyState(
                                    title = "Belum ada penyedia",
                                    message = "Tambahkan penyedia pertama Anda — nama, Base URL, API Key, dan model.",
                                    icon = Icons.Outlined.Dns,
                                    actionLabel = "Tambah penyedia",
                                    onAction = { vm.manageProvider() },
                                )
                            }
                        } else {
                            items(s.archive.providers, key = { it.id }) { config ->
                                ProviderRow(
                                    config = config,
                                    active = config.id == s.archive.selectedProviderId,
                                    busy = busy,
                                    onEdit = { vm.manageProvider(config.id) },
                                    onSelect = { vm.selectProvider(config.id) },
                                    onDelete = { vm.requestDeleteProvider(config.id) },
                                )
                            }
                        }
                        item {
                            Text("Perilaku", style = MaterialTheme.typography.titleLarge)
                            Text("Instruksi sistem dan tingkat penalaran. Satu perilaku dapat dipakai untuk semua penyedia.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        item {
                            Button(onClick = { vm.manageBehavior() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Text("Tambah perilaku")
                            }
                        }
                        if (s.archive.behaviors.isEmpty()) {
                            item {
                                EmptyState(
                                    title = "Belum ada perilaku",
                                    message = "Tambahkan perilaku pertama Anda — nama, tingkat penalaran, dan instruksi sistem.",
                                    icon = Icons.Outlined.Psychology,
                                    actionLabel = "Tambah perilaku",
                                    onAction = { vm.manageBehavior() },
                                )
                            }
                        } else {
                            items(s.archive.behaviors, key = { it.id }) { behavior ->
                                BehaviorRow(
                                    behavior = behavior,
                                    active = behavior.id == s.archive.selectedBehaviorId,
                                    busy = busy,
                                    onEdit = { vm.manageBehavior(behavior.id) },
                                    onSelect = { vm.selectBehavior(behavior.id) },
                                    onDelete = { vm.requestDeleteBehavior(behavior.id) },
                                )
                            }
                        }
                        item { Text(stringResource(R.string.appmuse_privacy_local_only), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            s.pendingDeleteProvider?.let { config ->
                AlertDialog(
                    onDismissRequest = { if (!busy) vm.requestDeleteProvider(null) },
                    title = { Text("Hapus penyedia?") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                            Text("Penyedia “${config.name}” dan API Key-nya dihapus dari perangkat. Percakapan tidak ikut dihapus.")
                            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    },
                    confirmButton = { TextButton(onClick = vm::deleteProvider, enabled = !busy) { Text("Hapus", color = MaterialTheme.colorScheme.error) } },
                    dismissButton = { TextButton(onClick = { vm.requestDeleteProvider(null) }, enabled = !busy) { Text("Batal") } },
                )
            }
            s.pendingDeleteBehavior?.let { behavior ->
                AlertDialog(
                    onDismissRequest = { if (!busy) vm.requestDeleteBehavior(null) },
                    title = { Text("Hapus perilaku?") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                            Text("Perilaku “${behavior.name}” dihapus dari perangkat. Penyedia dan percakapan tidak ikut dihapus.")
                            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    },
                    confirmButton = { TextButton(onClick = vm::deleteBehavior, enabled = !busy) { Text("Hapus", color = MaterialTheme.colorScheme.error) } },
                    dismissButton = { TextButton(onClick = { vm.requestDeleteBehavior(null) }, enabled = !busy) { Text("Batal") } },
                )
            }
        }
    }
}

@Composable
private fun ProviderRow(
    config: ProviderConfig,
    active: Boolean,
    busy: Boolean,
    onEdit: () -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(AppSpacing.md), verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                Box(Modifier.size(AppSpacing.xxl).background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(if (active) Icons.Outlined.CheckCircle else Icons.Outlined.Dns, null, tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f)) {
                    Text(config.name, style = MaterialTheme.typography.titleMedium)
                    Text(config.model, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDelete, enabled = !busy) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "Hapus penyedia ${config.name}", tint = MaterialTheme.colorScheme.error)
                }
            }
            Text(config.baseUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                if (active) Text("Penyedia aktif", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                else TextButton(onClick = onSelect, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Gunakan penyedia") }
                TextButton(onClick = onEdit, enabled = !busy) { Text("Edit") }
            }
        }
    }
}

@Composable
private fun BehaviorRow(
    behavior: BehaviorProfile,
    active: Boolean,
    busy: Boolean,
    onEdit: () -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(AppSpacing.md), verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                Box(Modifier.size(AppSpacing.xxl).background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(if (active) Icons.Outlined.CheckCircle else Icons.Outlined.Psychology, null, tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f)) {
                    Text(behavior.name, style = MaterialTheme.typography.titleMedium)
                    Text("Penalaran: ${reasoningLabel(behavior.reasoningEffort)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDelete, enabled = !busy) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "Hapus perilaku ${behavior.name}", tint = MaterialTheme.colorScheme.error)
                }
            }
            Text(
                behavior.systemPrompt.ifBlank { "Tanpa instruksi sistem" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                if (active) Text("Perilaku aktif", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                else TextButton(onClick = onSelect, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Gunakan perilaku") }
                TextButton(onClick = onEdit, enabled = !busy) { Text("Edit") }
            }
        }
    }
}

@Composable
private fun ProviderEditorForm(s: ProviderSettingsUiState.Success, vm: ProviderSettingsViewModel) {
    val config = s.providerEditor ?: return
    val busy = s.action != null
    var showKey by rememberSaveable(config.id) { mutableStateOf(false) }
    var showModels by rememberSaveable(config.id) { mutableStateOf(false) }
    var modelQuery by rememberSaveable(config.id) { mutableStateOf("") }
    ElevatedCard(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AppSpacing.base), verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
            Text(stringResource(R.string.appmuse_settings_protocol_help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingsField(config.name, { vm.editProvider("name", it) }, "Nama penyedia", s.fieldErrors["name"], !busy)
            SettingsField(
                config.baseUrl, { vm.editProvider("baseUrl", it) }, stringResource(R.string.appmuse_settings_base_url), s.fieldErrors["baseUrl"], !busy,
                help = "Contoh: https://api.openai.com/v1. Gunakan alamat API, bukan alamat situs web.",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            )
            OutlinedTextField(
                value = config.apiKey,
                onValueChange = { vm.editProvider("apiKey", it) },
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
                config.model, { vm.editProvider("model", it) }, stringResource(R.string.appmuse_settings_model), s.fieldErrors["model"], !busy,
                help = stringResource(R.string.appmuse_settings_manual_model),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            )
            Text("Uji koneksi mengirim pesan singkat ke penyedia, memakai perilaku yang sedang aktif. Biaya token mungkin berlaku dari penyedia Anda.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = vm::testConnection, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.appmuse_action_settings_test_connection))
            }
            if (s.action == "test") {
                SettingsProgress("test")
                TextButton(onClick = vm::cancelRequest) { Text("Batalkan permintaan") }
            }
            if (s.action == "save") SettingsProgress("save")
            Button(onClick = vm::saveProvider, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Simpan penyedia") }
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
                            TextButton(onClick = { vm.editProvider("model", model); showModels = false }, modifier = Modifier.fillMaxWidth()) {
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

@Composable
private fun BehaviorEditorForm(s: ProviderSettingsUiState.Success, vm: ProviderSettingsViewModel) {
    val behavior = s.behaviorEditor ?: return
    val busy = s.action != null
    ElevatedCard(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AppSpacing.base), verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
            SettingsField(behavior.name, { vm.editBehavior("name", it) }, "Nama perilaku", s.fieldErrors["name"], !busy)
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
                                selected = behavior.reasoningEffort == value,
                                onClick = { vm.editBehavior("reasoningEffort", value) },
                                label = { Text(label) },
                                enabled = !busy,
                                modifier = Modifier.weight(1f).sizeInTouchTarget(),
                            )
                        }
                    }
                }
            }
            s.fieldErrors["reasoningEffort"]?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Text(stringResource(R.string.appmuse_settings_reasoning_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = behavior.systemPrompt,
                onValueChange = { vm.editBehavior("systemPrompt", it) },
                label = { Text(stringResource(R.string.appmuse_settings_system_prompt)) },
                enabled = !busy,
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.appmuse_privacy_context), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (s.action == "save") SettingsProgress("save")
            Button(onClick = vm::saveBehavior, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Simpan perilaku") }
            TextButton(onClick = vm::closeEditor, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Batal") }
        }
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
                "delete" -> "Menghapus…"
                else -> "Menyimpan perubahan…"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
