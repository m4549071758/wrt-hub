package wrthub.app.ui.mesh

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import wrthub.app.data.SiteConfig
import wrthub.app.domain.NodeState
import wrthub.app.domain.SiteSession
import wrthub.app.domain.siteTopology
import wrthub.app.domain.routerOf
import wrthub.core.metrics.ewma
import wrthub.core.topology.SiteClient
import wrthub.core.topology.Presence

/**
 * [clients] are the devices under this node: Wi-Fi clients associated with its APs and, on the router, the devices
 * that are on the LAN without an association to any registered AP (wired, or behind a non-OpenWrt AP). Wired
 * devices behind other nodes cannot be told apart because LuCI's ACL does not expose the bridge forwarding table,
 * so they appear under the router.
 */
data class MeshNode(
    val id: String,
    val label: String,
    val online: Boolean,
    val clients: List<SiteClient>,
    val kind: MeshNodeKind = MeshNodeKind.NODE,
)

/** NETWORK: one of the router's LANs, standing between the router and the devices seen on it. */
enum class MeshNodeKind { NODE, NETWORK }

/** Expected throughput per direction, smoothed across loads; [signal] is the weaker side's average. */
data class MeshEdge(val a: String, val b: String, val aToBKbps: Double?, val bToAKbps: Double?, val signal: Int?)

sealed interface MeshSelection {
    data class Node(val id: String) : MeshSelection

    data class Edge(val a: String, val b: String) : MeshSelection

    data class Client(val mac: String) : MeshSelection
}

data class MeshUiState(
    val site: SiteConfig? = null,
    val nodes: List<MeshNode> = emptyList(),
    val edges: List<MeshEdge> = emptyList(),
    val selection: MeshSelection? = null,
    val routerId: String? = null,
    /** Devices that only left a DHCP lease; drawn as a count instead of nodes. */
    val departed: Int = 0,
    /** MACs the user marked as access points that are not registered nodes. */
    val unmanagedAps: Set<String> = emptySet(),
)

private data class Graph(
    val nodes: List<MeshNode> = emptyList(),
    val edges: List<MeshEdge> = emptyList(),
    val routerId: String? = null,
    val departed: Int = 0,
)

class MeshViewModel(private val session: SiteSession, private val refreshIntervalMs: Long = 10_000) : ViewModel() {
    private val selection = MutableStateFlow<MeshSelection?>(null)
    private val smoothed = mutableMapOf<Pair<String, String>, Double>()
    private var smoothedSiteId: String? = null
    private var lastNodes: List<NodeState>? = null
    private var lastGraph = Graph()
    private var liveJob: Job? = null

    val state: StateFlow<MeshUiState> = combine(session.state, selection) { site, selected ->
        val graph = graph(site.site?.id, site.nodes)
        val aps = site.site?.unmanagedAps.orEmpty()
        MeshUiState(site.site, splitByNetwork(graph.nodes, graph.routerId, aps), graph.edges, selected, graph.routerId, graph.departed, aps)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MeshUiState())

    fun select(selection: MeshSelection?) {
        this.selection.value = selection
    }

    fun setUnmanagedAp(mac: String, isAp: Boolean) = session.setUnmanagedAp(mac, isAp)

    /** While [active], reloads the site every [refreshIntervalMs] so links and clients stay current. */
    fun setLive(active: Boolean) {
        liveJob?.cancel()
        liveJob = if (active) session.keepFresh(refreshIntervalMs) else null
    }

    override fun onCleared() {
        liveJob?.cancel()
    }

    /** Smooths once per load: [nodes] is a new list instance exactly when the session finished a load. */
    private fun graph(siteId: String?, nodes: List<NodeState>): Graph {
        if (nodes === lastNodes) return lastGraph
        lastNodes = nodes
        if (siteId != smoothedSiteId) {
            smoothed.clear()
            smoothedSiteId = siteId
        }
        val topology = siteTopology(nodes)
        for (link in topology.links) {
            link.aToB?.expectedThroughputKbps?.let { smoothed[link.a to link.b] = ewma(smoothed[link.a to link.b], it.toDouble(), ALPHA) }
            link.bToA?.expectedThroughputKbps?.let { smoothed[link.b to link.a] = ewma(smoothed[link.b to link.a], it.toDouble(), ALPHA) }
        }
        val routerId = routerOf(nodes)?.config?.id
        val meshNodes = nodes.map { node ->
            MeshNode(
                id = node.config.id,
                label = node.config.label,
                online = node is NodeState.Online,
                clients = topology.clients.filter {
                    it.attachedTo == node.config.id || (node.config.id == routerId && it.presence == Presence.ONLINE_UNKNOWN_LINK)
                },
            )
        }
        val edges = topology.links.map { link ->
            MeshEdge(
                a = link.a,
                b = link.b,
                aToBKbps = smoothed[link.a to link.b],
                bToAKbps = smoothed[link.b to link.a],
                signal = listOfNotNull(link.aToB?.signalAvg, link.bToA?.signalAvg).minOrNull(),
            )
        }
        lastGraph = Graph(meshNodes, edges, routerId, topology.clients.count { it.presence == Presence.LEASE_ONLY })
        return lastGraph
    }

    private companion object {
        const val ALPHA = 0.3
    }
}

fun networkNodeId(network: String) = "network:$network"

/**
 * Moves the router's devices that no registered AP serves under one [MeshNodeKind.NETWORK] node per LAN they were
 * seen on, with the devices marked in [aps] first. Only done when it adds something: two or more LANs, or a marked
 * AP; otherwise [nodes] come back unchanged. Devices whose LAN is unknown stay under the router.
 */
fun splitByNetwork(nodes: List<MeshNode>, routerId: String?, aps: Set<String>): List<MeshNode> {
    val router = nodes.firstOrNull { it.id == routerId } ?: return nodes
    val unattached = router.clients.filter { it.presence == Presence.ONLINE_UNKNOWN_LINK && it.network != null }
    val groups = unattached.groupBy { it.network.orEmpty() }
    if (groups.size < 2 && unattached.none { it.mac in aps }) return nodes
    val networks = groups.map { (network, clients) ->
        MeshNode(networkNodeId(network), network, true, clients.sortedBy { it.mac !in aps }, MeshNodeKind.NETWORK)
    }
    val moved = unattached.map { it.mac }.toSet()
    return nodes.map { node -> if (node.id == routerId) node.copy(clients = node.clients.filter { it.mac !in moved }) else node } + networks
}
