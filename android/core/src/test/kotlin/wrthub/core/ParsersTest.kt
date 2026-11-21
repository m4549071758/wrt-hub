package wrthub.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNull
import wrthub.core.model.WirelessMode
import wrthub.core.model.deviceCounters
import wrthub.core.model.hostNames
import wrthub.core.model.lanIpv4
import wrthub.core.model.leases
import wrthub.core.model.netInterfaces
import wrthub.core.model.summary
import wrthub.core.model.usteerSightings
import wrthub.core.model.wirelessInterfaces
import wrthub.core.model.wanDevice

class ParsersTest {
    private val router = Fixtures.snapshot("mesh-site/router.json")
    private val home = Fixtures.snapshot("wired-site/router.json")

    @Test
    fun summary() {
        val summary = router.summary()
        assertEquals("ELECOM WRC-X3200GST3", summary.model)
        assertEquals("25.12.6", summary.firmware)
        assertTrue(summary.uptimeSeconds > 0)
        assertTrue(summary.load1 >= 0.0)
        assertTrue(summary.memoryAvailableBytes in 1..summary.memoryTotalBytes)
    }

    @Test
    fun `wireless interfaces are classified as mesh or ap`() {
        val byName = router.wirelessInterfaces().associateBy { it.ifname }
        assertEquals(setOf("wl0-ap0", "wl1-ap0", "wl1-mesh0"), byName.keys)
        val mesh = byName.getValue("wl1-mesh0")
        assertEquals(WirelessMode.MESH, mesh.mode)
        assertEquals("ESTAB", mesh.stations.single().meshPlink)
        assertEquals(WirelessMode.AP, byName.getValue("wl1-ap0").mode)
        assertEquals(3, byName.getValue("wl1-ap0").stations.size)
        assertTrue(byName.values.all { it.macaddr != null && it.macaddr == it.macaddr.lowercase() })
    }

    @Test
    fun `mesh station keeps averaged signal and expected throughput`() {
        val station = router.wirelessInterfaces().single { it.mode == WirelessMode.MESH }.stations.single()
        assertEquals(-59, station.signalAvg)
        assertEquals(5368L, station.expectedThroughputKbps)
    }

    @Test
    fun `interfaces and counters`() {
        assertTrue(Regex("""10\.99\.\d+\.1""").matches(assertNotNull(router.lanIpv4())))
        assertTrue(home.netInterfaces().any { it.name == "wan" && it.up })
        assertTrue(router.deviceCounters().getValue("br-lan").rxBytes > 0)
    }

    @Test
    fun `leases and host names`() {
        assertEquals(48, home.leases().size)
        assertEquals(4, router.leases().size)
        assertTrue(home.hostNames().isNotEmpty())
        assertTrue(home.leases().all { it.mac == it.mac.lowercase() })
    }

    @Test
    fun `usteer sightings split local and remote keys`() {
        val sightings = router.usteerSightings().values.flatten()
        assertTrue(sightings.any { it.nodeAddress == null })
        assertTrue(sightings.any { it.nodeAddress != null && it.apObject.startsWith("hostapd.") })
    }

    @Test
    fun `wan device is the l3 device of the up wan interface`() {
        assertEquals("wan", router.wanDevice())
        assertEquals("ipip6-wan", home.wanDevice())
        assertNull(Fixtures.snapshot("mesh-site/ap.json").wanDevice())
    }
}
