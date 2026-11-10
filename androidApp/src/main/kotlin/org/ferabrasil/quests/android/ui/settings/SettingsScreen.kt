package org.ferabrasil.quests.android.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ferabrasil.quests.android.R
import org.ferabrasil.quests.android.ui.RootUiState
import org.ferabrasil.quests.android.ui.RootViewModel
import org.ferabrasil.quests.app.AppInfo
import org.ferabrasil.quests.domain.preferences.ThemeMode

/** Settings (app.md §5.4). Tokens, export, app lock, network status arrive with their milestones. */
@Composable
fun SettingsScreen(vm: RootViewModel, state: RootUiState) {
    var confirmingWipe by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_theme)) },
            supportingContent = {
                ThemePicker(state.preferences.theme, vm::setTheme, Modifier.padding(top = 8.dp))
            },
        )
        HorizontalDivider()
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_leader_mode)) },
            supportingContent = { Text(stringResource(R.string.settings_leader_mode_hint)) },
            trailingContent = { Switch(checked = state.preferences.leaderMode, onCheckedChange = vm::setLeaderMode) },
        )
        HorizontalDivider()
        TextButton(
            onClick = { confirmingWipe = true },
            enabled = !state.busy,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.padding(8.dp),
        ) {
            Text(stringResource(R.string.settings_wipe))
        }
        HorizontalDivider()
        // Selectable so it can be copied into a bug report.
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_version)) },
            supportingContent = { SelectionContainer { Text(AppInfo.version) } },
        )
    }

    if (confirmingWipe) {
        WipeDialog(
            matches = vm::wipeWordMatches,
            onConfirm = {
                confirmingWipe = false
                vm.wipeIdentity()
            },
            onDismiss = { confirmingWipe = false },
        )
    }
}

@Composable
private fun ThemePicker(selected: ThemeMode, onSelect: (ThemeMode) -> Unit, modifier: Modifier = Modifier) {
    val options = listOf(
        ThemeMode.SYSTEM to R.string.theme_system,
        ThemeMode.LIGHT to R.string.theme_light,
        ThemeMode.DARK to R.string.theme_dark,
    )
    SingleChoiceSegmentedButtonRow(modifier) {
        options.forEachIndexed { i, (mode, label) ->
            SegmentedButton(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(stringResource(label)) }
        }
    }
}

/** Wipe requires typing the confirmation word (app.md §5.4). */
@Composable
private fun WipeDialog(matches: (String, String) -> Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val word = stringResource(R.string.wipe_word)
    var typed by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wipe_title)) },
        text = {
            Column {
                Text(stringResource(R.string.wipe_body, word))
                Row(Modifier.padding(top = 16.dp)) {
                    OutlinedTextField(value = typed, onValueChange = { typed = it }, singleLine = true)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = matches(typed, word),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.wipe_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
