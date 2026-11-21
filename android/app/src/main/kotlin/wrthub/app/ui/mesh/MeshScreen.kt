package wrthub.app.ui.mesh

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wrthub.app.R
import wrthub.app.ui.formatBps
import wrthub.app.ui.theme.LocalSemanticColors
import wrthub.app.ui.theme.forSignal
import wrthub.core.topology.Presence

@Composable
fun MeshRoute(viewModel: MeshViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.setLive(true)
        onPauseOrDispose { viewModel.setLive(false) }
    }
    MeshScreen(state, viewModel::select)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshScreen(state: MeshUiState, onSelect: (MeshSelection?) -> Unit) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(state.site?.name ?: stringResource(R.string.tab_mesh)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            Card(
                Modifier.fillMaxWidth().weight(1f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                MeshGraph(state, onSelect, Modifier.fillMaxSize())
            }
            Card(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                SelectionDetail(state, Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
fun MeshGraph(state: MeshUiState, onSelect: (MeshSelection?) -> Unit, modifier: Modifier = Modifier) {
    val semantic = LocalSemanticColors.current
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium.copy(color = colors.onSurface)
    val countStyle = MaterialTheme.typography.labelLarge.copy(color = colors.onPrimaryContainer)
    val rateStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val nodeRadius = with(density) { 22.dp.toPx() }
        val clientRadius = with(density) { 6.dp.toPx() }
        val orbit = with(density) { 48.dp.toPx() }
        val slop = with(density) { 14.dp.toPx() }
        val labelSpace = with(density) { (22 + 24).dp.toPx() }
        val layout = remember(state.nodes, state.edges, width, height) {
            meshLayout(state.nodes, state.edges, width, height, orbit, labelSpace)
        }
        Canvas(
            Modifier.fillMaxSize().pointerInput(layout, state.edges) {
                detectTapGestures { onSelect(hitTest(it, layout, state.edges, nodeRadius, clientRadius * 2, slop)) }
            },
        ) {
            for (edge in state.edges) {
                val a = layout.nodes[edge.a] ?: continue
                val b = layout.nodes[edge.b] ?: continue
                val selected = state.selection == MeshSelection.Edge(edge.a, edge.b)
                drawLine(
                    semantic.forSignal(edge.signal),
                    a,
                    b,
                    strokeWidth = (if (selected) 7.dp else 4.dp).toPx(),
                    cap = StrokeCap.Round,
                )
                val slowest = listOfNotNull(edge.aToBKbps, edge.bToAKbps).minOrNull()
                if (slowest != null) {
                    val text = measurer.measure(formatBps(slowest * 1000), rateStyle)
                    val middle = (a + b) / 2f
                    drawText(text, topLeft = Offset(middle.x - text.size.width / 2f, middle.y - text.size.height - 6.dp.toPx()))
                }
            }
            for (node in state.nodes) {
                val center = layout.nodes[node.id] ?: continue
                for (client in node.clients) {
                    val p = layout.clients[client.mac] ?: continue
                    val wireless = client.presence == Presence.WIRELESS
                    drawLine(
                        colors.outlineVariant,
                        center,
                        p,
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = if (wireless) null else PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
                    )
                    drawCircle(if (wireless) semantic.forSignal(client.signal) else semantic.inactive, clientRadius, p)
                    if (state.selection == MeshSelection.Client(client.mac)) {
                        drawCircle(colors.primary, clientRadius + 3.dp.toPx(), p, style = Stroke(2.dp.toPx()))
                    }
                }
            }
            for (node in state.nodes) {
                val center = layout.nodes[node.id] ?: continue
                drawCircle(if (node.online) colors.primaryContainer else colors.surfaceVariant, nodeRadius, center)
                if (state.selection == MeshSelection.Node(node.id)) {
                    drawCircle(colors.primary, nodeRadius + 3.dp.toPx(), center, style = Stroke(2.dp.toPx()))
                }
                val count = measurer.measure(node.clients.size.toString(), countStyle)
                drawText(count, topLeft = center - Offset(count.size.width / 2f, count.size.height / 2f))
                val label = measurer.measure(node.label, labelStyle)
                drawText(label, topLeft = Offset(center.x - label.size.width / 2f, center.y + nodeRadius + 4.dp.toPx()))
            }
        }
    }
}

@Composable
private fun SelectionDetail(state: MeshUiState, modifier: Modifier = Modifier) {
    val semantic = LocalSemanticColors.current
    val labels = state.nodes.associate { it.id to it.label }
    Column(modifier) {
        when (val selection = state.selection) {
            null -> Text(stringResource(R.string.mesh_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
            is MeshSelection.Node -> state.nodes.firstOrNull { it.id == selection.id }?.let { node ->
                Text(node.label, style = MaterialTheme.typography.titleMedium)
                DetailLine(stringResource(R.string.tab_clients), node.clients.size.toString())
                node.clients.take(6).forEach { client ->
                    DetailLine(client.name ?: client.mac, client.signal?.let { "$it dBm" } ?: "—")
                }
            }
            is MeshSelection.Edge -> state.edges.firstOrNull { it.a == selection.a && it.b == selection.b }?.let { edge ->
                val a = labels[edge.a] ?: edge.a
                val b = labels[edge.b] ?: edge.b
                Text("$a ⇄ $b", style = MaterialTheme.typography.titleMedium)
                DetailLine(stringResource(R.string.link_direction, a, b), edge.aToBKbps?.let { formatBps(it * 1000) } ?: "—")
                DetailLine(stringResource(R.string.link_direction, b, a), edge.bToAKbps?.let { formatBps(it * 1000) } ?: "—")
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(stringResource(R.string.signal), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(edge.signal?.let { "$it dBm" } ?: "—", color = semantic.forSignal(edge.signal))
                }
            }
            is MeshSelection.Client -> state.nodes.flatMap { it.clients }.firstOrNull { it.mac == selection.mac }?.let { client ->
                Text(client.name ?: client.mac, style = MaterialTheme.typography.titleMedium)
                DetailLine(stringResource(R.string.mac_address), client.mac)
                client.ipv4?.let { DetailLine(stringResource(R.string.ip_address), it) }
                DetailLine(stringResource(R.string.attached_to), listOfNotNull(client.attachedTo?.let { labels[it] ?: it }, client.ifname).joinToString(" "))
                client.signal?.let { DetailLine(stringResource(R.string.signal), "$it dBm") }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value)
    }
}
