package wrthub.app.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wrthub.app.R
import wrthub.core.admin.WanProto

data class WanActions(
    val onForm: ((WanForm) -> WanForm) -> Unit,
    val onSave: () -> Unit,
    val onRefresh: () -> Unit,
    val onDismissNotice: () -> Unit,
    val onBack: () -> Unit,
)

@Composable
fun WanRoute(viewModel: WanViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    WanScreen(state, WanActions(viewModel::onForm, viewModel::save, viewModel::refresh, viewModel::dismissNotice, onBack))
}

@Composable
fun WanScreen(state: WanUiState, actions: WanActions) {
    var confirming by remember { mutableStateOf(false) }
    AdminScaffold(
        title = stringResource(R.string.wan_title, state.title),
        notice = state.notice,
        onDismissNotice = actions.onDismissNotice,
        onBack = actions.onBack,
        onRefresh = actions.onRefresh,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
                val wan = state.wan
                when {
                    wan == null && !state.loading && state.failure == null -> Text(stringResource(R.string.no_wan))
                    wan != null && wan.proto == null -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            wan.options.forEach { (option, value) ->
                                Row(Modifier.fillMaxWidth()) {
                                    Text(option, modifier = Modifier.weight(0.4f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(value, modifier = Modifier.weight(0.6f))
                                }
                            }
                            Text(
                                stringResource(R.string.wan_unsupported, wan.rawProto ?: "?"),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                    wan != null -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        WanFormFields(state.form, wan.hasPassword, actions)
                        Button(
                            onClick = { confirming = true },
                            enabled = !state.saving,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        ) { Text(stringResource(R.string.save)) }
                    }
                }
            }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.wan_confirm_title)) },
            text = { Text(stringResource(R.string.wan_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    actions.onSave()
                }) { Text(stringResource(R.string.apply)) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun WanFormFields(form: WanForm, hasPassword: Boolean, actions: WanActions) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WanProto.entries.forEach { proto ->
                FilterChip(
                    selected = form.proto == proto,
                    onClick = { actions.onForm { it.copy(proto = proto) } },
                    label = { Text(stringResource(proto.label)) },
                )
            }
        }
        when (form.proto) {
            WanProto.DHCP -> Text(stringResource(R.string.wan_dhcp_note), style = MaterialTheme.typography.bodySmall)
            WanProto.STATIC -> {
                Field(form.ipaddr, { v -> actions.onForm { it.copy(ipaddr = v) } }, R.string.ip_address, form.error == WanField.IPADDR, R.string.error_ipv4, KeyboardType.Decimal)
                Field(form.netmask, { v -> actions.onForm { it.copy(netmask = v) } }, R.string.netmask, form.error == WanField.NETMASK, R.string.error_netmask, KeyboardType.Decimal)
                Field(form.gateway, { v -> actions.onForm { it.copy(gateway = v) } }, R.string.gateway, form.error == WanField.GATEWAY, R.string.error_ipv4, KeyboardType.Decimal)
                Field(form.dns, { v -> actions.onForm { it.copy(dns = v) } }, R.string.dns_servers, form.error == WanField.DNS, R.string.error_ipv4)
            }
            WanProto.PPPOE -> {
                Field(form.username, { v -> actions.onForm { it.copy(username = v) } }, R.string.pppoe_username, form.error == WanField.USERNAME, R.string.error_required)
                SecretField(
                    { v -> actions.onForm { it.copy(password = v) } },
                    if (hasPassword) R.string.pppoe_password_keep else R.string.pppoe_password,
                    form.error == WanField.PASSWORD,
                    R.string.error_required,
                )
            }
        }
    }
}

private val WanProto.label: Int
    get() = when (this) {
        WanProto.DHCP -> R.string.wan_dhcp
        WanProto.STATIC -> R.string.wan_static
        WanProto.PPPOE -> R.string.wan_pppoe
    }
