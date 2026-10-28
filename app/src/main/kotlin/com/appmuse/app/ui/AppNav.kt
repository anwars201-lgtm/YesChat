package com.appmuse.app.ui

import androidx.compose.foundation.layout.padding
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
import com.appmuse.app.ui.screens.ProfileScreen
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
    Profile("profile", "Profil", Icons.Filled.Person),
    Conversations("conversations", "Percakapan", Icons.AutoMirrored.Filled.Chat, R.string.appmuse_feature_conversations_title, R.string.appmuse_feature_conversations_nav),
    Chat("chat", "Chat", Icons.AutoMirrored.Filled.Chat, R.string.appmuse_feature_chat_title, R.string.appmuse_feature_chat_nav),
    ProviderSettings("provider_settings", "Pengaturan", Icons.Filled.Settings, R.string.appmuse_feature_provider_settings_title, R.string.appmuse_feature_provider_settings_nav),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNav(user: User, onLogout: () -> Unit) {
    val nav = rememberNavController()
    val tabDestinations = listOf(Dest.Conversations, Dest.Profile)
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Dest.Conversations.route
    val current = Dest.values().firstOrNull { it.route == currentRoute } ?: Dest.Conversations

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(current.titleRes?.let { stringResource(it) } ?: current.label) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            NavigationBar(
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
            composable(Dest.Profile.route) { ProfileScreen(user, onLogout) }
            composable(Dest.Conversations.route) {
                ConversationsScreen(onOpenConversation = { _ ->
                    nav.navigate(Dest.Chat.route) { launchSingleTop = true }
                })
            }
            composable(Dest.Chat.route) {
                ChatScreen(onOpenSettings = {
                    nav.navigate(Dest.ProviderSettings.route) { launchSingleTop = true }
                })
            }
            composable(Dest.ProviderSettings.route) { ProviderSettingsScreen() }
        }
    }
}
