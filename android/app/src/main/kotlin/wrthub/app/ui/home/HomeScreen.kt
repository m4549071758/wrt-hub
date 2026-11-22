package wrthub.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wrthub.app.R
import wrthub.app.domain.NodeState
import wrthub.app.domain.siteTopology
import wrthub.app.domain.stripModel
import wrthub.app.ui.SiteSwitcherBar
import wrthub.core.model.WirelessMode
import wrthub.core.model.wirelessInterfaces

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
    Scaffold(
        topBar = { SiteSwitcherBar(state.sites, state.site, onSelectSite) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) { padding ->
        val site = state.site
        if (site == null) {
            EmptySites(onAddSite, Modifier.padding(padding))
            return@Scaffold
        }
        val strip = remember(state.nodes) { stripModel(state.nodes) }
        val wifiClients = remember(state.nodes) { wifiClientCounts(state.nodes) }
        PullToRefreshBox(isRefreshing = state.loading, onRefresh = onRefresh, modifier = Modifier.padding(padding)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { TopologyCard(strip, onClick = onOpenMap) }
                item { BandwidthRow(state.rates, state.realtimeError) }
                item {
                    ListCard(stringResource(R.string.nodes)) {
                        state.nodes.forEachIndexed { index, node ->
                            if (index > 0) HorizontalDivider()
                            NodeRow(node.config, node, onClick = { onOpenNode(node.config.id) }, wifiClients = wifiClients[node.config.id])
                        }
                    }
                }
                if (wifiClients.isNotEmpty()) {
                    item {
                        ListCard(null) {
                            ListItem(
                                modifier = Modifier.clickable { onOpenSiteWifi(site.id) },
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                                leadingContent = { Icon(painterResource(R.drawable.ic_wifi), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                supportingContent = { Text(stringResource(R.string.site_wifi_body)) },
                                trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
                            ) { Text(stringResource(R.string.site_wifi)) }
                        }
                    }
                }
            }
        }
    }
}

/** A surface card holding list rows, with an optional section title. */
@Composable
fun ListCard(title: String?, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        title?.let { Text(it, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp)) }
        content()
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

@Composable
private fun EmptySites(onAddSite: () -> Unit, modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.no_sites_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.no_sites_body),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        Button(onClick = onAddSite) { Text(stringResource(R.string.add_site)) }
    }
}
