package wrthub.app.ui.sites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wrthub.app.R
import wrthub.app.ui.theme.LocalSemanticColors

data class WifiActions(
    val onSsid: (String) -> Unit,
    val onKey: (String) -> Unit,
    val validate: () -> Boolean,
    val onApply: () -> Unit,
    val onBack: () -> Unit,
)

@Composable
fun WifiRoute(viewModel: WifiViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    WifiScreen(state, WifiActions(viewModel::onSsid, viewModel::onKey, viewModel::validate, viewModel::apply, onBack))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiScreen(state: WifiUiState, actions: WifiActions) {
    var confirming by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wifi_settings_title, state.site?.name.orEmpty())) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row {
                        Text(stringResource(R.string.current_ssid), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text(state.currentSsids.joinToString(", ").ifEmpty { "—" })
                    }
                    OutlinedTextField(
                        value = state.ssid,
                        onValueChange = actions.onSsid,
                        label = { Text(stringResource(R.string.new_ssid)) },
                        singleLine = true,
                        enabled = !state.applying,
                        isError = state.inputError == WifiInputError.SSID,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val key = rememberTextFieldState(state.key)
                    LaunchedEffect(key, actions.onKey) { snapshotFlow { key.text.toString() }.collect(actions.onKey) }
                    OutlinedSecureTextField(
                        state = key,
                        label = { Text(stringResource(R.string.new_password)) },
                        enabled = !state.applying,
                        isError = state.inputError == WifiInputError.KEY,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    state.inputError?.let {
                        Text(
                            stringResource(if (it == WifiInputError.SSID) R.string.error_ssid else R.string.error_psk),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(stringResource(R.string.wifi_warning), style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = { if (actions.validate()) confirming = true },
                        enabled = !state.applying && state.site != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.applying) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.apply))
                        }
                    }
                }
            }
            state.results?.let { results ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp)) {
                        results.forEach { result ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text(result.label, modifier = Modifier.weight(1f))
                                OutcomeText(result.outcome)
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.wifi_confirm_title)) },
            text = { Text(stringResource(R.string.wifi_confirm_body, state.site?.nodes?.size ?: 0, state.ssid)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    actions.onApply()
                }) { Text(stringResource(R.string.apply)) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun OutcomeText(outcome: WifiOutcome) {
    val semantic = LocalSemanticColors.current
    when (outcome) {
        is WifiOutcome.Changed -> Text(stringResource(R.string.wifi_changed, outcome.interfaces), color = semantic.signalGood)
        WifiOutcome.NoAccessPoint -> Text(stringResource(R.string.wifi_no_ap), color = semantic.inactive)
        WifiOutcome.NotApplied -> Text(stringResource(R.string.wifi_not_applied), color = semantic.signalFair)
        is WifiOutcome.Failed -> Text(stringResource(R.string.operation_failed, outcome.reason), color = MaterialTheme.colorScheme.error)
    }
}
