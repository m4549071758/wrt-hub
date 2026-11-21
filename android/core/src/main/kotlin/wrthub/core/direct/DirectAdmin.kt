package wrthub.core.direct

import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.putJsonArray
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
        return writeSection("dhcp", "host", name, values, create = section == null, cleared = setOf("name") - values.keys)
    }

    /** Creates a forward when [section] is null, otherwise updates that `redirect` section. */
    suspend fun savePortForward(section: String?, input: PortForwardInput): WriteOutcome {
        val values = input.uciValues()
        val name = section ?: APP_SECTION_PREFIX + "fwd_" + newId()
        return writeSection("firewall", "redirect", name, values, create = section == null, cleared = setOf("name") - values.keys)
    }

    suspend fun setPortForwardEnabled(section: String, enabled: Boolean): WriteOutcome =
        writeSection("firewall", "redirect", section, mapOf("enabled" to if (enabled) "1" else "0"), create = false)

    suspend fun deletePortForward(section: String): WriteOutcome = deleteSection("firewall", section)

    /** Sets [values] on the section and, when updating, deletes the [cleared] options the user emptied. */
    private suspend fun writeSection(
        config: String,
        type: String,
        section: String,
        values: Map<String, String>,
        create: Boolean,
        cleared: Set<String> = emptySet(),
    ): WriteOutcome {
        ubus.requireNoPendingChanges()
        val current = uciGet(config).obj("values")?.obj(section)
        if (create) {
            require(current == null) { "section $section already exists in $config" }
            ubus.call(
                "uci",
                "add",
                buildJsonObject {
                    put("config", config)
                    put("type", type)
                    put("name", section)
                    putJsonObject("values") { values.forEach { (k, v) -> put(k, v) } }
                },
            )
        } else {
            requireNotNull(current) { "section $section not found in $config" }
            require(current.str(".type") == type) { "section $section in $config is not a $type" }
            val removed = cleared.filter { current.containsKey(it) }
            // `uci apply` fails when nothing is staged, so identical values must not reach it.
            if (removed.isEmpty() && values.all { (k, v) -> current.str(k) == v }) return WriteOutcome.UNCHANGED
            ubus.call(
                "uci",
                "set",
                buildJsonObject {
                    put("config", config)
                    put("section", section)
                    putJsonObject("values") { values.forEach { (k, v) -> put(k, v) } }
                },
            )
            if (removed.isNotEmpty()) {
                ubus.call(
                    "uci",
                    "delete",
                    buildJsonObject {
                        put("config", config)
                        put("section", section)
                        putJsonArray("options") { removed.forEach { add(it) } }
                    },
                )
            }
        }
        return ubus.applyWithRollback(rollbackSeconds)
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
