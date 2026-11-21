package wrthub.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import wrthub.core.direct.DirectCollector
import wrthub.core.direct.blockedMacs
import wrthub.core.snapshot.Snapshot
import wrthub.core.snapshot.encodeSnapshot
import wrthub.core.json.str
import wrthub.core.ubus.UbusException

class DirectCollectorTest {
    @Test
    fun `collect reproduces every fixture`() = runTest {
        for (path in Fixtures.paths) {
            val fixture = Fixtures.snapshot(path)
            val expected = fixture.copy(wireless = fixture.wireless.map { it.copy(hostapdClients = null) })
            val collected = DirectCollector(fakeRouter(fixture)) { fixture.collectedAt }.collect()
            assertEquals(expected, collected, path)
        }
    }

    @Test
    fun `wireless interfaces come from luci-rpc and its Wi-Fi config is not kept`() = runTest {
        val fake = fakeRouter(Fixtures.snapshot("mesh-site/router.json"))
        val collected = DirectCollector(fake).collect()
        assertEquals(listOf("wl0-ap0", "wl1-mesh0", "wl1-ap0"), collected.wireless.map { it.ifname })
        assertFalse(FAKE_WIFI_KEY in encodeSnapshot(collected))
        assertTrue(fake.calls.none { it.obj == "iwinfo" && it.method == "devices" })
    }

    @Test
    fun `calls refused by the ACL are reported while the rest still loads`() = runTest {
        val router = fakeRouter(Fixtures.snapshot("mesh-site/router.json"))
        val restricted = FakeUbus { obj, method, args ->
            when ("$obj.$method") {
                "luci-rpc.getWirelessDevices" -> throw UbusException(UbusException.PERMISSION_DENIED, "denied")
                "iwinfo.devices" -> throw UbusException(UbusException.RPC_ACCESS_DENIED, "Access denied")
                "usteer.get_clients" -> notFound()
                "file.exec" -> throw UbusException(UbusException.PERMISSION_DENIED, "denied")
                else -> router.handler(obj, method, args)
            }
        }
        val collected: Snapshot = DirectCollector(restricted).collect()
        assertEquals(listOf("luci-rpc.getWirelessDevices", "iwinfo.devices"), collected.deniedCalls)
        assertTrue(collected.wireless.isEmpty())
        assertEquals("ELECOM WRC-X3200GST3", collected.board.str("model"))
    }

    @Test
    fun `a node without Wi-Fi does not ask usteer and reports no denial`() = runTest {
        val router = fakeRouter(Fixtures.snapshot("wired-site/router.json"))
        val luciAcl = FakeUbus { obj, method, args ->
            if (obj == "usteer") throw UbusException(UbusException.RPC_ACCESS_DENIED, "Access denied") else router.handler(obj, method, args)
        }
        val collected = DirectCollector(luciAcl).collect()
        assertTrue(collected.deniedCalls.isEmpty())
        assertTrue(luciAcl.calls.none { it.obj == "usteer" })
    }

    @Test
    fun `falls back to iwinfo devices when luci-rpc is not installed`() = runTest {
        val router = fakeRouter(Fixtures.snapshot("mesh-site/ap.json"))
        val withoutLuci = FakeUbus { obj, method, args ->
            if (obj == "luci-rpc") notFound() else router.handler(obj, method, args)
        }
        val collected = DirectCollector(withoutLuci).collect()
        assertEquals(listOf("wl0-ap0", "wl1-mesh0", "wl1-ap0"), collected.wireless.map { it.ifname })
        assertTrue(collected.deniedCalls.isEmpty())
    }

    @Test
    fun `unexpected ubus errors propagate`() = runTest {
        val router = fakeRouter(Fixtures.snapshot("wired-site/router.json"))
        val failing = FakeUbus { obj, method, args ->
            if (obj == "luci-rpc") throw UbusException(UbusException.TIMEOUT, "timeout") else router.handler(obj, method, args)
        }
        val e = assertFailsWith<UbusException> { DirectCollector(failing).collect() }
        assertEquals(UbusException.TIMEOUT, e.code)
    }

    @Test
    fun `blocked clients come from wrt-hub firewall rules only`() {
        val uci = Json.parseToJsonElement(
            """{"values":{
                "wrthub_block_aabbccddeeff":{".type":"rule","src_mac":"AA:BB:CC:DD:EE:FF"},
                "wrthub_block_112233445566":{".type":"rule","src_mac":["11:22:33:44:55:66"]},
                "cfg0":{".type":"rule","src_mac":"99:99:99:99:99:99"}}}""",
        ).jsonObject
        assertEquals(listOf("11:22:33:44:55:66", "aa:bb:cc:dd:ee:ff"), blockedMacs(uci))
    }
}
