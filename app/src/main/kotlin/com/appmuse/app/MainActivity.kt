package com.appmuse.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appmuse.app.data.ApiClient
import com.appmuse.app.ui.AppNav
import com.appmuse.app.ui.AuthState
import com.appmuse.app.ui.AuthViewModel
import com.appmuse.app.ui.components.LoadingView
import com.appmuse.app.ui.screens.LoginScreen
import com.appmuse.app.ui.theme.AppmuseTheme

/**
 * App entry point. Initialises the single API client, then renders the auth gate:
 * Loading → spinner, Unauthenticated → LoginScreen, Authenticated → AppNav. State
 * comes from AuthViewModel (a StateFlow collected with lifecycle awareness) — the
 * agent adds features under AppNav, not here.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Branded splash (core-splashscreen). MUST be installed before super.onCreate().
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // One-time setup of Retrofit/OkHttp + the token store (needs a Context).
        ApiClient.init(applicationContext)
        setContent {
            AppmuseTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val vm: AuthViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val submitting by vm.submitting.collectAsStateWithLifecycle()

    when (val s = state) {
        is AuthState.Loading -> LoadingView()
        is AuthState.Unauthenticated -> LoginScreen(vm = vm, state = s, submitting = submitting)
        is AuthState.Authenticated -> AppNav(user = s.user, onLogout = vm::logout)
    }
}
