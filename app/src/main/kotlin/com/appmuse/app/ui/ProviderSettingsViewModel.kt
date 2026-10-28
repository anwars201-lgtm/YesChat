package com.appmuse.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appmuse.app.data.ApiError
import com.appmuse.app.data.ProviderChatRepository
import com.appmuse.app.data.ProviderProfileRepository
import com.appmuse.app.model.ProviderArchive
import com.appmuse.app.model.ProviderProfile
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

sealed interface ProviderSettingsUiState {
    data object Loading : ProviderSettingsUiState
    data class Error(val message: String) : ProviderSettingsUiState
    data class Success(
        val archive: ProviderArchive,
        val editor: ProviderProfile? = null,
        val fieldErrors: Map<String, String> = emptyMap(),
        val action: String? = null,
        val error: String? = null,
        val notice: String? = null,
        val models: List<String>? = null,
        val modelsError: String? = null,
        val pendingDelete: ProviderProfile? = null,
    ) : ProviderSettingsUiState
}

class ProviderSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val profiles = ProviderProfileRepository.get(application)
    private val provider = ProviderChatRepository()
    private val _state = MutableStateFlow<ProviderSettingsUiState>(ProviderSettingsUiState.Loading)
    val state = _state.asStateFlow()
    private var observer: Job? = null
    private var loadJob: Job? = null
    private var actionJob: Job? = null

    init { load() }

    fun load() {
        if (loadJob?.isActive == true || current()?.action != null) return
        _state.value = ProviderSettingsUiState.Loading
        loadJob = viewModelScope.launch {
            try {
                val archive = profiles.load()
                _state.value = ProviderSettingsUiState.Success(archive)
                if (observer == null) observer = viewModelScope.launch {
                    profiles.archive.collect { value ->
                        if (value != null) _state.value = current()?.copy(archive = value)
                            ?: ProviderSettingsUiState.Success(value)
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _state.value = ProviderSettingsUiState.Error("Profil tidak dapat dibaca. Data lama tidak dihapus. Periksa penyimpanan perangkat lalu coba lagi.")
            }
        }
    }

    private fun current() = _state.value as? ProviderSettingsUiState.Success
    private fun change(transform: (ProviderSettingsUiState.Success) -> ProviderSettingsUiState.Success) {
        current()?.let { _state.value = transform(it) }
    }

    fun manageProfiles(id: String? = null) {
        val s = current() ?: return
        if (s.action != null) return
        val profile = if (id == null) ProviderProfile(UUID.randomUUID().toString(), "", "", "", "")
            else s.archive.profiles.firstOrNull { it.id == id } ?: return
        _state.value = s.copy(editor = profile, fieldErrors = emptyMap(), error = null, notice = null, models = null, modelsError = null)
    }

    fun closeEditor() {
        if (current()?.action != null) return
        change { it.copy(editor = null, error = null, fieldErrors = emptyMap(), models = null, modelsError = null, notice = null) }
    }

    fun edit(field: String, value: String) {
        val s = current() ?: return
        val p = s.editor ?: return
        if (s.action != null) return
        val next = when (field) {
            "name" -> p.copy(name = value)
            "baseUrl" -> p.copy(baseUrl = value)
            "apiKey" -> p.copy(apiKey = value)
            "model" -> p.copy(model = value)
            "reasoningEffort" -> p.copy(reasoningEffort = value)
            "systemPrompt" -> p.copy(systemPrompt = value)
            else -> return
        }
        val resetModels = field == "baseUrl" || field == "apiKey"
        _state.value = s.copy(
            editor = next,
            fieldErrors = s.fieldErrors - field,
            error = null,
            notice = null,
            models = if (resetModels) null else s.models,
            modelsError = if (resetModels) null else s.modelsError,
        )
    }

    private fun validated(requireModel: Boolean, requireName: Boolean = false): ProviderProfile? {
        val s = current() ?: return null
        val p = s.editor ?: return null
        if (s.action != null) return null
        val normalized = p.copy(name = p.name.trim(), baseUrl = p.baseUrl.trim().trimEnd('/'), apiKey = p.apiKey.trim(), model = p.model.trim())
        val errors = validateProviderProfile(normalized, requireModel, requireName)
        change { it.copy(fieldErrors = errors, error = null, notice = null) }
        return normalized.takeIf { errors.isEmpty() }
    }

    fun saveProfile() {
        val p = validated(requireModel = true, requireName = true) ?: return
        runAction("save") {
            profiles.save(p)
            change { it.copy(editor = null, models = null, modelsError = null, notice = "Profil berhasil disimpan di perangkat.") }
        }
    }

    fun selectProfile(id: String) {
        if (current()?.archive?.profiles?.none { it.id == id } != false) return
        runAction("select") {
            profiles.select(id)
            change { it.copy(notice = "Profil aktif diperbarui. Percakapan baru akan menggunakan profil ini.") }
        }
    }

    fun requestDelete(id: String?) {
        if (current()?.action != null) return
        change { s -> s.copy(pendingDelete = s.archive.profiles.firstOrNull { it.id == id }, error = null) }
    }

    fun deleteProfile() {
        val p = current()?.pendingDelete ?: return
        runAction("delete") {
            profiles.delete(p.id)
            change { it.copy(pendingDelete = null, notice = "Profil dihapus. Riwayat percakapan tetap tersimpan.") }
        }
    }

    fun fetchModels() {
        val p = validated(requireModel = false) ?: return
        change { it.copy(models = null, modelsError = null) }
        runAction("models", network = true) {
            val models = provider.fetchModels(p)
            change { it.copy(models = models) }
        }
    }

    fun testConnection() {
        val p = validated(requireModel = true) ?: return
        runAction("test", network = true) {
            provider.testConnection(p)
            change { it.copy(notice = "Koneksi berhasil. Penyedia menerima pengaturan ini dan mengirim teks balasan.") }
        }
    }

    fun cancelRequest() {
        if (current()?.action in setOf("models", "test")) actionJob?.cancel()
    }

    private fun runAction(action: String, network: Boolean = false, block: suspend () -> Unit) {
        val s = current() ?: return
        if (s.action != null || actionJob?.isActive == true) return
        _state.value = s.copy(action = action, error = null, notice = null)
        actionJob = viewModelScope.launch {
            try {
                if (network) withTimeout(45_000) { block() } else block()
            } catch (error: TimeoutCancellationException) {
                showActionError(action, "Waktu tunggu habis. Periksa internet dan Base URL lalu coba lagi.")
            } catch (error: CancellationException) {
                change { it.copy(notice = "Permintaan dibatalkan.") }
                throw error
            } catch (error: Exception) {
                showActionError(action, if (network) networkMessage(error) else "Perubahan belum tersimpan. Periksa penyimpanan perangkat lalu coba lagi.")
            } finally {
                change { it.copy(action = null) }
            }
        }
    }

    private fun showActionError(action: String, message: String) {
        change { if (action == "models") it.copy(modelsError = message) else it.copy(error = message) }
    }

    private fun networkMessage(error: Throwable): String = when (error) {
        is java.net.SocketTimeoutException -> "Waktu tunggu habis. Periksa koneksi internet lalu coba lagi."
        is javax.net.ssl.SSLException -> "Sertifikat HTTPS penyedia tidak valid. Periksa Base URL."
        is java.io.IOException -> "Tidak dapat terhubung ke penyedia. Periksa internet dan Base URL."
        is NoSuchElementException -> "Penyedia tidak mengirim teks balasan. Periksa model dan dukungan streaming Chat Completions."
        else -> when (val classified = ProviderChatRepository.providerError(error)) {
            is ApiError.Unknown -> "Balasan penyedia tidak dapat dibaca. Periksa kompatibilitas API."
            else -> classified.userMessage
        }
    }
}

internal fun validateProviderProfile(
    profile: ProviderProfile,
    requireModel: Boolean,
    requireName: Boolean,
): Map<String, String> = buildMap {
    if (requireName && profile.name.isBlank()) put("name", "Nama profil wajib diisi.")
    val url = profile.baseUrl.toHttpUrlOrNull()
    if (url == null || !url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) {
        put("baseUrl", "Gunakan Base URL HTTPS tanpa kredensial, kueri, atau fragmen. Sertakan /v1 jika diperlukan penyedia.")
    }
    if (profile.apiKey.isBlank() || profile.apiKey.any { it.code !in 33..126 }) {
        put("apiKey", "API Key wajib diisi tanpa spasi atau karakter kontrol.")
    }
    if (requireModel && profile.model.isBlank()) put("model", "Pilih model dari daftar atau masukkan nama model.")
    if (profile.reasoningEffort !in setOf("default", "low", "medium", "high")) put("reasoningEffort", "Pilih tingkat penalaran yang tersedia.")
}
