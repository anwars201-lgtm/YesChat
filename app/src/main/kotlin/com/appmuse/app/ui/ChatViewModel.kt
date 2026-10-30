package com.appmuse.app.ui

import android.app.Application
import android.net.Uri
import com.appmuse.app.data.ChatAttachmentRepository
import com.appmuse.app.model.ChatAttachment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appmuse.app.data.ConversationRepository
import com.appmuse.app.data.ProviderChatRepository
import com.appmuse.app.data.ProviderProfileRepository
import com.appmuse.app.model.ConversationMessage
import com.appmuse.app.model.LocalConversation
import com.appmuse.app.model.ProviderArchive
import com.appmuse.app.model.ProviderProfile
import com.appmuse.app.model.resolvedWith
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ChatUiState {
    data object Loading : ChatUiState
    data class Error(val message: String) : ChatUiState
    data class Success(
        val conversation: LocalConversation?,
        val profiles: List<ProviderProfile>,
        val selectedProfile: ProviderProfile?,
    ) : ChatUiState
}

sealed interface ChatModelsUiState {
    data object Loading : ChatModelsUiState
    data class Success(val models: List<String>) : ChatModelsUiState
    data class Error(val message: String) : ChatModelsUiState
}

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val conversations = ConversationRepository.get(application)
    private val profiles = ProviderProfileRepository.get(application)
    private val provider = ProviderChatRepository()
    private val attachmentReader = ChatAttachmentRepository(application)
    private val _attachments = MutableStateFlow<List<ChatAttachment>>(emptyList())
    val attachments = _attachments.asStateFlow()
    private val _models = MutableStateFlow<ChatModelsUiState>(ChatModelsUiState.Loading)
    val models = _models.asStateFlow()
    private var modelsJob: Job? = null
    private var optionsJob: Job? = null
    private val _state = MutableStateFlow<ChatUiState>(ChatUiState.Loading)
    val state = _state.asStateFlow()
    private val _draft = MutableStateFlow("")
    val draft = _draft.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _streaming = MutableStateFlow(false)
    val streaming = _streaming.asStateFlow()
    private val _actionError = MutableStateFlow<String?>(null)
    val actionError = _actionError.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()
    private var streamJob: Job? = null
    private var loadJob: Job? = null
    private var observing = false

    init { load() }

    fun load() {
        if (loadJob?.isActive == true) return
        if (_busy.value) return
        loadJob = viewModelScope.launch {
            _state.value = ChatUiState.Loading
            try {
                val archive = conversations.load()
                profiles.load()
                archive.conversations.filter { c -> c.messages.any { it.status in setOf("streaming", "pending") } }.forEach { c ->
                    conversations.updateConversation(c.id) { current ->
                        current.copy(messages = current.messages.map {
                            if (it.status in setOf("streaming", "pending")) it.copy(status = "interrupted") else it
                        })
                    }
                }
                if (!observing) {
                    observing = true
                    viewModelScope.launch {
                        combine(conversations.archive, profiles.archive) { archiveState, profileState ->
                            if (archiveState == null || profileState == null) ChatUiState.Loading
                            else {
                                val selected = archiveState.conversations.firstOrNull { it.id == archiveState.selectedConversationId }
                                val resolved = resolveProfiles(profileState)
                                val profileId = selected?.profileId ?: profileState.selectedProviderId
                                ChatUiState.Success(selected, resolved, effectiveProfile(selected, resolved.firstOrNull { it.id == profileId }))
                            }
                        }.collect { next ->
                            val previous = _state.value as? ChatUiState.Success
                            if (!_busy.value && previous?.conversation != null && next is ChatUiState.Success &&
                                previous.conversation.id != next.conversation?.id) {
                                _draft.value = ""
                                _attachments.value = emptyList()
                                _actionError.value = null
                                modelsJob?.cancel()
                            }
                            _state.value = next
                        }
                    }
                } else refreshState()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _state.value = ChatUiState.Error("Data perangkat tidak dapat dibaca. Data lama tidak dihapus. Periksa penyimpanan dan coba lagi.")
            }
        }
    }

    private fun refreshState() {
        val archive = conversations.archive.value ?: return
        val settings = profiles.archive.value ?: return
        val selected = archive.conversations.firstOrNull { it.id == archive.selectedConversationId }
        val resolved = resolveProfiles(settings)
        val profileId = selected?.profileId ?: settings.selectedProviderId
        _state.value = ChatUiState.Success(selected, resolved, effectiveProfile(selected, resolved.firstOrNull { it.id == profileId }))
    }

    /** Providers resolved against the selected behaviour (falling back to the
     *  first behaviour, then to defaults) — the list the chat UI consumes. */
    private fun resolveProfiles(archive: ProviderArchive): List<ProviderProfile> {
        val behavior = archive.behaviors.firstOrNull { it.id == archive.selectedBehaviorId } ?: archive.behaviors.firstOrNull()
        return archive.providers.map { it.resolvedWith(behavior) }
    }

    private fun effectiveProfile(conversation: LocalConversation?, profile: ProviderProfile?): ProviderProfile? = profile?.copy(
        model = conversation?.modelOverride ?: profile.model,
        reasoningEffort = conversation?.reasoningOverride ?: profile.reasoningEffort,
    )

    fun loadModels() {
        modelsJob?.cancel()
        val profile = (_state.value as? ChatUiState.Success)?.selectedProfile ?: return
        modelsJob = viewModelScope.launch {
            _models.value = ChatModelsUiState.Loading
            _models.value = try { ChatModelsUiState.Success(provider.fetchModels(profile)) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                ChatModelsUiState.Error(ProviderChatRepository.providerError(error).userMessage)
            }
        }
    }

    fun setModel(value: String) {
        val model = value.trim()
        if (model.isBlank() || model.length > 200) {
            _actionError.value = "Nama model wajib diisi dan maksimal 200 karakter."
            return
        }
        updateOptions(model = model)
    }

    fun setReasoning(value: String) {
        if (value !in setOf("default", "low", "medium", "high")) return
        updateOptions(reasoning = value)
    }

    private fun updateOptions(model: String? = null, reasoning: String? = null) {
        if (_busy.value) return
        val current = _state.value as? ChatUiState.Success ?: return
        val profile = current.selectedProfile ?: return
        val previousSave = optionsJob
        optionsJob = viewModelScope.launch {
            previousSave?.join()
            try {
                val conversation = current.conversation ?: conversations.archive.value?.let { archive ->
                    archive.conversations.firstOrNull { it.id == archive.selectedConversationId }
                }
                if (conversation == null) {
                    conversations.create(
                        title = "Percakapan baru",
                        profileId = profile.id,
                        modelOverride = model ?: profile.model,
                        reasoningOverride = reasoning ?: profile.reasoningEffort,
                    )
                } else {
                    conversations.updateConversation(conversation.id) {
                        it.copy(profileId = profile.id, modelOverride = model ?: it.modelOverride,
                            reasoningOverride = reasoning ?: it.reasoningOverride)
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _actionError.value = "Pilihan belum tersimpan. Periksa penyimpanan perangkat dan coba lagi."
            }
        }
    }

    fun attachFile(uri: Uri) {
        if (_busy.value) return
        if (_attachments.value.size >= ChatAttachmentRepository.MAX_ATTACHMENTS) {
            _actionError.value = "Maksimal empat lampiran per pesan."
            return
        }
        _busy.value = true
        _actionError.value = null
        viewModelScope.launch {
            try {
                val attachment = attachmentReader.read(uri)
                require(_attachments.value.sumOf { it.sizeBytes } + attachment.sizeBytes <= ChatAttachmentRepository.MAX_TOTAL_BYTES) {
                    "Total lampiran per pesan maksimal 12 MB."
                }
                _attachments.value += attachment
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _actionError.value = when (error) {
                    is com.appmuse.app.data.ApiError -> error.userMessage
                    is IllegalArgumentException -> error.message
                    else -> "Lampiran tidak dapat dibaca. Gunakan gambar atau dokumen teks yang tersimpan di perangkat."
                }
            } finally { _busy.value = false }
        }
    }

    fun removeAttachment(id: String) {
        if (!_busy.value) _attachments.value = _attachments.value.filterNot { it.id == id }
    }

    fun setDraft(value: String) { _draft.value = value }
    fun consumeNotice() { _notice.value = null }
    fun copyContent(content: String): String {
        _notice.value = "Berhasil disalin."
        return content
    }

    fun switchProfile(id: String) {
        if (_busy.value) return
        val current = _state.value as? ChatUiState.Success ?: return
        if (current.profiles.none { it.id == id }) return
        _busy.value = true
        _actionError.value = null
        viewModelScope.launch {
            try {
                profiles.selectProvider(id)
                current.conversation?.let { c -> conversations.updateConversation(c.id) { it.copy(profileId = id, modelOverride = null, reasoningOverride = null) } }
                _notice.value = "Penyedia diganti. Permintaan berikutnya memakai riwayat lengkap percakapan ini lewat penyedia baru."
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _actionError.value = "Profil tidak dapat disimpan. Periksa penyimpanan perangkat dan coba lagi."
            } finally { _busy.value = false }
        }
    }

    fun sendAndStream() {
        if (_busy.value || streamJob?.isActive == true) return
        val pendingOptions = optionsJob
        if (pendingOptions?.isActive == true) {
            viewModelScope.launch {
                pendingOptions.join()
                refreshState()
                sendAndStream()
            }
            return
        }
        val current = _state.value as? ChatUiState.Success ?: return
        val text = _draft.value.trim()
        val selectedAttachments = _attachments.value
        if (text.isBlank() && selectedAttachments.isEmpty()) return
        val profile = current.selectedProfile ?: run {
            _actionError.value = "Pilih penyedia terlebih dahulu melalui Pengaturan."
            return
        }
        _busy.value = true
        _streaming.value = true
        _actionError.value = null
        _draft.value = ""
        _attachments.value = emptyList()
        _notice.value = null
        val now = System.currentTimeMillis()
        val user = ConversationMessage(UUID.randomUUID().toString(), "user", text, now, profile.id, "pending", attachments = selectedAttachments)
        val title = text.ifBlank { selectedAttachments.firstOrNull()?.name ?: "Percakapan baru" }.take(80)
        val assistantId = UUID.randomUUID().toString()
        var assistant = ConversationMessage(assistantId, "assistant", "", now + 1, profile.id, "streaming")
        val history = current.conversation?.messages.orEmpty() + user.copy(status = "complete")
        var target = current.conversation
        val optimistic = target ?: LocalConversation(UUID.randomUUID().toString(), title, now, now, profile.id)
        _state.value = current.copy(conversation = optimistic.copy(messages = optimistic.messages + user + assistant))
        streamJob = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            var persisted = false
            try {
                if (target == null) {
                    target = conversations.create(title)
                }
                val targetId = requireNotNull(target).id
                streamReply(
                    targetId = targetId,
                    profile = profile,
                    history = history,
                    seed = { it.messages + user.copy(status = "complete") + assistant },
                    initialAssistant = assistant,
                    onPersisted = { persisted = true },
                    onAssistant = { assistant = it },
                )
                assistant = if (assistant.content.isBlank()) {
                    _actionError.value = "Penyedia menyelesaikan permintaan tanpa teks balasan. Coba model lain atau periksa tingkat penalaran."
                    assistant.copy(status = "failed")
                } else assistant.copy(status = "complete")
            } catch (error: CancellationException) {
                assistant = assistant.copy(status = "stopped")
                _notice.value = "Balasan dihentikan. Teks yang sudah diterima tetap disimpan."
            } catch (error: Exception) {
                assistant = assistant.copy(status = "failed")
                _actionError.value = if (!persisted) {
                    _draft.value = text
                    _attachments.value = selectedAttachments
                    "Pesan belum dapat disimpan. Periksa ruang penyimpanan lalu kirim ulang."
                } else ProviderChatRepository.providerError(error).userMessage
            } finally {
                withContext(NonCancellable) {
                    if (persisted) {
                        try { saveAssistant(requireNotNull(target).id, assistant) }
                        catch (error: Exception) {
                            _actionError.value = "Teks terakhir belum tersimpan ke perangkat. Salin balasan ini sebelum meninggalkan Chat."
                        }
                    }
                    val visible = _state.value as? ChatUiState.Success
                    if (visible?.conversation?.messages?.any { it.id == assistantId } == true) {
                        _state.value = visible.copy(conversation = visible.conversation.copy(messages = visible.conversation.messages.map {
                            if (it.id == assistantId) assistant else it
                        }))
                    }
                    _streaming.value = false
                    _busy.value = false
                }
            }
        }
    }

    /** Shared streaming core for send and regenerate: writes [seed] as the
     *  conversation's message list, then collects the provider stream into the
     *  assistant message, mirroring chunks into UI state and saving throttled.
     *  The latest accumulated assistant is reported through [onAssistant], so a
     *  caller catching CancellationException still holds the partial text. */
    private suspend fun streamReply(
        targetId: String,
        profile: ProviderProfile,
        history: List<ConversationMessage>,
        seed: (LocalConversation) -> List<ConversationMessage>,
        initialAssistant: ConversationMessage,
        onPersisted: () -> Unit,
        onAssistant: (ConversationMessage) -> Unit,
    ) {
        var assistant = initialAssistant
        var lastSaved = 0L
        withContext(NonCancellable) {
            conversations.updateConversation(targetId) {
                it.copy(profileId = profile.id, updatedAt = System.currentTimeMillis(), messages = seed(it))
            }
            onPersisted()
        }
        currentCoroutineContext().ensureActive()
        provider.stream(profile, history).collect { chunk ->
            assistant = assistant.copy(content = assistant.content + chunk.content, usage = chunk.usage ?: assistant.usage)
            onAssistant(assistant)
            val visible = _state.value as? ChatUiState.Success
            if (visible?.conversation?.id == targetId) {
                _state.value = visible.copy(conversation = visible.conversation.copy(messages = visible.conversation.messages.map {
                    if (it.id == assistant.id) assistant else it
                }))
            }
            val time = System.currentTimeMillis()
            if (time - lastSaved >= 150) {
                saveAssistant(targetId, assistant)
                lastSaved = time
            }
        }
        onAssistant(assistant)
    }

    /** Replaces the newest assistant answer with a fresh stream for the same
     *  history. The old answer is discarded once the replacement is persisted. */
    fun regenerateLast() {
        if (_busy.value || streamJob?.isActive == true) return
        val pendingOptions = optionsJob
        if (pendingOptions?.isActive == true) {
            viewModelScope.launch {
                pendingOptions.join()
                refreshState()
                regenerateLast()
            }
            return
        }
        val current = _state.value as? ChatUiState.Success ?: return
        val conversation = current.conversation ?: return
        val profile = current.selectedProfile ?: run {
            _actionError.value = "Pilih penyedia terlebih dahulu melalui Pengaturan."
            return
        }
        val lastAssistantIndex = conversation.messages.indexOfLast { it.role == "assistant" }
        if (lastAssistantIndex < 0) return
        val history = conversation.messages.subList(0, lastAssistantIndex).map {
            if (it.status == "pending" || it.status == "streaming") it.copy(status = "complete") else it
        }
        if (history.none { it.role == "user" }) return
        _busy.value = true
        _streaming.value = true
        _actionError.value = null
        _notice.value = null
        val now = System.currentTimeMillis()
        var assistant = ConversationMessage(UUID.randomUUID().toString(), "assistant", "", now, profile.id, "streaming")
        val assistantId = assistant.id
        _state.value = current.copy(conversation = conversation.copy(messages = history + assistant))
        val targetId = conversation.id
        streamJob = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            var persisted = false
            try {
                streamReply(
                    targetId = targetId,
                    profile = profile,
                    history = history,
                    seed = { history + assistant },
                    initialAssistant = assistant,
                    onPersisted = { persisted = true },
                    onAssistant = { assistant = it },
                )
                assistant = if (assistant.content.isBlank()) {
                    _actionError.value = "Penyedia menyelesaikan permintaan tanpa teks balasan. Coba model lain atau periksa tingkat penalaran."
                    assistant.copy(status = "failed")
                } else assistant.copy(status = "complete")
            } catch (error: CancellationException) {
                assistant = assistant.copy(status = "stopped")
                _notice.value = "Balasan dihentikan. Teks yang sudah diterima tetap disimpan."
            } catch (error: Exception) {
                assistant = assistant.copy(status = "failed")
                _actionError.value = if (!persisted) {
                    "Balasan lama tidak dapat diganti. Periksa penyimpanan perangkat dan coba lagi."
                } else ProviderChatRepository.providerError(error).userMessage
                if (!persisted) refreshState()
            } finally {
                withContext(NonCancellable) {
                    if (persisted) {
                        try { saveAssistant(targetId, assistant) }
                        catch (error: Exception) {
                            _actionError.value = "Teks terakhir belum tersimpan ke perangkat. Salin balasan ini sebelum meninggalkan Chat."
                        }
                    }
                    val visible = _state.value as? ChatUiState.Success
                    if (visible?.conversation?.messages?.any { it.id == assistantId } == true) {
                        _state.value = visible.copy(conversation = visible.conversation.copy(messages = visible.conversation.messages.map {
                            if (it.id == assistantId) assistant else it
                        }))
                    }
                    _streaming.value = false
                    _busy.value = false
                }
            }
        }
    }

    private suspend fun saveAssistant(id: String, message: ConversationMessage) {
        conversations.updateConversation(id) { current ->
            current.copy(updatedAt = System.currentTimeMillis(), messages = current.messages.map {
                if (it.id == message.id) message else it
            })
        }
    }

    fun stopKeepPartial() { streamJob?.cancel() }

    override fun onCleared() {
        streamJob?.cancel()
        super.onCleared()
    }
}
