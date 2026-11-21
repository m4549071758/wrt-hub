package wrthub.core

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import wrthub.core.direct.DirectOperations
import wrthub.core.direct.ExecResult
import wrthub.core.direct.WriteOutcome
import wrthub.core.json.arr
import wrthub.core.json.bool
import wrthub.core.json.int
import wrthub.core.json.obj
import wrthub.core.json.str

class DirectOperationsTest {
    private fun recording(answer: (obj: String, method: String) -> JsonObject? = { _, _ -> null }) =
        FakeUbus { obj, method, _ -> answer(obj, method) }

    private val wireless = Json.parseToJsonElement(
        """{"values":{
            "default_radio0":{".type":"wifi-iface","mode":"ap","ssid":"old"},
            "mesh0":{".type":"wifi-iface","mode":"mesh","mesh_id":"m"},
            "radio0":{".type":"wifi-device"}}}""",
    ).jsonObject

    @Test
    fun `block adds a named reject rule and confirms after a reachability check`() = runTest {
        val fake = recording()
        assertEquals(WriteOutcome.APPLIED, DirectOperations(fake).blockClient("AA:BB:CC:DD:EE:FF"))
        assertEquals(listOf("uci.add", "uci.apply", "system.info", "uci.confirm"), fake.calls.map { "${it.obj}.${it.method}" })
        val add = fake.calls[0].args
        assertEquals("firewall", add.str("config"))
        assertEquals("wrthub_block_aabbccddeeff", add.str("name"))
        assertEquals("aa:bb:cc:dd:ee:ff", add.obj("values")?.str("src_mac"))
        assertEquals("REJECT", add.obj("values")?.str("target"))
        assertEquals(true, fake.calls[1].args.bool("rollback"))
        assertEquals(30, fake.calls[1].args.int("timeout"))
    }

    @Test
    fun `unreachable router after apply reports rollback and never confirms`() = runTest {
        val fake = recording { obj, method -> if (obj == "system" && method == "info") throw IOException("unreachable") else null }
        assertEquals(WriteOutcome.ROLLED_BACK, DirectOperations(fake).unblockClient("aa:bb:cc:dd:ee:ff"))
        assertEquals("wrthub_block_aabbccddeeff", fake.calls[0].args.str("section"))
        assertTrue(fake.calls.none { it.method == "confirm" })
    }

    @Test
    fun `staging wifi touches only ap interfaces and applies nothing`() = runTest {
        val fake = recording { obj, method -> if (obj == "uci" && method == "get") wireless else null }
        assertEquals(1, DirectOperations(fake).stageWifi("New-SSID", "correct horse"))
        val sets = fake.calls.filter { it.method == "set" }
        assertEquals(listOf("default_radio0"), sets.map { it.args.str("section") })
        assertEquals("New-SSID", sets.single().args.obj("values")?.str("ssid"))
        assertTrue(fake.calls.none { it.method == "apply" })
    }

    @Test
    fun `staged wifi is applied without rollback or reverted`() = runTest {
        val fake = recording()
        val ops = DirectOperations(fake)
        ops.applyUnchecked()
        ops.discardWifi()
        assertEquals(listOf("uci.apply", "uci.revert"), fake.calls.map { "${it.obj}.${it.method}" })
        assertEquals(false, fake.calls[0].args.bool("rollback"))
        assertEquals("wireless", fake.calls[1].args.str("config"))
    }

    @Test
    fun `staging wifi on a node without ap interfaces changes nothing`() = runTest {
        val empty = buildJsonObject { put("values", buildJsonObject {}) }
        val fake = recording { obj, method -> if (obj == "uci" && method == "get") empty else null }
        assertEquals(0, DirectOperations(fake).stageWifi("New-SSID", "correct horse"))
        assertEquals(listOf("uci.get"), fake.calls.map { "${it.obj}.${it.method}" })
    }

    @Test
    fun `invalid input is rejected before any call`() = runTest {
        val fake = recording()
        val ops = DirectOperations(fake)
        assertFailsWith<IllegalArgumentException> { ops.blockClient("not-a-mac") }
        assertFailsWith<IllegalArgumentException> { ops.stageWifi("ok", "short") }
        assertFailsWith<IllegalArgumentException> { ops.stageWifi("x".repeat(33), "long enough") }
        assertFailsWith<IllegalArgumentException> { ops.ping("-c 100 example.com") }
        assertFailsWith<IllegalArgumentException> { ops.traceroute("a;reboot") }
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `ping runs busybox ping with fixed arguments`() = runTest {
        val fake = recording { obj, method ->
            if (obj == "file" && method == "exec") {
                buildJsonObject {
                    put("code", 0)
                    put("stdout", "4 packets")
                }
            } else {
                null
            }
        }
        assertEquals(ExecResult(0, "4 packets", ""), DirectOperations(fake).ping("192.0.2.1"))
        val args = fake.calls.single().args
        assertEquals("/bin/ping", args.str("command"))
        assertEquals(listOf("-c", "4", "-W", "2", "192.0.2.1"), args.arr("params")?.map { it.jsonPrimitive.content })
    }
}
