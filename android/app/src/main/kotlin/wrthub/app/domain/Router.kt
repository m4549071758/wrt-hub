package wrthub.app.domain

import wrthub.core.admin.DhcpConfig
import wrthub.core.admin.parseDhcpConfig
import wrthub.core.model.wanDevice
import wrthub.core.topology.SiteTopology
import wrthub.core.topology.buildTopology

/** The reachable node with an up WAN: it owns the site's firewall and WAN counters. */
fun routerOf(nodes: List<NodeState>): NodeState.Online? =
    nodes.filterIsInstance<NodeState.Online>().firstOrNull { it.snapshot.wanDevice() != null }

fun siteTopology(nodes: List<NodeState>): SiteTopology =
    buildTopology(nodes.filterIsInstance<NodeState.Online>().associate { it.config.id to it.snapshot })

/** The reachable node that serves DHCP, with its config; static leases are edited there. */
fun dhcpServerOf(nodes: List<NodeState>): Pair<NodeState.Online, DhcpConfig>? =
    nodes.filterIsInstance<NodeState.Online>().firstNotNullOfOrNull { node ->
        node.snapshot.dhcpConfig?.let(::parseDhcpConfig)?.takeIf { it.servesDhcp }?.let { node to it }
    }
