package com.appmuse.app.ui

import com.appmuse.app.ui.theme.AppMotion
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Portable source guards for the local-only launch path; no emulator required. */
class LocalStartupContractTest {
    private fun source(path: String): String =
        File("src/main/kotlin/com/appmuse/app/$path").readText()

    @Test
    fun launchOpensLocalNavigationWithoutAuthenticationOrBackendBootstrap() {
        val activity = source("MainActivity.kt")
        assertTrue(activity.contains("AppNav()"))
        listOf("AuthViewModel", "ApiClient", "TokenStore", "LaunchedEffect", "guestSession", "AuthState").forEach {
            assertFalse(activity.contains(it), "Startup must not depend on $it")
        }
        val navigation = source("ui/AppNav.kt")
        assertTrue(navigation.contains("startDestination = Dest.Conversations.route"))
        assertTrue(navigation.contains("composable(Dest.Chat.route)"))
        assertTrue(navigation.contains("composable(Dest.Profile.route) { ProviderSettingsScreen() }"))
        listOf("AuthViewModel", "guestSession", "ApiClient", "TokenStore").forEach {
            assertFalse(navigation.contains(it), "Navigation must not bootstrap $it")
        }
    }

    @Test
    fun loadingLocalArchivesDoesNotCallNetworkOrSessionServices() {
        listOf("data/ConversationRepository.kt", "data/ProviderProfileRepository.kt").forEach { path ->
            val repository = source(path)
            listOf("ApiClient.", "TokenStore.", "guestSession(", "client.newCall(", "api.").forEach {
                assertFalse(repository.contains(it), "$path must load without $it")
            }
        }
        val viewModel = source("ui/ChatViewModel.kt")
        val initialLoad = viewModel.substringAfter("fun load() {").substringBefore("private fun refreshState()")
        assertFalse(initialLoad.contains("provider."))
        assertFalse(initialLoad.contains("loadModels()"))
    }

    @Test
    fun routineMotionIsShortAndNavigationHonorsReducedMotion() {
        listOf(AppMotion.micro, AppMotion.quick, AppMotion.standard, AppMotion.slow).forEach {
            assertTrue(it in 0..200)
        }
        assertTrue(AppMotion.shimmerCycle > AppMotion.quick)
        val navigation = source("ui/AppNav.kt")
        assertTrue(navigation.contains("rememberReducedMotion()"))
        assertTrue(navigation.contains("EnterTransition.None"))
        assertTrue(navigation.contains("ExitTransition.None"))
    }

    @Test
    fun optionChangesDoNotSetBusyOrEmitSuccessSnackbars() {
        val viewModel = source("ui/ChatViewModel.kt")
        val options = viewModel.substringAfter("private fun updateOptions(").substringBefore("fun attachFile(")
        assertTrue(options.contains("previousSave?.join()"))
        assertFalse(options.contains("_busy.value ="))
        assertFalse(options.contains("_notice.value ="))
        val screen = source("ui/screens/ChatScreen.kt")
        assertTrue(screen.contains("Box(Modifier.fillMaxSize())"))
        assertTrue(screen.contains("Modifier.align(Alignment.TopCenter)"))
    }
}
