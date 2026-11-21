package wrthub.app.ui.sites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wrthub.app.R
import wrthub.app.data.SiteConfig

@Composable
fun SitesRoute(viewModel: SitesViewModel, onAddSite: () -> Unit, onAddNode: (String) -> Unit) {
    val sites by viewModel.sites.collectAsStateWithLifecycle()
    SitesScreen(sites, onAddSite, onAddNode, viewModel::removeSite, viewModel::removeNode)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SitesScreen(
    sites: List<SiteConfig>,
    onAddSite: () -> Unit,
    onAddNode: (String) -> Unit,
    onRemoveSite: (String) -> Unit,
    onRemoveNode: (siteId: String, nodeId: String) -> Unit,
) {
    var pendingRemoval by remember { mutableStateOf<SiteConfig?>(null) }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.tab_sites)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddSite,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.add_site)) },
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(sites, key = { it.id }) { site ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(site.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            IconButton(onClick = { pendingRemoval = site }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                            }
                        }
                        site.nodes.forEach { node ->
                            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(node.label, style = MaterialTheme.typography.bodyLarge)
                                    Text(node.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (node.certSha256 != null) {
                                    Icon(Icons.Default.Lock, contentDescription = stringResource(R.string.pinned_certificate))
                                }
                                IconButton(onClick = { onRemoveNode(site.id, node.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                                }
                            }
                        }
                        TextButton(onClick = { onAddNode(site.id) }) { Text(stringResource(R.string.add_node)) }
                    }
                }
            }
        }
    }
    pendingRemoval?.let { site ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(stringResource(R.string.remove_site_title, site.name)) },
            text = { Text(stringResource(R.string.remove_site_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemoval = null
                    onRemoveSite(site.id)
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
