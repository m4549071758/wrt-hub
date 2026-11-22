package wrthub.app.ui.clients

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wrthub.app.R
import wrthub.app.ui.design.AppRow
import wrthub.app.ui.design.AppTopBar
import wrthub.app.ui.design.Badge
import wrthub.app.ui.design.GroupDivider
import wrthub.app.ui.design.KeyValueCard
import wrthub.app.ui.design.ListPadding
import wrthub.app.ui.design.PrimaryButton
import wrthub.app.ui.design.RowGroup
import wrthub.app.ui.design.ScreenPadding
import wrthub.app.ui.design.SecondaryButton
import wrthub.app.ui.design.SectionHeader
import wrthub.app.ui.design.SegmentedToggle
import wrthub.app.ui.design.SiteSwitcher
import wrthub.app.ui.design.ValueText
import wrthub.app.ui.theme.LocalSemanticColors
import wrthub.app.ui.theme.forSignal
import wrthub.core.direct.WriteOutcome
import wrthub.core.topology.Presence
import wrthub.core.topology.SiteClient

@Composable
fun ClientsRoute(viewModel: ClientsViewModel, onPinIp: (nodeId: String, client: SiteClient) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ClientsScreen(state, viewModel::setFilter, viewModel::refresh, viewModel::setBlocked, viewModel::dismissNotice, onPinIp, viewModel::select)
}

/** The clients attached to one node; [nodeId] is null for those not associated with any of the site's APs. */
private class ClientGroup(val nodeId: String?, val title: String, val ifnames: String?, val clients: List<SiteClient>)

private fun groups(clients: List<SiteClient>, nodeLabels: Map<String, String>, otherTitle: String): List<ClientGroup> {
    val byNode = clients.groupBy { it.attachedTo }
    val order = nodeLabels.keys.filter { it in byNode } + byNode.keys.filterNotNull().filter { it !in nodeLabels }
    val attached = order.map { id ->
        val members = byNode.getValue(id)
        val ifnames = members.mapNotNull { it.ifname }.distinct().sorted().joinToString(", ").ifEmpty { null }
        ClientGroup(id, nodeLabels[id] ?: id, ifnames, members)
    }
    val other = byNode[null]?.let { ClientGroup(null, otherTitle, null, it) }
    return attached + listOfNotNull(other)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientsScreen(
    state: ClientsUiState,
    onFilter: (ClientFilter) -> Unit,
    onRefresh: () -> Unit,
    onSetBlocked: (mac: String, blocked: Boolean) -> Unit,
    onDismissNotice: () -> Unit,
    onPinIp: (nodeId: String, client: SiteClient) -> Unit = { _, _ -> },
    onSelectSite: (String) -> Unit = {},
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
    val otherTitle = stringResource(R.string.filter_other)
    val groups = remember(state.clients, state.nodeLabels, otherTitle) { groups(state.clients, state.nodeLabels, otherTitle) }
    Scaffold(
        topBar = {
            AppTopBar(
                stringResource(R.string.tab_clients),
                subtitle = {
                    SiteSwitcher(
                        state.sites,
                        state.site,
                        onSelectSite,
                        suffix = pluralStringResource(R.plurals.clients_count, state.clients.size, state.clients.size),
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        PullToRefreshBox(isRefreshing = state.loading, onRefresh = onRefresh, modifier = Modifier.padding(padding)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = ListPadding) {
                item(key = "filter") {
                    Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
                        SegmentedToggle(
                            listOf(
                                ClientFilter.ALL to stringResource(R.string.filter_all),
                                ClientFilter.WIRELESS to stringResource(R.string.filter_wifi),
                                ClientFilter.OTHER to stringResource(R.string.filter_other),
                            ),
                            state.filter,
                            onFilter,
                        )
                    }
                }
                if (state.clients.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            stringResource(R.string.no_clients),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                        )
                    }
                }
                groups.forEach { group ->
                    item(key = "head-" + (group.nodeId ?: "")) {
                        SectionHeader(group.title, trailing = group.ifnames)
                    }
                    items(group.clients, key = { it.mac }) { client ->
                        ClientRow(client, Modifier.padding(bottom = 6.dp).animateItem(), onClick = { selectedMac = client.mac })
                    }
                }
            }
        }
    }
    val selected = state.clients.firstOrNull { it.mac == selectedMac }
    if (selected != null) {
        ModalBottomSheet(onDismissRequest = { selectedMac = null }, containerColor = MaterialTheme.colorScheme.background) {
            val pin = state.dhcpNodeId?.takeIf { selected.ipv4 != null }?.let { nodeId ->
                PinIp(pinned = selected.mac in state.staticMacs, onClick = {
                    selectedMac = null
                    onPinIp(nodeId, selected)
                })
            }
            ClientDetail(selected, state.nodeLabels, busy = selected.mac in state.busy, onSetBlocked = onSetBlocked, pin = pin)
        }
    }
}

@Composable
private fun ClientRow(client: SiteClient, modifier: Modifier, onClick: () -> Unit) {
    val semantic = LocalSemanticColors.current
    val wireless = client.presence == Presence.WIRELESS
    val badge = presenceBadge(client.presence)
    AppRow(
        client.name ?: client.mac,
        modifier,
        icon = if (wireless) R.drawable.ic_wifi else R.drawable.ic_settings_ethernet,
        iconTint = if (wireless) semantic.forSignal(client.signal) else MaterialTheme.colorScheme.onSurfaceVariant,
        subtitle = client.ipv4,
        trailing = {
            badge?.let { Badge(it) }
            val signal = client.signal
            when {
                client.blocked -> ValueText(stringResource(R.string.blocked), MaterialTheme.colorScheme.error)
                signal != null -> ValueText(dbm(signal), semantic.forSignal(signal))
            }
        },
        onClick = onClick,
        standalone = true,
        chevron = client.signal == null && !client.blocked,
    )
}

/** Wireless clients carry no badge: the band of their AP interface is not part of the client list. */
@Composable
private fun presenceBadge(presence: Presence): String? = when (presence) {
    Presence.WIRELESS -> null
    Presence.ONLINE_UNKNOWN_LINK -> stringResource(R.string.wired)
    Presence.LEASE_ONLY -> stringResource(R.string.presence_lease_only)
    Presence.OFFLINE -> stringResource(R.string.presence_offline)
}

private fun dbm(signal: Int): String = if (signal < 0) "−${-signal} dBm" else "$signal dBm"

@Composable
private fun placeText(client: SiteClient, nodeLabels: Map<String, String>): String = when (client.presence) {
    Presence.WIRELESS -> listOfNotNull(client.attachedTo?.let { nodeLabels[it] ?: it }, client.ifname).joinToString(" ")
    Presence.ONLINE_UNKNOWN_LINK -> stringResource(R.string.presence_unknown_link)
    Presence.LEASE_ONLY -> stringResource(R.string.presence_lease_only)
    Presence.OFFLINE -> stringResource(R.string.presence_offline)
}

/** "Fix this IP" in the client sheet; [pinned] clients already have a static lease and get "edit" instead. */
data class PinIp(val pinned: Boolean, val onClick: () -> Unit)

@Composable
fun ClientDetail(
    client: SiteClient,
    nodeLabels: Map<String, String>,
    busy: Boolean,
    pin: PinIp? = null,
    onSetBlocked: (String, Boolean) -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    val semantic = LocalSemanticColors.current
    val title = client.name ?: client.mac
    val place = placeText(client, nodeLabels)
    val rows = buildList<Pair<String, @Composable () -> Unit>> {
        add(stringResource(R.string.mac_address) to { ValueText(client.mac) })
        client.ipv4?.let { ip -> add(stringResource(R.string.ip_address) to { ValueText(ip) }) }
        add(stringResource(R.string.attached_to) to { ValueText(place) })
        client.signal?.let { signal -> add(stringResource(R.string.signal) to { ValueText(dbm(signal), semantic.forSignal(signal)) }) }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = ScreenPadding)
            .padding(top = 4.dp, bottom = 16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
        KeyValueCard(rows)
        if (client.sightings.isNotEmpty()) {
            SectionHeader(stringResource(R.string.seen_by))
            RowGroup {
                client.sightings.forEachIndexed { index, sighting ->
                    if (index > 0) GroupDivider()
                    AppRow(
                        nodeLabels[sighting.nodeId] ?: sighting.nodeId,
                        icon = R.drawable.ic_wifi,
                        iconTint = semantic.forSignal(sighting.signal),
                        trailing = {
                            if (sighting.connected) Badge(stringResource(R.string.connected))
                            ValueText(sighting.signal?.let(::dbm) ?: "—", semantic.forSignal(sighting.signal))
                        },
                    )
                }
            }
        }
        Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            pin?.let {
                SecondaryButton(stringResource(if (it.pinned) R.string.edit_static_ip else R.string.fix_ip), it.onClick, Modifier.fillMaxWidth())
            }
            if (client.blocked) {
                PrimaryButton(stringResource(R.string.unblock), { onSetBlocked(client.mac, false) }, Modifier.fillMaxWidth(), enabled = !busy)
            } else {
                PrimaryButton(stringResource(R.string.block), { confirming = true }, Modifier.fillMaxWidth(), enabled = !busy)
            }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.block_confirm_title, title)) },
            text = { Text(stringResource(R.string.block_confirm_text)) },
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
private fun noticeText(notice: ClientNotice, clients: List<SiteClient>): String {
    fun nameOf(mac: String) = clients.firstOrNull { it.mac == mac }?.name ?: mac
    return when (notice) {
        is ClientNotice.Done -> when (notice.outcome) {
            WriteOutcome.APPLIED, WriteOutcome.UNCHANGED -> stringResource(R.string.notice_applied, nameOf(notice.mac))
            WriteOutcome.ROLLED_BACK -> stringResource(R.string.notice_rolled_back, nameOf(notice.mac))
        }
        is ClientNotice.Failed -> stringResource(R.string.notice_failed, nameOf(notice.mac), notice.reason)
        ClientNotice.NoRouter -> stringResource(R.string.notice_no_router)
    }
}
