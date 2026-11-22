package wrthub.app.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import wrthub.app.ui.design.AppRow
import wrthub.app.ui.design.Badge
import wrthub.app.ui.design.GroupDivider
import wrthub.app.ui.design.ListPadding
import wrthub.app.ui.design.RowGroup
import wrthub.app.ui.design.SectionHeader
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
        title = stringResource(R.string.wireless),
        subtitle = state.title,
        notice = state.notice,
        onDismissNotice = actions.onDismissNotice,
        onBack = actions.onBack,
        onRefresh = actions.onRefresh,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(contentPadding = ListPadding) {
                state.failure?.let {
                    item { Text(failureText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(4.dp)) }
                }
                if (state.radios.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.radios)) }
                    item {
                        RowGroup {
                            state.radios.forEachIndexed { index, view ->
                                if (index > 0) GroupDivider()
                                RadioRow(view, state.saving, actions)
                            }
                        }
                    }
                }
                if (state.ssids.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.ssids)) }
                    item {
                        RowGroup {
                            state.ssids.forEachIndexed { index, ssid ->
                                if (index > 0) GroupDivider()
                                val band = state.radios.firstOrNull { it.radio.name == ssid.radio }?.radio?.band
                                AppRow(
                                    ssid.ssid ?: ssid.section,
                                    subtitle = ssid.radio,
                                    dim = !ssid.enabled,
                                    trailing = {
                                        bandLabel(band)?.let { Badge(it) }
                                        AdminSwitch(
                                            checked = ssid.enabled,
                                            enabled = !state.saving,
                                            onCheckedChange = { on -> if (on) actions.onSetSsidEnabled(ssid.section, true) else disabling = ssid },
                                        )
                                    },
                                )
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
            text = { Text(stringResource(R.string.ssid_disable_short)) },
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

private fun bandLabel(band: String?): String? = when (band) {
    "2g" -> "2.4 GHz"
    "5g" -> "5 GHz"
    "6g" -> "6 GHz"
    else -> null
}

private fun radioLabel(band: String?, name: String): String = bandLabel(band)?.let { "$it ($name)" } ?: name

@Composable
private fun RadioRow(view: RadioView, saving: Boolean, actions: WirelessActions) {
    val radio = view.radio
    val values = listOf(
        channelLabel(radio.channel),
        radio.htmode ?: stringResource(R.string.driver_default),
        txpowerLabel(radio.txpower),
    )
    AppRow(
        radioLabel(radio.band, radio.name),
        icon = R.drawable.ic_wifi,
        subtitle = values.joinToString(" · "),
        trailing = if (radio.carriesMesh) ({ Badge(stringResource(R.string.mesh_badge)) }) else null,
        onClick = { actions.onEdit(radio) },
        enabled = !saving,
    )
}

@Composable
private fun channelLabel(channel: String) = if (channel == "auto") stringResource(R.string.channel_auto) else "ch$channel"

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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                val notes = listOfNotNull(
                    stringResource(R.string.mesh_radio_locked).takeIf { locked },
                    stringResource(R.string.radio_down_note).takeIf { caps.channels.isEmpty() },
                    stringResource(R.string.radio_restart_short),
                )
                Text(
                    notes.joinToString(" "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = actions.onSave, enabled = !state.saving) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = actions.onCloseEditor) { Text(stringResource(R.string.cancel)) } },
    )
}
