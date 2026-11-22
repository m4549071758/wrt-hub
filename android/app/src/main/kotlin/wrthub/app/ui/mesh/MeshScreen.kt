package wrthub.app.ui.mesh

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.max
import kotlin.math.roundToInt
import wrthub.app.R
import wrthub.app.ui.design.AppCard
import wrthub.app.ui.design.AppTopBar
import wrthub.app.ui.design.PrimaryButton
import wrthub.app.ui.design.ScreenPadding
import wrthub.app.ui.design.StatusText
import wrthub.app.ui.design.SubtitleText
import wrthub.app.ui.design.ValueText
import wrthub.app.ui.formatBps
import wrthub.app.ui.theme.LocalSemanticColors
import wrthub.app.ui.theme.forSignal
import wrthub.app.ui.theme.weight
import wrthub.core.topology.Presence

@Composable
fun MeshRoute(viewModel: MeshViewModel, onBack: () -> Unit, onOpenNode: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.setLive(true)
        onPauseOrDispose { viewModel.setLive(false) }
    }
    MeshScreen(state, viewModel::select, onBack, onOpenNode)
}

@Composable
fun MeshScreen(state: MeshUiState, onSelect: (MeshSelection?) -> Unit, onBack: () -> Unit = {}, onOpenNode: (String) -> Unit = {}) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AppTopBar(
            stringResource(R.string.network_map),
            subtitle = state.site?.let { site -> { SubtitleText(site.name) } },
            onBack = onBack,
        )
        MeshGraph(state, onSelect, Modifier.fillMaxWidth().weight(1f))
        SelectionDetail(
            state,
            onOpenNode,
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = ScreenPadding, end = ScreenPadding, top = 8.dp, bottom = 16.dp),
        )
    }
}

/** Slowest direction of a mesh link, in bits per second. */
private fun MeshEdge.slowestBps(): Double? = listOfNotNull(aToBKbps, bToAKbps).minOrNull()?.let { it * 1000 }

private fun dbm(signal: Int): String = if (signal < 0) "−${-signal} dBm" else "$signal dBm"

@Composable
fun MeshGraph(state: MeshUiState, onSelect: (MeshSelection?) -> Unit, modifier: Modifier = Modifier) {
    val semantic = LocalSemanticColors.current
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val nameStyle = MaterialTheme.typography.labelMedium.weight(FontWeight.SemiBold).copy(color = colors.onSurface)
    val smallStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val internetLabel = stringResource(R.string.internet)
    val routerIcon = painterResource(R.drawable.ic_router)
    val apIcon = painterResource(R.drawable.ic_wifi)
    val globeIcon = painterResource(R.drawable.ic_public)
    val counts = state.nodes.associate { it.id to pluralStringResource(R.plurals.clients_count, it.clients.size, it.clients.size) }
    val density = LocalDensity.current
    val metrics = remember(density) {
        with(density) { TreeMetrics(cell = 56.dp.toPx(), level = 104.dp.toPx(), row = 60.dp.toPx(), padding = 28.dp.toPx()) }
    }
    val nodeRadius = with(density) { 22.dp.toPx() }
    val internetRadius = with(density) { 18.dp.toPx() }
    val clientRadius = with(density) { 6.dp.toPx() }
    val slop = with(density) { 14.dp.toPx() }
    val layout = remember(state.nodes, state.edges, state.routerId, metrics) {
        treeLayout(state.nodes, state.edges, state.routerId, metrics)
    }
    val clientsByMac = remember(state.nodes) { state.nodes.flatMap { it.clients }.associateBy { it.mac } }
    val online = remember(state.nodes) { state.nodes.associate { it.id to it.online } }
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
                        detectTapGestures {
                            val reach = 24.dp.toPx()
                            onSelect(hitTest(it - shift, layout, max(nodeRadius, reach), max(clientRadius * 2, reach), slop))
                        }
                    },
            ) {
                translate(shift.x, shift.y) {
                    val beam = colors.onSurface
                    val haloCenter = state.routerId?.let { layout.nodes[it] } ?: layout.nodes.values.firstOrNull()
                    if (haloCenter != null) {
                        val radius = 200.dp.toPx()
                        drawCircle(Brush.radialGradient(listOf(beam.copy(alpha = 0.08f), Color.Transparent), haloCenter, radius), radius, haloCenter)
                    }
                    for (link in layout.links) {
                        val edge = link.edge
                        val end = link.points.last()
                        when (link.kind) {
                            TreeLinkKind.WAN -> litPath(link.points, beam, dashed = true)
                            TreeLinkKind.MESH -> {
                                val selected = edge != null && state.selection == MeshSelection.Edge(edge.a, edge.b)
                                litPath(link.points, if (selected) colors.primary else beam, core = if (selected) 2.6.dp else 1.6.dp)
                            }
                            TreeLinkKind.BACKHAUL -> {
                                val childOnline = layout.nodes.entries.firstOrNull { it.value == end }?.let { online[it.key] } ?: true
                                if (childOnline) {
                                    litPath(link.points, beam, dashed = true)
                                } else {
                                    drawPath(pathOf(link.points), colors.outline, style = Stroke(1.4.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 6.dp.toPx()))))
                                }
                            }
                            // Straight spokes from the node to each client, as in the reference map.
                            TreeLinkKind.CLIENT_WIFI, TreeLinkKind.CLIENT_OTHER -> drawLine(
                                colors.onSurfaceVariant.copy(alpha = 0.45f),
                                link.points.first(),
                                end,
                                1.dp.toPx(),
                                pathEffect = if (link.kind == TreeLinkKind.CLIENT_OTHER) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())) else null,
                            )
                        }
                        val rate = edge?.slowestBps()
                        if (rate != null) {
                            val text = measurer.measure(formatBps(rate), smallStyle)
                            val bend = link.points[link.points.size - 2]
                            drawText(text, topLeft = Offset(end.x + 8.dp.toPx(), (bend.y + end.y) / 2 - text.size.height / 2f))
                        }
                    }
                    layout.internet?.let { p ->
                        drawCircle(colors.background, internetRadius, p)
                        drawCircle(colors.onSurfaceVariant, internetRadius, p, style = Stroke(1.2.dp.toPx()))
                        icon(globeIcon, p, 20.dp.toPx(), colors.onSurface)
                        val label = measurer.measure(internetLabel, smallStyle)
                        drawText(label, topLeft = Offset(p.x + internetRadius + 8.dp.toPx(), p.y - label.size.height / 2f))
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
                            smallStyle,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            constraints = Constraints(maxWidth = (metrics.cell - 4.dp.toPx()).toInt()),
                        )
                        drawText(label, topLeft = Offset(p.x - label.size.width / 2f, p.y + clientRadius + 4.dp.toPx()))
                    }
                    for (node in state.nodes) {
                        val center = layout.nodes[node.id] ?: continue
                        val selected = state.selection == MeshSelection.Node(node.id)
                        drawCircle(colors.surface, nodeRadius, center)
                        drawCircle(
                            when {
                                selected -> colors.primary
                                node.online -> colors.onSurface
                                else -> colors.outline
                            },
                            nodeRadius,
                            center,
                            style = Stroke((if (selected) 2.4.dp else 1.4.dp).toPx()),
                        )
                        icon(
                            if (node.id == state.routerId) routerIcon else apIcon,
                            center,
                            24.dp.toPx(),
                            if (node.online) colors.onSurface else colors.onSurfaceVariant,
                        )
                        // Right of the circle, not below: below is where the lines to the node's clients run.
                        val name = measurer.measure(
                            node.label,
                            nameStyle,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            constraints = Constraints(maxWidth = 96.dp.toPx().toInt()),
                        )
                        val count = measurer.measure(counts.getValue(node.id), smallStyle, maxLines = 1)
                        val left = center.x + nodeRadius + 8.dp.toPx()
                        val top = center.y - (name.size.height + count.size.height) / 2f
                        drawText(name, topLeft = Offset(left, top))
                        drawText(count, topLeft = Offset(left, top + name.size.height))
                    }
                }
            }
        }
    }
}

private fun pathOf(points: List<Offset>) = Path().apply {
    moveTo(points.first().x, points.first().y)
    points.drop(1).forEach { lineTo(it.x, it.y) }
}

/** A line that glows: two wide faint strokes under a thin solid one, like the home screen's topology. */
private fun DrawScope.litPath(points: List<Offset>, color: Color, dashed: Boolean = false, core: androidx.compose.ui.unit.Dp = 1.6.dp) {
    val path = pathOf(points)
    drawPath(path, color.copy(alpha = 0.06f), style = Stroke(10.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, color.copy(alpha = 0.12f), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(
        path,
        color,
        style = Stroke(
            core.toPx(),
            join = StrokeJoin.Round,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 5.dp.toPx())) else null,
        ),
    )
}

private fun DrawScope.icon(painter: Painter, center: Offset, size: Float, tint: Color) {
    translate(center.x - size / 2, center.y - size / 2) {
        with(painter) { draw(Size(size, size), colorFilter = ColorFilter.tint(tint)) }
    }
}

@Composable
private fun SelectionDetail(state: MeshUiState, onOpenNode: (String) -> Unit, modifier: Modifier = Modifier) {
    val semantic = LocalSemanticColors.current
    val labels = state.nodes.associate { it.id to it.label }
    when (val selection = state.selection) {
        null -> Column(modifier) {
            Text(
                stringResource(R.string.mesh_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.departed > 0) {
                Panel(null, Modifier.padding(top = 10.dp)) {
                    Line(stringResource(R.string.departed)) {
                        ValueText(pluralStringResource(R.plurals.clients_count, state.departed, state.departed))
                    }
                }
            }
        }
        is MeshSelection.Node -> state.nodes.firstOrNull { it.id == selection.id }?.let { node ->
            Panel(
                node.label,
                modifier,
                status = {
                    if (node.online) {
                        StatusText(stringResource(R.string.status_online), semantic.signalGood)
                    } else {
                        StatusText(stringResource(R.string.status_unreachable), semantic.signalPoor)
                    }
                },
            ) {
                state.edges.firstOrNull { it.a == node.id || it.b == node.id }?.let { edge ->
                    val peer = if (edge.a == node.id) edge.b else edge.a
                    Line(stringResource(R.string.link_with, labels[peer] ?: peer)) {
                        ValueText(listOfNotNull(edge.slowestBps()?.let(::formatBps), edge.signal?.let(::dbm)).joinToString(" · ").ifEmpty { "—" })
                    }
                }
                Line(stringResource(R.string.tab_clients)) {
                    ValueText(pluralStringResource(R.plurals.clients_count, node.clients.size, node.clients.size))
                }
                PrimaryButton(stringResource(R.string.open_node), { onOpenNode(node.id) }, Modifier.fillMaxWidth().padding(top = 10.dp), height = 44)
            }
        }
        is MeshSelection.Edge -> state.edges.firstOrNull { it.a == selection.a && it.b == selection.b }?.let { edge ->
            val a = labels[edge.a] ?: edge.a
            val b = labels[edge.b] ?: edge.b
            Panel("$a ⇄ $b", modifier) {
                Line(stringResource(R.string.link_direction, a, b)) { ValueText(edge.aToBKbps?.let { formatBps(it * 1000) } ?: "—") }
                Line(stringResource(R.string.link_direction, b, a)) { ValueText(edge.bToAKbps?.let { formatBps(it * 1000) } ?: "—") }
                Line(stringResource(R.string.signal)) { ValueText(edge.signal?.let(::dbm) ?: "—", semantic.forSignal(edge.signal)) }
            }
        }
        is MeshSelection.Client -> state.nodes.flatMap { it.clients }.firstOrNull { it.mac == selection.mac }?.let { client ->
            Panel(client.name ?: client.mac, modifier) {
                Line(stringResource(R.string.mac_address)) { ValueText(client.mac) }
                client.ipv4?.let { ip -> Line(stringResource(R.string.ip_address)) { ValueText(ip) } }
                val place = listOfNotNull(client.attachedTo?.let { labels[it] ?: it }, client.ifname).joinToString(" ")
                if (place.isNotEmpty()) Line(stringResource(R.string.attached_to)) { ValueText(place) }
                client.signal?.let { signal -> Line(stringResource(R.string.signal)) { ValueText(dbm(signal), semantic.forSignal(signal)) } }
            }
        }
    }
}

/** The card under the map: a title with an optional state on the right, then label/value lines. */
@Composable
private fun Panel(
    title: String?,
    modifier: Modifier = Modifier,
    status: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    AppCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp)) {
            if (title != null) {
                Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium.weight(FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    status?.invoke()
                }
            }
            content()
        }
    }
}

@Composable
private fun Line(label: String, value: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        value()
    }
}
