package wrthub.app

import kotlin.test.assertEquals
import org.junit.Test
import wrthub.app.ui.mesh.MeshNode
import wrthub.app.ui.mesh.MeshNodeKind
import wrthub.app.ui.mesh.networkNodeId
import wrthub.app.ui.mesh.splitByNetwork
import wrthub.core.topology.Presence
import wrthub.core.topology.SiteClient

class SplitByNetworkTest {
    private fun client(mac: String, network: String?, presence: Presence = Presence.ONLINE_UNKNOWN_LINK, node: String? = null) =
        SiteClient(mac, null, null, presence, node, null, null, false, emptyList(), network)

    private fun router(vararg clients: SiteClient) = MeshNode("router", "Router", true, clients.toList())

    @Test
    fun `one LAN without a marked AP stays as it is`() {
        val nodes = listOf(router(client("a", "lan"), client("b", "lan")))
        assertEquals(nodes, splitByNetwork(nodes, "router", emptySet()))
    }

    @Test
    fun `devices on two LANs move under one network each, Wi-Fi clients of the router stay`() {
        val wifi = client("w", null, Presence.WIRELESS, "router")
        val nodes = listOf(router(wifi, client("a", "lan"), client("b", "guest"), client("c", "lan")))
        val split = splitByNetwork(nodes, "router", emptySet())
        assertEquals(listOf(wifi), split.single { it.id == "router" }.clients)
        val lan = split.single { it.id == networkNodeId("lan") }
        assertEquals(MeshNodeKind.NETWORK, lan.kind)
        assertEquals(listOf("a", "c"), lan.clients.map { it.mac })
        assertEquals(listOf("b"), split.single { it.id == networkNodeId("guest") }.clients.map { it.mac })
    }

    @Test
    fun `a marked AP alone is enough to split, and it comes first`() {
        val nodes = listOf(router(client("a", "lan"), client("ap", "lan"), client("x", null)))
        val split = splitByNetwork(nodes, "router", setOf("ap"))
        assertEquals(listOf("ap", "a"), split.single { it.id == networkNodeId("lan") }.clients.map { it.mac })
        assertEquals(listOf("x"), split.single { it.id == "router" }.clients.map { it.mac })
    }
}
