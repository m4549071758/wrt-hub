package wrthub.app.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import wrthub.app.R
import wrthub.app.ui.design.AppCard
import wrthub.app.ui.design.AppRow
import wrthub.app.ui.design.Badge
import wrthub.app.ui.design.ListPadding
import wrthub.app.ui.design.ScreenPadding
import wrthub.app.ui.design.SectionHeader
import wrthub.app.ui.design.SegmentedToggle
import wrthub.app.ui.design.StatusText
import wrthub.app.ui.design.UnderlineField
import wrthub.app.ui.design.ValueText
import wrthub.app.ui.formatBytes
import wrthub.app.ui.theme.LocalSemanticColors
import wrthub.core.admin.Connection
import wrthub.core.admin.ESSENTIAL_SERVICES
import wrthub.core.admin.IpFamily
import wrthub.core.admin.LogLine
import wrthub.core.admin.Route
import wrthub.core.admin.RouteTable
import wrthub.core.admin.Service
import wrthub.core.admin.ServiceAction
import wrthub.core.admin.allowedFor

data class StatusActions(
    val onTab: (StatusTab) -> Unit,
    val onLogKind: (LogKind) -> Unit,
    val onLogFilter: (String) -> Unit,
    val onFamily: (IpFamily) -> Unit,
    val onServiceAction: (String, ServiceAction) -> Unit,
    val onRefresh: () -> Unit,
    val onDismissNotice: () -> Unit,
    val onBack: () -> Unit,
)

@Composable
fun StatusRoute(viewModel: StatusViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    StatusScreen(
        state,
        StatusActions(
            viewModel::selectTab,
            viewModel::setLogKind,
            viewModel::setLogFilter,
            viewModel::setFamily,
            viewModel::runServiceAction,
            viewModel::refresh,
            viewModel::dismissNotice,
            onBack,
        ),
    )
}

@Composable
fun StatusScreen(state: StatusUiState, actions: StatusActions) {
    AdminScaffold(
        title = stringResource(R.string.status_title, state.title),
        notice = state.notice,
        onDismissNotice = actions.onDismissNotice,
        onBack = actions.onBack,
        onRefresh = actions.onRefresh,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = ScreenPadding, vertical = 8.dp)) {
                SegmentedToggle(StatusTab.entries.map { it to stringResource(it.label) }, state.tab, actions.onTab)
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Box(Modifier.padding(top = 4.dp))
            state.failure?.let {
                Text(failureText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp))
            }
            when (state.tab) {
                StatusTab.LOGS -> Logs(state, actions)
                StatusTab.ROUTES -> Routes(state, actions)
                StatusTab.CONNECTIONS -> Connections(state)
                StatusTab.SERVICES -> Services(state, actions)
            }
        }
    }
}

private val StatusTab.label: Int
    get() = when (this) {
        StatusTab.LOGS -> R.string.tab_logs
        StatusTab.ROUTES -> R.string.tab_routes
        StatusTab.CONNECTIONS -> R.string.tab_connections
        StatusTab.SERVICES -> R.string.tab_services
    }

@Composable
private fun Logs(state: StatusUiState, actions: StatusActions) {
    Column(Modifier.padding(horizontal = ScreenPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SegmentedToggle(
            listOf(LogKind.SYSTEM to stringResource(R.string.log_system), LogKind.KERNEL to stringResource(R.string.log_kernel)),
            state.logKind,
            actions.onLogKind,
        )
        UnderlineField(stringResource(R.string.filter), state.logFilter, actions.onLogFilter, Modifier.fillMaxWidth())
    }
    LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Box(Modifier.padding(top = 8.dp)) }
        items(state.logs) { LogRow(it) }
    }
}

private val TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

@Composable
private fun LogRow(line: LogLine) {
    val semantic = LocalSemanticColors.current
    val color = when {
        line.severity <= 3 -> semantic.signalPoor
        line.severity == 4 -> semantic.signalFair
        else -> MaterialTheme.colorScheme.onSurface
    }
    val time = line.epochMillis?.let { TIME.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) }
        ?: line.bootSeconds?.let { String.format(Locale.ROOT, "%.1f s", it) }
    AppCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            time?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(line.message, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = color)
        }
    }
}

@Composable
private fun Routes(state: StatusUiState, actions: StatusActions) {
    SegmentedToggle(
        listOf(IpFamily.V4 to "IPv4", IpFamily.V6 to "IPv6"),
        state.family,
        actions.onFamily,
        Modifier.padding(horizontal = ScreenPadding),
    )
    when (val routes = state.routes) {
        is RouteTable.Parsed -> LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Box(Modifier.padding(top = 4.dp)) }
            items(routes.routes) { RouteRow(it) }
        }
        is RouteTable.Text -> AppCard(Modifier.padding(ScreenPadding).fillMaxWidth()) {
            Text(
                routes.text,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp).horizontalScroll(rememberScrollState()),
            )
        }
        null -> Unit
    }
}

@Composable
private fun RouteRow(route: Route) {
    val tags = listOf(route.table, route.type).filterNot { it == "main" || it == "unicast" }.joinToString(" ")
    AppRow(
        route.destination,
        subtitle = listOfNotNull(
            route.gateway?.let { stringResource(R.string.route_via, it) },
            route.device,
            route.protocol,
            route.metric?.let { stringResource(R.string.route_metric, it) },
        ).joinToString(" · "),
        trailing = if (tags.isNotEmpty()) ({ Badge(tags) }) else null,
        standalone = true,
    )
}

@Composable
private fun Connections(state: StatusUiState) {
    LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        state.usage?.let {
            item { SectionHeader(stringResource(R.string.tab_connections), Modifier.padding(top = 0.dp), trailing = stringResource(R.string.conntrack_usage, it.count, it.max)) }
        }
        items(state.connections) { ConnectionRow(it) }
    }
}

@Composable
private fun ConnectionRow(c: Connection) {
    fun endpoint(address: String, port: Int?) = when {
        port == null -> address
        ':' in address -> "[$address]:$port"
        else -> "$address:$port"
    }
    AppRow(
        endpoint(c.source, c.sourcePort),
        subtitle = "→ " + endpoint(c.destination, c.destinationPort),
        trailing = {
            Badge("${c.family} ${c.protocol}".uppercase())
            ValueText(formatBytes(c.bytes))
        },
        standalone = true,
    )
}

@Composable
private fun Services(state: StatusUiState, actions: StatusActions) {
    var selected by remember { mutableStateOf<Service?>(null) }
    LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Box(Modifier.padding(top = 4.dp)) }
        items(state.services, key = { it.name }) { service ->
            ServiceRow(service, busy = state.busyService == service.name, onClick = { selected = service })
        }
    }
    selected?.let { service ->
        ServiceDialog(
            service = state.services.firstOrNull { it.name == service.name } ?: service,
            onAction = { action ->
                selected = null
                actions.onServiceAction(service.name, action)
            },
            onDismiss = { selected = null },
        )
    }
}

@Composable
private fun ServiceRow(service: Service, busy: Boolean, onClick: () -> Unit) {
    val semantic = LocalSemanticColors.current
    AppRow(
        service.name,
        subtitle = stringResource(if (service.enabled) R.string.service_enabled else R.string.service_disabled),
        trailing = {
            when (service.running) {
                true -> StatusText(stringResource(R.string.service_running), semantic.signalGood)
                false -> StatusText(stringResource(R.string.service_stopped), semantic.inactive)
                null -> Unit
            }
        },
        onClick = onClick,
        enabled = !busy,
        standalone = true,
    )
}

@Composable
private fun ServiceDialog(service: Service, onAction: (ServiceAction) -> Unit, onDismiss: () -> Unit) {
    var confirming by remember { mutableStateOf<ServiceAction?>(null) }
    val offered = buildList {
        if (service.running != true) add(ServiceAction.START)
        if (service.running != false) add(ServiceAction.RESTART)
        if (service.running == true) add(ServiceAction.STOP)
        add(if (service.enabled) ServiceAction.DISABLE else ServiceAction.ENABLE)
    }.filter { it.allowedFor(service.name) }
    val action = confirming
    if (action == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(service.name) },
            text = {
                Column {
                    if (service.name in ESSENTIAL_SERVICES) {
                        Text(stringResource(R.string.service_restart_only), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    offered.forEach { option ->
                        TextButton(onClick = { confirming = option }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(option.label)) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.service_confirm_title, service.name, stringResource(action.label))) },
            text = { Text(stringResource(R.string.service_confirm_body, service.name, action.rcAction)) },
            confirmButton = { TextButton(onClick = { onAction(action) }) { Text(stringResource(action.label)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
