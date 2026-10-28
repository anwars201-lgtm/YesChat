package com.appmuse.app.data

import com.appmuse.app.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import retrofit2.HttpException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AuthContractTest {
    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun loginPersistsOneCompleteSessionAndSendsRequiredHeaders() = runTest {
        server.enqueue(jsonResponse(AUTH_RESPONSE))
        val store = RecordingSessionStore()
        val api = ApiClient.createApiService(server.url("/").toString(), "proxy-key", store)

        val user = AuthRepository(api, store).login("ada@example.com", "password123")

        assertEquals("user-1", user.id)
        assertEquals("access-1", store.accessToken)
        assertEquals("refresh-1", store.refreshToken)
        assertTrue(store.cachedUser.orEmpty().contains("ada@example.com"))
        assertEquals(1, store.persistCalls)
        val request = server.takeRequest()
        assertEquals("/auth/login", request.path)
        assertEquals("proxy-key", request.getHeader("X-API-Key"))
        assertTrue(request.body.readUtf8().contains("\"email\":\"ada@example.com\""))
    }

    @Test
    fun loginFailsClosedWhenAtomicPersistenceFails() = runTest {
        server.enqueue(jsonResponse(AUTH_RESPONSE))
        val store = RecordingSessionStore().apply {
            persistSession("previous-access", "previous-refresh", "{\"id\":\"previous-user\"}")
            failPersistence = true
        }
        val api = ApiClient.createApiService(server.url("/").toString(), "", store)

        assertIs<ApiError.Unknown>(
            assertFailsWith<ApiError> { AuthRepository(api, store).login("ada@example.com", "password123") },
        )
        assertNull(store.accessToken)
        assertNull(store.refreshToken)
    }

    @Test
    fun logoutSendsRefreshTokenBodyAndAlwaysClearsLocalSession() = runTest {
        val store = RecordingSessionStore().apply {
            persistSession("access-1", "refresh-1", "{\"id\":\"user-1\"}")
        }
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"message\":\"Logged out\"}"))
        val api = ApiClient.createApiService(server.url("/").toString(), "", store)

        AuthRepository(api, store).logout()

        val request = server.takeRequest()
        assertEquals("/auth/logout", request.path)
        assertEquals("{\"refreshToken\":\"refresh-1\"}", request.body.readUtf8())
        assertNull(store.accessToken)
        assertNull(store.refreshToken)
    }

    @Test
    fun unauthorizedRequestRefreshesAtomicallyAndRetriesWithNewBearer() = runTest {
        val store = RecordingSessionStore().apply {
            persistSession("access-old", "refresh-old", "{\"id\":\"user-1\"}")
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/auth/refresh" -> jsonResponse(
                    """{"accessToken":"access-new","refreshToken":"refresh-new"}""",
                )
                "/auth/me" -> if (request.getHeader("Authorization") == "Bearer access-new") {
                    jsonResponse(ME_RESPONSE)
                } else {
                    MockResponse().setResponseCode(401).setBody("{\"error\":\"expired\"}")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val api = ApiClient.createApiService(server.url("/").toString(), "proxy-key", store)

        assertEquals("user-1", api.me().user.id)
        assertEquals("access-new", store.accessToken)
        assertEquals("refresh-new", store.refreshToken)
        assertEquals(1, store.updateTokenCalls)
        assertTrue(store.invalidatedReasons.isEmpty())
    }

    @Test
    fun terminalRefreshRejectionInvalidatesSessionButTransientFailureDoesNot() = runTest {
        val terminalStore = RecordingSessionStore().apply {
            persistSession("access-old", "refresh-old", "{\"id\":\"user-1\"}")
        }
        server.dispatcher = authFailureDispatcher(refreshStatus = 401)
        val terminalApi = ApiClient.createApiService(server.url("/").toString(), "", terminalStore)
        assertFailsWith<HttpException> { terminalApi.me() }
        assertEquals(listOf(SessionInvalidationReason.EXPIRED), terminalStore.invalidatedReasons)
        assertNull(terminalStore.accessToken)

        server.shutdown()
        server = MockWebServer().apply {
            dispatcher = authFailureDispatcher(refreshStatus = 503)
            start()
        }
        val transientStore = RecordingSessionStore().apply {
            persistSession(
                "access-old",
                "refresh-old",
                "{\"id\":\"user-1\",\"email\":\"ada@example.com\",\"name\":\"Ada\"}",
            )
        }
        val transientApi = ApiClient.createApiService(server.url("/").toString(), "", transientStore)
        val cachedUser = AuthRepository(transientApi, transientStore).currentUser()
        assertEquals("user-1", cachedUser.id)
        assertTrue(transientStore.invalidatedReasons.isEmpty())
        assertEquals("access-old", transientStore.accessToken)
    }

    @Test
    fun concurrentRefreshAndLogoutRevokesTheRotatedServerSession() = runTest {
        val refreshStarted = CountDownLatch(1)
        val releaseRefresh = CountDownLatch(1)
        val store = RecordingSessionStore().apply {
            persistSession("access-old", "refresh-old", "{\"id\":\"user-1\"}")
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/auth/refresh" -> {
                    refreshStarted.countDown()
                    check(releaseRefresh.await(5, TimeUnit.SECONDS)) { "refresh test timed out" }
                    jsonResponse("""{"accessToken":"access-new","refreshToken":"refresh-new"}""")
                }
                "/auth/me" -> if (request.getHeader("Authorization") == "Bearer access-new") {
                    jsonResponse(ME_RESPONSE)
                } else {
                    MockResponse().setResponseCode(401).setBody("{\"error\":\"expired\"}")
                }
                "/auth/logout" -> MockResponse().setResponseCode(200).setBody("{\"message\":\"Logged out\"}")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val api = ApiClient.createApiService(server.url("/").toString(), "", store)
        val repo = AuthRepository(api, store)

        val request = async(Dispatchers.IO) { api.me() }
        assertTrue(refreshStarted.await(5, TimeUnit.SECONDS))
        val logout = async(Dispatchers.IO) { repo.logout() }
        releaseRefresh.countDown()
        request.await()
        logout.await()

        val recorded = buildList {
            repeat(server.requestCount) { add(server.takeRequest()) }
        }
        val logoutRequest = recorded.single { it.path == "/auth/logout" }
        assertEquals("{\"refreshToken\":\"refresh-new\"}", logoutRequest.body.readUtf8())
        assertNull(store.accessToken)
        assertNull(store.refreshToken)
    }

    private fun authFailureDispatcher(refreshStatus: Int) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
            "/auth/refresh" -> MockResponse().setResponseCode(refreshStatus).setBody("{\"error\":\"refresh failed\"}")
            "/auth/me" -> MockResponse().setResponseCode(401).setBody("{\"error\":\"expired\"}")
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private companion object {
        const val AUTH_RESPONSE =
            """{"accessToken":"access-1","refreshToken":"refresh-1","user":{"id":"user-1","email":"ada@example.com","name":"Ada"}}"""
        const val ME_RESPONSE =
            """{"user":{"id":"user-1","email":"ada@example.com","name":"Ada"}}"""
    }
}

private class RecordingSessionStore(
    var failPersistence: Boolean = false,
) : SessionStore {
    private val events = MutableSharedFlow<SessionInvalidation>(extraBufferCapacity = 4)
    private val mutationMutex = Mutex()
    override val invalidations: Flow<SessionInvalidation> = events.asSharedFlow()
    override var accessToken: String? = null
        private set
    override var refreshToken: String? = null
        private set
    override var cachedUser: String? = null
        private set
    var persistCalls = 0
        private set
    var updateTokenCalls = 0
        private set
    val invalidatedReasons = mutableListOf<SessionInvalidationReason>()

    override suspend fun <T> serializedMutation(block: suspend () -> T): T {
        mutationMutex.lock()
        return try {
            block()
        } finally {
            mutationMutex.unlock()
        }
    }

    override fun persistSession(accessToken: String, refreshToken: String, cachedUser: String) {
        if (failPersistence) throw SessionStorageException("test persistence failure")
        persistCalls++
        this.accessToken = accessToken
        this.refreshToken = refreshToken
        this.cachedUser = cachedUser
    }

    override fun updateTokens(accessToken: String, refreshToken: String?) {
        if (failPersistence) throw SessionStorageException("test persistence failure")
        updateTokenCalls++
        this.accessToken = accessToken
        if (!refreshToken.isNullOrBlank()) this.refreshToken = refreshToken
    }

    override fun updateCachedUser(cachedUser: String) {
        if (failPersistence) throw SessionStorageException("test persistence failure")
        this.cachedUser = cachedUser
    }

    override fun clear() {
        accessToken = null
        refreshToken = null
        cachedUser = null
    }

    override fun invalidate(reason: SessionInvalidationReason) {
        invalidatedReasons += reason
        clear()
        events.tryEmit(SessionInvalidation(reason))
    }
}
