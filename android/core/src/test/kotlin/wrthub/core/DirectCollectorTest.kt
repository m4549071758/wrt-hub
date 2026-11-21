package wrthub.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import wrthub.core.direct.DirectCollector
import wrthub.core.direct.blockedMacs
import wrthub.core.ubus.UbusException

class DirectCollectorTest {
    @Test
    fun `collect reproduces every fixture`() = runTest {
        for (path in Fixtures.paths) {
            val fixture = Fixtures.snapshot(path)
            val collected = DirectCollector(fakeRouter(fixture)) { fixture.collectedAt }.collect()
            assertEquals(fixture, collected, path)
        }
    }

    @Test
    fun `hostapd is queried only for AP interfaces`() = runTest {
        val fake = fakeRouter(Fixtures.snapshot("mesh-site/router.json"))
        DirectCollector(fake).collect()
        val hostapd = fake.calls.map { it.obj }.filter { it.startsWith("hostapd.") }.toSet()
        assertEquals(setOf("hostapd.wl0-ap0", "hostapd.wl1-ap0"), hostapd)
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
