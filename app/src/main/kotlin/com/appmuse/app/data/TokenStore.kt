package com.appmuse.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Why an authenticated session was invalidated outside an explicit logout. */
enum class SessionInvalidationReason {
    EXPIRED,
    MISSING_REFRESH_TOKEN,
    STORAGE_FAILURE,
}

data class SessionInvalidation(val reason: SessionInvalidationReason)

/**
 * Narrow session boundary used by networking, repositories and the auth ViewModel.
 * A session is always persisted as one unit; callers cannot create a half-written
 * access-token/refresh-token/user combination.
 */
interface SessionStore {
    val accessToken: String?
    val refreshToken: String?
    val cachedUser: String?
    val invalidations: Flow<SessionInvalidation>

    /** Serializes login/logout/refresh so token rotation cannot resurrect a session during logout. */
    suspend fun <T> serializedMutation(block: suspend () -> T): T

    @Throws(SessionStorageException::class)
    fun persistSession(accessToken: String, refreshToken: String, cachedUser: String)

    @Throws(SessionStorageException::class)
    fun updateTokens(accessToken: String, refreshToken: String?)

    @Throws(SessionStorageException::class)
    fun updateCachedUser(cachedUser: String)

    fun clear()
    fun invalidate(reason: SessionInvalidationReason)
}

class SessionStorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

@Serializable
private data class StoredSession(
    val version: Int = 1,
    val accessToken: String,
    val refreshToken: String,
    val cachedUser: String,
)

/**
 * Keystore-backed, atomic session storage.
 *
 * The AES key is non-exportable in Android Keystore. The authenticated ciphertext
 * lives in noBackupFilesDir, so bearer credentials are neither plaintext nor copied
 * to a different device by cloud backup/device transfer. AtomicFile guarantees that
 * a killed process leaves either the old complete session or the new complete session.
 */
class TokenStore(context: Context) : SessionStore {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }
    private val file = AtomicFile(File(appContext.noBackupFilesDir, SESSION_FILE))
    private val invalidationEvents = MutableSharedFlow<SessionInvalidation>(extraBufferCapacity = 1)
    private val mutationMutex = Mutex()

    init {
        // New projects never create this V1 file. If the storage is introduced
        // during an explicit migration, remove the old plaintext credentials.
        appContext.deleteSharedPreferences(LEGACY_PREFS_NAME)
    }

    @Volatile
    private var snapshot: StoredSession? = loadOrDiscardInvalidSession()

    override val accessToken: String? get() = snapshot?.accessToken
    override val refreshToken: String? get() = snapshot?.refreshToken
    override val cachedUser: String? get() = snapshot?.cachedUser
    override val invalidations: Flow<SessionInvalidation> = invalidationEvents.asSharedFlow()

    override suspend fun <T> serializedMutation(block: suspend () -> T): T {
        mutationMutex.lock()
        return try {
            block()
        } finally {
            mutationMutex.unlock()
        }
    }

    @Synchronized
    override fun persistSession(accessToken: String, refreshToken: String, cachedUser: String) {
        require(accessToken.isNotBlank()) { "accessToken must not be blank" }
        require(refreshToken.isNotBlank()) { "refreshToken must not be blank" }
        write(StoredSession(accessToken = accessToken, refreshToken = refreshToken, cachedUser = cachedUser))
    }

    @Synchronized
    override fun updateTokens(accessToken: String, refreshToken: String?) {
        require(accessToken.isNotBlank()) { "accessToken must not be blank" }
        val current = snapshot ?: throw SessionStorageException("Cannot refresh a missing session")
        val nextRefresh = refreshToken?.takeIf { it.isNotBlank() } ?: current.refreshToken
        write(current.copy(accessToken = accessToken, refreshToken = nextRefresh))
    }

    @Synchronized
    override fun updateCachedUser(cachedUser: String) {
        val current = snapshot ?: throw SessionStorageException("Cannot cache a user without a session")
        write(current.copy(cachedUser = cachedUser))
    }

    @Synchronized
    override fun clear() {
        snapshot = null
        file.delete()
    }

    override fun invalidate(reason: SessionInvalidationReason) {
        clear()
        invalidationEvents.tryEmit(SessionInvalidation(reason))
    }

    private fun loadOrDiscardInvalidSession(): StoredSession? = try {
        if (!file.baseFile.exists()) null else {
            require(file.baseFile.length() <= MAX_CIPHERTEXT_BYTES + 64L) { "Oversized session file" }
            decrypt(file.readFully())
        }.also { session ->
            if (session != null) {
                require(session.version == SESSION_SCHEMA_VERSION) { "Unsupported session version ${session.version}" }
                require(session.accessToken.isNotBlank() && session.refreshToken.isNotBlank()) { "Incomplete session" }
            }
        }
    } catch (_: Exception) {
        // Corrupt, restored or key-invalidated ciphertext must fail closed. At app
        // startup there is no authenticated UI yet, so an absent session naturally
        // routes to login; no stale credentials are retained.
        file.delete()
        null
    }

    private fun write(value: StoredSession) {
        val encrypted = try {
            encrypt(value)
        } catch (error: Exception) {
            throw SessionStorageException("Could not encrypt the session", error)
        }
        val output = try {
            file.startWrite()
        } catch (error: Exception) {
            throw SessionStorageException("Could not open atomic session storage", error)
        }
        try {
            output.write(encrypted)
            output.fd.sync()
            file.finishWrite(output)
            snapshot = value
        } catch (error: Exception) {
            try {
                file.failWrite(output)
            } catch (_: Exception) {
                // Preserve the original persistence failure.
            }
            throw SessionStorageException("Could not persist the session", error)
        }
    }

    private fun encrypt(value: StoredSession): ByteArray {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val plaintext = json.encodeToString(value).toByteArray(Charsets.UTF_8)
        val ciphertext = cipher.doFinal(plaintext)
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(FILE_MAGIC)
                out.writeInt(FILE_FORMAT_VERSION)
                out.writeInt(cipher.iv.size)
                out.write(cipher.iv)
                out.writeInt(ciphertext.size)
                out.write(ciphertext)
            }
            bytes.toByteArray()
        }
    }

    private fun decrypt(bytes: ByteArray): StoredSession = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readInt() == FILE_MAGIC) { "Invalid session file" }
        require(input.readInt() == FILE_FORMAT_VERSION) { "Unsupported session file format" }
        val ivSize = input.readInt()
        require(ivSize in 12..16) { "Invalid AES-GCM IV" }
        val iv = ByteArray(ivSize).also(input::readFully)
        val ciphertextSize = input.readInt()
        require(ciphertextSize in 16..MAX_CIPHERTEXT_BYTES) { "Invalid session ciphertext" }
        val ciphertext = ByteArray(ciphertextSize).also(input::readFully)
        require(input.available() == 0) { "Trailing session data" }
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        json.decodeFromString(StoredSession.serializer(), cipher.doFinal(ciphertext).toString(Charsets.UTF_8))
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val SESSION_FILE = "appmuse_session.bin"
        const val LEGACY_PREFS_NAME = "appmuse_auth"
        const val SESSION_SCHEMA_VERSION = 1
        const val FILE_FORMAT_VERSION = 1
        const val FILE_MAGIC = 0x41504D53 // APMS
        const val MAX_CIPHERTEXT_BYTES = 1_048_576
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "appmuse.auth.session.v1"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
