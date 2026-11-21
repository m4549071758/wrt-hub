package wrthub.app.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wrthub.app.R
import wrthub.core.admin.Radio
import wrthub.core.admin.Ssid
import wrthub.core.admin.htmodesFor

data class WirelessActions(
    val onEdit: (Radio) -> Unit,
    val onChannel: (String) -> Unit,
    val onHtmode: (String) -> Unit,
    val onTxpower: (Int?) -> Unit,
    val onSave: () -> Unit,
    val onCloseEditor: () -> Unit,
    val onSetSsidEnabled: (String, Boolean) -> Unit,
    val onRefresh: () -> Unit,
    val onDismissNotice: () -> Unit,
    val onBack: () -> Unit,
)

@Composable
fun WirelessRoute(viewModel: WirelessViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    WirelessScreen(
        state,
        WirelessActions(
            viewModel::edit,
            viewModel::onChannel,
            viewModel::onHtmode,
            viewModel::onTxpower,
            viewModel::save,
            viewModel::closeEditor,
            viewModel::setSsidEnabled,
            viewModel::refresh,
            viewModel::dismissNotice,
            onBack,
        ),
    )
}

@Composable
fun WirelessScreen(state: WirelessUiState, actions: WirelessActions) {
    var disabling by remember { mutableStateOf<Ssid?>(null) }
    AdminScaffold(
        title = stringResource(R.string.wireless_title, state.title),
        notice = state.notice,
        onDismissNotice = actions.onDismissNotice,
        onBack = actions.onBack,
        onRefresh = actions.onRefresh,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.failure?.let { item { Text(failureText(it), color = MaterialTheme.colorScheme.error) } }
                items(state.radios, key = { it.radio.name }) { view -> RadioCard(view, state.saving, actions) }
                if (state.ssids.isNotEmpty()) {
                    item {
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Text(stringResource(R.string.ssids), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                            state.ssids.forEachIndexed { index, ssid ->
                                if (index > 0) HorizontalDivider()
                                ListItem(
                                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                                    supportingContent = { Text(radioLabel(state.radios.firstOrNull { it.radio.name == ssid.radio }?.radio?.band, ssid.radio)) },
                                    trailingContent = {
                                        Switch(
                                            checked = ssid.enabled,
                                            enabled = !state.saving,
                                            onCheckedChange = { on -> if (on) actions.onSetSsidEnabled(ssid.section, true) else disabling = ssid },
                                        )
                                    },
                                ) { Text(ssid.ssid ?: ssid.section) }
                            }
                        }
                    }
                }
            }
        }
    }
    state.editor?.let { RadioDialog(it, state, actions) }
    disabling?.let { ssid ->
        AlertDialog(
            onDismissRequest = { disabling = null },
            title = { Text(stringResource(R.string.ssid_disable_title, ssid.ssid ?: ssid.section)) },
            text = { Text(stringResource(R.string.ssid_disable_body)) },
            confirmButton = {
                TextButton(onClick = {
                    disabling = null
                    actions.onSetSsidEnabled(ssid.section, false)
                }) { Text(stringResource(R.string.disable)) }
            },
            dismissButton = { TextButton(onClick = { disabling = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun radioLabel(band: String?, name: String): String = when (band) {
    "2g" -> "2.4GHz ($name)"
    "5g" -> "5GHz ($name)"
    "6g" -> "6GHz ($name)"
    else -> name
}

@Composable
private fun RadioCard(view: RadioView, saving: Boolean, actions: WirelessActions) {
    val radio = view.radio
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(radioLabel(radio.band, radio.name), style = MaterialTheme.typography.titleMedium)
            Value(stringResource(R.string.channel), channelLabel(radio.channel))
            Value(stringResource(R.string.width), radio.htmode ?: stringResource(R.string.driver_default))
            Value(stringResource(R.string.txpower), txpowerLabel(radio.txpower))
            if (radio.carriesMesh) Text(stringResource(R.string.mesh_radio_note), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { actions.onEdit(radio) }, enabled = !saving) { Text(stringResource(R.string.edit)) }
        }
    }
}

@Composable
private fun Value(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

@Composable
private fun channelLabel(channel: String) = if (channel == "auto") stringResource(R.string.channel_auto) else channel

@Composable
private fun txpowerLabel(txpower: Int?) = txpower?.let { "$it dBm" } ?: stringResource(R.string.driver_default)

@Composable
private fun RadioDialog(editor: RadioEditor, state: WirelessUiState, actions: WirelessActions) {
    val view = state.radios.firstOrNull { it.radio.name == editor.radio } ?: return
    val caps = view.capabilities
    val locked = view.radio.carriesMesh
    AlertDialog(
        onDismissRequest = actions.onCloseEditor,
        title = { Text(radioLabel(view.radio.band, view.radio.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Selector(
                    stringResource(R.string.channel),
                    editor.channel,
                    listOf("auto") + caps.channels.map { it.channel.toString() },
                    { channelLabel(it) },
                    actions.onChannel,
                    enabled = !locked,
                )
                Selector(
                    stringResource(R.string.width),
                    editor.htmode.orEmpty(),
                    caps.htmodesFor(editor.channel),
                    { it.ifEmpty { stringResource(R.string.driver_default) } },
                    actions.onHtmode,
                    enabled = !locked,
                )
                Selector(stringResource(R.string.txpower), editor.txpower, listOf<Int?>(null) + caps.txpowers, { txpowerLabel(it) }, actions.onTxpower)
                if (locked) Text(stringResource(R.string.mesh_radio_note), style = MaterialTheme.typography.bodySmall)
                if (caps.channels.isEmpty()) Text(stringResource(R.string.radio_down_note), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.radio_restart_note), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = actions.onSave, enabled = !state.saving) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = actions.onCloseEditor) { Text(stringResource(R.string.cancel)) } },
    )
}
