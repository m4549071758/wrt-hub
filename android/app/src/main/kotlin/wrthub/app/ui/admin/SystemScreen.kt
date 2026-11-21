package wrthub.app.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wrthub.app.R
import wrthub.core.admin.Led

data class SystemActions(
    val onForm: ((SystemForm) -> SystemForm) -> Unit,
    val onSaveSystem: () -> Unit,
    val onEditLed: (Led) -> Unit,
    val onLed: ((LedEditor) -> LedEditor) -> Unit,
    val onSaveLed: () -> Unit,
    val onCloseLed: () -> Unit,
    val onCron: (String) -> Unit,
    val onSaveCron: () -> Unit,
    val onRefresh: () -> Unit,
    val onDismissNotice: () -> Unit,
    val onBack: () -> Unit,
)

@Composable
fun SystemRoute(viewModel: SystemViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SystemScreen(
        state,
        SystemActions(
            viewModel::onForm,
            viewModel::saveSystem,
            viewModel::editLed,
            viewModel::onLed,
            viewModel::saveLed,
            viewModel::closeLed,
            viewModel::onCron,
            viewModel::saveCron,
            viewModel::refresh,
            viewModel::dismissNotice,
            onBack,
        ),
    )
}

@Composable
fun SystemScreen(state: SystemUiState, actions: SystemActions) {
    var confirmCron by remember { mutableStateOf(false) }
    AdminScaffold(
        title = stringResource(R.string.system_title, state.title),
        notice = state.notice,
        onDismissNotice = actions.onDismissNotice,
        onBack = actions.onBack,
        onRefresh = actions.onRefresh,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
                if (state.config != null) {
                    GeneralCard(state, actions)
                    if (state.leds.isNotEmpty()) LedCard(state.leds, state.saving, actions)
                    CronCard(state, onSave = { confirmCron = true }, actions)
                }
            }
        }
    }
    state.ledEditor?.let { editor -> state.leds.firstOrNull { it.name == editor.name }?.let { LedDialog(it, editor, state, actions) } }
    if (confirmCron) {
        AlertDialog(
            onDismissRequest = { confirmCron = false },
            title = { Text(stringResource(R.string.cron_confirm_title)) },
            text = { Text(stringResource(R.string.cron_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmCron = false
                    actions.onSaveCron()
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { confirmCron = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SectionCard(title: Int, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun GeneralCard(state: SystemUiState, actions: SystemActions) {
    val form = state.form
    var pickingZone by remember { mutableStateOf(false) }
    SectionCard(R.string.system_general) {
        Field(form.hostname, { v -> actions.onForm { it.copy(hostname = v) } }, R.string.hostname, form.error == SystemField.HOSTNAME, R.string.error_hostname)
        Column {
            Text(stringResource(R.string.timezone), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { pickingZone = true }, modifier = Modifier.fillMaxWidth()) { Text(form.zonename) }
            if (form.error == SystemField.ZONE) Text(stringResource(R.string.error_timezone), color = MaterialTheme.colorScheme.error)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ntp_enabled), modifier = Modifier.weight(1f))
            Switch(checked = form.ntpEnabled, onCheckedChange = { on -> actions.onForm { it.copy(ntpEnabled = on) } })
        }
        OutlinedTextField(
            value = form.ntpServers,
            onValueChange = { v -> actions.onForm { it.copy(ntpServers = v) } },
            label = { Text(stringResource(R.string.ntp_servers)) },
            isError = form.error == SystemField.NTP,
            supportingText = { Text(stringResource(if (form.error == SystemField.NTP) R.string.error_ntp else R.string.one_per_line)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.rollback_note), style = MaterialTheme.typography.bodySmall)
        Button(onClick = actions.onSaveSystem, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save)) }
    }
    if (pickingZone) {
        ZonePicker(state.zones, onPick = { zone ->
            pickingZone = false
            actions.onForm { it.copy(zonename = zone) }
        }, onDismiss = { pickingZone = false })
    }
}

@Composable
private fun ZonePicker(zones: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.timezone)) },
        text = {
            Column {
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text(stringResource(R.string.filter)) }, singleLine = true)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(zones.filter { it.contains(query.trim(), ignoreCase = true) }) { zone ->
                        Text(zone, modifier = Modifier.fillMaxWidth().clickable { onPick(zone) }.padding(vertical = 10.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun LedCard(leds: List<Led>, saving: Boolean, actions: SystemActions) {
    SectionCard(R.string.leds) {
        leds.forEachIndexed { index, led ->
            if (index > 0) HorizontalDivider()
            ListItem(
                modifier = Modifier.clickable(enabled = !saving) { actions.onEditLed(led) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                supportingContent = { Text(listOfNotNull(led.activeTrigger, led.device).joinToString(" · ")) },
            ) { Text(led.name) }
        }
    }
}

@Composable
private fun LedDialog(led: Led, editor: LedEditor, state: SystemUiState, actions: SystemActions) {
    AlertDialog(
        onDismissRequest = actions.onCloseLed,
        title = { Text(led.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Selector(stringResource(R.string.led_trigger), editor.trigger, led.triggers, { it }, { t -> actions.onLed { it.copy(trigger = t) } })
                if (editor.trigger == "netdev") {
                    Selector(stringResource(R.string.led_device), editor.device, state.devices, { it.ifEmpty { "—" } }, { d -> actions.onLed { it.copy(device = d) } })
                }
                Text(stringResource(R.string.rollback_note), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                onClick = actions.onSaveLed,
                enabled = !state.saving && (editor.trigger != "netdev" || editor.device.isNotEmpty()),
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = actions.onCloseLed) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun CronCard(state: SystemUiState, onSave: () -> Unit, actions: SystemActions) {
    SectionCard(R.string.cron) {
        OutlinedTextField(
            value = state.cron,
            onValueChange = actions.onCron,
            placeholder = { Text("0 4 * * * /sbin/reboot", fontFamily = FontFamily.Monospace) },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = onSave, enabled = !state.saving && state.cron != state.savedCron, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.save))
        }
    }
}
