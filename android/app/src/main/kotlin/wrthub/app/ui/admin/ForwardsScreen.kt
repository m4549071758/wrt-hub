package wrthub.app.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wrthub.app.R
import wrthub.app.ui.design.AppRow
import wrthub.app.ui.design.Badge
import wrthub.app.ui.design.GroupDivider
import wrthub.app.ui.design.ListPadding
import wrthub.app.ui.design.PrimaryButton
import wrthub.app.ui.design.RowGroup
import wrthub.app.ui.design.SectionHeader
import wrthub.app.ui.design.SegmentedToggle
import wrthub.core.admin.ForwardProtocol
import wrthub.core.admin.PortForward

data class ForwardsActions(
    val onAdd: () -> Unit,
    val onEdit: (PortForward) -> Unit,
    val onEditor: ((ForwardEditor) -> ForwardEditor) -> Unit,
    val onSave: () -> Unit,
    val onSetEnabled: (String, Boolean) -> Unit,
    val onDelete: (String) -> Unit,
    val onCloseEditor: () -> Unit,
    val onRefresh: () -> Unit,
    val onDismissNotice: () -> Unit,
    val onBack: () -> Unit,
)

@Composable
fun ForwardsRoute(viewModel: ForwardsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ForwardsScreen(
        state,
        ForwardsActions(
            viewModel::add,
            viewModel::edit,
            viewModel::onEditor,
            viewModel::save,
            viewModel::setEnabled,
            viewModel::delete,
            viewModel::closeEditor,
            viewModel::refresh,
            viewModel::dismissNotice,
            onBack,
        ),
    )
}

@Composable
fun ForwardsScreen(state: ForwardsUiState, actions: ForwardsActions) {
    AdminScaffold(
        title = stringResource(R.string.port_forwards_title, state.title),
        notice = state.notice,
        onDismissNotice = actions.onDismissNotice,
        onBack = actions.onBack,
        onRefresh = actions.onRefresh,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(contentPadding = ListPadding) {
                state.failure?.let { item { Text(failureText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) } }
                state.forwards?.let { forwards ->
                    item { SectionHeader(stringResource(R.string.port_forwards), trailing = forwards.size.toString()) }
                    item {
                        RowGroup {
                            if (forwards.isEmpty()) AppRow(stringResource(R.string.no_forwards), dim = true)
                            forwards.forEachIndexed { index, forward ->
                                if (index > 0) GroupDivider()
                                ForwardRow(forward, state.saving, actions)
                            }
                        }
                    }
                    item { PrimaryButton(stringResource(R.string.add), actions.onAdd, Modifier.fillMaxWidth().padding(top = 16.dp), enabled = !state.saving) }
                }
            }
        }
    }
    state.editor?.let { ForwardDialog(it, state.targets, state.saving, actions) }
}

@Composable
private fun ForwardRow(forward: PortForward, saving: Boolean, actions: ForwardsActions) {
    val colors = MaterialTheme.colorScheme
    AppRow(
        forward.name ?: forward.section,
        subtitle = "${forward.externalPort ?: "?"} → ${forward.internalIp ?: "?"}:${forward.internalPort ?: forward.externalPort ?: "?"}",
        trailing = {
            Badge(if (forward.editable) forward.protocol?.let { stringResource(it.label) } ?: "?" else stringResource(R.string.entry_locked))
            Switch(
                checked = forward.enabled,
                onCheckedChange = { actions.onSetEnabled(forward.section, it) },
                enabled = forward.editable && !saving,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = colors.onSurface,
                    checkedThumbColor = colors.background,
                    uncheckedTrackColor = colors.surfaceVariant,
                    uncheckedBorderColor = colors.outline,
                ),
            )
        },
        onClick = { actions.onEdit(forward) },
        enabled = forward.editable,
        dim = !forward.enabled,
        chevron = false,
    )
}

private val ForwardProtocol.label: Int
    get() = when (this) {
        ForwardProtocol.TCP -> R.string.protocol_tcp
        ForwardProtocol.UDP -> R.string.protocol_udp
        ForwardProtocol.BOTH -> R.string.protocol_both
    }

@Composable
private fun ForwardDialog(editor: ForwardEditor, targets: List<ForwardTarget>, saving: Boolean, actions: ForwardsActions) {
    var confirmDelete by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = actions.onCloseEditor,
        title = { Text(stringResource(if (editor.section == null) R.string.forward_add_title else R.string.forward_edit_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field(editor.name, { v -> actions.onEditor { it.copy(name = v) } }, R.string.optional_name, editor.error == ForwardField.NAME, R.string.error_name)
                SegmentedToggle(
                    ForwardProtocol.entries.map { it to stringResource(it.label) },
                    editor.protocol,
                    { protocol -> actions.onEditor { it.copy(protocol = protocol) } },
                )
                Field(
                    editor.externalPort,
                    { v -> actions.onEditor { it.copy(externalPort = v) } },
                    R.string.external_port,
                    editor.error == ForwardField.EXTERNAL_PORT,
                    R.string.error_port,
                    KeyboardType.Number,
                )
                Box {
                    Field(
                        editor.internalIp,
                        { v -> actions.onEditor { it.copy(internalIp = v) } },
                        R.string.internal_ip,
                        editor.error == ForwardField.INTERNAL_IP,
                        R.string.error_ipv4,
                        KeyboardType.Decimal,
                    )
                    DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                        targets.forEach { target ->
                            DropdownMenuItem(
                                text = { Text("${target.label}  ${target.ip}") },
                                onClick = {
                                    choosing = false
                                    actions.onEditor { it.copy(internalIp = target.ip) }
                                },
                            )
                        }
                    }
                }
                if (targets.isNotEmpty()) {
                    TextButton(onClick = { choosing = true }) { Text(stringResource(R.string.choose_client)) }
                }
                Field(
                    editor.internalPort,
                    { v -> actions.onEditor { it.copy(internalPort = v) } },
                    R.string.internal_port,
                    editor.error == ForwardField.INTERNAL_PORT,
                    R.string.error_port,
                    KeyboardType.Number,
                )
                DialogButtons(R.string.save, actions.onSave, !saving, actions.onCloseEditor)
                if (editor.section != null) {
                    TextButton(onClick = { confirmDelete = true }, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {},
    )
    if (confirmDelete && editor.section != null) {
        DeleteDialog(editor.name.ifBlank { editor.externalPort }, onDelete = { actions.onDelete(editor.section) }, onClose = { confirmDelete = false })
    }
}
