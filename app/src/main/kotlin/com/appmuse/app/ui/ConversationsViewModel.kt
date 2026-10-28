package com.appmuse.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appmuse.app.data.ConversationRepository
import com.appmuse.app.model.LocalConversation
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ConversationsUiState {
    data object Loading : ConversationsUiState
    data object Empty : ConversationsUiState
    data class Success(val conversations: List<LocalConversation>) : ConversationsUiState
    data class Error(val message: String) : ConversationsUiState
}

class ConversationsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ConversationRepository.get(application)
    private val _state = MutableStateFlow<ConversationsUiState>(ConversationsUiState.Loading)
    val state = _state.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _mutationError = MutableStateFlow<String?>(null)
    val mutationError = _mutationError.asStateFlow()
    private val _openedId = MutableStateFlow<String?>(null)
    val openedId = _openedId.asStateFlow()
    private val _mutationComplete = MutableStateFlow(0)
    val mutationComplete = _mutationComplete.asStateFlow()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            repository.archive.collect { archive ->
                if (archive != null) {
                    _state.value = if (archive.conversations.isEmpty()) ConversationsUiState.Empty
                    else ConversationsUiState.Success(archive.conversations.sortedByDescending { it.updatedAt })
                }
            }
        }
        load()
    }

    fun load() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _state.value = ConversationsUiState.Loading
            try {
                val archive = repository.load()
                _state.value = if (archive.conversations.isEmpty()) ConversationsUiState.Empty
                else ConversationsUiState.Success(archive.conversations.sortedByDescending { it.updatedAt })
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _state.value = ConversationsUiState.Error(friendlyError(error))
            }
        }
    }

    fun create(title: String) = mutate {
        _openedId.value = repository.create(title).id
    }

    fun browseAndOpen(id: String) = mutate {
        repository.browseAndOpen(id)
        _openedId.value = id
    }

    fun rename(id: String, title: String) = mutate { repository.rename(id, title) }
    fun delete(id: String) = mutate { repository.delete(id) }
    fun consumeOpen() { _openedId.value = null }
    fun clearMutationError() { if (!_busy.value) _mutationError.value = null }

    private fun mutate(action: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        _mutationError.value = null
        viewModelScope.launch {
            try {
                action()
                _mutationComplete.value += 1
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _mutationError.value = friendlyError(error)
            } finally {
                _busy.value = false
            }
        }
    }

    private fun friendlyError(error: Exception): String = when (error) {
        is IllegalArgumentException -> error.message ?: "Periksa kembali nama percakapan."
        is IOException -> "Penyimpanan perangkat tidak dapat diakses. Periksa ruang kosong dan coba lagi."
        else -> "Data percakapan tidak dapat dibaca atau disimpan. Data lama tidak dihapus. Coba lagi."
    }
}
