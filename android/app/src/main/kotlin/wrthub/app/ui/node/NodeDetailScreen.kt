package wrthub.app.ui.node

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wrthub.app.R
import wrthub.app.ui.home.NodeCard

data class NodeDetailActions(
    val onHost: (String) -> Unit,
    val onPing: () -> Unit,
    val onTraceroute: () -> Unit,
    val onReboot: () -> Unit,
    val onDismissNotice: () -> Unit,
    val onBack: () -> Unit,
)

@Composable
fun NodeDetailRoute(viewModel: NodeDetailViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    NodeDetailScreen(
        state,
        NodeDetailActions(viewModel::onHost, viewModel::ping, viewModel::traceroute, viewModel::reboot, viewModel::dismissNotice, onBack),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeDetailScreen(state: NodeDetailUiState, actions: NodeDetailActions) {
    val snackbar = remember { SnackbarHostState() }
    val notice = state.notice?.let { noticeText(it) }
    LaunchedEffect(state.notice) {
        if (notice != null) {
            snackbar.showSnackbar(notice)
            actions.onDismissNotice()
        }
    }
    var confirmReboot by remember { mutableStateOf(false) }
    val label = state.node?.config?.label.orEmpty()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(label) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.node?.let { NodeCard(it, wifiClients = null) }
            Section(stringResource(R.string.diagnostics)) {
                OutlinedTextField(
                    value = state.host,
                    onValueChange = actions.onHost,
                    label = { Text(stringResource(R.string.target_host)) },
                    singleLine = true,
                    enabled = !state.running,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = actions.onPing, enabled = !state.running) { Text("ping") }
                    OutlinedButton(onClick = actions.onTraceroute, enabled = !state.running) { Text("traceroute") }
                    if (state.running) CircularProgressIndicator(Modifier.size(24.dp).padding(top = 8.dp), strokeWidth = 2.dp)
                }
                state.result?.let { result ->
                    Text(
                        "$ ${result.title}  (exit ${result.exitCode})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(
                        result.output,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Section(stringResource(R.string.reboot)) {
                Text(stringResource(R.string.reboot_body), style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = { confirmReboot = true },
                    enabled = !state.running && state.node != null,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text(stringResource(R.string.reboot)) }
            }
        }
    }
    if (confirmReboot) {
        AlertDialog(
            onDismissRequest = { confirmReboot = false },
            title = { Text(stringResource(R.string.reboot_confirm_title, label)) },
            text = { Text(stringResource(R.string.reboot_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReboot = false
                    actions.onReboot()
                }) { Text(stringResource(R.string.reboot)) }
            },
            dismissButton = { TextButton(onClick = { confirmReboot = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            content()
        }
    }
}

@Composable
private fun noticeText(notice: NodeNotice): String = when (notice) {
    NodeNotice.InvalidHost -> stringResource(R.string.error_invalid_host)
    NodeNotice.RebootRequested -> stringResource(R.string.notice_reboot_requested)
    is NodeNotice.Failed -> stringResource(R.string.operation_failed, notice.reason)
}
