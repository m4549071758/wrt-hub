package wrthub.app.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import wrthub.app.R
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
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryScrollableTabRow(selectedTabIndex = state.tab.ordinal, edgePadding = 8.dp) {
                StatusTab.entries.forEach { tab ->
                    Tab(selected = state.tab == tab, onClick = { actions.onTab(tab) }, text = { Text(stringResource(tab.label)) })
                }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Box(Modifier.padding(top = 4.dp))
            state.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
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
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(state.logKind == LogKind.SYSTEM, { actions.onLogKind(LogKind.SYSTEM) }, { Text(stringResource(R.string.log_system)) })
        FilterChip(state.logKind == LogKind.KERNEL, { actions.onLogKind(LogKind.KERNEL) }, { Text(stringResource(R.string.log_kernel)) })
    }
    OutlinedTextField(
        value = state.logFilter,
        onValueChange = actions.onLogFilter,
        label = { Text(stringResource(R.string.filter)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(state.logs) { LogRow(it) }
    }
}

private val TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

@Composable
private fun LogRow(line: LogLine) {
    val semantic = LocalSemanticColors.current
    val color = when {
        line.severity <= 3 -> MaterialTheme.colorScheme.error
        line.severity == 4 -> semantic.signalFair
        else -> Color.Unspecified
    }
    val time = line.epochMillis?.let { TIME.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) }
        ?: line.bootSeconds?.let { String.format(Locale.ROOT, "%.1f s", it) }
    Column {
        time?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(line.message, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun Routes(state: StatusUiState, actions: StatusActions) {
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(state.family == IpFamily.V4, { actions.onFamily(IpFamily.V4) }, { Text("IPv4") })
        FilterChip(state.family == IpFamily.V6, { actions.onFamily(IpFamily.V6) }, { Text("IPv6") })
    }
    when (val routes = state.routes) {
        is RouteTable.Parsed -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            items(routes.routes) { route ->
                RouteRow(route)
                HorizontalDivider()
            }
        }
        is RouteTable.Text -> Text(
            routes.text,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(16.dp).horizontalScroll(rememberScrollState()),
        )
        null -> Unit
    }
}

@Composable
private fun RouteRow(route: Route) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        supportingContent = {
            Text(
                listOfNotNull(
                    route.gateway?.let { stringResource(R.string.route_via, it) },
                    route.device,
                    route.protocol,
                    route.metric?.let { stringResource(R.string.route_metric, it) },
                ).joinToString(" · "),
            )
        },
        trailingContent = {
            Text(
                listOf(route.table, route.type).filterNot { it == "main" || it == "unicast" }.joinToString(" "),
                style = MaterialTheme.typography.labelMedium,
            )
        },
    ) {
        Text(route.destination, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Connections(state: StatusUiState) {
    state.usage?.let {
        Text(
            stringResource(R.string.conntrack_usage, it.count, it.max),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        items(state.connections) { connection ->
            ConnectionRow(connection)
            HorizontalDivider()
        }
    }
}

@Composable
private fun ConnectionRow(c: Connection) {
    fun endpoint(address: String, port: Int?) = when {
        port == null -> address
        ':' in address -> "[$address]:$port"
        else -> "$address:$port"
    }
    ListItem(
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        overlineContent = { Text("${c.family} ${c.protocol}".uppercase()) },
        supportingContent = { Text("→ " + endpoint(c.destination, c.destinationPort), fontFamily = FontFamily.Monospace) },
        trailingContent = { Text(formatBytes(c.bytes)) },
    ) {
        Text(endpoint(c.source, c.sourcePort), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Services(state: StatusUiState, actions: StatusActions) {
    var selected by remember { mutableStateOf<Service?>(null) }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        items(state.services, key = { it.name }) { service ->
            ServiceRow(service, busy = state.busyService == service.name, onClick = { selected = service })
            HorizontalDivider()
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
    ListItem(
        modifier = Modifier.clickable(enabled = !busy, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        supportingContent = {
            Text(stringResource(if (service.enabled) R.string.service_enabled else R.string.service_disabled))
        },
        trailingContent = {
            when (service.running) {
                true -> Text(stringResource(R.string.service_running), color = semantic.signalGood)
                false -> Text(stringResource(R.string.service_stopped), color = semantic.inactive)
                null -> Unit
            }
        },
    ) {
        Text(service.name)
    }
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
                        Text(stringResource(R.string.service_essential_note), style = MaterialTheme.typography.bodyMedium)
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
