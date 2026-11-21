package wrthub.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import wrthub.core.json.obj
import wrthub.core.json.str
import wrthub.core.topology.LinkMetrics
import wrthub.core.topology.Presence
import wrthub.core.topology.Sighting
import wrthub.core.topology.buildTopology

class TopologyTest {
    @Test
    fun `mesh site has one link with both directions`() {
        val topology = buildTopology(Fixtures.meshSite)
        assertEquals(listOf("ap", "router"), topology.nodeIds)
        val link = topology.links.single()
        assertEquals("ap", link.a)
        assertEquals("router", link.b)
        assertEquals(LinkMetrics(-61, 1003176), link.aToB)
        assertEquals(LinkMetrics(-59, 5368), link.bToA)
    }

    @Test
    fun `wireless clients are attached to the node that serves them`() {
        val topology = buildTopology(Fixtures.meshSite)
        val wireless = topology.clients.filter { it.presence == Presence.WIRELESS }
        assertEquals(3, wireless.count { it.attachedTo == "router" })
        assertEquals(2, wireless.count { it.attachedTo == "ap" })
        assertTrue(topology.clients.none { it.presence == Presence.LEASE_ONLY })
    }

    @Test
    fun `usteer sightings resolve remote node addresses`() {
        val topology = buildTopology(Fixtures.meshSite)
        for (client in topology.clients.filter { it.presence == Presence.WIRELESS }) {
            assertTrue(client.sightings.any { it.nodeId == client.attachedTo && it.connected }, client.mac)
        }
        val seenByBoth = topology.clients.single { it.sightings.size == 2 }
        assertEquals(listOf(Sighting("ap", true, -55), Sighting("router", false, -64)), seenByBoth.sightings)
    }

    @Test
    fun `wired site splits live devices from those that only left a lease`() {
        val topology = buildTopology(Fixtures.wiredSite)
        assertTrue(topology.links.isEmpty())
        assertEquals(57, topology.clients.count { it.presence == Presence.ONLINE_UNKNOWN_LINK })
        assertEquals(4, topology.clients.count { it.presence == Presence.LEASE_ONLY })
        assertTrue(topology.clients.filter { it.presence == Presence.ONLINE_UNKNOWN_LINK }.all { it.ipv4 != null })
        assertTrue(topology.clients.any { it.name != null })
    }

    @Test
    fun `neighbour table adds static devices but not upstream peers or the site's own nodes`() {
        val ap = Fixtures.meshSite.getValue("ap")
        val apLanMac = ap.devices.obj("br-lan")!!.str("macaddr")!!
        val router = Fixtures.meshSite.getValue("router").copy(
            neighbors = """
                10.99.0.50 dev br-lan lladdr 02:00:00:00:aa:01 REACHABLE
                10.99.0.51 dev br-lan  FAILED
                100.64.0.1 dev wan lladdr 02:00:00:00:aa:02 REACHABLE
                10.99.0.2 dev br-lan lladdr $apLanMac STALE
            """.trimIndent(),
        )
        val clients = buildTopology(mapOf("router" to router, "ap" to ap)).clients.associateBy { it.mac }
        val static = clients.getValue("02:00:00:00:aa:01")
        assertEquals(Presence.ONLINE_UNKNOWN_LINK, static.presence)
        assertEquals("10.99.0.50", static.ipv4)
        assertTrue("02:00:00:00:aa:02" !in clients)
        assertTrue(apLanMac.lowercase() !in clients)
    }

    @Test
    fun `blocked client without lease or association is listed offline`() {
        val router = Fixtures.meshSite.getValue("router").copy(blockedClients = listOf("02:00:00:00:FF:FF"))
        val client = buildTopology(mapOf("router" to router)).clients.single { it.mac == "02:00:00:00:ff:ff" }
        assertEquals(Presence.OFFLINE, client.presence)
        assertTrue(client.blocked)
        assertNull(client.attachedTo)
    }

    @Test
    fun `mesh peer that is not a known node is ignored`() {
        assertTrue(buildTopology(mapOf("router" to Fixtures.meshSite.getValue("router"))).links.isEmpty())
    }
}
