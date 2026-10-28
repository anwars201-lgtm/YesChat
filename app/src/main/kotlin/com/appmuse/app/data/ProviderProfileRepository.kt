package com.appmuse.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.appmuse.app.model.ProviderArchive
import com.appmuse.app.model.ProviderProfile
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Provider credentials never enter ApiClient, analytics, backups, or the app backend. */
class ProviderProfileRepository private constructor(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "yeschat-profiles.enc"))
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _archive = MutableStateFlow<ProviderArchive?>(null)
    val archive = _archive.asStateFlow()

    suspend fun load(): ProviderArchive = withContext(Dispatchers.IO) {
        mutex.withLock { read().also { _archive.value = it } }
    }

    suspend fun save(profile: ProviderProfile) {
        require(profile.name.isNotBlank()) { "Nama profil wajib diisi." }
        require(profile.apiKey.isNotBlank()) { "API Key wajib diisi." }
        require(profile.model.isNotBlank()) { "Model wajib diisi." }
        require(profile.reasoningEffort in setOf("default", "low", "medium", "high")) {
            "Tingkat penalaran tidak valid."
        }
        val url = profile.baseUrl.toHttpUrlOrNull()
        require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
            "Gunakan Base URL HTTPS yang valid tanpa kredensial, kueri, atau fragmen."
        }
        update { current ->
            current.copy(
                profiles = current.profiles.filterNot { it.id == profile.id } + profile,
                selectedProfileId = current.selectedProfileId ?: profile.id,
            )
        }
    }

    suspend fun select(id: String) = update { current ->
        require(current.profiles.any { it.id == id }) { "Profil penyedia tidak ditemukan." }
        current.copy(selectedProfileId = id)
    }

    suspend fun delete(id: String) = update { current ->
        val remaining = current.profiles.filterNot { it.id == id }
        current.copy(profiles = remaining, selectedProfileId = current.selectedProfileId.takeUnless { it == id } ?: remaining.firstOrNull()?.id)
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
        return json.decodeFromString<ProviderArchive>(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8))
    }

    private suspend fun update(transform: (ProviderArchive) -> ProviderArchive) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = transform(read())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val bytes = cipher.iv + cipher.doFinal(json.encodeToString(next).toByteArray(Charsets.UTF_8))
            val stream = file.startWrite()
            try { stream.write(bytes); file.finishWrite(stream) }
            catch (error: Exception) { file.failWrite(stream); throw error }
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
