package wrthub.app

import androidx.compose.ui.geometry.Offset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import wrthub.app.ui.mesh.MeshEdge
import wrthub.app.ui.mesh.MeshNode
import wrthub.app.ui.mesh.MeshSelection
import wrthub.app.ui.mesh.TreeLinkKind
import wrthub.app.ui.mesh.TreeMetrics
import wrthub.app.ui.mesh.hitTest
import wrthub.app.ui.mesh.treeLayout
import wrthub.core.topology.Presence
import wrthub.core.topology.SiteClient

class MeshGeometryTest {
    private val metrics = TreeMetrics(cell = 10f, level = 100f, row = 20f, padding = 5f, columns = 4)

    private fun clients(node: String, count: Int, presence: Presence = Presence.WIRELESS) =
        (1..count).map { SiteClient("$node-c$it", null, null, presence, node, null, -50, false, emptyList()) }

    private fun edge(a: String, b: String) = MeshEdge(a, b, 1000.0, 1000.0, -60)

    @Test
    fun `internet, router, mesh node and their clients go downwards`() {
        val nodes = listOf(MeshNode("router", "R", true, clients("router", 1)), MeshNode("ap", "AP", true, clients("ap", 2)))
        val layout = treeLayout(nodes, listOf(edge("ap", "router")), "router", metrics)
        val internet = layout.internet!!
        val router = layout.nodes.getValue("router")
        val ap = layout.nodes.getValue("ap")
        assertTrue(internet.y < router.y && router.y < ap.y)
        assertEquals(router.y + metrics.level, layout.clients.getValue("router-c1").y)
        assertTrue(layout.clients.getValue("ap-c1").y > ap.y)
        assertEquals(setOf(TreeLinkKind.WAN, TreeLinkKind.MESH, TreeLinkKind.CLIENT_WIFI), layout.links.map { it.kind }.toSet())
        assertEquals(edge("ap", "router"), layout.links.single { it.kind == TreeLinkKind.MESH }.edge)
    }

    @Test
    fun `clients wrap after four columns and only the first row gets connectors`() {
        val layout = treeLayout(listOf(MeshNode("router", "R", true, clients("router", 6))), emptyList(), "router", metrics)
        val first = layout.clients.getValue("router-c1")
        val fifth = layout.clients.getValue("router-c5")
        assertEquals(first.x, fifth.x)
        assertEquals(first.y + metrics.row, fifth.y)
        assertEquals(4, layout.links.count { it.kind == TreeLinkKind.CLIENT_WIFI })
    }

    @Test
    fun `multi-hop mesh nests deeper and sibling subtrees do not overlap`() {
        val nodes = listOf(
            MeshNode("router", "R", true, emptyList()),
            MeshNode("ap1", "A1", true, clients("ap1", 3)),
            MeshNode("ap2", "A2", true, clients("ap2", 4)),
            MeshNode("ap3", "A3", true, clients("ap3", 2)),
        )
        val edges = listOf(edge("router", "ap1"), edge("ap1", "ap2"), edge("router", "ap3"))
        val layout = treeLayout(nodes, edges, "router", metrics)
        assertEquals(layout.nodes.getValue("ap1").y + metrics.level, layout.nodes.getValue("ap2").y)
        val leftSubtree = (layout.clients.filterKeys { it.startsWith("ap1") || it.startsWith("ap2") }.values + layout.nodes.getValue("ap2"))
        val rightSubtree = layout.clients.filterKeys { it.startsWith("ap3") }.values
        assertTrue(leftSubtree.maxOf { it.x } < rightSubtree.minOf { it.x })
        assertTrue(layout.width >= rightSubtree.maxOf { it.x } + metrics.padding)
    }

    @Test
    fun `nodes without a mesh path hang under the router as backhaul`() {
        val nodes = listOf(MeshNode("router", "R", true, emptyList()), MeshNode("wired", "W", false, emptyList()))
        val layout = treeLayout(nodes, emptyList(), "router", metrics)
        assertEquals(layout.nodes.getValue("router").y + metrics.level, layout.nodes.getValue("wired").y)
        assertEquals(1, layout.links.count { it.kind == TreeLinkKind.BACKHAUL })
    }

    @Test
    fun `without a router every node is a root`() {
        val nodes = listOf(MeshNode("a", "A", true, emptyList()), MeshNode("b", "B", true, emptyList()))
        val layout = treeLayout(nodes, emptyList(), null, metrics)
        assertNull(layout.internet)
        assertEquals(layout.nodes.getValue("a").y, layout.nodes.getValue("b").y)
    }

    @Test
    fun `taps resolve to clients, then nodes, then mesh links`() {
        val nodes = listOf(MeshNode("router", "R", true, clients("router", 1)), MeshNode("ap", "AP", true, emptyList()))
        val layout = treeLayout(nodes, listOf(edge("ap", "router")), "router", metrics)
        val client = layout.clients.getValue("router-c1")
        val router = layout.nodes.getValue("router")
        val ap = layout.nodes.getValue("ap")
        fun hit(p: Offset) = hitTest(p, layout, nodeRadius = 4f, clientRadius = 3f, linkSlop = 2f)
        assertEquals(MeshSelection.Client("router-c1"), hit(client + Offset(1f, 1f)))
        assertEquals(MeshSelection.Node("router"), hit(router + Offset(1f, 0f)))
        assertEquals(MeshSelection.Edge("ap", "router"), hit(Offset(ap.x, ap.y - 10f)))
        assertNull(hit(Offset(layout.width - 1f, router.y - 50f)))
    }
}
