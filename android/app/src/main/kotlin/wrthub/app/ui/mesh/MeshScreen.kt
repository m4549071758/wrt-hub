package wrthub.app.ui.mesh

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.runtime.LaunchedEffect
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
    val clientStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val countStyle = MaterialTheme.typography.labelLarge.copy(color = colors.onPrimaryContainer)
    val rateStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val internetLabel = stringResource(R.string.internet)
    val density = LocalDensity.current
    val metrics = remember(density) {
        with(density) { TreeMetrics(cell = 56.dp.toPx(), level = 104.dp.toPx(), row = 60.dp.toPx(), padding = 24.dp.toPx()) }
    }
    val nodeRadius = with(density) { 22.dp.toPx() }
    val clientRadius = with(density) { 7.dp.toPx() }
    val slop = with(density) { 14.dp.toPx() }
    val layout = remember(state.nodes, state.edges, state.routerId, metrics) {
        treeLayout(state.nodes, state.edges, state.routerId, metrics)
    }
    val clientsByMac = remember(state.nodes) { state.nodes.flatMap { it.clients }.associateBy { it.mac } }
    BoxWithConstraints(modifier) {
        val canvasWidth = max(layout.width, constraints.maxWidth.toFloat())
        val canvasHeight = max(layout.height, constraints.maxHeight.toFloat())
        val shift = Offset((canvasWidth - layout.width) / 2, 0f)
        val horizontal = rememberScrollState()
        val viewportWidth = constraints.maxWidth
        LaunchedEffect(layout, viewportWidth) {
            val focus = layout.internet ?: layout.nodes.values.firstOrNull() ?: return@LaunchedEffect
            horizontal.scrollTo((focus.x + shift.x - viewportWidth / 2f).roundToInt().coerceAtLeast(0))
        }
        Box(Modifier.fillMaxSize().horizontalScroll(horizontal).verticalScroll(rememberScrollState())) {
            Canvas(
                Modifier
                    .size(with(density) { canvasWidth.toDp() }, with(density) { canvasHeight.toDp() })
                    .pointerInput(layout, shift) {
                        detectTapGestures { onSelect(hitTest(it - shift, layout, nodeRadius, clientRadius * 2, slop)) }
                    },
            ) {
                translate(shift.x, shift.y) {
                    val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                    for (link in layout.links) {
                        val path = Path().apply {
                            moveTo(link.points.first().x, link.points.first().y)
                            link.points.drop(1).forEach { lineTo(it.x, it.y) }
                        }
                        val edge = link.edge
                        val selected = edge != null && state.selection == MeshSelection.Edge(edge.a, edge.b)
                        val (color, width, effect) = when (link.kind) {
                            TreeLinkKind.WAN -> Triple(colors.outline, 2.dp.toPx(), null)
                            TreeLinkKind.MESH -> Triple(semantic.forSignal(edge?.signal), (if (selected) 7.dp else 4.dp).toPx(), null)
                            TreeLinkKind.BACKHAUL -> Triple(colors.outline, 2.dp.toPx(), dash)
                            TreeLinkKind.CLIENT_WIFI -> Triple(colors.outlineVariant, 1.5.dp.toPx(), null)
                            TreeLinkKind.CLIENT_OTHER -> Triple(colors.outlineVariant, 1.5.dp.toPx(), dash)
                        }
                        drawPath(path, color, style = Stroke(width = width, pathEffect = effect))
                        val slowest = edge?.let { listOfNotNull(it.aToBKbps, it.bToAKbps).minOrNull() }
                        if (slowest != null) {
                            val text = measurer.measure(formatBps(slowest * 1000), rateStyle)
                            val end = link.points.last()
                            val bend = link.points[link.points.size - 2]
                            drawText(text, topLeft = Offset(end.x + 6.dp.toPx(), (bend.y + end.y) / 2 - text.size.height / 2f))
                        }
                    }
                    layout.internet?.let { p ->
                        drawCircle(colors.surface, nodeRadius, p)
                        drawCircle(colors.outline, nodeRadius, p, style = Stroke(2.dp.toPx()))
                        val wan = measurer.measure("WAN", countStyle.copy(color = colors.onSurface))
                        drawText(wan, topLeft = p - Offset(wan.size.width / 2f, wan.size.height / 2f))
                        val label = measurer.measure(internetLabel, labelStyle)
                        drawText(label, topLeft = Offset(p.x + nodeRadius + 6.dp.toPx(), p.y - label.size.height / 2f))
                    }
                    for ((mac, p) in layout.clients) {
                        val client = clientsByMac[mac] ?: continue
                        val wireless = client.presence == Presence.WIRELESS
                        drawCircle(if (wireless) semantic.forSignal(client.signal) else semantic.inactive, clientRadius, p)
                        if (state.selection == MeshSelection.Client(mac)) {
                            drawCircle(colors.primary, clientRadius + 3.dp.toPx(), p, style = Stroke(2.dp.toPx()))
                        }
                        val label = measurer.measure(
                            client.name ?: mac.takeLast(8),
                            clientStyle,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            constraints = Constraints(maxWidth = (metrics.cell - 4.dp.toPx()).toInt()),
                        )
                        drawText(label, topLeft = Offset(p.x - label.size.width / 2f, p.y + clientRadius + 3.dp.toPx()))
                    }
                    for (node in state.nodes) {
                        val center = layout.nodes[node.id] ?: continue
                        drawCircle(if (node.online) colors.primaryContainer else colors.surfaceVariant, nodeRadius, center)
                        if (state.selection == MeshSelection.Node(node.id)) {
                            drawCircle(colors.primary, nodeRadius + 3.dp.toPx(), center, style = Stroke(2.dp.toPx()))
                        }
                        val count = measurer.measure(node.clients.size.toString(), countStyle)
                        drawText(count, topLeft = center - Offset(count.size.width / 2f, count.size.height / 2f))
                        // Right of the circle, not below: below is where the line to the node's clients runs.
                        val label = measurer.measure(
                            node.label,
                            labelStyle,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            constraints = Constraints(maxWidth = 96.dp.toPx().toInt()),
                        )
                        drawText(label, topLeft = Offset(center.x + nodeRadius + 6.dp.toPx(), center.y - label.size.height / 2f))
                    }
                }
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
            null -> {
                Text(stringResource(R.string.mesh_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.departed > 0) {
                    DetailLine(stringResource(R.string.departed), state.departed.toString())
                }
            }
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
