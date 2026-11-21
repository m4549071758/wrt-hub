package wrthub.core.admin

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import wrthub.core.direct.normalizeMac
import wrthub.core.json.asObject
import wrthub.core.json.asString
import wrthub.core.json.obj
import wrthub.core.json.int
import wrthub.core.json.str

/** Sections the app creates get this prefix, the same convention as client blocks. */
const val APP_SECTION_PREFIX = "wrthub_"

/** A `dhcp host` section. Several MACs on one host is a LuCI feature the app shows but does not edit. */
data class StaticLease(
    val section: String,
    val name: String?,
    val macs: List<String>,
    val ip: String?,
) {
    val editable: Boolean get() = macs.size == 1
}

data class StaticLeaseInput(val name: String?, val mac: String, val ip: String)

/** The node's `uci get dhcp` response, read once for both the leases and whether this node serves DHCP. */
data class DhcpConfig(val servesDhcp: Boolean, val leases: List<StaticLease>)

fun parseDhcpConfig(uciGet: JsonObject): DhcpConfig {
    val sections = uciGet.obj("values")?.values.orEmpty().mapNotNull { it.asObject() }
    return DhcpConfig(
        servesDhcp = sections.any { it.str(".type") == "dhcp" && it.str("ignore") != "1" && it.str("interface") != null },
        leases = sections.filter { it.str(".type") == "host" }.mapNotNull { o ->
            val section = o.str(".name") ?: return@mapNotNull null
            StaticLease(section, o.str("name"), stringList(o["mac"]).map { it.lowercase() }, o.str("ip"))
        }.sortedWith(compareBy({ it.name ?: "\uFFFF" }, { it.section })),
    )
}

/** Static lease names by MAC, so a name given on the router wins over the DHCP client's own hostname. */
fun staticLeaseNames(uciGet: JsonObject): Map<String, String> =
    parseDhcpConfig(uciGet).leases.flatMap { lease -> lease.name?.let { name -> lease.macs.map { it to name } }.orEmpty() }
        .toMap()

private val HOSTNAME = Regex("^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
private val IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$")

fun requireValidHostname(name: String): String {
    require(HOSTNAME.matches(name)) { "invalid host name: $name" }
    return name
}

fun requireValidIpv4(ip: String): String {
    require(IPV4.matches(ip)) { "invalid IPv4 address: $ip" }
    return ip
}

/** The uci options the app writes for a lease; everything else in the section (instance, leasetime, …) is kept. */
fun StaticLeaseInput.uciValues(): Map<String, String> = buildMap {
    name?.let { put("name", requireValidHostname(it)) }
    put("mac", normalizeMac(mac))
    put("ip", requireValidIpv4(ip))
}

enum class ForwardProtocol(val uci: String) { TCP("tcp"), UDP("udp"), BOTH("tcp udp") }

/** A DNAT `redirect`: traffic arriving on [externalPort] from the WAN goes to [internalIp]:[internalPort]. */
data class PortForward(
    val section: String,
    val name: String?,
    /** Null for protocols the app does not edit (icmp, all, …). */
    val protocol: ForwardProtocol?,
    val externalPort: String?,
    val internalIp: String?,
    val internalPort: String?,
    val enabled: Boolean,
    /** False when the rule uses options the app cannot show (source IP limits, reflection, other zones, …). */
    val editable: Boolean,
)

data class PortForwardInput(
    val name: String?,
    val protocol: ForwardProtocol,
    val externalPort: String,
    val internalIp: String,
    val internalPort: String,
)

private val EDITABLE_OPTIONS = setOf("name", "src", "dest", "target", "proto", "src_dport", "dest_ip", "dest_port", "enabled")

/** Parses the DNAT redirects of a `uci get firewall` response, in config order. */
fun parsePortForwards(uciGet: JsonObject): List<PortForward> =
    uciGet.obj("values")?.values.orEmpty().mapNotNull { it.asObject() }
        .filter { it.str(".type") == "redirect" && (it.str("target") ?: "DNAT") == "DNAT" }
        .sortedBy { it.int(".index") ?: Int.MAX_VALUE }
        .mapNotNull { o ->
            val section = o.str(".name") ?: return@mapNotNull null
            val protocol = forwardProtocol(o["proto"])
            val options = o.keys.filterNot { it.startsWith(".") }.toSet()
            PortForward(
                section = section,
                name = o.str("name"),
                protocol = protocol,
                externalPort = o.str("src_dport"),
                internalIp = o.str("dest_ip"),
                internalPort = o.str("dest_port"),
                enabled = o.str("enabled") != "0",
                editable = protocol != null && options.all { it in EDITABLE_OPTIONS } &&
                    (o.str("src") ?: "wan") == "wan" && (o.str("dest") ?: "lan") == "lan",
            )
        }

private fun forwardProtocol(element: JsonElement?): ForwardProtocol? {
    if (element == null) return ForwardProtocol.BOTH
    val protocols = stringList(element).flatMap { it.split(' ') }.filter { it.isNotEmpty() }.toSet()
    return when (protocols) {
        setOf("tcp") -> ForwardProtocol.TCP
        setOf("udp") -> ForwardProtocol.UDP
        setOf("tcp", "udp"), setOf("tcpudp") -> ForwardProtocol.BOTH
        else -> null
    }
}

private val PORT_RANGE = Regex("^(\\d{1,5})(-(\\d{1,5}))?$")

fun requireValidPortRange(port: String): String {
    val match = PORT_RANGE.matchEntire(port)
    val low = match?.groupValues?.get(1)?.toIntOrNull()
    val high = match?.groupValues?.get(3)?.ifEmpty { null }?.toIntOrNull() ?: low
    require(low != null && high != null && low in 1..65535 && high in low..65535) { "invalid port or port range: $port" }
    return port
}

/** The uci options the app writes for a forward. The rule is always WAN → LAN DNAT. */
fun PortForwardInput.uciValues(): Map<String, String> = buildMap {
    name?.let { put("name", it.also { n -> require(n.length in 1..64 && n.none(Char::isISOControl)) { "invalid name" } }) }
    put("src", "wan")
    put("dest", "lan")
    put("target", "DNAT")
    put("proto", protocol.uci)
    put("src_dport", requireValidPortRange(externalPort))
    put("dest_ip", requireValidIpv4(internalIp))
    put("dest_port", requireValidPortRange(internalPort))
}

/** A uci option is a string, or a list for options such as `mac`; a string list is space-separated. */
internal fun stringList(element: JsonElement?): List<String> = when (element) {
    null -> emptyList()
    is JsonArray -> element.mapNotNull { it.asString() }
    else -> element.asString()?.split(' ')?.filter { it.isNotEmpty() }.orEmpty()
}
