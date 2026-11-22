package wrthub.app.ui.admin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wrthub.app.R
import wrthub.app.ui.design.KeyValueCard
import wrthub.app.ui.design.PrimaryButton
import wrthub.app.ui.design.ScreenPadding
import wrthub.app.ui.design.SegmentedToggle
import wrthub.app.ui.design.UnderlineField
import wrthub.app.ui.design.ValueText
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
        title = stringResource(R.string.wan),
        subtitle = state.title,
        notice = state.notice,
        onDismissNotice = actions.onDismissNotice,
        onBack = actions.onBack,
        onRefresh = actions.onRefresh,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(start = ScreenPadding, end = ScreenPadding, top = 4.dp, bottom = 24.dp),
            ) {
                state.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(4.dp)) }
                val wan = state.wan
                when {
                    wan == null && !state.loading && state.failure == null -> Note(stringResource(R.string.no_wan))
                    wan != null && wan.proto == null -> {
                        KeyValueCard(wan.options.map { (option, value) -> option to @Composable { ValueText(value) } })
                        Note(stringResource(R.string.wan_unsupported_short, wan.rawProto ?: "?"))
                    }
                    wan != null -> {
                        WanFormFields(state.form, wan.hasPassword, actions)
                        PrimaryButton(
                            stringResource(R.string.save),
                            onClick = { confirming = true },
                            enabled = !state.saving,
                            modifier = Modifier.fillMaxWidth().padding(top = 28.dp),
                        )
                    }
                }
            }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.wan_confirm_title)) },
            text = { Text(stringResource(R.string.wan_confirm_short)) },
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
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp),
    )
}

@Composable
private fun WanFormFields(form: WanForm, hasPassword: Boolean, actions: WanActions) {
    SegmentedToggle(
        WanProto.entries.map { it to stringResource(it.label) },
        form.proto,
        { proto -> actions.onForm { it.copy(proto = proto) } },
        Modifier.padding(top = 8.dp),
    )
    when (form.proto) {
        WanProto.DHCP -> Note(stringResource(R.string.wan_dhcp_note))
        WanProto.STATIC -> {
            FormField(form.ipaddr, R.string.ip_address, form.error == WanField.IPADDR, R.string.error_ipv4, KeyboardType.Decimal) { v -> actions.onForm { it.copy(ipaddr = v) } }
            FormField(form.netmask, R.string.netmask, form.error == WanField.NETMASK, R.string.error_netmask, KeyboardType.Decimal) { v -> actions.onForm { it.copy(netmask = v) } }
            FormField(form.gateway, R.string.gateway, form.error == WanField.GATEWAY, R.string.error_ipv4, KeyboardType.Decimal) { v -> actions.onForm { it.copy(gateway = v) } }
            FormField(form.dns, R.string.dns_servers, form.error == WanField.DNS, R.string.error_ipv4) { v -> actions.onForm { it.copy(dns = v) } }
        }
        WanProto.PPPOE -> {
            FormField(form.username, R.string.pppoe_username, form.error == WanField.USERNAME, R.string.error_required) { v -> actions.onForm { it.copy(username = v) } }
            UnderlineField(
                stringResource(if (hasPassword) R.string.pppoe_password_keep else R.string.pppoe_password),
                form.password,
                { v -> actions.onForm { it.copy(password = v) } },
                Modifier.padding(top = 8.dp),
                error = if (form.error == WanField.PASSWORD) stringResource(R.string.error_required) else null,
                password = true,
            )
        }
    }
}

@Composable
private fun FormField(value: String, label: Int, error: Boolean, errorText: Int, keyboard: KeyboardType = KeyboardType.Text, onValue: (String) -> Unit) {
    UnderlineField(
        stringResource(label),
        value,
        onValue,
        Modifier.padding(top = 8.dp),
        error = if (error) stringResource(errorText) else null,
        keyboardType = keyboard,
    )
}

private val WanProto.label: Int
    get() = when (this) {
        WanProto.DHCP -> R.string.wan_dhcp
        WanProto.STATIC -> R.string.wan_static
        WanProto.PPPOE -> R.string.wan_pppoe
    }
