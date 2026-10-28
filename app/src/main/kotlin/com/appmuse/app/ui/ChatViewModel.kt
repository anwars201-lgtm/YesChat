package com.appmuse.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appmuse.app.data.ConversationRepository
import com.appmuse.app.data.ProviderChatRepository
import com.appmuse.app.data.ProviderProfileRepository
import com.appmuse.app.model.ConversationMessage
import com.appmuse.app.model.LocalConversation
import com.appmuse.app.model.ProviderProfile
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val conversations = ConversationRepository.get(application)
    private val profiles = ProviderProfileRepository.get(application)
    private val provider = ProviderChatRepository()
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
                                val profileId = selected?.profileId ?: profileState.selectedProfileId
                                ChatUiState.Success(selected, profileState.profiles, profileState.profiles.firstOrNull { it.id == profileId })
                            }
                        }.collect { _state.value = it }
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
        val profileId = selected?.profileId ?: settings.selectedProfileId
        _state.value = ChatUiState.Success(selected, settings.profiles, settings.profiles.firstOrNull { it.id == profileId })
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
                profiles.select(id)
                current.conversation?.let { c -> conversations.updateConversation(c.id) { it.copy(profileId = id) } }
                _notice.value = "Profil diganti. Permintaan berikutnya memakai riwayat lengkap dan instruksi sistem profil ini."
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _actionError.value = "Profil tidak dapat disimpan. Periksa penyimpanan perangkat dan coba lagi."
            } finally { _busy.value = false }
        }
    }

    fun sendAndStream() {
        if (_busy.value || streamJob?.isActive == true) return
        val current = _state.value as? ChatUiState.Success ?: return
        val text = _draft.value.trim()
        if (text.isBlank()) return
        val profile = current.selectedProfile ?: run {
            _actionError.value = "Pilih profil penyedia terlebih dahulu melalui Pengaturan."
            return
        }
        _busy.value = true
        _streaming.value = true
        _actionError.value = null
        _draft.value = ""
        _notice.value = null
        val now = System.currentTimeMillis()
        val user = ConversationMessage(UUID.randomUUID().toString(), "user", text, now, profile.id, "pending")
        val assistantId = UUID.randomUUID().toString()
        var assistant = ConversationMessage(assistantId, "assistant", "", now + 1, profile.id, "streaming")
        val history = current.conversation?.messages.orEmpty() + user.copy(status = "complete")
        var target = current.conversation
        val optimistic = target ?: LocalConversation(UUID.randomUUID().toString(), text.take(80), now, now, profile.id)
        _state.value = current.copy(conversation = optimistic.copy(messages = optimistic.messages + user + assistant))
        streamJob = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            var persisted = false
            var lastSaved = 0L
            try {
                if (target == null) {
                    target = conversations.create(text.take(80))
                }
                val targetId = requireNotNull(target).id
                withContext(NonCancellable) {
                    conversations.updateConversation(targetId) {
                        it.copy(profileId = profile.id, updatedAt = now, messages = it.messages + user.copy(status = "complete") + assistant)
                    }
                    persisted = true
                }
                ensureActive()
                provider.stream(profile, history).collect { token ->
                    assistant = assistant.copy(content = assistant.content + token)
                    val visible = _state.value as? ChatUiState.Success
                    if (visible?.conversation?.id == targetId) {
                        _state.value = visible.copy(conversation = visible.conversation.copy(messages = visible.conversation.messages.map {
                            if (it.id == assistantId) assistant else it
                        }))
                    }
                    val time = System.currentTimeMillis()
                    if (time - lastSaved >= 150) {
                        saveAssistant(targetId, assistant)
                        lastSaved = time
                    }
                }
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
