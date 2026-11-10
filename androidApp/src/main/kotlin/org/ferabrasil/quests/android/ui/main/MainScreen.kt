package org.ferabrasil.quests.android.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import org.ferabrasil.quests.android.R
import org.ferabrasil.quests.android.ui.RootUiState
import org.ferabrasil.quests.android.ui.RootViewModel
import org.ferabrasil.quests.android.ui.privacy.SecureScreen
import org.ferabrasil.quests.android.ui.settings.SettingsScreen
import kotlin.reflect.KClass

// Type-safe routes (app.md §5.5). Nested destinations (detail, claim, submit…) hang off these.
@Serializable data object QuestsRoute
@Serializable data object MineRoute
@Serializable data object WalletRoute
@Serializable data object LeaderRoute
@Serializable data object SettingsRoute

private enum class Tab(val route: Any, val routeClass: KClass<*>, val label: Int, val icon: ImageVector) {
    QUESTS(QuestsRoute, QuestsRoute::class, R.string.tab_quests, Icons.Filled.Search),
    MINE(MineRoute, MineRoute::class, R.string.tab_mine, Icons.AutoMirrored.Filled.List),
    WALLET(WalletRoute, WalletRoute::class, R.string.tab_wallet, Icons.Filled.Star),
    LEADER(LeaderRoute, LeaderRoute::class, R.string.tab_leader, Icons.Filled.Build),
    SETTINGS(SettingsRoute, SettingsRoute::class, R.string.tab_settings, Icons.Filled.Settings),
}

@Composable
fun MainScreen(vm: RootViewModel, state: RootUiState) {
    val nav = rememberNavController()
    val tabs = Tab.entries.filter { it != Tab.LEADER || state.preferences.leaderMode }
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination

    // Leaving leader mode while on the Leader tab: fall back to Settings, where the switch lives.
    LaunchedEffect(state.preferences.leaderMode) {
        if (!state.preferences.leaderMode && current?.hasRoute(LeaderRoute::class) == true) {
            nav.navigate(SettingsRoute) { popUpTo(nav.graph.findStartDestination().id) }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = current?.hasRoute(tab.routeClass) == true,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = QuestsRoute, modifier = Modifier.padding(padding)) {
            composable<QuestsRoute> { EmptyState(stringResource(R.string.quests_empty)) }
            composable<MineRoute> { EmptyState(stringResource(R.string.mine_empty)) }
            composable<WalletRoute> {
                SecureScreen() // C5
                WalletPlaceholder()
            }
            composable<LeaderRoute> { EmptyState(stringResource(R.string.leader_empty)) }
            composable<SettingsRoute> { SettingsScreen(vm, state) }
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Wallet balance = count of unspent vouchers in the vault (app.md §5.2); the vault lands in A1/A6. */
@Composable
private fun WalletPlaceholder() {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("0", style = MaterialTheme.typography.displayLarge)
                Text(stringResource(R.string.wallet_balance_label), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
