// APPBUILDER-NATIVE-CAPABILITY:conversations.browse_and_open
// APPBUILDER-NATIVE-CAPABILITY:conversations.create
// APPBUILDER-NATIVE-CAPABILITY:conversations.rename
// APPBUILDER-NATIVE-CAPABILITY:conversations.delete
// APPBUILDER-NATIVE-CAPABILITY:navigation.conversations
package com.appmuse.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appmuse.app.R
import com.appmuse.app.model.LocalConversation
import com.appmuse.app.ui.ConversationsUiState
import com.appmuse.app.ui.ConversationsViewModel
import com.appmuse.app.ui.components.EmptyState
import com.appmuse.app.ui.components.ErrorView
import com.appmuse.app.ui.components.SkeletonList
import com.appmuse.app.ui.design.ProfileSurface
import com.appmuse.app.ui.theme.AppSpacing
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ConversationsScreen(
    onOpenConversation: (String) -> Unit,
    vm: ConversationsViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val mutationError by vm.mutationError.collectAsStateWithLifecycle()
    val openedId by vm.openedId.collectAsStateWithLifecycle()
    val completion by vm.mutationComplete.collectAsStateWithLifecycle()
    var dialogAction by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var selectedTitle by rememberSaveable { mutableStateOf("") }
    var titleInput by rememberSaveable { mutableStateOf("") }
    val createLabel = stringResource(R.string.appmuse_action_conversations_create)
    val renameLabel = stringResource(R.string.appmuse_action_conversations_rename)
    val deleteLabel = stringResource(R.string.appmuse_action_conversations_delete)
    val canCreate = !busy && (state is ConversationsUiState.Success || state is ConversationsUiState.Empty)

    LaunchedEffect(openedId) {
        openedId?.let { id ->
            vm.consumeOpen()
            onOpenConversation(id)
        }
    }
    LaunchedEffect(completion) {
        if (completion > 0) dialogAction = null
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = AppSpacing.base),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
    ) {
        ProfileSurface(Modifier.fillMaxWidth().padding(top = AppSpacing.sm)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
            ) {
                Box(
                    Modifier.size(AppSpacing.xxl).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Chat, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    Text(
                        stringResource(R.string.appmuse_app_name),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stringResource(R.string.appmuse_feature_conversations_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        stringResource(R.string.appmuse_feature_conversations_nav) + " disimpan di perangkat ini",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(
                    Modifier.size(AppSpacing.md).background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape),
                )
            }
        }
        Button(
            onClick = { vm.create(createLabel) },
            enabled = canCreate,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(createLabel, Modifier.padding(start = AppSpacing.sm))
        }
        if (mutationError != null && dialogAction == null) {
            Text(
                mutationError.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Box(Modifier.weight(1f)) {
            when (val current = state) {
                ConversationsUiState.Loading -> SkeletonList()
                ConversationsUiState.Empty -> EmptyState(
                    title = stringResource(R.string.appmuse_conversations_empty_title),
                    message = stringResource(R.string.appmuse_conversations_empty_body),
                    icon = Icons.AutoMirrored.Filled.Chat,
                )
                is ConversationsUiState.Error -> Column(Modifier.fillMaxSize()) {
                    ErrorView(
                        message = current.message,
                        title = "Percakapan belum dapat dimuat",
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = vm::load, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Coba lagi")
                    }
                }
                is ConversationsUiState.Success -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
                ) {
                    items(current.conversations, key = { it.id }) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            enabled = !busy,
                            onOpen = { vm.browseAndOpen(conversation.id) },
                            onRename = {
                                vm.clearMutationError()
                                selectedId = conversation.id
                                selectedTitle = conversation.title
                                titleInput = conversation.title
                                dialogAction = "rename"
                            },
                            onDelete = {
                                vm.clearMutationError()
                                selectedId = conversation.id
                                selectedTitle = conversation.title
                                dialogAction = "delete"
                            },
                        )
                    }
                    item { Spacer(Modifier.size(AppSpacing.sm)) }
                }
            }
        }
    }

    dialogAction?.let { action ->
        val isRename = action == "rename"
        AlertDialog(
            onDismissRequest = { if (!busy) { dialogAction = null; vm.clearMutationError() } },
            title = {
                Text(if (isRename) renameLabel else stringResource(R.string.appmuse_conversations_delete_confirmation))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                    if (isRename) {
                        OutlinedTextField(
                            value = titleInput,
                            onValueChange = { titleInput = it },
                            label = { Text("Nama percakapan") },
                            supportingText = { Text("${titleInput.trim().length}/120") },
                            isError = titleInput.isBlank() || titleInput.trim().length > 120,
                            singleLine = true,
                            enabled = !busy,
                        )
                    } else {
                        Text("\"$selectedTitle\" beserta seluruh pesannya akan dihapus dari perangkat. Tindakan ini tidak dapat dibatalkan.")
                    }
                    mutationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (busy) Text("Menyimpan perubahan…", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && (!isRename || (titleInput.isNotBlank() && titleInput.trim().length <= 120)),
                    onClick = {
                        if (isRename) vm.rename(selectedId, titleInput) else vm.delete(selectedId)
                    },
                ) {
                    Text(
                        if (isRename) renameLabel else deleteLabel,
                        color = if (isRename) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { dialogAction = null; vm.clearMutationError() }) {
                    Text("Batal")
                }
            },
        )
    }
}

@Composable
private fun ConversationRow(
    conversation: LocalConversation,
    enabled: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale("id", "ID"))
        .format(Date(conversation.updatedAt))
    ProfileSurface(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(AppSpacing.xxl).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.Chat, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Column(Modifier.weight(1f).padding(start = AppSpacing.md)) {
                Text(conversation.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(date, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            conversation.messages.lastOrNull { it.content.isNotBlank() }?.content ?: "Belum ada pesan. Mulai ide baru Anda di sini.",
            modifier = Modifier.padding(top = AppSpacing.sm),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onOpen, enabled = enabled, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.appmuse_action_conversations_open))
            }
            IconButton(onClick = onRename, enabled = enabled) {
                Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.appmuse_action_conversations_rename), tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.appmuse_action_conversations_delete), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
