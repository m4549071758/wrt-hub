package wrthub.app.ui.nodes

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
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wrthub.app.R
import wrthub.app.data.SiteConfig
import wrthub.app.ui.home.NodeRow

data class NodesActions(
    val onOpenNode: (siteId: String, nodeId: String) -> Unit,
    val onAddSite: () -> Unit,
    val onAddNode: (String) -> Unit,
    val onOpenWifi: (String) -> Unit,
    val onRemoveSite: (String) -> Unit,
    val onRemoveNode: (siteId: String, nodeId: String) -> Unit,
)

@Composable
fun NodesRoute(
    viewModel: NodesViewModel,
    onOpenNode: (String) -> Unit,
    onAddSite: () -> Unit,
    onAddNode: (String) -> Unit,
    onOpenWifi: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    NodesScreen(
        state,
        NodesActions(
            onOpenNode = { siteId, nodeId ->
                viewModel.selectSiteOf(siteId)
                onOpenNode(nodeId)
            },
            onAddSite = onAddSite,
            onAddNode = onAddNode,
            onOpenWifi = { siteId ->
                viewModel.selectSiteOf(siteId)
                onOpenWifi(siteId)
            },
            onRemoveSite = viewModel::removeSite,
            onRemoveNode = viewModel::removeNode,
        ),
    )
}

/** What a destructive menu entry asks to confirm. */
private sealed interface Removal {
    data class Site(val site: SiteConfig) : Removal

    data class Node(val site: SiteConfig) : Removal
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodesScreen(state: NodesUiState, actions: NodesActions) {
    var removal by remember { mutableStateOf<Removal?>(null) }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.tab_nodes)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = actions.onAddSite,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.add_site)) },
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.sites, key = { it.id }) { site ->
                Card(Modifier.fillMaxWidth().animateItem(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.padding(start = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            site.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        SiteMenu(site, actions, onRemove = { removal = it })
                    }
                    site.nodes.forEachIndexed { index, node ->
                        if (index > 0) HorizontalDivider()
                        NodeRow(node, state.states[node.id], onClick = { actions.onOpenNode(site.id, node.id) })
                    }
                }
            }
        }
    }
    when (val pending = removal) {
        is Removal.Site -> AlertDialog(
            onDismissRequest = { removal = null },
            title = { Text(stringResource(R.string.remove_site_title, pending.site.name)) },
            text = { Text(stringResource(R.string.remove_site_body)) },
            confirmButton = {
                TextButton(onClick = {
                    removal = null
                    actions.onRemoveSite(pending.site.id)
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { removal = null }) { Text(stringResource(R.string.cancel)) } },
        )
        is Removal.Node -> RemoveNodeDialog(pending.site, onRemove = { nodeId ->
            removal = null
            actions.onRemoveNode(pending.site.id, nodeId)
        }, onDismiss = { removal = null })
        null -> Unit
    }
}

@Composable
private fun SiteMenu(site: SiteConfig, actions: NodesActions, onRemove: (Removal) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.site_menu, site.name))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.add_node)) }, onClick = {
                open = false
                actions.onAddNode(site.id)
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.site_wifi)) }, onClick = {
                open = false
                actions.onOpenWifi(site.id)
            })
            if (site.nodes.isNotEmpty()) {
                DropdownMenuItem(text = { Text(stringResource(R.string.remove_node)) }, onClick = {
                    open = false
                    onRemove(Removal.Node(site))
                })
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.remove_site), color = MaterialTheme.colorScheme.error) }, onClick = {
                open = false
                onRemove(Removal.Site(site))
            })
        }
    }
}

@Composable
private fun RemoveNodeDialog(site: SiteConfig, onRemove: (String) -> Unit, onDismiss: () -> Unit) {
    var chosen by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remove_node)) },
        text = {
            Column {
                Text(stringResource(R.string.remove_node_body), style = MaterialTheme.typography.bodyMedium)
                site.nodes.forEach { node ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = chosen == node.id, role = Role.RadioButton) { chosen = node.id }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = chosen == node.id, onClick = null)
                        Text(node.label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { chosen?.let(onRemove) }, enabled = chosen != null) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
