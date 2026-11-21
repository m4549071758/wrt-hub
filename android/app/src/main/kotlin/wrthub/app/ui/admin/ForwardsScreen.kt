package wrthub.app.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wrthub.app.R
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
        floatingActionButton = {
            if (state.forwards != null) {
                ExtendedFloatingActionButton(
                    onClick = actions.onAdd,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.add)) },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.failure?.let { item { Text(failureText(it), color = MaterialTheme.colorScheme.error) } }
                state.forwards?.let { forwards ->
                    item {
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            if (forwards.isEmpty()) Text(stringResource(R.string.no_forwards), modifier = Modifier.padding(16.dp))
                            forwards.forEachIndexed { index, forward ->
                                if (index > 0) HorizontalDivider()
                                ForwardRow(forward, state.saving, actions)
                            }
                        }
                    }
                }
            }
        }
    }
    state.editor?.let { ForwardDialog(it, state.targets, state.saving, actions) }
}

@Composable
private fun ForwardRow(forward: PortForward, saving: Boolean, actions: ForwardsActions) {
    ListItem(
        modifier = Modifier.clickable(enabled = forward.editable, onClick = { actions.onEdit(forward) }),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        supportingContent = {
            Column {
                Text(
                    stringResource(
                        R.string.forward_summary,
                        forward.protocol?.let { stringResource(it.label) } ?: "?",
                        forward.externalPort ?: "?",
                        forward.internalIp ?: "?",
                        forward.internalPort ?: forward.externalPort ?: "?",
                    ),
                )
                if (!forward.editable) Text(stringResource(R.string.forward_locked), style = MaterialTheme.typography.bodySmall)
            }
        },
        trailingContent = {
            Switch(
                checked = forward.enabled,
                onCheckedChange = { actions.onSetEnabled(forward.section, it) },
                enabled = forward.editable && !saving,
            )
        },
    ) {
        Text(forward.name ?: forward.section)
    }
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
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(editor.name, { v -> actions.onEditor { it.copy(name = v) } }, R.string.optional_name, editor.error == ForwardField.NAME, R.string.error_name)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ForwardProtocol.entries.forEach { protocol ->
                        FilterChip(
                            selected = editor.protocol == protocol,
                            onClick = { actions.onEditor { it.copy(protocol = protocol) } },
                            label = { Text(stringResource(protocol.label)) },
                        )
                    }
                }
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
                Text(stringResource(R.string.rollback_note), style = MaterialTheme.typography.bodySmall)
                if (editor.section != null) {
                    TextButton(onClick = { confirmDelete = true }, enabled = !saving) {
                        Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = actions.onSave, enabled = !saving) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = actions.onCloseEditor) { Text(stringResource(R.string.cancel)) } },
    )
    if (confirmDelete && editor.section != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_confirm_title, editor.name.ifBlank { editor.externalPort })) },
            text = { Text(stringResource(R.string.rollback_note)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    actions.onDelete(editor.section)
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
