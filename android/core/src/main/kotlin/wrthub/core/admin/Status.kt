package wrthub.core.admin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import wrthub.core.json.arr
import wrthub.core.json.asObject
import wrthub.core.json.bool
import wrthub.core.json.int
import wrthub.core.json.long
import wrthub.core.json.str

/** Syslog severity: 0 emergency … 7 debug. */
data class LogLine(
    /** Wall clock in epoch milliseconds; null for kernel lines, which only know the time since boot. */
    val epochMillis: Long?,
    val bootSeconds: Double?,
    val severity: Int,
    val message: String,
)

/** Parses `ubus call log read`, oldest first as logd returns it. */
fun parseSyslog(response: JsonObject): List<LogLine> =
    response.arr("log").orEmpty().mapNotNull { it.asObject() }.mapNotNull { o ->
        val message = o.str("msg") ?: return@mapNotNull null
        LogLine(o.long("time"), null, (o.int("priority") ?: 6) and 7, message)
    }

private val DMESG_LINE = Regex("""^<(\d+)>\[\s*([0-9.]+)]\s?(.*)$""")

/** Parses `dmesg -r`, whose lines look like `<6>[  12.345678] message`. Lines without that prefix are kept as-is. */
fun parseDmesg(text: String): List<LogLine> =
    text.lineSequence().filter { it.isNotBlank() }.map { line ->
        DMESG_LINE.matchEntire(line)?.let { m ->
            LogLine(null, m.groupValues[2].toDoubleOrNull(), m.groupValues[1].toInt() and 7, m.groupValues[3])
        } ?: LogLine(null, null, 6, line)
    }.toList()

enum class IpFamily(val flag: String) { V4("-4"), V6("-6") }

data class Route(
    val table: String,
    val destination: String,
    val gateway: String?,
    val device: String?,
    val protocol: String?,
    val metric: Int?,
    /** `unicast` when ip omits it; otherwise `local`, `broadcast`, `unreachable`, … */
    val type: String,
)

private val lenientJson = Json { ignoreUnknownKeys = true }

/** Parses `ip -j route show table all`; ip leaves out `table` for the main table and `type` for unicast. */
fun parseRoutes(json: String): List<Route> =
    (lenientJson.parseToJsonElement(json) as? JsonArray).orEmpty().mapNotNull { it.asObject() }.mapNotNull { o ->
        val destination = o.str("dst") ?: return@mapNotNull null
        Route(
            table = o.str("table") ?: "main",
            destination = destination,
            gateway = o.str("gateway"),
            device = o.str("dev"),
            protocol = o.str("protocol"),
            metric = o.int("metric"),
            type = o.str("type") ?: "unicast",
        )
    }

/** Either parsed routes, or the plain-text table when the node's `ip` cannot print JSON (busybox). */
sealed interface RouteTable {
    data class Parsed(val routes: List<Route>) : RouteTable

    data class Text(val text: String) : RouteTable
}

data class Connection(
    val family: String,
    val protocol: String,
    val source: String,
    val destination: String,
    val sourcePort: Int?,
    val destinationPort: Int?,
    val bytes: Long,
    val packets: Long,
)

/** Parses `luci getConntrackList`; ICMP and other portless protocols have no ports. */
fun parseConntrack(response: JsonObject): List<Connection> =
    response.arr("result").orEmpty().mapNotNull { it.asObject() }.mapNotNull { o ->
        Connection(
            family = o.str("layer3") ?: return@mapNotNull null,
            protocol = o.str("layer4") ?: return@mapNotNull null,
            source = o.str("src") ?: return@mapNotNull null,
            destination = o.str("dst") ?: return@mapNotNull null,
            sourcePort = o.str("sport")?.toIntOrNull() ?: o.int("sport"),
            destinationPort = o.str("dport")?.toIntOrNull() ?: o.int("dport"),
            bytes = o.long("bytes") ?: 0,
            packets = o.long("packets") ?: 0,
        )
    }

data class ConntrackUsage(val count: Long, val max: Long)

data class Service(
    val name: String,
    val enabled: Boolean,
    /** Null for init scripts without a procd instance (one-shot scripts such as `boot`), which have no running state. */
    val running: Boolean?,
)

/** Parses `rc list`, sorted by name. */
fun parseServices(response: JsonObject): List<Service> =
    response.entries.mapNotNull { (name, value) ->
        val o = value.asObject() ?: return@mapNotNull null
        Service(name, o.bool("enabled") ?: false, o.bool("running"))
    }.sortedBy { it.name }

enum class ServiceAction(val rcAction: String) {
    START("start"),
    STOP("stop"),
    RESTART("restart"),
    ENABLE("enable"),
    DISABLE("disable"),
}

/** Stopping or disabling these cuts the app (and LuCI) off from the node, so the app never offers it. */
val ESSENTIAL_SERVICES = setOf("rpcd", "uhttpd", "network", "firewall", "dnsmasq")

fun ServiceAction.allowedFor(service: String): Boolean =
    service !in ESSENTIAL_SERVICES || this != ServiceAction.STOP && this != ServiceAction.DISABLE
