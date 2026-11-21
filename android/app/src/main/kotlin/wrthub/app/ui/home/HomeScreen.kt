package wrthub.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wrthub.app.R
import wrthub.app.data.SiteConfig
import wrthub.app.domain.NodeState
import wrthub.app.domain.stripModel
import wrthub.core.model.WirelessMode
import wrthub.core.model.wirelessInterfaces
import wrthub.app.domain.siteTopology

@Composable
fun HomeRoute(viewModel: HomeViewModel, onAddSite: () -> Unit, onOpenNode: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.setRealtime(true)
        onPauseOrDispose { viewModel.setRealtime(false) }
    }
    HomeScreen(state, onSelectSite = viewModel::select, onRefresh = viewModel::refresh, onAddSite = onAddSite, onOpenNode = onOpenNode)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onSelectSite: (String) -> Unit,
    onRefresh: () -> Unit,
    onAddSite: () -> Unit,
    onOpenNode: (String) -> Unit = {},
) {
    Scaffold(
        topBar = { SiteSwitcherBar(state.sites, state.site, onSelectSite, onAddSite) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) { padding ->
        if (state.site == null) {
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
                item { TopologyCard(strip) }
                item { BandwidthRow(state.rates, state.realtimeError) }
                items(state.nodes, key = { it.config.id }) { node ->
                    NodeCard(node, wifiClients[node.config.id], Modifier.animateItem(), onClick = { onOpenNode(node.config.id) })
                }
            }
        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SiteSwitcherBar(sites: List<SiteConfig>, selected: SiteConfig?, onSelect: (String) -> Unit, onAddSite: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    CenterAlignedTopAppBar(
        title = {
            Box {
                val title = selected?.name ?: stringResource(R.string.app_name)
                if (sites.size > 1) {
                    TextButton(onClick = { expanded = true }) {
                        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                    }
                } else {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    sites.forEach { site ->
                        DropdownMenuItem(text = { Text(site.name) }, onClick = {
                            expanded = false
                            onSelect(site.id)
                        })
                    }
                }
            }
        },
        actions = {
            IconButton(onClick = onAddSite) { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_site)) }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    )
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
