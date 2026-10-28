package com.appmuse.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appmuse.app.config.AppConfig
import com.appmuse.app.data.ApiError
import com.appmuse.app.data.AuthRepository
import com.appmuse.app.data.AuthSessionRepository
import com.appmuse.app.model.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Auth UI state — drives navigation (login vs home). */
sealed interface AuthState {
    data object Loading : AuthState
    data class Authenticated(val user: User) : AuthState
    data class Unauthenticated(val error: ApiError? = null) : AuthState
}

/**
 * Owns the auth session as a StateFlow the UI collects with
 * collectAsStateWithLifecycle. MVVM: the screen renders state + calls the
 * intent methods; the ViewModel runs the repository in viewModelScope. This is
 * the pattern the agent should follow for every feature (ViewModel + StateFlow +
 * repository), NOT business logic in composables.
 */
class AuthViewModel @JvmOverloads constructor(
    private val repo: AuthSessionRepository = AuthRepository(),
) : ViewModel() {
    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _submitting = MutableStateFlow(false)
    val submitting: StateFlow<Boolean> = _submitting.asStateFlow()

    init {
        observeSessionInvalidation()
        bootstrap()
    }

    private fun observeSessionInvalidation() {
        viewModelScope.launch {
            repo.sessionInvalidations.collectLatest {
                // Flow delivery can lag behind a successful account switch.
                // Ignore an invalidation emitted for an older bearer when a
                // complete newer session is already present.
                if (repo.isLoggedIn) return@collectLatest
                _submitting.value = false
                if (!AppConfig.authEnabled) {
                    // No login screen exists in this mode: replace the dead
                    // guest session with a fresh one for the same device.
                    bootstrapGuest()
                    return@collectLatest
                }
                _state.value = AuthState.Unauthenticated(ApiError.Auth())
            }
        }
    }

    /** Resolve the initial session: a per-device guest session when authMode ==
     *  none (ADR-0046), otherwise restore the cached/remote user or fall to
     *  Unauthenticated. */
    fun bootstrap() {
        if (!AppConfig.authEnabled) {
            bootstrapGuest()
            return
        }
        if (!repo.isLoggedIn) {
            _state.value = AuthState.Unauthenticated()
            return
        }
        viewModelScope.launch {
            _state.value = try {
                AuthState.Authenticated(repo.currentUser())
            } catch (e: Exception) {
                AuthState.Unauthenticated(e as? ApiError ?: ApiError.from(e))
            }
        }
    }

    /** APP_AUTH=none: the app has no accounts, but every feature route still needs
     *  a bearer token. Reuse the stored guest session (the same /auth/me check a
     *  normal session gets, cached user offline) or open one for this device; a
     *  dead stored session gets one fresh attempt. Only when even that fails
     *  (offline before the first session) does the UI open on a placeholder
     *  guest — the next start, or the next invalidation, retries. */
    private fun bootstrapGuest() {
        viewModelScope.launch {
            _state.value = try {
                AuthState.Authenticated(if (repo.isLoggedIn) repo.currentUser() else repo.guestSession())
            } catch (_: Exception) {
                try {
                    AuthState.Authenticated(repo.guestSession())
                } catch (_: Exception) {
                    AuthState.Authenticated(User(id = "guest", email = ""))
                }
            }
        }
    }

    fun login(email: String, password: String) = submit { repo.login(email, password) }

    fun register(email: String, password: String, name: String?) = submit { repo.register(email, password, name) }

    fun logout() {
        viewModelScope.launch {
            repo.logout()
            _state.value = AuthState.Unauthenticated()
        }
    }

    private fun submit(action: suspend () -> User) {
        if (_submitting.value) return
        viewModelScope.launch {
            _submitting.value = true
            _state.value = try {
                AuthState.Authenticated(action())
            } catch (e: Exception) {
                AuthState.Unauthenticated(e as? ApiError ?: ApiError.from(e))
            } finally {
                _submitting.value = false
            }
        }
    }
}
