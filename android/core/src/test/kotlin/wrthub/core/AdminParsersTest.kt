package wrthub.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import wrthub.core.admin.ForwardProtocol
import wrthub.core.admin.LogLine
import wrthub.core.admin.ServiceAction
import wrthub.core.admin.allowedFor
import wrthub.core.admin.parseConntrack
import wrthub.core.admin.parseDhcpConfig
import wrthub.core.admin.parseDmesg
import wrthub.core.admin.parsePortForwards
import wrthub.core.admin.parseRoutes
import wrthub.core.admin.parseServices
import wrthub.core.admin.parseSyslog
import wrthub.core.model.hostNames

class AdminParsersTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `syslog keeps the severity bits of the priority`() {
        val lines = parseSyslog(json("""{"log":[{"msg":"daemon.err odhcpd: x","id":1,"priority":27,"source":1,"time":1700000000000}]}"""))
        assertEquals(listOf(LogLine(1700000000000, null, 3, "daemon.err odhcpd: x")), lines)
    }

    @Test
    fun `dmesg lines carry severity and time since boot`() {
        val lines = parseDmesg("<4>[   12.500000] eth0: link down\n<6>[3710978.123] ok\nplain\n")
        assertEquals(LogLine(null, 12.5, 4, "eth0: link down"), lines[0])
        assertEquals(3710978.123, lines[1].bootSeconds)
        assertEquals(LogLine(null, null, 6, "plain"), lines[2])
    }

    @Test
    fun `routes default to the main table and unicast type`() {
        val routes = parseRoutes(
            """[{"dst":"default","gateway":"192.168.77.1","dev":"wan","protocol":"static","flags":[]},
               {"type":"local","dst":"192.168.1.1","table":"local","dev":"br-lan","protocol":"kernel","scope":"host","flags":[]}]""",
        )
        assertEquals(listOf("main" to "unicast", "local" to "local"), routes.map { it.table to it.type })
        assertEquals("192.168.77.1", routes[0].gateway)
    }

    @Test
    fun `icmp connections have no ports`() {
        val list = parseConntrack(
            json(
                """{"result":[
                {"bytes":84,"packets":1,"layer3":"ipv4","layer4":"icmp","timeout":29,"src":"192.168.1.10","dst":"192.168.77.1","type":8,"code":0,"id":3},
                {"bytes":1200,"packets":9,"layer3":"ipv6","layer4":"tcp","timeout":300,"src":"2001:db8::10","dst":"2001:db8::1","sport":51000,"dport":443}]}""",
            ),
        )
        assertNull(list[0].sourcePort)
        assertEquals(443, list[1].destinationPort)
        assertEquals(1200, list[1].bytes)
    }

    @Test
    fun `services without a procd instance have no running state`() {
        val services = parseServices(json("""{"sysntpd":{"start":98,"enabled":true,"running":true},"boot":{"start":10,"stop":10,"enabled":true}}"""))
        assertEquals(listOf("boot", "sysntpd"), services.map { it.name })
        assertNull(services[0].running)
    }

    @Test
    fun `essential services can be restarted but never stopped or disabled`() {
        assertTrue(ServiceAction.RESTART.allowedFor("rpcd"))
        assertFalse(ServiceAction.STOP.allowedFor("uhttpd"))
        assertFalse(ServiceAction.DISABLE.allowedFor("network"))
        assertTrue(ServiceAction.STOP.allowedFor("collectd"))
    }

    private val dhcp = json(
        """{"values":{
        "lan":{".type":"dhcp",".name":"lan",".index":0,"interface":"lan","start":"100","limit":"150"},
        "wan":{".type":"dhcp",".name":"wan",".index":1,"interface":"wan","ignore":"1"},
        "cfg05fe63":{".type":"host",".name":"cfg05fe63",".index":3,"name":"nas","ip":"192.168.1.20","mac":["CE:11:22:00:00:01"],"instance":"lan"},
        "cfg06fe63":{".type":"host",".name":"cfg06fe63",".index":4,"name":"tv","ip":"192.168.1.21","mac":"ce:11:22:00:00:02 ce:11:22:00:00:03"}}}""",
    )

    @Test
    fun `static leases accept mac lists and space separated macs`() {
        val config = parseDhcpConfig(dhcp)
        assertTrue(config.servesDhcp)
        assertEquals(listOf(listOf("ce:11:22:00:00:01"), listOf("ce:11:22:00:00:02", "ce:11:22:00:00:03")), config.leases.map { it.macs })
        assertEquals(listOf(true, false), config.leases.map { it.editable })
    }

    @Test
    fun `a node whose dhcp sections are all ignored does not serve dhcp`() {
        val config = parseDhcpConfig(json("""{"values":{"lan":{".type":"dhcp",".name":"lan","interface":"lan","ignore":"1"}}}"""))
        assertFalse(config.servesDhcp)
    }

    @Test
    fun `static lease names override the names clients announce`() {
        val snapshot = Fixtures.snapshot("wired-site/router.json").copy(
            hostHints = json("""{"CE:11:22:00:00:01":{"name":"android-1234"},"CE:11:22:00:00:09":{"name":"printer"}}"""),
            dhcpConfig = dhcp,
        )
        val names = snapshot.hostNames()
        assertEquals("nas", names["ce:11:22:00:00:01"])
        assertEquals("printer", names["ce:11:22:00:00:09"])
        assertEquals("tv", names["ce:11:22:00:00:03"])
    }

    @Test
    fun `port forwards default to tcp and udp and lock rules with options the app cannot show`() {
        val forwards = parsePortForwards(
            json(
                """{"values":{
                "b":{".type":"redirect",".name":"b",".index":2,"name":"game","src":"wan","dest":"lan","target":"DNAT","proto":"udp","src_dport":"3074","dest_ip":"192.168.1.30","dest_port":"3074","enabled":"0"},
                "a":{".type":"redirect",".name":"a",".index":1,"name":"web","src":"wan","dest":"lan","target":"DNAT","src_dport":"8443","dest_ip":"192.168.1.20","dest_port":"443"},
                "c":{".type":"redirect",".name":"c",".index":3,"name":"ssh","src":"wan","dest":"lan","target":"DNAT","proto":"tcp","src_dport":"2222","dest_ip":"192.168.1.20","dest_port":"22","reflection":"0"},
                "d":{".type":"redirect",".name":"d",".index":4,"src":"lan","dest":"wan","target":"SNAT","src_ip":"192.168.1.0/24"}}}""",
            ),
        )
        assertEquals(listOf("a", "b", "c"), forwards.map { it.section })
        assertEquals(listOf(ForwardProtocol.BOTH, ForwardProtocol.UDP, ForwardProtocol.TCP), forwards.map { it.protocol })
        assertEquals(listOf(true, false, true), forwards.map { it.enabled })
        assertEquals(listOf(true, true, false), forwards.map { it.editable })
    }
}
