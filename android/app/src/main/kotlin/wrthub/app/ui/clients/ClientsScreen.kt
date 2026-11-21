package wrthub.app.ui.clients

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wrthub.app.R
import wrthub.app.ui.theme.LocalSemanticColors
import wrthub.app.ui.theme.forSignal
import wrthub.core.direct.WriteOutcome
import wrthub.core.topology.Presence
import wrthub.core.topology.SiteClient

@Composable
fun ClientsRoute(viewModel: ClientsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ClientsScreen(state, viewModel::setFilter, viewModel::refresh, viewModel::setBlocked, viewModel::dismissNotice)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientsScreen(
    state: ClientsUiState,
    onFilter: (ClientFilter) -> Unit,
    onRefresh: () -> Unit,
    onSetBlocked: (mac: String, blocked: Boolean) -> Unit,
    onDismissNotice: () -> Unit,
) {
    var selectedMac by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val noticeText = state.notice?.let { noticeText(it, state.clients) }
    LaunchedEffect(state.notice) {
        if (noticeText != null) {
            snackbar.showSnackbar(noticeText)
            onDismissNotice()
        }
    }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(state.site?.name ?: stringResource(R.string.tab_clients)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) { padding ->
        PullToRefreshBox(isRefreshing = state.loading, onRefresh = onRefresh, modifier = Modifier.padding(padding)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterOption(state.filter, ClientFilter.ALL, R.string.filter_all, onFilter)
                        FilterOption(state.filter, ClientFilter.WIRELESS, R.string.filter_wifi, onFilter)
                        FilterOption(state.filter, ClientFilter.OTHER, R.string.filter_other, onFilter)
                    }
                }
                item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        if (state.clients.isEmpty()) {
                            Text(stringResource(R.string.no_clients), modifier = Modifier.padding(16.dp))
                        }
                        state.clients.forEachIndexed { index, client ->
                            if (index > 0) HorizontalDivider()
                            ClientRow(client, state.nodeLabels, onClick = { selectedMac = client.mac })
                        }
                    }
                }
            }
        }
    }
    val selected = state.clients.firstOrNull { it.mac == selectedMac }
    if (selected != null) {
        ModalBottomSheet(onDismissRequest = { selectedMac = null }) {
            ClientDetail(selected, state.nodeLabels, busy = selected.mac in state.busy, onSetBlocked = onSetBlocked)
        }
    }
}

@Composable
private fun FilterOption(current: ClientFilter, option: ClientFilter, label: Int, onFilter: (ClientFilter) -> Unit) {
    FilterChip(selected = current == option, onClick = { onFilter(option) }, label = { Text(stringResource(label)) })
}

@Composable
private fun ClientRow(client: SiteClient, nodeLabels: Map<String, String>, onClick: () -> Unit) {
    val semantic = LocalSemanticColors.current
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        leadingContent = {
            Box(Modifier.size(12.dp).background(semantic.forSignal(client.signal), CircleShape))
        },
        supportingContent = { Text(listOfNotNull(client.ipv4, placeText(client, nodeLabels)).joinToString(" · ")) },
        trailingContent = {
            when {
                client.blocked -> Text(stringResource(R.string.blocked), color = MaterialTheme.colorScheme.error)
                client.signal != null -> Text("${client.signal} dBm", color = semantic.forSignal(client.signal))
            }
        },
    ) {
        Text(client.name ?: client.mac)
    }
}

@Composable
private fun placeText(client: SiteClient, nodeLabels: Map<String, String>): String = when (client.presence) {
    Presence.WIRELESS -> listOfNotNull(client.attachedTo?.let { nodeLabels[it] ?: it }, client.ifname).joinToString(" ")
    Presence.LEASE_ONLY -> stringResource(R.string.presence_lease_only)
    Presence.OFFLINE -> stringResource(R.string.presence_offline)
}

@Composable
fun ClientDetail(client: SiteClient, nodeLabels: Map<String, String>, busy: Boolean, onSetBlocked: (String, Boolean) -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    val semantic = LocalSemanticColors.current
    val title = client.name ?: client.mac
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        DetailRow(stringResource(R.string.mac_address), client.mac)
        client.ipv4?.let { DetailRow(stringResource(R.string.ip_address), it) }
        DetailRow(stringResource(R.string.attached_to), placeText(client, nodeLabels))
        client.signal?.let { DetailRow(stringResource(R.string.signal), "$it dBm") }
        if (client.sightings.isNotEmpty()) {
            Text(
                stringResource(R.string.seen_by),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 16.dp),
            )
            client.sightings.forEach { sighting ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(nodeLabels[sighting.nodeId] ?: sighting.nodeId, modifier = Modifier.weight(1f))
                    if (sighting.connected) {
                        Text(
                            stringResource(R.string.connected),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    Text(sighting.signal?.let { "$it dBm" } ?: "—", color = semantic.forSignal(sighting.signal))
                }
            }
        }
        if (client.blocked) {
            OutlinedButton(
                onClick = { onSetBlocked(client.mac, false) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp),
            ) { Text(stringResource(R.string.unblock)) }
        } else {
            Button(
                onClick = { confirming = true },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp),
            ) { Text(stringResource(R.string.block)) }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.block_confirm_title, title)) },
            text = { Text(stringResource(R.string.block_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onSetBlocked(client.mac, true)
                }) { Text(stringResource(R.string.block)) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value)
    }
}

@Composable
private fun noticeText(notice: ClientNotice, clients: List<SiteClient>): String {
    fun nameOf(mac: String) = clients.firstOrNull { it.mac == mac }?.name ?: mac
    return when (notice) {
        is ClientNotice.Done -> when (notice.outcome) {
            WriteOutcome.APPLIED -> stringResource(R.string.notice_applied, nameOf(notice.mac))
            WriteOutcome.ROLLED_BACK -> stringResource(R.string.notice_rolled_back, nameOf(notice.mac))
        }
        is ClientNotice.Failed -> stringResource(R.string.notice_failed, nameOf(notice.mac), notice.reason)
        ClientNotice.NoRouter -> stringResource(R.string.notice_no_router)
    }
}
