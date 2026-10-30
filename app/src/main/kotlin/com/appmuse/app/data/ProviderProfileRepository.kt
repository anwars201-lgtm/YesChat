package com.appmuse.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.appmuse.app.model.BehaviorProfile
import com.appmuse.app.model.LegacyProviderArchive
import com.appmuse.app.model.ProviderArchive
import com.appmuse.app.model.ProviderConfig
import com.appmuse.app.model.migrated
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Provider credentials never enter ApiClient, analytics, backups, or the app backend.
 *  The archive is split in two layers: providers (connection + model) and behaviours
 *  (reasoning + system prompt), selectable independently. Files written by the
 *  pre-split single-profile format are migrated 1:1 on first read. */
class ProviderProfileRepository private constructor(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "yeschat-profiles.enc"))
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _archive = MutableStateFlow<ProviderArchive?>(null)
    val archive = _archive.asStateFlow()
    private var rewriteAfterMigration = false

    suspend fun load(): ProviderArchive = withContext(Dispatchers.IO) {
        mutex.withLock {
            val loaded = read()
            if (rewriteAfterMigration) {
                write(loaded)
                rewriteAfterMigration = false
            }
            _archive.value = loaded
            loaded
        }
    }

    suspend fun saveProvider(config: ProviderConfig) {
        require(config.name.isNotBlank()) { "Nama penyedia wajib diisi." }
        require(config.apiKey.isNotBlank()) { "API Key wajib diisi." }
        require(config.model.isNotBlank()) { "Model wajib diisi." }
        val url = config.baseUrl.toHttpUrlOrNull()
        require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
            "Gunakan Base URL HTTPS yang valid tanpa kredensial, kueri, atau fragmen."
        }
        update { current ->
            current.copy(
                providers = current.providers.filterNot { it.id == config.id } + config,
                selectedProviderId = current.selectedProviderId ?: config.id,
            )
        }
    }

    suspend fun saveBehavior(behavior: BehaviorProfile) {
        require(behavior.name.isNotBlank()) { "Nama perilaku wajib diisi." }
        require(behavior.reasoningEffort in setOf("default", "low", "medium", "high")) {
            "Tingkat penalaran tidak valid."
        }
        update { current ->
            current.copy(
                behaviors = current.behaviors.filterNot { it.id == behavior.id } + behavior,
                selectedBehaviorId = current.selectedBehaviorId ?: behavior.id,
            )
        }
    }

    suspend fun selectProvider(id: String) = update { current ->
        require(current.providers.any { it.id == id }) { "Penyedia tidak ditemukan." }
        current.copy(selectedProviderId = id)
    }

    suspend fun selectBehavior(id: String) = update { current ->
        require(current.behaviors.any { it.id == id }) { "Perilaku tidak ditemukan." }
        current.copy(selectedBehaviorId = id)
    }

    suspend fun deleteProvider(id: String) = update { current ->
        val remaining = current.providers.filterNot { it.id == id }
        current.copy(providers = remaining, selectedProviderId = current.selectedProviderId.takeUnless { it == id } ?: remaining.firstOrNull()?.id)
    }

    suspend fun deleteBehavior(id: String) = update { current ->
        val remaining = current.behaviors.filterNot { it.id == id }
        current.copy(behaviors = remaining, selectedBehaviorId = current.selectedBehaviorId.takeUnless { it == id } ?: remaining.firstOrNull()?.id)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun read(): ProviderArchive {
        _archive.value?.let { return it }
        val bytes = try { file.openRead().use { it.readBytes() } }
        catch (error: java.io.FileNotFoundException) {
            if (file.baseFile.exists()) throw error
            return ProviderArchive()
        }
        require(bytes.size > 12) { "Penyimpanan profil tidak dapat dibaca." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val text = cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        val element = json.parseToJsonElement(text)
        if (element is JsonObject && "profiles" in element) {
            rewriteAfterMigration = true
            return json.decodeFromString<LegacyProviderArchive>(text).migrated()
        }
        return json.decodeFromString<ProviderArchive>(text)
    }

    private fun write(next: ProviderArchive) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(json.encodeToString(next).toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }

    private suspend fun update(transform: (ProviderArchive) -> ProviderArchive) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = transform(read())
            write(next)
            _archive.value = next
        }
    }

    companion object {
        private const val KEY_ALIAS = "yeschat-provider-profiles-v1"
        @Volatile private var instance: ProviderProfileRepository? = null
        fun get(context: Context): ProviderProfileRepository = instance ?: synchronized(this) {
            instance ?: ProviderProfileRepository(context.applicationContext).also { instance = it }
        }
    }
}
