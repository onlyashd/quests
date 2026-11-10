package org.ferabrasil.quests.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.ferabrasil.quests.android.R
import org.ferabrasil.quests.android.ui.main.MainScreen
import org.ferabrasil.quests.android.ui.onboarding.WelcomeScreen
import org.ferabrasil.quests.domain.identity.IdentityState

/** Root switch (app.md §5.5): onboarding until an identity exists, then the main tabs. */
@Composable
fun QuestsRoot(vm: RootViewModel, state: RootUiState) {
    when (state.identity) {
        IdentityState.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        IdentityState.ABSENT -> WelcomeScreen(busy = state.busy, onCreate = vm::createIdentity)
        IdentityState.PRESENT -> MainScreen(vm, state)
    }

    if (state.error) {
        AlertDialog(
            onDismissRequest = vm::dismissError,
            confirmButton = { TextButton(onClick = vm::dismissError) { Text(stringResource(android.R.string.ok)) } },
            text = { Text(stringResource(R.string.error_generic)) },
        )
    }
}
