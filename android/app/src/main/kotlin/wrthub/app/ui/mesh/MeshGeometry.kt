package wrthub.app.ui.mesh

import androidx.compose.ui.geometry.Offset
import kotlin.math.max
import wrthub.core.topology.Presence

enum class TreeLinkKind { WAN, MESH, BACKHAUL, CLIENT_WIFI, CLIENT_OTHER }

/** A connector drawn through [points]; [edge] is set for mesh links. */
data class TreeLink(val kind: TreeLinkKind, val points: List<Offset>, val edge: MeshEdge? = null)

data class TreeLayout(
    val width: Float,
    val height: Float,
    val internet: Offset?,
    val nodes: Map<String, Offset>,
    val clients: Map<String, Offset>,
    val links: List<TreeLink>,
)

/** Sizes in px: [cell] per client column, [level] between tiers, [row] between client rows, [padding] around it all. */
data class TreeMetrics(val cell: Float, val level: Float, val row: Float, val padding: Float, val columns: Int = 4)

/**
 * Internet on top, then the router, then the nodes reached from it over mesh links (breadth first), with every
 * node's clients in rows of [TreeMetrics.columns] directly under it. A node's client block and its child nodes sit
 * side by side, so subtrees never overlap. Nodes without a mesh path to the router hang under it as backhaul
 * (wired, or unknown); without a router every node is a root.
 */
fun treeLayout(nodes: List<MeshNode>, edges: List<MeshEdge>, routerId: String?, metrics: TreeMetrics): TreeLayout {
    val ids = nodes.map { it.id }
    val byId = nodes.associateBy { it.id }
    val root = routerId?.takeIf { it in byId }
    val children = ids.associateWith { mutableListOf<String>() }
    val parentEdge = mutableMapOf<String, MeshEdge?>()
    val roots = if (root == null) {
        ids
    } else {
        val seen = mutableSetOf(root)
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (id in ids) {
                if (id in seen) continue
                val edge = edges.firstOrNull { setOf(it.a, it.b) == setOf(current, id) } ?: continue
                seen += id
                children.getValue(current) += id
                parentEdge[id] = edge
                queue += id
            }
        }
        ids.filter { it !in seen }.forEach { children.getValue(root) += it }
        listOf(root)
    }

    val gap = metrics.cell / 2
    fun clientBlockWidth(id: String) = byId.getValue(id).clients.size.coerceAtMost(metrics.columns) * metrics.cell
    val widths = mutableMapOf<String, Float>()
    fun width(id: String): Float = widths[id] ?: run {
        val groups = listOfNotNull(clientBlockWidth(id).takeIf { it > 0f }) + children.getValue(id).map { width(it) }
        max(metrics.cell, groups.sum() + gap * (groups.size - 1).coerceAtLeast(0)).also { widths[id] = it }
    }

    val nodePositions = mutableMapOf<String, Offset>()
    val clientPositions = mutableMapOf<String, Offset>()
    val links = mutableListOf<TreeLink>()
    var bottom = 0f

    fun place(id: String, left: Float, y: Float) {
        val center = Offset(left + width(id) / 2, y)
        nodePositions[id] = center
        bottom = max(bottom, y)
        var x = left
        val clients = byId.getValue(id).clients
        clients.forEachIndexed { i, client ->
            val p = Offset(x + (i % metrics.columns + 0.5f) * metrics.cell, y + metrics.level + (i / metrics.columns) * metrics.row)
            clientPositions[client.mac] = p
            bottom = max(bottom, p.y)
            if (i < metrics.columns) {
                val kind = if (client.presence == Presence.WIRELESS) TreeLinkKind.CLIENT_WIFI else TreeLinkKind.CLIENT_OTHER
                links += TreeLink(kind, elbow(center, p))
            }
        }
        if (clients.isNotEmpty()) x += clientBlockWidth(id) + gap
        for (child in children.getValue(id)) {
            place(child, x, y + metrics.level)
            val edge = parentEdge[child]
            links += TreeLink(if (edge != null) TreeLinkKind.MESH else TreeLinkKind.BACKHAUL, elbow(center, nodePositions.getValue(child)), edge)
            x += width(child) + gap
        }
    }

    val top = metrics.padding + if (root != null) metrics.level else 0f
    var x = metrics.padding
    for (id in roots) {
        place(id, x, top)
        x += width(id) + gap
    }
    val internet = root?.let { Offset(nodePositions.getValue(it).x, metrics.padding) }
    if (internet != null) links += TreeLink(TreeLinkKind.WAN, listOf(internet, nodePositions.getValue(root)))
    val width = if (roots.isEmpty()) 2 * metrics.padding else x - gap + metrics.padding
    return TreeLayout(width, bottom + metrics.padding, internet, nodePositions, clientPositions, links)
}

/** Down from the parent to the middle, across, then down to the child. */
private fun elbow(from: Offset, to: Offset): List<Offset> {
    val middle = (from.y + to.y) / 2
    return listOf(from, Offset(from.x, middle), Offset(to.x, middle), to)
}

/** Clients are drawn on top, then nodes, then links, so taps resolve in that order. */
fun hitTest(point: Offset, layout: TreeLayout, nodeRadius: Float, clientRadius: Float, linkSlop: Float): MeshSelection? {
    layout.clients.entries.firstOrNull { (_, p) -> (point - p).getDistance() <= clientRadius }
        ?.let { return MeshSelection.Client(it.key) }
    layout.nodes.entries.firstOrNull { (_, p) -> (point - p).getDistance() <= nodeRadius }
        ?.let { return MeshSelection.Node(it.key) }
    return layout.links.firstOrNull { link ->
        link.edge != null && link.points.zipWithNext().any { (a, b) -> distanceToSegment(point, a, b) <= linkSlop }
    }?.edge?.let { MeshSelection.Edge(it.a, it.b) }
}

private fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
    val ab = b - a
    val lengthSquared = ab.x * ab.x + ab.y * ab.y
    if (lengthSquared == 0f) return (p - a).getDistance()
    val ap = p - a
    val t = ((ap.x * ab.x + ap.y * ab.y) / lengthSquared).coerceIn(0f, 1f)
    return (p - (a + ab * t)).getDistance()
}
