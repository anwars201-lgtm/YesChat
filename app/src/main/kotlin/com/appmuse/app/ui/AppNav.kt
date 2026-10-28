package com.appmuse.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.appmuse.app.ui.components.ChatTopBar
import com.appmuse.app.ui.theme.AppSpacing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.appmuse.app.model.User
import com.appmuse.app.ui.design.DesignProfileTokens
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.appmuse.app.R
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Settings
import com.appmuse.app.ui.screens.ConversationsScreen
import com.appmuse.app.ui.screens.ChatScreen
import com.appmuse.app.ui.screens.ProviderSettingsScreen

/**
 * Top-level navigation for the authenticated app — a Compose-Navigation NavHost
 * behind a bottom NavigationBar. This is the REFERENCE wiring the agent extends:
 * add a destination by adding a [Dest] entry + a `composable(route) { … }`. The
 * single Scaffold owns the TopAppBar (title follows the current route) and the
 * bottom bar; screens render stateless content into the inner padding.
 *
 * Chrome colors are wired HERE, ONCE, from MaterialTheme.colorScheme — screens
 * must NEVER set TopAppBar/NavigationBar/FAB colors or hardcode a Color(...). A
 * bare TopAppBar() inherits Material3's surfaceTint overlay (reads bluish and
 * clashes with the onboarding palette); pinning containerColor = surface kills
 * that, and the nav indicator/selection read the palette container roles — so the
 * whole chrome follows the user's chosen colors automatically.
 */
private enum class Dest(val route: String, val label: String, val icon: ImageVector, @StringRes val titleRes: Int? = null, @StringRes val labelRes: Int? = null) {
    Profile("profile", "Pengaturan", Icons.Filled.Settings, R.string.appmuse_feature_provider_settings_title, R.string.appmuse_feature_provider_settings_nav),
    Conversations("conversations", "Percakapan", Icons.AutoMirrored.Filled.Chat, R.string.appmuse_feature_conversations_title, R.string.appmuse_feature_conversations_nav),
    Chat("chat", "Chat", Icons.AutoMirrored.Filled.Chat, R.string.appmuse_feature_chat_title, R.string.appmuse_feature_chat_nav),
    ProviderSettings("provider_settings", "Pengaturan", Icons.Filled.Settings, R.string.appmuse_feature_provider_settings_title, R.string.appmuse_feature_provider_settings_nav),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNav(user: User, onLogout: () -> Unit) {
    val nav = rememberNavController()
    val tabDestinations = listOf(Dest.Conversations, Dest.Profile)
    val chatVm: ChatViewModel = viewModel()
    val chatState by chatVm.state.collectAsStateWithLifecycle()
    val streaming by chatVm.streaming.collectAsStateWithLifecycle()
    val chatBusy by chatVm.busy.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Dest.Conversations.route
    val current = if (currentRoute == Dest.ProviderSettings.route) Dest.Profile
        else Dest.entries.firstOrNull { it.route == currentRoute } ?: Dest.Conversations

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = current == Dest.Chat && !chatBusy,
        drawerContent = {
            ModalDrawerSheet(Modifier.fillMaxWidth(0.88f).fillMaxHeight()) {
                if (chatBusy) {
                    Column(Modifier.padding(AppSpacing.base)) {
                        Text("Hentikan balasan sebelum berpindah percakapan.")
                        TextButton(onClick = chatVm::stopKeepPartial) { Text(stringResource(R.string.appmuse_action_chat_stop)) }
                    }
                } else {
                    ConversationsScreen(onOpenConversation = {
                        scope.launch { drawer.close() }
                        if (current != Dest.Chat) nav.navigate(Dest.Chat.route) { launchSingleTop = true }
                    })
                }
            }
        },
    ) {
    Scaffold(
        topBar = {
            if (current == Dest.Chat) {
                val latestAssistant = (chatState as? ChatUiState.Success)?.conversation?.messages?.lastOrNull { it.role == "assistant" }
                ChatTopBar(
                    usage = latestAssistant?.usage,
                    streaming = streaming,
                    onOpenSidebar = { scope.launch { drawer.open() } },
                    onOpenSettings = { nav.navigate(Dest.Profile.route) { launchSingleTop = true } },
                )
            } else TopAppBar(
                title = { Text(current.titleRes?.let { stringResource(it) } ?: current.label) },
                navigationIcon = {
                    if (current == Dest.ProviderSettings || current == Dest.Profile) IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            if (current != Dest.Chat) NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = DesignProfileTokens.navigationElevation,
            ) {
                tabDestinations.forEach { dest ->
                    NavigationBarItem(
                        selected = current == dest,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        onClick = {
                            if (current != dest) {
                                nav.navigate(dest.route) {
                                    // Single-top + state restore is the standard
                                    // bottom-nav pattern (no back-stack pile-up).
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.labelRes?.let { stringResource(it) } ?: dest.label) },
                        label = { Text(dest.labelRes?.let { stringResource(it) } ?: dest.label) },
                        alwaysShowLabel = DesignProfileTokens.alwaysShowNavigationLabels,
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = nav,
            startDestination = Dest.Conversations.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Dest.Profile.route) { ProviderSettingsScreen() }
            composable(Dest.Conversations.route) {
                ConversationsScreen(onOpenConversation = { _ ->
                    nav.navigate(Dest.Chat.route) { launchSingleTop = true }
                })
            }
            composable(Dest.Chat.route) {
                ChatScreen(vm = chatVm, onOpenSettings = {
                    nav.navigate(Dest.Profile.route) { launchSingleTop = true }
                })
            }
            composable(Dest.ProviderSettings.route) { ProviderSettingsScreen() }
        }
    }
    }
}
