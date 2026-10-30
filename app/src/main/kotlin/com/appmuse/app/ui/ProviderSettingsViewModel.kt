package com.appmuse.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appmuse.app.data.ApiError
import com.appmuse.app.data.ProviderChatRepository
import com.appmuse.app.data.ProviderProfileRepository
import com.appmuse.app.model.BehaviorProfile
import com.appmuse.app.model.ProviderArchive
import com.appmuse.app.model.ProviderConfig
import com.appmuse.app.model.resolvedWith
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
        val providerEditor: ProviderConfig? = null,
        val behaviorEditor: BehaviorProfile? = null,
        val fieldErrors: Map<String, String> = emptyMap(),
        val action: String? = null,
        val error: String? = null,
        val notice: String? = null,
        val models: List<String>? = null,
        val modelsError: String? = null,
        val pendingDeleteProvider: ProviderConfig? = null,
        val pendingDeleteBehavior: BehaviorProfile? = null,
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
                _state.value = ProviderSettingsUiState.Error("Pengaturan tidak dapat dibaca. Data lama tidak dihapus. Periksa penyimpanan perangkat lalu coba lagi.")
            }
        }
    }

    private fun current() = _state.value as? ProviderSettingsUiState.Success
    private fun change(transform: (ProviderSettingsUiState.Success) -> ProviderSettingsUiState.Success) {
        current()?.let { _state.value = transform(it) }
    }

    fun manageProvider(id: String? = null) {
        val s = current() ?: return
        if (s.action != null) return
        val config = if (id == null) ProviderConfig(UUID.randomUUID().toString(), "", "", "", "")
            else s.archive.providers.firstOrNull { it.id == id } ?: return
        _state.value = s.copy(providerEditor = config, behaviorEditor = null, fieldErrors = emptyMap(), error = null, notice = null, models = null, modelsError = null)
    }

    fun manageBehavior(id: String? = null) {
        val s = current() ?: return
        if (s.action != null) return
        val behavior = if (id == null) BehaviorProfile(UUID.randomUUID().toString(), "")
            else s.archive.behaviors.firstOrNull { it.id == id } ?: return
        _state.value = s.copy(behaviorEditor = behavior, providerEditor = null, fieldErrors = emptyMap(), error = null, notice = null, models = null, modelsError = null)
    }

    fun closeEditor() {
        if (current()?.action != null) return
        change { it.copy(providerEditor = null, behaviorEditor = null, error = null, fieldErrors = emptyMap(), models = null, modelsError = null, notice = null) }
    }

    fun editProvider(field: String, value: String) {
        val s = current() ?: return
        val p = s.providerEditor ?: return
        if (s.action != null) return
        val next = when (field) {
            "name" -> p.copy(name = value)
            "baseUrl" -> p.copy(baseUrl = value)
            "apiKey" -> p.copy(apiKey = value)
            "model" -> p.copy(model = value)
            else -> return
        }
        val resetModels = field == "baseUrl" || field == "apiKey"
        _state.value = s.copy(
            providerEditor = next,
            fieldErrors = s.fieldErrors - field,
            error = null,
            notice = null,
            models = if (resetModels) null else s.models,
            modelsError = if (resetModels) null else s.modelsError,
        )
    }

    fun editBehavior(field: String, value: String) {
        val s = current() ?: return
        val b = s.behaviorEditor ?: return
        if (s.action != null) return
        val next = when (field) {
            "name" -> b.copy(name = value)
            "reasoningEffort" -> b.copy(reasoningEffort = value)
            "systemPrompt" -> b.copy(systemPrompt = value)
            else -> return
        }
        _state.value = s.copy(
            behaviorEditor = next,
            fieldErrors = s.fieldErrors - field,
            error = null,
            notice = null,
        )
    }

    private fun validatedProvider(requireModel: Boolean): ProviderConfig? {
        val s = current() ?: return null
        val p = s.providerEditor ?: return null
        if (s.action != null) return null
        val normalized = p.copy(name = p.name.trim(), baseUrl = p.baseUrl.trim().trimEnd('/'), apiKey = p.apiKey.trim(), model = p.model.trim())
        val errors = validateProviderConfig(normalized, requireModel)
        change { it.copy(fieldErrors = errors, error = null, notice = null) }
        return normalized.takeIf { errors.isEmpty() }
    }

    private fun validatedBehavior(): BehaviorProfile? {
        val s = current() ?: return null
        val b = s.behaviorEditor ?: return null
        if (s.action != null) return null
        val normalized = b.copy(name = b.name.trim())
        val errors = validateBehaviorProfile(normalized)
        change { it.copy(fieldErrors = errors, error = null, notice = null) }
        return normalized.takeIf { errors.isEmpty() }
    }

    fun saveProvider() {
        val p = validatedProvider(requireModel = true) ?: return
        runAction("save") {
            profiles.saveProvider(p)
            change { it.copy(providerEditor = null, models = null, modelsError = null, notice = "Penyedia berhasil disimpan di perangkat.") }
        }
    }

    fun saveBehavior() {
        val b = validatedBehavior() ?: return
        runAction("save") {
            profiles.saveBehavior(b)
            change { it.copy(behaviorEditor = null, notice = "Perilaku berhasil disimpan di perangkat.") }
        }
    }

    fun selectProvider(id: String) {
        if (current()?.archive?.providers?.none { it.id == id } != false) return
        runAction("select") {
            profiles.selectProvider(id)
            change { it.copy(notice = "Penyedia aktif diperbarui. Percakapan baru akan menggunakan penyedia ini.") }
        }
    }

    fun selectBehavior(id: String) {
        if (current()?.archive?.behaviors?.none { it.id == id } != false) return
        runAction("select") {
            profiles.selectBehavior(id)
            change { it.copy(notice = "Perilaku aktif diperbarui dan berlaku untuk semua penyedia.") }
        }
    }

    fun requestDeleteProvider(id: String?) {
        if (current()?.action != null) return
        change { s -> s.copy(pendingDeleteProvider = s.archive.providers.firstOrNull { it.id == id }, error = null) }
    }

    fun requestDeleteBehavior(id: String?) {
        if (current()?.action != null) return
        change { s -> s.copy(pendingDeleteBehavior = s.archive.behaviors.firstOrNull { it.id == id }, error = null) }
    }

    fun deleteProvider() {
        val p = current()?.pendingDeleteProvider ?: return
        runAction("delete") {
            profiles.deleteProvider(p.id)
            change { it.copy(pendingDeleteProvider = null, notice = "Penyedia dihapus. Riwayat percakapan tetap tersimpan.") }
        }
    }

    fun deleteBehavior() {
        val b = current()?.pendingDeleteBehavior ?: return
        runAction("delete") {
            profiles.deleteBehavior(b.id)
            change { it.copy(pendingDeleteBehavior = null, notice = "Perilaku dihapus.") }
        }
    }

    fun fetchModels() {
        val p = validatedProvider(requireModel = false) ?: return
        change { it.copy(models = null, modelsError = null) }
        runAction("models", network = true) {
            val models = provider.fetchModels(p.resolvedWith(null))
            change { it.copy(models = models) }
        }
    }

    fun testConnection() {
        val p = validatedProvider(requireModel = true) ?: return
        val s = current() ?: return
        val behavior = s.archive.behaviors.firstOrNull { it.id == s.archive.selectedBehaviorId } ?: s.archive.behaviors.firstOrNull()
        runAction("test", network = true) {
            provider.testConnection(p.resolvedWith(behavior))
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

internal fun validateProviderConfig(
    config: ProviderConfig,
    requireModel: Boolean,
): Map<String, String> = buildMap {
    if (config.name.isBlank()) put("name", "Nama penyedia wajib diisi.")
    val url = config.baseUrl.toHttpUrlOrNull()
    if (url == null || !url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) {
        put("baseUrl", "Gunakan Base URL HTTPS tanpa kredensial, kueri, atau fragmen. Sertakan /v1 jika diperlukan penyedia.")
    }
    if (config.apiKey.isBlank() || config.apiKey.any { it.code !in 33..126 }) {
        put("apiKey", "API Key wajib diisi tanpa spasi atau karakter kontrol.")
    }
    if (requireModel && config.model.isBlank()) put("model", "Pilih model dari daftar atau masukkan nama model.")
}

internal fun validateBehaviorProfile(
    behavior: BehaviorProfile,
): Map<String, String> = buildMap {
    if (behavior.name.isBlank()) put("name", "Nama perilaku wajib diisi.")
    if (behavior.reasoningEffort !in setOf("default", "low", "medium", "high")) put("reasoningEffort", "Pilih tingkat penalaran yang tersedia.")
}
