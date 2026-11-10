package org.ferabrasil.quests.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.ferabrasil.quests.android.ui.QuestsRoot
import org.ferabrasil.quests.android.ui.RootViewModel
import org.ferabrasil.quests.android.ui.theme.QuestsTheme
import org.ferabrasil.quests.android.ui.theme.isDarkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as QuestsApplication).graph
        setContent {
            val vm: RootViewModel = viewModel { RootViewModel(graph) }
            val state by vm.state.collectAsStateWithLifecycle()
            val dark = isDarkTheme(state.preferences.theme)
            SystemBars(dark)
            QuestsTheme(dark) { QuestsRoot(vm, state) }
        }
    }

    @Composable
    private fun SystemBars(dark: Boolean) {
        LaunchedEffect(dark) {
            val style = if (dark) {
                SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
            }
            enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        }
    }
}
