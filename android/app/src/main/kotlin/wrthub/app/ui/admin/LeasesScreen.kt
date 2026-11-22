package wrthub.app.ui.admin

import androidx.compose.foundation.background
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
import androidx.compose.runtime.LaunchedEffect
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
import wrthub.app.ui.design.SecondaryButton
import wrthub.app.ui.design.SectionHeader
import wrthub.app.ui.design.UnderlineField
import wrthub.core.admin.DhcpRange
import wrthub.core.admin.StaticLease
import wrthub.core.admin.addresses

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
    val onEditRange: (DhcpRange) -> Unit = {},
    val onRange: ((RangeEditor) -> RangeEditor) -> Unit = {},
    val onSaveRange: () -> Unit = {},
    val onCloseRange: () -> Unit = {},
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
            viewModel::editRange,
            viewModel::onRange,
            viewModel::saveRange,
            viewModel::closeRange,
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
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(contentPadding = ListPadding) {
                state.failure?.let { item { Text(failureText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) } }
                if (state.servesDhcp == false) item { SectionHeader(stringResource(R.string.not_dhcp_server)) }
                if (state.servesDhcp == true) {
                    if (state.ranges.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.dhcp_range)) }
                        item {
                            RowGroup {
                                state.ranges.forEachIndexed { index, range ->
                                    if (index > 0) GroupDivider()
                                    val addresses = range.addresses()
                                    AppRow(
                                        range.network,
                                        subtitle = if (addresses != null) {
                                            stringResource(R.string.dhcp_range_addresses, addresses.first, addresses.second, range.leasetime)
                                        } else {
                                            stringResource(R.string.dhcp_range_summary, range.start, range.limit, range.leasetime)
                                        },
                                        trailing = range.subnet?.let { subnet -> { Badge(subnet) } },
                                        onClick = { actions.onEditRange(range) },
                                        enabled = !state.saving,
                                    )
                                }
                            }
                        }
                    }
                    item { SectionHeader(stringResource(R.string.static_leases), trailing = state.leases.size.toString()) }
                    item {
                        RowGroup {
                            if (state.leases.isEmpty()) AppRow(stringResource(R.string.no_leases), dim = true)
                            state.leases.forEachIndexed { index, lease ->
                                if (index > 0) GroupDivider()
                                LeaseRow(lease, onClick = { actions.onEdit(lease) })
                            }
                        }
                    }
                    item { PrimaryButton(stringResource(R.string.add), actions.onAdd, Modifier.fillMaxWidth().padding(top = 16.dp), enabled = !state.saving) }
                }
            }
        }
    }
    state.editor?.let { LeaseDialog(it, state.saving, actions) }
    state.rangeEditor?.let { RangeDialog(it, state.ranges.firstOrNull { r -> r.section == it.section }, state.saving, actions) }
}

@Composable
private fun RangeDialog(editor: RangeEditor, range: DhcpRange?, saving: Boolean, actions: LeasesActions) {
    AlertDialog(
        onDismissRequest = actions.onCloseRange,
        title = { Text(stringResource(R.string.dhcp_range_title, range?.network.orEmpty())) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                range?.subnet?.let { Text(stringResource(R.string.lan_address, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Field(editor.start, { v -> actions.onRange { it.copy(start = v) } }, R.string.dhcp_start, editor.error == RangeField.START, R.string.error_dhcp_start, KeyboardType.Number)
                Field(editor.limit, { v -> actions.onRange { it.copy(limit = v) } }, R.string.dhcp_limit, editor.error == RangeField.LIMIT, R.string.error_dhcp_limit, KeyboardType.Number)
                Field(editor.leasetime, { v -> actions.onRange { it.copy(leasetime = v) } }, R.string.dhcp_leasetime, editor.error == RangeField.LEASETIME, R.string.error_leasetime)
                DialogButtons(R.string.save, actions.onSaveRange, !saving, actions.onCloseRange)
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun LeaseRow(lease: StaticLease, onClick: () -> Unit) {
    AppRow(
        lease.name ?: lease.macs.firstOrNull().orEmpty(),
        subtitle = listOfNotNull(lease.ip, lease.macs.joinToString()).joinToString(" · "),
        trailing = if (lease.editable) null else ({ Badge(stringResource(R.string.entry_locked)) }),
        onClick = onClick,
        enabled = lease.editable,
        chevron = lease.editable,
    )
}

@Composable
private fun LeaseDialog(editor: LeaseEditor, saving: Boolean, actions: LeasesActions) {
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = actions.onCloseEditor,
        title = { Text(stringResource(if (editor.section == null) R.string.lease_add_title else R.string.lease_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field(editor.name, actions.onName, R.string.optional_name, editor.error == LeaseField.NAME, R.string.error_hostname)
                Field(editor.mac, actions.onMac, R.string.mac_address, editor.error == LeaseField.MAC, R.string.error_mac)
                Field(editor.ip, actions.onIp, R.string.ip_address, editor.error == LeaseField.IP, R.string.error_ipv4, KeyboardType.Decimal)
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
        DeleteDialog(editor.name.ifBlank { editor.mac }, onDelete = { actions.onDelete(editor.section) }, onClose = { confirmDelete = false })
    }
}

/** Confirms removing the entry [name]. */
@Composable
internal fun DeleteDialog(name: String, onDelete: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.delete_confirm_title, name)) },
        text = { Text(stringResource(R.string.entry_removed)) },
        confirmButton = {
            TextButton(onClick = {
                onClose()
                onDelete()
            }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The primary action above Cancel at the bottom of an edit dialog. */
@Composable
internal fun DialogButtons(primary: Int, onPrimary: () -> Unit, enabled: Boolean, onCancel: () -> Unit) {
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryButton(stringResource(primary), onPrimary, Modifier.fillMaxWidth(), enabled = enabled, height = 46)
        SecondaryButton(stringResource(R.string.cancel), onCancel, Modifier.fillMaxWidth(), height = 46)
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
    UnderlineField(
        stringResource(label),
        value,
        onValue,
        Modifier.fillMaxWidth(),
        error = if (isError) stringResource(errorText) else null,
        keyboardType = keyboard,
    )
}

/** A masked field; starts empty and forwards each edit to [onValue]. */
@Composable
internal fun SecretField(onValue: (String) -> Unit, label: Int, isError: Boolean, errorText: Int) {
    var value by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { onValue(value) }
    UnderlineField(
        stringResource(label),
        value,
        {
            value = it
            onValue(it)
        },
        Modifier.fillMaxWidth(),
        error = if (isError) stringResource(errorText) else null,
        password = true,
    )
}
