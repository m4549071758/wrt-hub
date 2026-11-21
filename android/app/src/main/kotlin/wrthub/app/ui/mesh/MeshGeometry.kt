package wrthub.app.ui.mesh

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import wrthub.core.layout.forceLayout

data class MeshLayout(val nodes: Map<String, Offset>, val clients: Map<String, Offset>)

/**
 * Nodes by force layout. Each node's clients sit evenly on rings around it, starting at the top: the first
 * [RING_SIZE] on a circle of radius [orbit], each further ring 60% of [orbit] farther out. Nodes are kept far
 * enough from the edges that the outermost ring and [labelSpace] below a node stay inside [width] x [height].
 */
fun meshLayout(
    nodes: List<MeshNode>,
    edges: List<MeshEdge>,
    width: Float,
    height: Float,
    orbit: Float,
    labelSpace: Float = 0f,
): MeshLayout {
    val rings = (nodes.maxOfOrNull { it.clients.size } ?: 0).let { (it + RING_SIZE - 1) / RING_SIZE }
    val outer = if (rings == 0) 0f else orbit * (1 + RING_STEP * (rings - 1))
    val inset = maxOf(outer, labelSpace)
    val innerWidth = (width - 2 * inset).coerceAtLeast(1f)
    val innerHeight = (height - 2 * inset).coerceAtLeast(1f)
    val points = forceLayout(nodes.map { it.id }, edges.map { it.a to it.b }, innerWidth.toDouble(), innerHeight.toDouble())
    val centers = points.mapValues { (_, p) -> Offset(p.x.toFloat() + inset, p.y.toFloat() + inset) }
    val clients = buildMap {
        for (node in nodes) {
            val center = centers[node.id] ?: continue
            node.clients.chunked(RING_SIZE).forEachIndexed { ring, members ->
                val radius = orbit * (1 + RING_STEP * ring)
                members.forEachIndexed { i, client ->
                    val angle = 2 * PI * i / members.size - PI / 2
                    put(client.mac, center + Offset((cos(angle) * radius).toFloat(), (sin(angle) * radius).toFloat()))
                }
            }
        }
    }
    return MeshLayout(centers, clients)
}

private const val RING_SIZE = 12
private const val RING_STEP = 0.6f

/** Clients are drawn on top, then nodes, then links, so taps resolve in that order. */
fun hitTest(
    point: Offset,
    layout: MeshLayout,
    edges: List<MeshEdge>,
    nodeRadius: Float,
    clientRadius: Float,
    edgeSlop: Float,
): MeshSelection? {
    layout.clients.entries.firstOrNull { (_, p) -> (point - p).getDistance() <= clientRadius }
        ?.let { return MeshSelection.Client(it.key) }
    layout.nodes.entries.firstOrNull { (_, p) -> (point - p).getDistance() <= nodeRadius }
        ?.let { return MeshSelection.Node(it.key) }
    return edges.firstOrNull { edge ->
        val a = layout.nodes[edge.a]
        val b = layout.nodes[edge.b]
        a != null && b != null && distanceToSegment(point, a, b) <= edgeSlop
    }?.let { MeshSelection.Edge(it.a, it.b) }
}

private fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
    val ab = b - a
    val lengthSquared = ab.x * ab.x + ab.y * ab.y
    if (lengthSquared == 0f) return (p - a).getDistance()
    val ap = p - a
    val t = ((ap.x * ab.x + ap.y * ab.y) / lengthSquared).coerceIn(0f, 1f)
    return (p - (a + ab * t)).getDistance()
}
