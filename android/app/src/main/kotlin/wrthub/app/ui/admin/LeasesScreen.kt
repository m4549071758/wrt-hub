package wrthub.app.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import wrthub.core.admin.StaticLease

data class LeasesActions(
    val onAdd: () -> Unit,
    val onEdit: (StaticLease) -> Unit,
    val onName: (String) -> Unit,
    val onMac: (String) -> Unit,
    val onIp: (String) -> Unit,
    val onSave: () -> Unit,
    val onDelete: (String) -> Unit,
    val onCloseEditor: () -> Unit,
    val onRefresh: () -> Unit,
    val onDismissNotice: () -> Unit,
    val onBack: () -> Unit,
)

@Composable
fun LeasesRoute(viewModel: LeasesViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LeasesScreen(
        state,
        LeasesActions(
            viewModel::add,
            viewModel::edit,
            viewModel::onName,
            viewModel::onMac,
            viewModel::onIp,
            viewModel::save,
            viewModel::delete,
            viewModel::closeEditor,
            viewModel::refresh,
            viewModel::dismissNotice,
            onBack,
        ),
    )
}

@Composable
fun LeasesScreen(state: LeasesUiState, actions: LeasesActions) {
    AdminScaffold(
        title = stringResource(R.string.static_leases_title, state.title),
        notice = state.notice,
        onDismissNotice = actions.onDismissNotice,
        onBack = actions.onBack,
        onRefresh = actions.onRefresh,
        floatingActionButton = {
            if (state.servesDhcp == true) {
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
                if (state.servesDhcp == false) item { Text(stringResource(R.string.not_dhcp_server)) }
                if (state.servesDhcp == true) {
                    item {
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            if (state.leases.isEmpty()) Text(stringResource(R.string.no_leases), modifier = Modifier.padding(16.dp))
                            state.leases.forEachIndexed { index, lease ->
                                if (index > 0) HorizontalDivider()
                                LeaseRow(lease, onClick = { actions.onEdit(lease) })
                            }
                        }
                    }
                }
            }
        }
    }
    state.editor?.let { LeaseDialog(it, state.saving, actions) }
}

@Composable
private fun LeaseRow(lease: StaticLease, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(enabled = lease.editable, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        supportingContent = {
            Column {
                Text(lease.macs.joinToString())
                if (!lease.editable) Text(stringResource(R.string.lease_locked), style = MaterialTheme.typography.bodySmall)
            }
        },
        trailingContent = { lease.ip?.let { Text(it) } },
    ) {
        Text(lease.name ?: lease.macs.firstOrNull().orEmpty())
    }
}

@Composable
private fun LeaseDialog(editor: LeaseEditor, saving: Boolean, actions: LeasesActions) {
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = actions.onCloseEditor,
        title = { Text(stringResource(if (editor.section == null) R.string.lease_add_title else R.string.lease_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(editor.name, actions.onName, R.string.optional_name, editor.error == LeaseField.NAME, R.string.error_hostname)
                Field(editor.mac, actions.onMac, R.string.mac_address, editor.error == LeaseField.MAC, R.string.error_mac)
                Field(editor.ip, actions.onIp, R.string.ip_address, editor.error == LeaseField.IP, R.string.error_ipv4, KeyboardType.Decimal)
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
            title = { Text(stringResource(R.string.delete_confirm_title, editor.name.ifBlank { editor.mac })) },
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

@Composable
internal fun Field(
    value: String,
    onValue: (String) -> Unit,
    label: Int,
    isError: Boolean,
    errorText: Int,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(stringResource(label)) },
        isError = isError,
        supportingText = if (isError) ({ Text(stringResource(errorText)) }) else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}
