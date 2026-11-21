package wrthub.app

import androidx.compose.ui.geometry.Offset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test
import wrthub.app.ui.mesh.MeshEdge
import wrthub.app.ui.mesh.MeshLayout
import wrthub.app.ui.mesh.MeshNode
import wrthub.app.ui.mesh.MeshSelection
import wrthub.app.ui.mesh.hitTest
import wrthub.app.ui.mesh.meshLayout
import wrthub.core.topology.Presence
import wrthub.core.topology.SiteClient

class MeshGeometryTest {
    private fun client(mac: String, node: String) = SiteClient(mac, null, null, Presence.WIRELESS, node, "wl1-ap0", -50, false, emptyList())

    private val edges = listOf(MeshEdge("a", "b", 1000.0, 2000.0, -60))
    private val layout = MeshLayout(
        nodes = mapOf("a" to Offset(100f, 100f), "b" to Offset(300f, 100f)),
        clients = mapOf("c1" to Offset(100f, 60f)),
    )

    private fun hit(x: Float, y: Float) = hitTest(Offset(x, y), layout, edges, nodeRadius = 20f, clientRadius = 10f, edgeSlop = 12f)

    @Test
    fun `clients win over nodes, nodes over links`() {
        assertEquals(MeshSelection.Client("c1"), hit(101f, 61f))
        assertEquals(MeshSelection.Node("a"), hit(105f, 105f))
        assertEquals(MeshSelection.Edge("a", "b"), hit(200f, 108f))
        assertNull(hit(200f, 200f))
    }

    @Test
    fun `clients orbit their node`() {
        val nodes = listOf(MeshNode("a", "A", true, listOf(client("c1", "a"), client("c2", "a"))), MeshNode("b", "B", true, emptyList()))
        val placed = meshLayout(nodes, edges, 400f, 400f, orbit = 40f)
        for (mac in listOf("c1", "c2")) {
            assertEquals(40f, (placed.clients.getValue(mac) - placed.nodes.getValue("a")).getDistance(), 0.01f)
        }
    }

    @Test
    fun `crowded nodes put clients on outer rings`() {
        val many = (1..13).map { client("c$it", "a") }
        val nodes = listOf(MeshNode("a", "A", true, many), MeshNode("b", "B", true, emptyList()))
        val placed = meshLayout(nodes, edges, 400f, 400f, orbit = 40f)
        val center = placed.nodes.getValue("a")
        assertEquals(40f, (placed.clients.getValue("c12") - center).getDistance(), 0.01f)
        assertEquals(64f, (placed.clients.getValue("c13") - center).getDistance(), 0.01f)
    }
}
