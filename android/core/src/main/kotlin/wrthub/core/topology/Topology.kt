package wrthub.core.topology

import wrthub.core.model.UsteerSighting
import wrthub.core.model.WirelessInterface
import wrthub.core.model.WirelessMode
import wrthub.core.model.hostNames
import wrthub.core.model.live
import wrthub.core.model.neighborEntries
import wrthub.core.model.ownMacs
import wrthub.core.model.netInterfaces
import wrthub.core.model.upstreamDevices
import wrthub.core.model.lanIpv4
import wrthub.core.model.leases
import wrthub.core.model.usteerSightings
import wrthub.core.model.wirelessInterfaces
import wrthub.core.snapshot.Snapshot

data class LinkMetrics(val signalAvg: Int?, val expectedThroughputKbps: Long?)

data class MeshLink(val a: String, val b: String, val aToB: LinkMetrics?, val bToA: LinkMetrics?)

data class Sighting(val nodeId: String, val connected: Boolean, val signal: Int?)

enum class Presence {
    /** Associated with an AP interface of one of the site's nodes. */
    WIRELESS,

    /**
     * On the LAN now (a live entry in a node's neighbour table) but not associated with any AP of the site:
     * wired, or behind an AP that is not a registered OpenWrt node.
     */
    ONLINE_UNKNOWN_LINK,

    /** Only a DHCP lease remains: the device left recently. */
    LEASE_ONLY,

    /** Known only because wrt-hub blocks it. */
    OFFLINE,
}

data class SiteClient(
    val mac: String,
    val name: String?,
    val ipv4: String?,
    val presence: Presence,
    val attachedTo: String?,
    val ifname: String?,
    val signal: Int?,
    val blocked: Boolean,
    val sightings: List<Sighting>,
    /** Name of the node's logical interface (`lan`, `guest`, …) whose L3 device has the client as a neighbour. */
    val network: String? = null,
)

data class SiteTopology(val nodeIds: List<String>, val links: List<MeshLink>, val clients: List<SiteClient>)

fun buildTopology(snapshots: Map<String, Snapshot>): SiteTopology {
    val wireless = snapshots.mapValues { (_, snapshot) -> snapshot.wirelessInterfaces() }
    return SiteTopology(snapshots.keys.sorted(), meshLinks(wireless), clients(snapshots, wireless))
}

private fun meshLinks(wireless: Map<String, List<WirelessInterface>>): List<MeshLink> {
    val meshOwner = wireless.flatMap { (node, ifaces) ->
        ifaces.filter { it.mode == WirelessMode.MESH }.mapNotNull { iface -> iface.macaddr?.let { it to node } }
    }.toMap()
    val directed = mutableMapOf<Pair<String, String>, LinkMetrics>()
    for ((node, ifaces) in wireless) {
        for (iface in ifaces.filter { it.mode == WirelessMode.MESH }) {
            for (station in iface.stations) {
                if (station.meshPlink != "ESTAB") continue
                val peer = meshOwner[station.mac] ?: continue
                if (peer == node) continue
                directed[node to peer] = LinkMetrics(station.signalAvg ?: station.signal, station.expectedThroughputKbps)
            }
        }
    }
    return directed.keys
        .map { (x, y) -> if (x < y) x to y else y to x }
        .distinct()
        .sortedWith(compareBy({ it.first }, { it.second }))
        .map { (a, b) -> MeshLink(a, b, directed[a to b], directed[b to a]) }
}

private data class Attachment(val node: String, val ifname: String, val signal: Int?)

private fun clients(snapshots: Map<String, Snapshot>, wireless: Map<String, List<WirelessInterface>>): List<SiteClient> {
    val attachments = mutableMapOf<String, Attachment>()
    for ((node, ifaces) in wireless) {
        for (iface in ifaces.filter { it.mode == WirelessMode.AP }) {
            for (station in iface.stations) {
                val current = attachments[station.mac]
                if (current == null || (station.signal ?: Int.MIN_VALUE) > (current.signal ?: Int.MIN_VALUE)) {
                    attachments[station.mac] = Attachment(node, iface.ifname, station.signal)
                }
            }
        }
    }
    val leases = snapshots.values.flatMap { it.leases() }.associateBy { it.mac }
    val names = snapshots.values.fold(emptyMap<String, String>()) { acc, snapshot -> acc + snapshot.hostNames() }
    val blocked = snapshots.values.flatMap { it.blockedClients }.map { it.lowercase() }.toSet()
    val sightings = sightings(snapshots)
    val ownMacs = snapshots.values.flatMap { it.ownMacs() }.toSet()
    val online = snapshots.values.flatMap { snapshot ->
        val upstream = snapshot.upstreamDevices()
        val networkOf = snapshot.netInterfaces().filter { it.device != null }.associate { it.device to it.name }
        snapshot.neighborEntries().filter { it.live && it.device !in upstream }.map { it to networkOf[it.device] }
    }.associateBy { it.first.mac }
    return (attachments.keys + leases.keys + online.keys + blocked - ownMacs).map { mac ->
        val attachment = attachments[mac]
        val lease = leases[mac]
        SiteClient(
            mac = mac,
            name = names[mac] ?: lease?.hostname,
            ipv4 = lease?.ipv4 ?: online[mac]?.first?.ipv4,
            presence = when {
                attachment != null -> Presence.WIRELESS
                mac in online -> Presence.ONLINE_UNKNOWN_LINK
                lease != null -> Presence.LEASE_ONLY
                else -> Presence.OFFLINE
            },
            attachedTo = attachment?.node,
            ifname = attachment?.ifname,
            signal = attachment?.signal,
            blocked = mac in blocked,
            sightings = sightings[mac].orEmpty(),
            network = online[mac]?.second,
        )
    }.sortedWith(compareBy<SiteClient>({ it.presence }, { it.attachedTo }, { it.name ?: "\uFFFF" }, { it.mac }))
}

private fun sightings(snapshots: Map<String, Snapshot>): Map<String, List<Sighting>> {
    val nodeByAddress = snapshots.mapNotNull { (id, snapshot) -> snapshot.lanIpv4()?.let { it to id } }.toMap()
    val seen = mutableMapOf<String, MutableMap<Pair<String, String>, UsteerSighting>>()
    for ((id, snapshot) in snapshots) {
        for ((mac, list) in snapshot.usteerSightings()) {
            for (sighting in list) {
                val node = sighting.nodeAddress?.let { nodeByAddress[it] ?: continue } ?: id
                seen.getOrPut(mac) { mutableMapOf() }.putIfAbsent(node to sighting.apObject, sighting)
            }
        }
    }
    return seen.mapValues { (_, byAp) ->
        byAp.entries
            .groupBy({ it.key.first }, { it.value })
            .map { (node, list) -> Sighting(node, list.any { it.connected }, list.mapNotNull { it.signal }.maxOrNull()) }
            .sortedBy { it.nodeId }
    }
}
