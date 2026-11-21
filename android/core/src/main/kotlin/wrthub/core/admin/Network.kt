package wrthub.core.admin

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import wrthub.core.json.asObject
import wrthub.core.json.obj
import wrthub.core.json.str

/** The address pool of the `dhcp` section serving [network], next to that network's address for context. */
data class DhcpRange(
    val section: String,
    val network: String,
    val start: Int,
    val limit: Int,
    val leasetime: String,
    /** CIDR of the network's IPv4 address (`192.168.1.1/24`); null when the network has no static address. */
    val subnet: String?,
)

data class DhcpRangeInput(val start: Int, val limit: Int, val leasetime: String)

/** The pools of the DHCP sections this node serves; uci's defaults (100, 150, 12h) stand in for missing options. */
fun parseDhcpRanges(dhcp: JsonObject, network: JsonObject): List<DhcpRange> {
    val interfaces = network.obj("values")
    return dhcp.obj("values")?.values.orEmpty().mapNotNull { it.asObject() }
        .filter { it.str(".type") == "dhcp" && it.str("ignore") != "1" }
        .mapNotNull { o ->
            val net = o.str("interface") ?: return@mapNotNull null
            DhcpRange(
                section = o.str(".name") ?: return@mapNotNull null,
                network = net,
                start = o.str("start")?.toIntOrNull() ?: 100,
                limit = o.str("limit")?.toIntOrNull() ?: 150,
                leasetime = o.str("leasetime") ?: "12h",
                subnet = interfaces?.obj(net)?.let(::ipv4Cidr),
            )
        }
}

/** `ipaddr` with `netmask`, or an `ipaddr` that is already CIDR (a list on newer configs: the first entry). */
private fun ipv4Cidr(iface: JsonObject): String? {
    val raw = (iface["ipaddr"] as? JsonArray)?.firstOrNull()?.let { stringList(it).firstOrNull() } ?: iface.str("ipaddr") ?: return null
    if ('/' in raw) return raw
    val mask = iface.str("netmask") ?: return null
    return "$raw/${prefixLength(mask) ?: return null}"
}

/** The prefix length of a contiguous netmask (`255.255.255.0` → 24); null for anything else. */
fun prefixLength(mask: String): Int? {
    val bits = ipv4ToLong(mask) ?: return null
    val prefix = java.lang.Long.bitCount(bits)
    return prefix.takeIf { bits == (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL }
}

/** The pool's first and last address, counted from the network address like dnsmasq does; null without a subnet. */
fun DhcpRange.addresses(): Pair<String, String>? {
    val cidr = subnet ?: return null
    val ip = ipv4ToLong(cidr.substringBefore('/')) ?: return null
    val prefix = cidr.substringAfter('/').toIntOrNull() ?: return null
    val network = ip and ((0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL)
    return longToIpv4(network + start) to longToIpv4(network + start + limit - 1)
}

private fun longToIpv4(value: Long): String = (3 downTo 0).joinToString(".") { ((value shr (8 * it)) and 0xFF).toString() }

internal fun ipv4ToLong(ip: String): Long? {
    val parts = ip.split('.').map { it.toIntOrNull() ?: return null }
    if (parts.size != 4 || parts.any { it !in 0..255 }) return null
    return parts.fold(0L) { acc, part -> acc shl 8 or part.toLong() }
}

private val LEASETIME = Regex("^(infinite|\\d{1,7}[smhdw]?)$")

/** The pool must fit in the subnet after the network address and before broadcast, as dnsmasq counts from it. */
fun DhcpRange.validate(input: DhcpRangeInput) {
    require(input.start >= 1) { "start must be at least 1" }
    require(input.limit >= 1) { "limit must be at least 1" }
    require(LEASETIME.matches(input.leasetime)) { "invalid lease time: ${input.leasetime}" }
    val prefix = subnet?.substringAfter('/')?.toIntOrNull() ?: return
    val hosts = (1L shl (32 - prefix)) - 2
    require(input.start.toLong() + input.limit - 1 <= hosts) { "the pool ends beyond the $subnet subnet" }
}

enum class WanProto { DHCP, STATIC, PPPOE }

/** The `wan` interface. [proto] is null for protocols the app shows but does not edit (ipip6, dslite, …). */
data class WanConfig(
    val rawProto: String?,
    val proto: WanProto?,
    val ipaddr: String?,
    val netmask: String?,
    val gateway: String?,
    val dns: List<String>,
    val username: String?,
    /** The PPPoE password is never read into the app; only whether one is set. */
    val hasPassword: Boolean,
)

fun parseWan(network: JsonObject): WanConfig? {
    val wan = network.obj("values")?.obj("wan") ?: return null
    val raw = wan.str("proto")
    return WanConfig(
        rawProto = raw,
        proto = when (raw) {
            "dhcp" -> WanProto.DHCP
            "static" -> WanProto.STATIC
            "pppoe" -> WanProto.PPPOE
            else -> null
        },
        ipaddr = wan.str("ipaddr"),
        netmask = wan.str("netmask"),
        gateway = wan.str("gateway"),
        dns = stringList(wan["dns"]),
        username = wan.str("username"),
        hasPassword = !wan.str("password").isNullOrEmpty(),
    )
}

sealed interface WanInput {
    data object Dhcp : WanInput

    data class Static(val ipaddr: String, val netmask: String, val gateway: String, val dns: List<String>) : WanInput

    /** [password] null keeps the current one. */
    data class Pppoe(val username: String, val password: String?) : WanInput
}

/** Options belonging to one of the editable protocols; switching protocol clears the others'. */
internal val WAN_PROTO_OPTIONS = setOf("ipaddr", "netmask", "gateway", "dns", "username", "password")

internal fun WanInput.validated(): WanInput = also {
    when (this) {
        WanInput.Dhcp -> Unit
        is WanInput.Static -> {
            requireValidIpv4(ipaddr)
            require(prefixLength(netmask) != null) { "invalid netmask: $netmask" }
            requireValidIpv4(gateway)
            dns.forEach(::requireValidIpv4)
        }
        is WanInput.Pppoe -> {
            require(username.isNotEmpty() && username.length <= 128 && username.none(Char::isISOControl)) { "invalid PPPoE user name" }
            password?.let { require(it.isNotEmpty() && it.length <= 128 && it.none(Char::isISOControl)) { "invalid PPPoE password" } }
        }
    }
}
