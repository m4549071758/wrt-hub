package wrthub.core

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import wrthub.core.admin.ChannelOption
import wrthub.core.admin.DhcpRangeInput
import wrthub.core.admin.LedInput
import wrthub.core.admin.RadioCapabilities
import wrthub.core.admin.RadioInput
import wrthub.core.admin.SystemInput
import wrthub.core.admin.WanInput
import wrthub.core.admin.WanProto
import wrthub.core.admin.htmodesFor
import wrthub.core.admin.addresses
import wrthub.core.admin.parseDhcpRanges
import wrthub.core.admin.parseWan
import wrthub.core.admin.parseWireless
import wrthub.core.admin.validate
import wrthub.core.direct.DirectAdmin
import wrthub.core.direct.WriteOutcome
import wrthub.core.json.arr
import wrthub.core.json.obj
import wrthub.core.json.str

class AdminPart2Test {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private val wireless = json(
        """{"values":{
        "radio0":{".type":"wifi-device",".name":"radio0",".index":0,"band":"2g","channel":"auto","htmode":"HT40","txpower":"20"},
        "radio1":{".type":"wifi-device",".name":"radio1",".index":1,"band":"5g","channel":"48","htmode":"HE80"},
        "ap0":{".type":"wifi-iface",".name":"ap0",".index":2,"device":"radio0","mode":"ap","ssid":"Home","key":"$FAKE_WIFI_KEY"},
        "ap1":{".type":"wifi-iface",".name":"ap1",".index":3,"device":"radio1","mode":"ap","ssid":"Home","disabled":"1"},
        "mesh":{".type":"wifi-iface",".name":"mesh",".index":4,"device":"radio1","mode":"mesh","mesh_id":"m","key":"$FAKE_WIFI_KEY"}}}""",
    )

    private val caps = RadioCapabilities(
        channels = listOf(ChannelOption(36, 5180, setOf("no_ht40-")), ChannelOption(140, 5700, setOf("no_80mhz", "no_160mhz"))),
        htmodes = listOf("HT20", "HT40", "VHT80", "HE20", "HE40", "HE80"),
        txpowers = listOf(10, 15, 20),
    )

    @Test
    fun `the mesh radio is flagged and the mesh interface is not an ssid`() {
        val config = parseWireless(wireless)
        assertEquals(listOf(false, true), config.radios.map { it.carriesMesh })
        assertEquals(listOf("ap0" to true, "ap1" to false), config.ssids.map { it.section to it.enabled })
        assertNull(config.radios[1].txpower)
    }

    @Test
    fun `widths a channel cannot use are not offered`() {
        assertEquals(listOf("HT20", "HT40", "HE20", "HE40"), caps.htmodesFor("140"))
        assertEquals(caps.htmodes, caps.htmodesFor("auto"))
    }

    @Test
    fun `the mesh radio keeps its channel and width but may change power`() {
        val mesh = parseWireless(wireless).radios[1]
        assertFailsWith<IllegalArgumentException> { mesh.validate(RadioInput("36", "HE80", null), caps) }
        mesh.validate(RadioInput("48", "HE80", 15), caps)
        assertFailsWith<IllegalArgumentException> { mesh.validate(RadioInput("48", "HE80", 17), caps) }
    }

    private val network = json(
        """{"values":{
        "lan":{".type":"interface",".name":"lan","proto":"static","ipaddr":"192.168.1.1","netmask":"255.255.255.0"},
        "guest":{".type":"interface",".name":"guest","proto":"static","ipaddr":["192.168.77.1/25"]},
        "wan":{".type":"interface",".name":"wan","proto":"static","device":"eth1","ipaddr":"203.0.113.2","netmask":"255.255.255.0","gateway":"203.0.113.1","dns":["192.0.2.53"]}}}""",
    )

    @Test
    fun `dhcp pools know their subnet and must fit in it`() {
        val dhcp = json(
            """{"values":{
            "lan":{".type":"dhcp",".name":"lan","interface":"lan","start":"100","limit":"150"},
            "guest":{".type":"dhcp",".name":"guest","interface":"guest"},
            "wan":{".type":"dhcp",".name":"wan","interface":"wan","ignore":"1"}}}""",
        )
        val ranges = parseDhcpRanges(dhcp, network)
        assertEquals(listOf("192.168.1.1/24", "192.168.77.1/25"), ranges.map { it.subnet })
        assertEquals("192.168.1.100" to "192.168.1.249", ranges[0].addresses())
        assertEquals("192.168.77.100" to "192.168.77.249", ranges[1].addresses())
        ranges[0].validate(DhcpRangeInput(100, 155, "12h"))
        assertFailsWith<IllegalArgumentException> { ranges[0].validate(DhcpRangeInput(100, 156, "12h")) }
        assertFailsWith<IllegalArgumentException> { ranges[1].validate(DhcpRangeInput(100, 50, "12h")) }
        assertFailsWith<IllegalArgumentException> { ranges[0].validate(DhcpRangeInput(100, 10, "soon")) }
    }

    @Test
    fun `wan protocols the app does not edit are read only`() {
        val ipip6 = json(
            """{"values":{"wan":{".type":"interface","proto":"ipip6","peeraddr":"2001:db8::1","ip4ifaddr":"192.0.2.10",
            "auth_key":"x","password":"y","dns":["192.0.2.53","192.0.2.54"]}}}""",
        )
        val wan = parseWan(ipip6)!!
        assertNull(wan.proto)
        assertEquals(
            listOf("proto" to "ipip6", "peeraddr" to "2001:db8::1", "ip4ifaddr" to "192.0.2.10", "dns" to "192.0.2.53 192.0.2.54"),
            wan.options,
        )
        assertEquals(WanProto.STATIC, parseWan(network)!!.proto)
    }

    private fun router(
        configs: Map<String, JsonObject>,
        ping: Int = 0,
        failSystemInfo: Int = 0,
        extra: (String, JsonObject) -> JsonObject? = { _, _ -> null },
    ): FakeUbus {
        var infoFailures = failSystemInfo
        return FakeUbus { obj, method, args ->
            when ("$obj.$method") {
                "uci.changes" -> json("""{"changes":{}}""")
                "uci.get" -> configs.getValue(args.str("config")!!)
                "system.info" -> if (infoFailures-- > 0) throw IOException("radio restarting") else JsonObject(emptyMap())
                "file.exec" -> buildJsonObject { put("code", if (args.str("command") == "/bin/ping") ping else 0) }
                else -> extra("$obj.$method", args)
            }
        }
    }

    private fun FakeUbus.writes() = calls.map { "${it.obj}.${it.method}" }.filter { it.startsWith("uci.") && it !in setOf("uci.get", "uci.changes") }

    @Test
    fun `a radio restart that briefly cuts the phone off is still confirmed`() = runTest {
        val fake = router(mapOf("wireless" to wireless), failSystemInfo = 2) { call, _ ->
            if (call == "iwinfo.txpowerlist") json("""{"results":[{"dbm":15},{"dbm":20}]}""") else null
        }
        assertEquals(WriteOutcome.APPLIED, DirectAdmin(fake).saveRadio("radio0", RadioInput("auto", "HT40", 15)))
        assertEquals(listOf("uci.set", "uci.apply", "uci.confirm"), fake.writes())
        assertEquals("15", fake.calls.first { it.method == "set" }.args.obj("values")?.str("txpower"))
    }

    @Test
    fun `switching wan to dhcp clears the static options`() = runTest {
        val fake = router(mapOf("network" to network))
        assertEquals(WriteOutcome.APPLIED, DirectAdmin(fake).saveWan(WanInput.Dhcp))
        val delete = fake.calls.first { it.method == "delete" }.args
        assertEquals(setOf("ipaddr", "netmask", "gateway", "dns"), delete.arr("options")!!.map { it.jsonPrimitive.content }.toSet())
    }

    @Test
    fun `pppoe without a new password keeps the old one`() = runTest {
        val pppoe = json("""{"values":{"wan":{".type":"interface","proto":"pppoe","username":"old","password":"secret"}}}""")
        val fake = router(mapOf("network" to pppoe))
        DirectAdmin(fake).saveWan(WanInput.Pppoe("new", null))
        assertEquals(listOf("uci.set", "uci.apply", "uci.confirm"), fake.writes())
        assertEquals(setOf("username"), fake.calls.first { it.method == "set" }.args.obj("values")!!.keys)
    }

    @Test
    fun `a wan change is not confirmed while the router cannot reach the internet`() = runTest {
        val fake = router(mapOf("network" to network), ping = 1)
        assertEquals(WriteOutcome.ROLLED_BACK, DirectAdmin(fake).saveWan(WanInput.Dhcp))
        assertFalse(fake.calls.any { it.method == "confirm" })
    }

    @Test
    fun `system settings and ntp are applied together and ntp is created when missing`() = runTest {
        val system = json("""{"values":{"cfg01":{".type":"system",".name":"cfg01","hostname":"OpenWrt","zonename":"UTC","timezone":"UTC"}}}""")
        val fake = router(mapOf("system" to system)) { call, _ ->
            if (call == "luci.getTimezones") json("""{"Asia/Tokyo":{"tzstring":"JST-9"},"UTC":{"tzstring":"UTC"}}""") else null
        }
        DirectAdmin(fake).saveSystem(SystemInput("gw", "Asia/Tokyo", true, listOf("ntp.example")))
        assertEquals(listOf("uci.set", "uci.add", "uci.apply", "uci.confirm"), fake.writes())
        assertEquals("JST-9", fake.calls.first { it.method == "set" }.args.obj("values")?.str("timezone"))
        val ntp = fake.calls.first { it.method == "add" }.args
        assertEquals("timeserver", ntp.str("type"))
        assertEquals(listOf("ntp.example"), ntp.obj("values")!!["server"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `an unknown time zone is refused`() = runTest {
        val system = json("""{"values":{"cfg01":{".type":"system",".name":"cfg01"}}}""")
        val fake = router(mapOf("system" to system)) { call, _ -> if (call == "luci.getTimezones") json("""{"UTC":{"tzstring":"UTC"}}""") else null }
        assertFailsWith<IllegalArgumentException> { DirectAdmin(fake).saveSystem(SystemInput("gw", "Mars/Base", true, emptyList())) }
        assertTrue(fake.writes().isEmpty())
    }

    @Test
    fun `an led gets an app section named after it, and netdev needs a device`() = runTest {
        val system = json("""{"values":{}}""")
        val fake = router(mapOf("system" to system)) { call, _ ->
            if (call == "luci.getLEDs") json("""{"blue:power-3":{"triggers":["none","default-on","netdev"],"active_trigger":"none"}}""") else null
        }
        val admin = DirectAdmin(fake)
        assertFailsWith<IllegalArgumentException> { admin.saveLed("blue:power-3", LedInput("netdev", null)) }
        admin.saveLed("blue:power-3", LedInput("default-on", null))
        val add = fake.calls.first { it.method == "add" }.args
        assertEquals("wrthub_led_blue_power_3", add.str("name"))
        assertEquals("1", add.obj("values")?.str("default"))
    }

    @Test
    fun `a missing crontab reads as empty, and saving ends with a newline and reloads cron`() = runTest {
        val fake = router(emptyMap()) { call, _ ->
            if (call == "file.read") throw wrthub.core.ubus.UbusException(wrthub.core.ubus.UbusException.NOT_FOUND, "not found") else null
        }
        val admin = DirectAdmin(fake)
        assertEquals("", admin.crontab())
        admin.saveCrontab("0 4 * * * /sbin/reboot")
        assertEquals("0 4 * * * /sbin/reboot\n", fake.calls.first { it.method == "write" }.args.str("data"))
        assertEquals("/etc/init.d/cron", fake.calls.last().args.str("command"))
    }

    @Test
    fun `a failure while staging reverts what was staged`() = runTest {
        val system = json("""{"values":{"cfg01":{".type":"system",".name":"cfg01","hostname":"OpenWrt"}}}""")
        val fake = router(mapOf("system" to system)) { call, _ ->
            when (call) {
                "luci.getTimezones" -> json("""{"UTC":{"tzstring":"UTC"}}""")
                "uci.add" -> throw IOException("connection reset")
                else -> null
            }
        }
        assertFailsWith<IOException> { DirectAdmin(fake).saveSystem(SystemInput("gw", "UTC", true, emptyList())) }
        assertEquals(listOf("uci.set", "uci.add", "uci.revert"), fake.writes())
    }
}
