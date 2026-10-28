package com.appmuse.app.ui

import com.appmuse.app.data.AuthSessionRepository
import com.appmuse.app.data.SessionInvalidation
import com.appmuse.app.data.SessionInvalidationReason
import com.appmuse.app.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelContractTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun terminalSessionInvalidationRoutesAuthenticatedUiBackToLogin() = runTest(dispatcher) {
        val repo = FakeAuthRepository()
        val viewModel = AuthViewModel(repo)
        advanceUntilIdle()
        assertIs<AuthState.Authenticated>(viewModel.state.value)

        repo.invalidate(SessionInvalidationReason.EXPIRED)
        advanceUntilIdle()

        val state = assertIs<AuthState.Unauthenticated>(viewModel.state.value)
        // Pin the BEHAVIOR (terminal invalidation surfaces an Auth error), not the
        // copy: userMessage is localized into the app's language by the platform's
        // i18n pass, so an exact-string assertion breaks every non-English app.
        assertIs<com.appmuse.app.data.ApiError.Auth>(state.error)
        assertEquals(false, state.error?.userMessage.isNullOrBlank())
    }

    @Test
    fun staleSessionInvalidationDoesNotLogOutAReplacementSession() = runTest(dispatcher) {
        val repo = FakeAuthRepository()
        val viewModel = AuthViewModel(repo)
        advanceUntilIdle()
        assertIs<AuthState.Authenticated>(viewModel.state.value)

        repo.emitStaleInvalidation(SessionInvalidationReason.EXPIRED)
        advanceUntilIdle()

        assertIs<AuthState.Authenticated>(viewModel.state.value)
    }
}

private class FakeAuthRepository : AuthSessionRepository {
    private val events = MutableSharedFlow<SessionInvalidation>(extraBufferCapacity = 1)
    override val sessionInvalidations: Flow<SessionInvalidation> = events
    override var isLoggedIn: Boolean = true
        private set
    private val user = User(id = "user-1", email = "ada@example.com", name = "Ada")

    override fun cachedUser(): User = user
    override suspend fun login(email: String, password: String): User = user
    override suspend fun register(email: String, password: String, name: String?): User = user
    override suspend fun guestSession(): User = user
    override suspend fun currentUser(): User = user
    override suspend fun logout() = Unit

    fun invalidate(reason: SessionInvalidationReason) {
        isLoggedIn = false
        events.tryEmit(SessionInvalidation(reason))
    }

    fun emitStaleInvalidation(reason: SessionInvalidationReason) {
        events.tryEmit(SessionInvalidation(reason))
    }
}
