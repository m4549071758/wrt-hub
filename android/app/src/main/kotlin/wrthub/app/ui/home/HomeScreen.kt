package wrthub.app.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wrthub.app.R
import wrthub.app.domain.NodeState
import wrthub.app.domain.siteTopology
import wrthub.app.domain.stripModel
import wrthub.app.ui.design.AlertCard
import wrthub.app.ui.design.AppRow
import wrthub.app.ui.design.PrimaryButton
import wrthub.app.ui.design.RingButton
import wrthub.app.ui.design.ScreenPadding
import wrthub.app.ui.design.SectionHeader
import wrthub.app.ui.design.SiteSwitcher
import wrthub.app.ui.design.StatCard
import wrthub.app.ui.formatBps
import wrthub.app.ui.theme.LocalSemanticColors
import wrthub.core.model.WirelessMode
import wrthub.core.model.wirelessInterfaces
import wrthub.core.topology.Presence

@Composable
fun HomeRoute(
    viewModel: HomeViewModel,
    onAddSite: () -> Unit,
    onOpenNode: (String) -> Unit,
    onOpenMap: () -> Unit,
    onOpenSiteWifi: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.setRealtime(true)
        onPauseOrDispose { viewModel.setRealtime(false) }
    }
    HomeScreen(state, viewModel::select, viewModel::refresh, onAddSite, onOpenNode, onOpenMap, onOpenSiteWifi)
}

private enum class Health { CONNECTING, ONLINE, PARTIAL, OFFLINE }

private fun health(nodes: List<NodeState>): Health = when {
    nodes.isEmpty() -> Health.CONNECTING
    nodes.none { it is NodeState.Online } -> Health.OFFLINE
    nodes.any { it is NodeState.Unreachable } -> Health.PARTIAL
    else -> Health.ONLINE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onSelectSite: (String) -> Unit,
    onRefresh: () -> Unit,
    onAddSite: () -> Unit,
    onOpenNode: (String) -> Unit = {},
    onOpenMap: () -> Unit = {},
    onOpenSiteWifi: (String) -> Unit = {},
) {
    val site = state.site
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (site == null) {
            EmptySites(onAddSite)
            return@Box
        }
        val health = health(state.nodes)
        val strip = remember(state.nodes) { stripModel(state.nodes) }
        val wifiClients = remember(state.nodes) { wifiClientCounts(state.nodes) }
        val clientCount = remember(state.nodes) { siteTopology(state.nodes).clients.count { it.presence != Presence.LEASE_ONLY } }
        val ssid = remember(state.nodes) { firstApSsid(state.nodes) }
        val unreachable = state.nodes.filterIsInstance<NodeState.Unreachable>()
        if (health == Health.PARTIAL || health == Health.OFFLINE) TroubleBackdrop()
        PullToRefreshBox(isRefreshing = state.loading, onRefresh = onRefresh, modifier = Modifier.statusBarsPadding()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "head") {
                    Header(state, health, clientCount, unreachable.firstOrNull()?.config?.label, onSelectSite, onRefresh)
                }
                item(key = "hero") { TopologyHero(strip, Modifier.padding(top = 12.dp), onClick = onOpenMap) }
                items(unreachable, key = { "alert-" + it.config.id }) { node ->
                    AlertCard(
                        stringResource(R.string.node_unreachable_title, node.config.label),
                        node.reason,
                        Modifier.padding(horizontal = ScreenPadding).padding(bottom = 10.dp),
                        primary = stringResource(R.string.retry) to onRefresh,
                    )
                }
                item(key = "rates") { Rates(state, Modifier.padding(horizontal = ScreenPadding)) }
                item(key = "nodes-head") {
                    SectionHeader(
                        stringResource(R.string.nodes),
                        Modifier.padding(horizontal = ScreenPadding),
                        trailing = stringResource(R.string.nodes_online_count, state.nodes.count { it is NodeState.Online }, state.nodes.size),
                    )
                }
                items(state.nodes, key = { it.config.id }) { node ->
                    NodeRow(
                        node.config,
                        node,
                        onClick = { onOpenNode(node.config.id) },
                        modifier = Modifier.padding(horizontal = ScreenPadding).padding(bottom = 6.dp).animateItem(),
                        wifiClients = wifiClients[node.config.id],
                    )
                }
                item(key = "map") {
                    AppRow(
                        stringResource(R.string.network_map),
                        Modifier.padding(horizontal = ScreenPadding).padding(top = 12.dp, bottom = 6.dp),
                        icon = R.drawable.ic_hub,
                        onClick = onOpenMap,
                        standalone = true,
                    )
                }
                if (wifiClients.isNotEmpty()) {
                    item(key = "wifi") {
                        AppRow(
                            stringResource(R.string.site_wifi),
                            Modifier.padding(horizontal = ScreenPadding),
                            icon = R.drawable.ic_wifi,
                            subtitle = ssid?.let { stringResource(R.string.site_wifi_summary, it, wifiClients.size) },
                            onClick = { onOpenSiteWifi(site.id) },
                            standalone = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(
    state: HomeUiState,
    health: Health,
    clientCount: Int,
    firstDown: String?,
    onSelectSite: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    val semantic = LocalSemanticColors.current
    val (title, dot) = when (health) {
        Health.CONNECTING -> stringResource(R.string.state_connecting) to semantic.inactive
        Health.ONLINE -> stringResource(R.string.state_online) to semantic.signalGood
        Health.PARTIAL -> stringResource(R.string.state_partial) to semantic.signalPoor
        Health.OFFLINE -> stringResource(R.string.state_offline) to semantic.signalPoor
    }
    val subtitle = when {
        health == Health.PARTIAL && firstDown != null -> stringResource(R.string.node_unreachable_title, firstDown)
        health == Health.CONNECTING -> null
        else -> stringResource(R.string.home_counts, state.nodes.size, clientCount)
    }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            SiteSwitcher(
                state.sites,
                state.site,
                onSelectSite,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.displaySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(10.dp).shadow(6.dp, CircleShape, ambientColor = dot, spotColor = dot).background(dot, CircleShape))
            }
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        RingButton(R.drawable.ic_refresh, stringResource(R.string.refresh), onRefresh)
    }
}

@Composable
private fun Rates(state: HomeUiState, modifier: Modifier) {
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RateCard("↓ " + stringResource(R.string.download), state.rates.map { it.rxBps }, Modifier.weight(1f))
            RateCard("↑ " + stringResource(R.string.upload), state.rates.map { it.txBps }, Modifier.weight(1f))
        }
        state.realtimeError?.let {
            Text(
                stringResource(R.string.realtime_unavailable, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun RateCard(label: String, values: List<Double>, modifier: Modifier) {
    val formatted = values.lastOrNull()?.let(::formatBps)
    StatCard(
        label,
        formatted?.substringBefore(' ') ?: "—",
        modifier,
        unit = formatted?.substringAfter(' '),
        history = values,
    )
}

/** The red glow behind the header while a node is down, as Starlink tints its scene on an outage. */
@Composable
private fun TroubleBackdrop() {
    val color = LocalSemanticColors.current.signalPoor
    Canvas(Modifier.fillMaxWidth().height(440.dp)) {
        drawRect(
            Brush.radialGradient(
                listOf(color.copy(alpha = 0.30f), color.copy(alpha = 0.10f), Color.Transparent),
                center = Offset(size.width / 2, size.height * 0.32f),
                radius = size.width * 0.9f,
            ),
        )
    }
}

/** Wireless clients per node; nodes without an AP interface are absent from the map. */
private fun wifiClientCounts(nodes: List<NodeState>): Map<String, Int> {
    val online = nodes.filterIsInstance<NodeState.Online>()
    val clients = siteTopology(nodes).clients
    return online
        .filter { node -> node.snapshot.wirelessInterfaces().any { it.mode == WirelessMode.AP } }
        .associate { node -> node.config.id to clients.count { it.attachedTo == node.config.id } }
}

private fun firstApSsid(nodes: List<NodeState>): String? =
    nodes.filterIsInstance<NodeState.Online>()
        .flatMap { it.snapshot.wirelessInterfaces() }
        .firstOrNull { it.mode == WirelessMode.AP && it.ssid != null }
        ?.ssid

@Composable
private fun EmptySites(onAddSite: () -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.no_sites_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))
        PrimaryButton(stringResource(R.string.add_site), onAddSite, Modifier.fillMaxWidth())
    }
}
