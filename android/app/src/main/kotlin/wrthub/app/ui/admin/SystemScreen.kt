package wrthub.app.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wrthub.app.R
import wrthub.app.ui.design.AppCard
import wrthub.app.ui.design.AppRow
import wrthub.app.ui.design.GroupDivider
import wrthub.app.ui.design.PrimaryButton
import wrthub.app.ui.design.RowGroup
import wrthub.app.ui.design.ScreenPadding
import wrthub.app.ui.design.SectionHeader
import wrthub.app.ui.design.UnderlineField
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
        title = stringResource(R.string.system),
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
                    .padding(start = ScreenPadding, end = ScreenPadding, bottom = 24.dp),
            ) {
                state.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(4.dp)) }
                if (state.config != null) {
                    General(state, actions)
                    if (state.leds.isNotEmpty()) Leds(state.leds, state.saving, actions)
                    Cron(state, onSave = { confirmCron = true }, actions)
                }
            }
        }
    }
    state.ledEditor?.let { editor -> state.leds.firstOrNull { it.name == editor.name }?.let { LedDialog(it, editor, state, actions) } }
    if (confirmCron) {
        AlertDialog(
            onDismissRequest = { confirmCron = false },
            title = { Text(stringResource(R.string.cron_confirm_title)) },
            text = { Text(stringResource(R.string.cron_confirm_short)) },
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
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp),
    )
}

@Composable
private fun General(state: SystemUiState, actions: SystemActions) {
    val form = state.form
    var pickingZone by remember { mutableStateOf(false) }
    SectionHeader(stringResource(R.string.system_general))
    UnderlineField(
        stringResource(R.string.hostname),
        form.hostname,
        { v -> actions.onForm { it.copy(hostname = v) } },
        error = if (form.error == SystemField.HOSTNAME) stringResource(R.string.error_hostname) else null,
    )
    SelectorField(
        stringResource(R.string.timezone),
        form.zonename,
        enabled = true,
        onClick = { pickingZone = true },
        error = if (form.error == SystemField.ZONE) stringResource(R.string.error_timezone) else null,
    )
    AppRow(
        stringResource(R.string.ntp_enabled),
        Modifier.padding(top = 16.dp),
        trailing = { AdminSwitch(checked = form.ntpEnabled, onCheckedChange = { on -> actions.onForm { it.copy(ntpEnabled = on) } }) },
        standalone = true,
    )
    UnderlineField(
        stringResource(R.string.ntp_servers),
        form.ntpServers,
        { v -> actions.onForm { it.copy(ntpServers = v) } },
        Modifier.padding(top = 4.dp),
        error = if (form.error == SystemField.NTP) stringResource(R.string.error_ntp) else null,
        singleLine = false,
    )
    Note(stringResource(R.string.rollback_short))
    PrimaryButton(stringResource(R.string.save), actions.onSaveSystem, Modifier.fillMaxWidth().padding(top = 16.dp), enabled = !state.saving)
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
                UnderlineField(stringResource(R.string.filter), query, { query = it })
                LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 8.dp)) {
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
private fun Leds(leds: List<Led>, saving: Boolean, actions: SystemActions) {
    SectionHeader(stringResource(R.string.leds))
    RowGroup {
        leds.forEachIndexed { index, led ->
            if (index > 0) GroupDivider()
            AppRow(
                led.name,
                subtitle = listOfNotNull(led.activeTrigger, led.device).joinToString(" · ").ifEmpty { null },
                onClick = { actions.onEditLed(led) },
                enabled = !saving,
            )
        }
    }
}

@Composable
private fun LedDialog(led: Led, editor: LedEditor, state: SystemUiState, actions: SystemActions) {
    AlertDialog(
        onDismissRequest = actions.onCloseLed,
        title = { Text(led.name) },
        text = {
            Column {
                Selector(stringResource(R.string.led_trigger), editor.trigger, led.triggers, { it }, { t -> actions.onLed { it.copy(trigger = t) } })
                if (editor.trigger == "netdev") {
                    Selector(stringResource(R.string.led_device), editor.device, state.devices, { it.ifEmpty { "—" } }, { d -> actions.onLed { it.copy(device = d) } })
                }
                Note(stringResource(R.string.rollback_short))
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
private fun Cron(state: SystemUiState, onSave: () -> Unit, actions: SystemActions) {
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = colors.onSurface)
    val field = rememberTextFieldState(state.cron)
    val current by rememberUpdatedState(state.cron)
    val report by rememberUpdatedState(actions.onCron)
    LaunchedEffect(state.cron) {
        if (field.text.toString() != state.cron) field.setTextAndPlaceCursorAtEnd(state.cron)
    }
    LaunchedEffect(field) {
        snapshotFlow { field.text.toString() }.collect { if (it != current) report(it) }
    }
    SectionHeader(stringResource(R.string.cron))
    AppCard(Modifier.fillMaxWidth()) {
        BasicTextField(
            state = field,
            textStyle = style,
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 4),
            cursorBrush = SolidColor(colors.onSurface),
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            decorator = TextFieldDecorator { inner ->
                Box {
                    if (field.text.isEmpty()) Text("0 4 * * * /sbin/reboot", style = style.copy(color = colors.onSurfaceVariant))
                    inner()
                }
            },
        )
    }
    PrimaryButton(
        stringResource(R.string.save),
        onSave,
        Modifier.fillMaxWidth().padding(top = 16.dp),
        enabled = !state.saving && state.cron != state.savedCron,
    )
}
