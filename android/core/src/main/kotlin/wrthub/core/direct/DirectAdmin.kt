package wrthub.core.direct

import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import wrthub.core.admin.DhcpRange
import wrthub.core.admin.DhcpRangeInput
import wrthub.core.admin.Led
import wrthub.core.admin.LedInput
import wrthub.core.admin.RadioCapabilities
import wrthub.core.admin.RadioInput
import wrthub.core.admin.SystemConfig
import wrthub.core.admin.SystemInput
import wrthub.core.admin.WAN_PROTO_OPTIONS
import wrthub.core.admin.WanConfig
import wrthub.core.admin.WanInput
import wrthub.core.admin.WirelessConfig
import wrthub.core.admin.ledSectionName
import wrthub.core.admin.parseCapabilities
import wrthub.core.admin.parseDhcpRanges
import wrthub.core.admin.parseLeds
import wrthub.core.admin.parseSystem
import wrthub.core.admin.parseTimezones
import wrthub.core.admin.parseWan
import wrthub.core.admin.parseWireless
import wrthub.core.admin.requireValidCrontab
import wrthub.core.admin.validate
import wrthub.core.admin.validated
import wrthub.core.json.asObject
import wrthub.core.admin.APP_SECTION_PREFIX
import wrthub.core.admin.Connection
import wrthub.core.admin.ConntrackUsage
import wrthub.core.admin.DhcpConfig
import wrthub.core.admin.IpFamily
import wrthub.core.admin.LogLine
import wrthub.core.admin.PortForward
import wrthub.core.admin.PortForwardInput
import wrthub.core.admin.RouteTable
import wrthub.core.admin.Service
import wrthub.core.admin.ServiceAction
import wrthub.core.admin.StaticLeaseInput
import wrthub.core.admin.allowedFor
import wrthub.core.admin.parseConntrack
import wrthub.core.admin.parseDhcpConfig
import wrthub.core.admin.parseDmesg
import wrthub.core.admin.parsePortForwards
import wrthub.core.admin.parseRoutes
import wrthub.core.admin.parseServices
import wrthub.core.admin.parseSyslog
import wrthub.core.admin.uciValues
import wrthub.core.json.obj
import wrthub.core.json.str
import wrthub.core.ubus.UbusCaller
import wrthub.core.ubus.UbusException
import wrthub.core.ubus.call

private const val SERVICE_NAME = "^[A-Za-z0-9._-]{1,64}$"
private const val CRONTAB = "/etc/crontabs/root"
private const val PING_PATH = "/bin/ping"

/** Probed by the router after a WAN change; an anycast address that answers ping from anywhere. */
private const val WAN_PROBE = "1.1.1.1"

/**
 * The LuCI-replacement reads and writes of one node, using only calls LuCI's own ACLs grant.
 * Config writes go through rpcd's rollback: staged, applied with a timer, and confirmed only while the node answers.
 */
class DirectAdmin(
    private val ubus: UbusCaller,
    private val rollbackSeconds: Int = 30,
    private val newId: () -> String = { UUID.randomUUID().toString().replace("-", "").take(12) },
) {
    suspend fun syslog(lines: Int = 500): List<LogLine> =
        parseSyslog(
            ubus.call(
                "log",
                "read",
                buildJsonObject {
                    put("lines", lines)
                    put("stream", false)
                },
            ) ?: noData("log.read"),
        )

    /** LuCI grants exactly `dmesg -r`. */
    suspend fun kernelLog(): List<LogLine> = parseDmesg(ubus.exec("/bin/dmesg", listOf("-r")).successOrThrow("dmesg"))

    /** JSON from ip-full when available; busybox `ip` has no `-j`, so the plain table is shown instead. */
    suspend fun routes(family: IpFamily): RouteTable {
        val json = ubus.exec("/sbin/ip", listOf(family.flag, "-j", "route", "show", "table", "all"))
        if (json.exitCode == 0 && json.stdout.trimStart().startsWith("[")) return RouteTable.Parsed(parseRoutes(json.stdout))
        return RouteTable.Text(ubus.exec("/sbin/ip", listOf(family.flag, "route", "show", "table", "all")).successOrThrow("ip route"))
    }

    suspend fun connections(): List<Connection> = parseConntrack(ubus.call("luci", "getConntrackList") ?: noData("luci.getConntrackList"))

    suspend fun conntrackUsage(): ConntrackUsage? {
        val count = readNumber("/proc/sys/net/netfilter/nf_conntrack_count") ?: return null
        val max = readNumber("/proc/sys/net/netfilter/nf_conntrack_max") ?: return null
        return ConntrackUsage(count, max)
    }

    suspend fun services(): List<Service> = parseServices(ubus.call("rc", "list") ?: noData("rc.list"))

    suspend fun serviceAction(name: String, action: ServiceAction) {
        require(Regex(SERVICE_NAME).matches(name)) { "invalid service name: $name" }
        require(action.allowedFor(name)) { "${action.rcAction} of $name would cut the app off from the node" }
        ubus.call(
            "rc",
            "init",
            buildJsonObject {
                put("name", name)
                put("action", action.rcAction)
            },
        )
    }

    suspend fun dhcpConfig(): DhcpConfig = parseDhcpConfig(uciGet("dhcp"))

    suspend fun deleteStaticLease(section: String): WriteOutcome = deleteSection("dhcp", section)

    suspend fun portForwards(): List<PortForward> = parsePortForwards(uciGet("firewall"))

    /** Creates a lease when [section] is null, otherwise updates that `host` section. A MAC or IP may have only one lease. */
    suspend fun saveStaticLease(section: String?, input: StaticLeaseInput): WriteOutcome {
        val values = input.uciValues()
        val others = dhcpConfig().leases.filter { it.section != section }
        require(others.none { values.getValue("mac") in it.macs }) { "${values.getValue("mac")} already has a static lease" }
        require(others.none { it.ip == values.getValue("ip") }) { "${values.getValue("ip")} is already leased statically" }
        val name = section ?: APP_SECTION_PREFIX + "host_" + values.getValue("mac").replace(":", "")
        return write(SectionEdit("dhcp", name, "host", values.strings(), setOf("name") - values.keys, modeFor(section)))
    }

    /** Creates a forward when [section] is null, otherwise updates that `redirect` section. */
    suspend fun savePortForward(section: String?, input: PortForwardInput): WriteOutcome {
        val values = input.uciValues()
        val name = section ?: APP_SECTION_PREFIX + "fwd_" + newId()
        return write(SectionEdit("firewall", name, "redirect", values.strings(), setOf("name") - values.keys, modeFor(section)))
    }

    suspend fun setPortForwardEnabled(section: String, enabled: Boolean): WriteOutcome =
        write(SectionEdit("firewall", section, "redirect", mapOf("enabled" to if (enabled) "1" else "0").strings()))

    suspend fun deletePortForward(section: String): WriteOutcome = deleteSection("firewall", section)

    suspend fun wireless(): WirelessConfig = parseWireless(uciGet("wireless"))

    /** iwinfo answers for a radio by its uci name; a radio that is down has no answers, so the lists stay empty. */
    suspend fun radioCapabilities(radio: String): RadioCapabilities {
        val device = buildJsonObject { put("device", radio) }
        suspend fun optional(method: String): JsonObject? =
            try {
                ubus.call("iwinfo", method, device)
            } catch (e: UbusException) {
                if (e.code == UbusException.NOT_FOUND || e.code == UbusException.NO_DATA) null else throw e
            }
        return parseCapabilities(optional("info"), optional("freqlist"), optional("txpowerlist"))
    }

    suspend fun saveRadio(name: String, input: RadioInput): WriteOutcome {
        val radio = requireNotNull(wireless().radios.firstOrNull { it.name == name }) { "no radio $name" }
        radio.validate(input, radioCapabilities(name))
        val values = buildMap {
            put("channel", input.channel)
            input.htmode?.let { put("htmode", it) }
            input.txpower?.let { put("txpower", it.toString()) }
        }
        return write(SectionEdit("wireless", name, "wifi-device", values.strings(), setOf("htmode", "txpower") - values.keys))
    }

    /** Only AP interfaces: turning off the mesh interface would cut the node off the site. */
    suspend fun setSsidEnabled(section: String, enabled: Boolean): WriteOutcome {
        require(wireless().ssids.any { it.section == section }) { "$section is not an AP interface" }
        return write(
            if (enabled) {
                SectionEdit("wireless", section, "wifi-iface", emptyMap(), setOf("disabled"))
            } else {
                SectionEdit("wireless", section, "wifi-iface", mapOf("disabled" to "1").strings())
            },
        )
    }

    suspend fun dhcpRanges(): List<DhcpRange> = parseDhcpRanges(uciGet("dhcp"), uciGet("network"))

    suspend fun saveDhcpRange(section: String, input: DhcpRangeInput): WriteOutcome {
        val range = requireNotNull(dhcpRanges().firstOrNull { it.section == section }) { "no DHCP pool $section" }
        range.validate(input)
        val values = mapOf("start" to input.start.toString(), "limit" to input.limit.toString(), "leasetime" to input.leasetime)
        return write(SectionEdit("dhcp", section, "dhcp", values.strings()))
    }

    suspend fun wan(): WanConfig? = parseWan(uciGet("network"))

    /**
     * Confirms only once the router itself reaches the internet again: a broken WAN does not cut the phone off the
     * LAN, so reachability alone would keep a bad change.
     */
    suspend fun saveWan(input: WanInput): WriteOutcome {
        val current = requireNotNull(wan()) { "this node has no wan interface" }
        requireNotNull(current.proto) { "wan uses ${current.rawProto}, which the app does not edit" }
        val values: Map<String, JsonElement> = when (val valid = input.validated()) {
            WanInput.Dhcp -> mapOf("proto" to JsonPrimitive("dhcp"))
            is WanInput.Static -> mapOf(
                "proto" to JsonPrimitive("static"),
                "ipaddr" to JsonPrimitive(valid.ipaddr),
                "netmask" to JsonPrimitive(valid.netmask),
                "gateway" to JsonPrimitive(valid.gateway),
            ) + if (valid.dns.isEmpty()) emptyMap() else mapOf("dns" to JsonArray(valid.dns.map(::JsonPrimitive)))
            is WanInput.Pppoe -> listOfNotNull(
                "proto" to JsonPrimitive("pppoe"),
                "username" to JsonPrimitive(valid.username),
                valid.password?.let { "password" to JsonPrimitive(it) },
            ).toMap()
        }
        val kept = if (input is WanInput.Pppoe && input.password == null) setOf("password") else emptySet()
        val edit = SectionEdit("network", "wan", "interface", values, WAN_PROTO_OPTIONS - values.keys - kept)
        return write(edit) { ubus.exec(PING_PATH, listOf("-c", "1", "-W", "2", WAN_PROBE)).exitCode == 0 }
    }

    suspend fun system(): SystemConfig = parseSystem(uciGet("system")) ?: noData("uci.get system")

    suspend fun timezones(): Map<String, String> = parseTimezones(ubus.call("luci", "getTimezones") ?: noData("luci.getTimezones"))

    suspend fun saveSystem(input: SystemInput): WriteOutcome {
        val zones = timezones()
        input.validate(zones)
        val current = system()
        val system = mapOf("hostname" to input.hostname, "zonename" to input.zonename, "timezone" to zones.getValue(input.zonename))
        val ntp = mapOf(
            "enabled" to JsonPrimitive(if (input.ntpEnabled) "1" else "0"),
            "server" to JsonArray(input.ntpServers.map(::JsonPrimitive)),
        )
        return write(
            SectionEdit("system", current.section, "system", system.strings()),
            SectionEdit("system", "ntp", "timeserver", ntp, mode = EditMode.UPSERT),
        )
    }

    suspend fun leds(): List<Led> = parseLeds(ubus.call("luci", "getLEDs") ?: noData("luci.getLEDs"), uciGet("system"))

    /** Writes the LED's existing `led` section if the user made one, otherwise the app's own. */
    suspend fun saveLed(name: String, input: LedInput): WriteOutcome {
        val led = requireNotNull(leds().firstOrNull { it.name == name }) { "no LED $name" }
        led.validate(input)
        val values = input.uciValues(name)
        val section = led.section ?: ledSectionName(name)
        return write(SectionEdit("system", section, "led", values.strings(), setOf("dev", "mode") - values.keys, EditMode.UPSERT))
    }

    /** An absent crontab is an empty one. */
    suspend fun crontab(): String =
        try {
            ubus.call("file", "read", buildJsonObject { put("path", CRONTAB) })?.str("data").orEmpty()
        } catch (e: UbusException) {
            if (e.code == UbusException.NOT_FOUND) "" else throw e
        }

    /** A plain file write: there is no rollback, so the screen confirms first. LuCI reloads cron the same way. */
    suspend fun saveCrontab(text: String) {
        requireValidCrontab(text)
        val data = if (text.isEmpty() || text.endsWith("\n")) text else "$text\n"
        ubus.call(
            "file",
            "write",
            buildJsonObject {
                put("path", CRONTAB)
                put("data", data)
            },
        )
        ubus.exec("/etc/init.d/cron", listOf("reload")).successOrThrow("cron reload")
    }

    private enum class EditMode { CREATE, UPDATE, UPSERT }

    private fun modeFor(section: String?) = if (section == null) EditMode.CREATE else EditMode.UPDATE

    /** Sets [values] on one section and deletes its [cleared] options, so emptied fields do not keep old values. */
    private data class SectionEdit(
        val config: String,
        val section: String,
        val type: String,
        val values: Map<String, JsonElement>,
        val cleared: Set<String> = emptySet(),
        val mode: EditMode = EditMode.UPDATE,
    )

    private fun Map<String, String>.strings(): Map<String, JsonElement> = mapValues { JsonPrimitive(it.value) }

    /**
     * Stages only what differs from the current config, then applies all [edits] with one rollback timer.
     * Nothing staged means nothing to apply (`uci apply` fails on an empty change set). A failure while staging
     * reverts what was staged, so the next write does not find it pending.
     */
    private suspend fun write(vararg edits: SectionEdit, check: suspend () -> Boolean = { true }): WriteOutcome {
        ubus.requireNoPendingChanges()
        val configs = edits.map { it.config }.distinct().associateWith { uciGet(it).obj("values").orEmpty() }
        var staged = false
        try {
            for (edit in edits) {
                val current = configs.getValue(edit.config)[edit.section]?.asObject()
                if (current == null) {
                    require(edit.mode != EditMode.UPDATE) { "section ${edit.section} not found in ${edit.config}" }
                    ubus.call(
                        "uci",
                        "add",
                        buildJsonObject {
                            put("config", edit.config)
                            put("type", edit.type)
                            put("name", edit.section)
                            put("values", JsonObject(edit.values))
                        },
                    )
                    staged = true
                    continue
                }
                require(edit.mode != EditMode.CREATE) { "section ${edit.section} already exists in ${edit.config}" }
                require(current.str(".type") == edit.type) { "section ${edit.section} in ${edit.config} is not a ${edit.type}" }
                val changed = edit.values.filter { (key, value) -> current[key] != value }
                if (changed.isNotEmpty()) {
                    ubus.call(
                        "uci",
                        "set",
                        buildJsonObject {
                            put("config", edit.config)
                            put("section", edit.section)
                            put("values", JsonObject(changed))
                        },
                    )
                    staged = true
                }
                val removed = edit.cleared.filter { current.containsKey(it) }
                if (removed.isNotEmpty()) {
                    ubus.call(
                        "uci",
                        "delete",
                        buildJsonObject {
                            put("config", edit.config)
                            put("section", edit.section)
                            putJsonArray("options") { removed.forEach { add(it) } }
                        },
                    )
                    staged = true
                }
            }
        } catch (e: Exception) {
            if (staged) configs.keys.forEach { config -> runCatching { ubus.call("uci", "revert", buildJsonObject { put("config", config) }) } }
            throw e
        }
        if (!staged) return WriteOutcome.UNCHANGED
        return ubus.applyWithRollback(rollbackSeconds, check)
    }

    private suspend fun deleteSection(config: String, section: String): WriteOutcome {
        ubus.requireNoPendingChanges()
        if (uciGet(config).obj("values")?.obj(section) == null) return WriteOutcome.UNCHANGED
        ubus.call(
            "uci",
            "delete",
            buildJsonObject {
                put("config", config)
                put("section", section)
            },
        )
        return ubus.applyWithRollback(rollbackSeconds)
    }

    private suspend fun uciGet(config: String): JsonObject =
        ubus.call("uci", "get", buildJsonObject { put("config", config) }) ?: noData("uci.get $config")

    private suspend fun readNumber(path: String): Long? =
        try {
            ubus.call("file", "read", buildJsonObject { put("path", path) })?.str("data")?.trim()?.toLongOrNull()
        } catch (e: UbusException) {
            // Kernels without conntrack have no such file; the list is still useful without the usage line.
            if (e.code == UbusException.NOT_FOUND) null else throw e
        }

    private fun ExecResult.successOrThrow(what: String): String {
        if (exitCode != 0) throw UbusException(UbusException.NO_DATA, "$what exited with $exitCode: ${stderr.trim().take(200)}")
        return stdout
    }

    private fun noData(what: String): Nothing = throw UbusException(UbusException.NO_DATA, "$what returned no data")
}
