package org.ferabrasil.quests.android.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.ferabrasil.quests.android.R
import org.ferabrasil.quests.android.ui.theme.QuestsTheme

/**
 * Onboarding → Welcome (app.md §5.1). Create identity / Verify backup / Restore get their
 * own screens with BIP-39 in milestone A1.
 */
@Composable
fun WelcomeScreen(busy: Boolean, onCreate: () -> Unit) {
    var showRestoreNote by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.welcome_body), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(32.dp))
        Button(onClick = onCreate, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.welcome_create))
        }
        OutlinedButton(onClick = { showRestoreNote = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.welcome_restore))
        }
        if (showRestoreNote) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.welcome_restore_soon),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Preview
@Composable
private fun WelcomePreview() = QuestsTheme(dark = false) { WelcomeScreen(busy = false, onCreate = {}) }
