package wrthub.core.model

import kotlinx.serialization.json.JsonObject
import wrthub.core.json.arr
import wrthub.core.json.asLong
import wrthub.core.json.asObject
import wrthub.core.json.bool
import wrthub.core.json.int
import wrthub.core.json.long
import wrthub.core.json.obj
import wrthub.core.json.str
import wrthub.core.snapshot.Snapshot
import wrthub.core.snapshot.WirelessRaw

data class NodeSummary(
    val hostname: String,
    val model: String,
    val firmware: String,
    val uptimeSeconds: Long,
    val load1: Double,
    val memoryTotalBytes: Long,
    val memoryAvailableBytes: Long,
)

data class NetInterface(
    val name: String,
    val up: Boolean,
    val proto: String,
    val device: String?,
    val ipv4: List<String>,
    val uptimeSeconds: Long?,
)

data class DeviceCounters(val rxBytes: Long, val txBytes: Long)

enum class WirelessMode { AP, MESH, OTHER }

data class Station(
    val mac: String,
    val signal: Int?,
    val signalAvg: Int?,
    val expectedThroughputKbps: Long?,
    val meshPlink: String?,
    val connectedSeconds: Long?,
)

data class WirelessInterface(
    val ifname: String,
    val mode: WirelessMode,
    val ssid: String?,
    val channel: Int?,
    val frequencyMhz: Int?,
    val macaddr: String?,
    val stations: List<Station>,
)

data class Lease(val mac: String, val ipv4: String, val hostname: String?)

data class UsteerSighting(val nodeAddress: String?, val apObject: String, val connected: Boolean, val signal: Int?)

data class Neighbor(val ipv4: String, val device: String?, val mac: String, val state: String)

private val LIVE_NEIGHBOR_STATES = setOf("REACHABLE", "STALE", "DELAY", "PROBE", "PERMANENT", "NOARP")

/** STALE counts as live: the kernel keeps it for minutes after the last traffic, until garbage collection. */
val Neighbor.live: Boolean get() = state in LIVE_NEIGHBOR_STATES

private const val LOAD_SCALE = 65536.0

fun Snapshot.summary(): NodeSummary {
    val memory = info.obj("memory")
    return NodeSummary(
        hostname = board.str("hostname").orEmpty(),
        model = board.str("model").orEmpty(),
        firmware = board.obj("release")?.str("version").orEmpty(),
        uptimeSeconds = info.long("uptime") ?: 0,
        load1 = (info.arr("load")?.firstOrNull()?.asLong() ?: 0) / LOAD_SCALE,
        memoryTotalBytes = memory?.long("total") ?: 0,
        memoryAvailableBytes = memory?.long("available") ?: 0,
    )
}

fun Snapshot.netInterfaces(): List<NetInterface> =
    interfaces.arr("interface").orEmpty().mapNotNull { it.asObject() }.map { o ->
        NetInterface(
            name = o.str("interface").orEmpty(),
            up = o.bool("up") ?: false,
            proto = o.str("proto").orEmpty(),
            device = o.str("l3_device") ?: o.str("device"),
            ipv4 = o.arr("ipv4-address").orEmpty().mapNotNull { it.asObject()?.str("address") },
            uptimeSeconds = o.long("uptime"),
        )
    }

fun Snapshot.lanIpv4(): String? = netInterfaces().firstOrNull { it.name == "lan" }?.ipv4?.firstOrNull()

fun parseDeviceCounters(devices: JsonObject): Map<String, DeviceCounters> =
    devices.entries.mapNotNull { (name, value) ->
        val stats = value.asObject()?.obj("statistics") ?: return@mapNotNull null
        name to DeviceCounters(stats.long("rx_bytes") ?: 0, stats.long("tx_bytes") ?: 0)
    }.toMap()

fun Snapshot.deviceCounters(): Map<String, DeviceCounters> = parseDeviceCounters(devices)

/** The L3 device of the `wan` interface while it is up; null on nodes without WAN such as dumb APs. */
fun Snapshot.wanDevice(): String? = netInterfaces().firstOrNull { it.name == "wan" && it.up }?.device

fun Snapshot.wirelessInterfaces(): List<WirelessInterface> = wireless.map { it.toInterface(devices) }

private fun WirelessRaw.toInterface(devices: JsonObject): WirelessInterface = WirelessInterface(
    ifname = ifname,
    mode = when (info.str("mode")) {
        "Master" -> WirelessMode.AP
        "Mesh Point" -> WirelessMode.MESH
        else -> WirelessMode.OTHER
    },
    ssid = info.str("ssid"),
    channel = info.int("channel"),
    frequencyMhz = info.int("frequency"),
    macaddr = (devices.obj(ifname)?.str("macaddr") ?: info.str("bssid"))?.lowercase(),
    stations = assoclist.arr("results").orEmpty().mapNotNull { it.asObject() }.mapNotNull { s ->
        val mac = s.str("mac")?.lowercase() ?: return@mapNotNull null
        Station(
            mac = mac,
            signal = s.int("signal"),
            signalAvg = s.int("signal_avg"),
            expectedThroughputKbps = s.long("thr"),
            meshPlink = s.str("mesh plink"),
            connectedSeconds = s.long("connected_time"),
        )
    },
)

fun Snapshot.leases(): List<Lease> =
    dhcpLeases?.arr("dhcp_leases").orEmpty().mapNotNull { it.asObject() }.mapNotNull { o ->
        val mac = o.str("macaddr")?.lowercase() ?: return@mapNotNull null
        val ip = o.str("ipaddr") ?: return@mapNotNull null
        Lease(mac, ip, o.str("hostname"))
    }

fun Snapshot.hostNames(): Map<String, String> =
    hostHints?.entries.orEmpty().mapNotNull { (mac, value) ->
        value.asObject()?.str("name")?.let { mac.lowercase() to it }
    }.toMap()

fun Snapshot.usteerSightings(): Map<String, List<UsteerSighting>> =
    usteerClients?.entries.orEmpty().associate { (mac, perAp) ->
        mac.lowercase() to perAp.asObject()?.entries.orEmpty().mapNotNull { (key, value) ->
            val o = value.asObject() ?: return@mapNotNull null
            UsteerSighting(
                nodeAddress = key.substringBefore('#', missingDelimiterValue = "").ifEmpty { null },
                apObject = key.substringAfter('#'),
                connected = o.bool("connected") ?: false,
                signal = o.int("signal"),
            )
        }
    }

/** Entries of the kernel neighbour (ARP) table that carry a MAC; FAILED and INCOMPLETE entries have none. */
fun Snapshot.neighborEntries(): List<Neighbor> =
    neighbors.orEmpty().lineSequence().mapNotNull { line ->
        val tokens = line.trim().split(WHITESPACE)
        val mac = tokens.valueAfter("lladdr")?.lowercase() ?: return@mapNotNull null
        Neighbor(ipv4 = tokens.first(), device = tokens.valueAfter("dev"), mac = mac, state = tokens.last())
    }.toList()

private val WHITESPACE = Regex("\\s+")

private fun List<String>.valueAfter(key: String): String? = indexOf(key).takeIf { it >= 0 }?.let { getOrNull(it + 1) }

/** MACs of the node's own network devices, so a node never shows up as a client. */
fun Snapshot.ownMacs(): Set<String> =
    devices.values.mapNotNull { it.asObject()?.str("macaddr")?.lowercase() }
        .filterNot { it == "00:00:00:00:00:00" }
        .toSet()

/** L3 devices of upstream interfaces; OpenWrt names them `wan*`, and `wwan*` for cellular modems. */
fun Snapshot.upstreamDevices(): Set<String> =
    netInterfaces().filter { it.name.startsWith("wan") || it.name.startsWith("wwan") }.mapNotNull { it.device }.toSet()
