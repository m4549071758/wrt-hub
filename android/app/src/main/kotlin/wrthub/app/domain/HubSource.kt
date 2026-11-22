package wrthub.app.domain

import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import wrthub.app.data.HubLink
import wrthub.app.data.NodeConfig
import wrthub.app.data.SiteConfig
import wrthub.core.direct.DirectOperations
import wrthub.core.hub.HubClient
import wrthub.core.hub.HubNodeSnapshot
import wrthub.core.model.DeviceCounters
import wrthub.core.snapshot.Snapshot
import wrthub.core.snapshot.decodeSnapshot
import wrthub.core.ubus.UbusException

/** A hub node's config: it is reached through the server, so only its id and label matter. */
fun hubNodeConfig(link: HubLink, id: String, label: String) = NodeConfig(id = id, label = label, url = link.serverUrl, username = "", passwordCipher = "")

private fun decode(snapshot: JsonObject): Snapshot = decodeSnapshot(snapshot.toString())

/**
 * Node states from the server's latest snapshots. A stale snapshot still shows (it is only late); a node silent for
 * long, or not heard from since the server started, is unreachable.
 */
class HubSiteLoader(private val hubs: (HubLink) -> HubClient) {
    suspend fun load(site: SiteConfig): List<NodeState> {
        val link = requireNotNull(site.hub) { "${site.name} is not a hub site" }
        return hubs(link).snapshots(link.siteId).nodes.map { node -> state(link, node) }
    }

    /** Each snapshot the server receives for the site, as it arrives. */
    fun updates(site: SiteConfig): Flow<NodeUpdate> {
        val link = requireNotNull(site.hub) { "${site.name} is not a hub site" }
        return hubs(link).stream(link.siteId).map { NodeUpdate(it.nodeId, decode(it.snapshot)) }
    }

    private fun state(link: HubLink, node: HubNodeSnapshot): NodeState {
        val config = hubNodeConfig(link, node.id, node.label)
        val snapshot = node.snapshot
        return when {
            snapshot == null || node.state == "never" -> NodeState.Unreachable(config, "no snapshot from this node yet")
            node.state == "offline" -> NodeState.Unreachable(config, "the node has stopped reporting")
            else -> try {
                NodeState.Online(config, decode(snapshot))
            } catch (e: IllegalArgumentException) {
                NodeState.Unreachable(config, e.message ?: "unreadable snapshot")
            }
        }
    }
}

data class NodeUpdate(val nodeId: String, val snapshot: Snapshot)

/** Direct-mode operations over the relayed ubus; only reboot is the agent's own command (the relay refuses it). */
class HubNodeOperations(
    private val relayed: NodeOperations,
    private val hub: HubClient,
    private val nodeId: String,
) : NodeOperations by relayed {
    override suspend fun reboot() {
        hub.run(nodeId, "reboot")
    }

    companion object {
        fun create(hub: HubClient, nodeId: String, caller: wrthub.core.hub.HubUbusCaller): HubNodeOperations =
            HubNodeOperations(DirectNodeOperations(DirectOperations(caller)), hub, nodeId)
    }
}

/**
 * Live rates through the hub: the counters of the node's latest snapshot. While someone reads, the node is kept in
 * fast mode (a snapshot every second), renewing its 60-second lease every [leaseRenewMs].
 */
class HubCounterReader(
    private val hubFor: (NodeConfig) -> HubClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val leaseRenewMs: Long = 45_000,
) : CounterReading {
    private val renewedAt = mutableMapOf<String, Long>()

    override suspend fun read(node: NodeConfig): Map<String, DeviceCounters> {
        val hub = hubFor(node)
        val now = clock()
        if (now - (renewedAt[node.id] ?: Long.MIN_VALUE / 2) >= leaseRenewMs) {
            try {
                hub.run(node.id, "set_fast_mode")
                renewedAt[node.id] = now
            } catch (e: IOException) {
                // Rates still come, every 10 seconds instead of every second.
            }
        }
        val latest = hub.counters(node.id).lastOrNull() ?: throw UbusException(UbusException.NO_DATA, "no counters from ${node.label} yet")
        return latest.devices.mapValues { (_, c) -> DeviceCounters(c.rx, c.tx) }
    }
}
