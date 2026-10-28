package com.appmuse.app.data

import com.appmuse.app.model.AuthRequest
import com.appmuse.app.model.AuthResponse
import com.appmuse.app.model.GuestRequest
import com.appmuse.app.model.LogoutRequest
import com.appmuse.app.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

/**
 * Auth repository — the single place that talks to the backend's auth routes
 * (auth/login, auth/register, auth/me, auth/refresh, auth/logout) and persists
 * tokens + the cached user. ViewModels call this; they never touch
 * ApiClient or TokenStore directly. All calls throw a classified [ApiError].
 */
interface AuthSessionRepository {
    val isLoggedIn: Boolean
    val sessionInvalidations: Flow<SessionInvalidation>
    fun cachedUser(): User?
    suspend fun login(email: String, password: String): User
    suspend fun register(email: String, password: String, name: String?): User
    /** Open (or resume) the per-device guest session of an app without accounts (APP_AUTH=none). */
    suspend fun guestSession(): User
    suspend fun currentUser(): User
    suspend fun logout()
}

class AuthRepository(
    private val api: ApiService = ApiClient.service,
    private val tokens: SessionStore = ApiClient.tokens,
    // Resolved lazily: ApiClient.init runs at app start, while unit tests
    // construct the repository with fakes and never initialise ApiClient.
    private val deviceIdentity: DeviceIdentityProvider = DeviceIdentityProvider { ApiClient.deviceIdentity.deviceId() },
) : AuthSessionRepository {
    private val json = Json { ignoreUnknownKeys = true }

    override val isLoggedIn: Boolean get() = !tokens.accessToken.isNullOrEmpty()
    override val sessionInvalidations: Flow<SessionInvalidation> get() = tokens.invalidations

    override fun cachedUser(): User? = tokens.cachedUser?.let {
        runCatching { json.decodeFromString<User>(it) }.getOrNull()
    }

    override suspend fun login(email: String, password: String): User = call(authAttempt = true) {
        val response = api.login(AuthRequest(email = email, password = password))
        tokens.serializedMutation { persist(response) }
    }

    override suspend fun register(email: String, password: String, name: String?): User = call(authAttempt = true) {
        val response = api.register(AuthRequest(email = email, password = password, name = name))
        tokens.serializedMutation { persist(response) }
    }

    /** ADR-0046: every feature route needs a bearer token even without accounts.
     *  The backend keeps one guest user per device id and answers like a login,
     *  so tokens + cached user are persisted the normal way. */
    override suspend fun guestSession(): User = call {
        val response = api.guest(GuestRequest(deviceId = deviceIdentity.deviceId()))
        tokens.serializedMutation { persist(response) }
    }

    /** Fetch the current user from the backend; falls back to the cached user on
     *  a network error so the app stays usable offline (mirrors the Flutter app). */
    override suspend fun currentUser(): User = try {
        val user = api.me().user
        tokens.serializedMutation {
            tokens.updateCachedUser(json.encodeToString(User.serializer(), user))
        }
        user
    } catch (e: Exception) {
        val err = ApiError.from(e)
        if (err is ApiError.Network) cachedUser() ?: throw err else throw err
    }

    override suspend fun logout() {
        tokens.serializedMutation {
            val refreshToken = tokens.refreshToken
            try {
                if (!refreshToken.isNullOrBlank()) api.logout(LogoutRequest(refreshToken))
            } catch (_: Exception) {
                // Best-effort — clear locally regardless.
            } finally {
                tokens.clear()
            }
        }
    }

    private fun persist(res: AuthResponse): User = try {
        val refreshToken = res.refreshToken?.takeIf { it.isNotBlank() }
            ?: throw SessionStorageException("The backend did not return a refresh token")
        tokens.persistSession(
            accessToken = res.accessToken,
            refreshToken = refreshToken,
            cachedUser = json.encodeToString(User.serializer(), res.user),
        )
        res.user
    } catch (error: Exception) {
        // A failed account switch must not leave the previous account's bearer
        // credentials active behind an unauthenticated/error UI. Login is either
        // one complete new session or no local session at all.
        tokens.clear()
        throw error
    }

    private inline fun <T> call(authAttempt: Boolean = false, block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        throw ApiError.from(e, authAttempt)
    }
}
