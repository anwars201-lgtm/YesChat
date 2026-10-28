package com.appmuse.app.data

import android.content.Context
import com.appmuse.app.config.AppConfig
import com.appmuse.app.model.RefreshRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.Retrofit
import retrofit2.HttpException
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Single Retrofit/OkHttp client for the shared backend — the Kotlin equivalent
 * of the Flutter app's api_client.dart. Mirrors that contract exactly:
 *   - base URL = AppConfig.apiUrl (trailing slash enforced)
 *   - 10s connect/read timeouts
 *   - X-API-Key header (when the proxy key is present) + Authorization Bearer
 *   - 401 → transparent token refresh via OkHttp's Authenticator (retries once);
 *     on refresh failure the session is cleared (the UI sees ApiError.Auth).
 *
 * Call ApiClient.init(context) once at app start (MainActivity), then use
 * ApiClient.service for requests and ApiClient.tokens for the token store.
 */
object ApiClient {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val contentType = "application/json".toMediaType()

    private lateinit var tokenStore: TokenStore
    private lateinit var sessionRefresher: SessionRefresher
    private lateinit var deviceIdentityProvider: DeviceIdentityProvider

    lateinit var service: ApiService
        private set

    val tokens: TokenStore get() = tokenStore

    /** Per-installation id for guest sessions (APP_AUTH=none, ADR-0046). */
    val deviceIdentity: DeviceIdentityProvider get() = deviceIdentityProvider

    fun init(context: Context) {
        if (::service.isInitialized) return
        tokenStore = TokenStore(context)
        deviceIdentityProvider = DeviceIdentity(context)
        val components = createApiComponents(
            baseUrl = AppConfig.apiUrl,
            apiKey = AppConfig.apiKey,
            store = tokenStore,
        )
        sessionRefresher = components.refresher
        service = components.service
    }

    internal fun createApiService(
        baseUrl: String,
        apiKey: String,
        store: SessionStore,
    ): ApiService = createApiComponents(baseUrl, apiKey, store).service

    private fun createApiComponents(
        baseUrl: String,
        apiKey: String,
        store: SessionStore,
    ): ApiComponents {
        val refreshService = retrofit(plainClient(apiKey), baseUrl).create(ApiService::class.java)
        val refresher = SessionRefresher(store, refreshService)
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .addInterceptor(headerInterceptor(apiKey, store))
            .authenticator(TokenAuthenticator(store, refresher))
            .build()
        return ApiComponents(
            service = retrofit(client, baseUrl).create(ApiService::class.java),
            refresher = refresher,
        )
    }

    /**
     * Realtime streams do not pass through OkHttp's [Authenticator], so their
     * 401 path calls the same serialized refresh coordinator explicitly.
     */
    internal suspend fun refreshSessionForRealtime(rejectedAccessToken: String?): SessionRefreshOutcome {
        if (!::sessionRefresher.isInitialized) return SessionRefreshOutcome.TRANSIENT_FAILURE
        return sessionRefresher.refresh(rejectedAccessToken)
    }

    /** A freshly minted token was rejected too. Invalidate only that session,
     * never a newer login that won a race with the old stream callback. */
    internal suspend fun rejectFreshRealtimeSession(rejectedAccessToken: String?): SessionRefreshOutcome =
        if (::sessionRefresher.isInitialized) {
            sessionRefresher.rejectFreshToken(rejectedAccessToken)
        } else {
            SessionRefreshOutcome.TRANSIENT_FAILURE
        }

    /** Adds JSON headers + X-API-Key + the current bearer token to every request. */
    private fun headerInterceptor(apiKey: String, store: SessionStore) = Interceptor { chain ->
        val b = chain.request().newBuilder()
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
        if (apiKey.isNotEmpty()) b.header("X-API-Key", apiKey)
        store.accessToken?.takeIf { it.isNotEmpty() }?.let { b.header("Authorization", "Bearer $it") }
        chain.proceed(b.build())
    }

    /** A no-auth client (X-API-Key + JSON only) used for the refresh call so the
     *  refresh request itself never recurses through the Authenticator. */
    private fun plainClient(apiKey: String) = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val b = chain.request().newBuilder()
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
            if (apiKey.isNotEmpty()) b.header("X-API-Key", apiKey)
            chain.proceed(b.build())
        }
        .build()

    private fun retrofit(client: OkHttpClient, baseUrl: String) = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory(contentType))
        .build()
}

private data class ApiComponents(
    val service: ApiService,
    val refresher: SessionRefresher,
)

internal enum class SessionRefreshOutcome {
    REFRESHED,
    TERMINAL_FAILURE,
    TRANSIENT_FAILURE,
}

/**
 * One refresh authority shared by finite API calls and long-lived realtime
 * streams. [SessionStore.serializedMutation] makes refresh-token rotation
 * single-flight and lets a waiter reuse a token already minted by another
 * request.
 */
private class SessionRefresher(
    private val store: SessionStore,
    private val refreshService: ApiService,
) {
    suspend fun refresh(rejectedAccessToken: String?): SessionRefreshOutcome =
        store.serializedMutation {
            val currentToken = store.accessToken
            if (!currentToken.isNullOrBlank() && currentToken != rejectedAccessToken) {
                return@serializedMutation SessionRefreshOutcome.REFRESHED
            }
            val refreshToken = store.refreshToken
            if (refreshToken.isNullOrBlank()) {
                store.invalidate(SessionInvalidationReason.MISSING_REFRESH_TOKEN)
                return@serializedMutation SessionRefreshOutcome.TERMINAL_FAILURE
            }

            val refreshed = try {
                refreshService.refresh(RefreshRequest(refreshToken))
            } catch (error: Exception) {
                val code = (error as? HttpException)?.code()
                if (code == 400 || code == 401 || code == 403) {
                    store.invalidate(SessionInvalidationReason.EXPIRED)
                    return@serializedMutation SessionRefreshOutcome.TERMINAL_FAILURE
                }
                return@serializedMutation SessionRefreshOutcome.TRANSIENT_FAILURE
            }

            try {
                store.updateTokens(refreshed.accessToken, refreshed.refreshToken)
            } catch (_: Exception) {
                store.invalidate(SessionInvalidationReason.STORAGE_FAILURE)
                return@serializedMutation SessionRefreshOutcome.TERMINAL_FAILURE
            }
            SessionRefreshOutcome.REFRESHED
        }

    suspend fun rejectFreshToken(rejectedAccessToken: String?): SessionRefreshOutcome =
        store.serializedMutation {
            // A 401 callback without the exact bearer that produced it cannot
            // safely invalidate any session. Treat it as terminal for this
            // recovery attempt, but preserve credentials that may belong to a
            // newer login. Returning REFRESHED here would create an immediate
            // reconnect loop for malformed/missing Authorization headers.
            if (rejectedAccessToken.isNullOrBlank()) {
                return@serializedMutation SessionRefreshOutcome.TERMINAL_FAILURE
            }
            val currentToken = store.accessToken
            if (!currentToken.isNullOrBlank() && currentToken != rejectedAccessToken) {
                return@serializedMutation SessionRefreshOutcome.REFRESHED
            }
            store.invalidate(SessionInvalidationReason.EXPIRED)
            SessionRefreshOutcome.TERMINAL_FAILURE
        }
}

/**
 * Refreshes the access token on a 401 and retries the original request once. On
 * terminal refresh rejection it clears the session and notifies the auth UI.
 * Connectivity/5xx failures preserve the local session for a later retry.
 */
private class TokenAuthenticator(
    private val store: SessionStore,
    private val refresher: SessionRefresher,
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        // The original request never carried a token → nothing to refresh.
        if (response.request.header("Authorization") == null) return null
        // Logout is deliberately serialized against refresh and its backend route
        // needs only the refresh-token body. Never recurse into the authenticator.
        if (response.request.url.encodedPath.endsWith("/auth/logout")) return null
        return runBlocking {
            val requestToken = response.request.header("Authorization")?.removePrefix("Bearer ")
            val outcome =
                if (responseCount(response) >= 2) {
                    refresher.rejectFreshToken(requestToken)
                } else {
                    refresher.refresh(requestToken)
                }
            when (outcome) {
                SessionRefreshOutcome.REFRESHED -> {
                    val refreshedAccessToken = store.accessToken ?: return@runBlocking null
                    response.request.newBuilder()
                        .header("Authorization", "Bearer $refreshedAccessToken")
                        .build()
                }
                SessionRefreshOutcome.TERMINAL_FAILURE -> null
                SessionRefreshOutcome.TRANSIENT_FAILURE ->
                    throw IOException("Session refresh temporarily unavailable")
            }
        }
    }

    private fun responseCount(response: Response): Int {
        var prior = response.priorResponse
        var count = 1
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
