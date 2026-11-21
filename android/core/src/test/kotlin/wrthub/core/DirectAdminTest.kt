package wrthub.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import wrthub.core.admin.ForwardProtocol
import wrthub.core.admin.IpFamily
import wrthub.core.admin.PortForwardInput
import wrthub.core.admin.RouteTable
import wrthub.core.admin.ServiceAction
import wrthub.core.admin.StaticLeaseInput
import wrthub.core.direct.DirectAdmin
import wrthub.core.direct.PendingChangesException
import wrthub.core.direct.WriteOutcome
import wrthub.core.json.arr
import wrthub.core.json.obj
import wrthub.core.json.str

class DirectAdminTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private val dhcp = json(
        """{"values":{
        "lan":{".type":"dhcp",".name":"lan","interface":"lan"},
        "cfg05fe63":{".type":"host",".name":"cfg05fe63","name":"nas","ip":"192.168.1.20","mac":"ce:11:22:00:00:01"}}}""",
    )

    private val firewall = json(
        """{"values":{"a":{".type":"redirect",".name":"a",".index":1,"name":"web","src":"wan","dest":"lan","target":"DNAT",
        "proto":"tcp udp","src_dport":"8443","dest_ip":"192.168.1.20","dest_port":"443"}}}""",
    )

    private val noChanges = json("""{"changes":{}}""")

    private fun router(
        changes: JsonObject = noChanges,
        exec: (List<String>) -> JsonObject = { buildJsonObject { put("code", 0); put("stdout", "") } },
    ) = FakeUbus { obj, method, args ->
        when ("$obj.$method") {
            "uci.changes" -> changes
            "uci.get" -> if (args.str("config") == "dhcp") dhcp else firewall
            "file.exec" -> exec(listOf(args.str("command")!!) + args.arr("params")!!.map { it.toString().trim('"') })
            else -> null
        }
    }

    private fun FakeUbus.writes() = calls.map { "${it.obj}.${it.method}" }.filter { it !in setOf("uci.get", "uci.changes") }

    @Test
    fun `routes fall back to the plain table when ip cannot print json`() = runTest {
        val fake = router(exec = { cmd ->
            if ("-j" in cmd) {
                buildJsonObject { put("code", 255); put("stderr", "Option \"-j\" is unknown") }
            } else {
                buildJsonObject { put("code", 0); put("stdout", "default via 192.168.77.1 dev wan\n") }
            }
        })
        val table = DirectAdmin(fake).routes(IpFamily.V4)
        assertEquals(RouteTable.Text("default via 192.168.77.1 dev wan\n"), table)
        assertEquals(listOf("-4", "route", "show", "table", "all"), fake.calls.last().args.arr("params")!!.map { it.toString().trim('"') })
    }

    @Test
    fun `a new static lease is added under an app section name and applied with rollback`() = runTest {
        val fake = router()
        val outcome = DirectAdmin(fake).saveStaticLease(null, StaticLeaseInput("printer", "CE:11:22:00:00:09", "192.168.1.30"))
        assertEquals(WriteOutcome.APPLIED, outcome)
        assertEquals(listOf("uci.add", "uci.apply", "system.info", "uci.confirm"), fake.writes())
        val add = fake.calls.first { it.method == "add" }.args
        assertEquals("wrthub_host_ce1122000009", add.str("name"))
        assertEquals("ce:11:22:00:00:09", add.obj("values")?.str("mac"))
        assertEquals(true, fake.calls.first { it.method == "apply" }.args["rollback"].toString().toBoolean())
    }

    @Test
    fun `saving a lease with its current values applies nothing`() = runTest {
        val fake = router()
        val outcome = DirectAdmin(fake).saveStaticLease("cfg05fe63", StaticLeaseInput("nas", "ce:11:22:00:00:01", "192.168.1.20"))
        assertEquals(WriteOutcome.UNCHANGED, outcome)
        assertTrue(fake.writes().isEmpty())
    }

    @Test
    fun `clearing a lease's name deletes the option`() = runTest {
        val fake = router()
        val outcome = DirectAdmin(fake).saveStaticLease("cfg05fe63", StaticLeaseInput(null, "ce:11:22:00:00:01", "192.168.1.20"))
        assertEquals(WriteOutcome.APPLIED, outcome)
        val delete = fake.calls.single { it.method == "delete" }.args
        assertEquals(listOf("name"), delete.arr("options")!!.map { it.toString().trim('"') })
        assertTrue(fake.writes().indexOf("uci.delete") < fake.writes().indexOf("uci.apply"))
    }

    @Test
    fun `a mac or ip that already has a lease is refused before writing`() = runTest {
        val fake = router()
        val admin = DirectAdmin(fake)
        assertFailsWith<IllegalArgumentException> { admin.saveStaticLease(null, StaticLeaseInput(null, "ce:11:22:00:00:01", "192.168.1.31")) }
        assertFailsWith<IllegalArgumentException> { admin.saveStaticLease(null, StaticLeaseInput(null, "ce:11:22:00:00:09", "192.168.1.20")) }
        assertTrue(fake.writes().isEmpty())
    }

    @Test
    fun `changes already staged in the session stop the write`() = runTest {
        val fake = router(changes = json("""{"changes":{"wireless":[["set","default_radio0","ssid","x"]]}}"""))
        val error = assertFailsWith<PendingChangesException> {
            DirectAdmin(fake).savePortForward(null, PortForwardInput("ssh", ForwardProtocol.TCP, "2222", "192.168.1.20", "22"))
        }
        assertEquals(setOf("wireless"), error.configs)
        assertTrue(fake.writes().isEmpty())
    }

    @Test
    fun `a port forward is a wan to lan dnat rule`() = runTest {
        val fake = router()
        DirectAdmin(fake, newId = { "abc" }).savePortForward(null, PortForwardInput("ssh", ForwardProtocol.TCP, "2222", "192.168.1.20", "22"))
        val add = fake.calls.first { it.method == "add" }.args
        assertEquals("wrthub_fwd_abc", add.str("name"))
        assertEquals("redirect", add.str("type"))
        val values = add.obj("values")!!
        assertEquals(listOf("wan", "lan", "DNAT", "tcp", "2222", "22"), listOf("src", "dest", "target", "proto", "src_dport", "dest_port").map { values.str(it) })
    }

    @Test
    fun `disabling a forward sets only enabled`() = runTest {
        val fake = router()
        DirectAdmin(fake).setPortForwardEnabled("a", false)
        val set = fake.calls.first { it.method == "set" }.args
        assertEquals(mapOf("enabled" to "0"), set.obj("values")!!.mapValues { it.value.toString().trim('"') })
    }

    @Test
    fun `deleting a section that is already gone applies nothing`() = runTest {
        val fake = router()
        assertEquals(WriteOutcome.UNCHANGED, DirectAdmin(fake).deletePortForward("missing"))
        assertTrue(fake.writes().isEmpty())
    }

    @Test
    fun `invalid input and essential service stops never reach the router`() = runTest {
        val fake = router()
        val admin = DirectAdmin(fake)
        assertFailsWith<IllegalArgumentException> { admin.serviceAction("rpcd", ServiceAction.STOP) }
        assertFailsWith<IllegalArgumentException> { admin.serviceAction("a;reboot", ServiceAction.RESTART) }
        assertFailsWith<IllegalArgumentException> {
            admin.savePortForward(null, PortForwardInput(null, ForwardProtocol.TCP, "70000", "192.168.1.20", "22"))
        }
        assertFailsWith<IllegalArgumentException> { admin.saveStaticLease(null, StaticLeaseInput("bad name", "ce:11:22:00:00:09", "192.168.1.30")) }
        assertTrue(fake.writes().isEmpty())
    }

    @Test
    fun `service actions go to rc init`() = runTest {
        val fake = router()
        DirectAdmin(fake).serviceAction("collectd", ServiceAction.DISABLE)
        val call = fake.calls.single()
        assertEquals("rc.init", "${call.obj}.${call.method}")
        assertEquals(listOf("collectd", "disable"), listOf(call.args.str("name"), call.args.str("action")))
    }

    @Test
    fun `parsed routes are returned when ip prints json`() = runTest {
        val fake = router(exec = { buildJsonObject { put("code", 0); put("stdout", """[{"dst":"default","dev":"wan","flags":[]}]""") } })
        assertIs<RouteTable.Parsed>(DirectAdmin(fake).routes(IpFamily.V6))
    }
}
