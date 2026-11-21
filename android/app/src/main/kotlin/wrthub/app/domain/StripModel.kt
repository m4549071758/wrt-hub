package wrthub.app.domain

import wrthub.core.model.netInterfaces
import wrthub.core.model.wanDevice
import wrthub.core.topology.buildTopology

data class StripNode(val id: String, val label: String, val online: Boolean)

/** [downKbps] is router → node, [upKbps] node → router, both expected throughput; [signal] is the weaker side. */
data class StripLink(val downKbps: Long?, val upKbps: Long?, val signal: Int?)

data class StripSegment(val link: StripLink?, val node: StripNode)

data class StripModel(val wanAddress: String?, val wanUp: Boolean, val router: StripNode?, val segments: List<StripSegment>)

/** Internet → router → every other node, with the mesh link to the router when there is one. */
fun stripModel(nodes: List<NodeState>): StripModel {
    val online = nodes.filterIsInstance<NodeState.Online>()
    val router = online.firstOrNull { it.snapshot.wanDevice() != null }
    val routerId = router?.config?.id
    val wan = router?.snapshot?.netInterfaces()?.firstOrNull { it.name == "wan" }
    val links = buildTopology(online.associate { it.config.id to it.snapshot }).links
    return StripModel(
        wanAddress = wan?.ipv4?.firstOrNull(),
        wanUp = wan?.up == true,
        router = router?.let { StripNode(it.config.id, it.config.label, true) },
        segments = nodes.filter { it.config.id != routerId }.map { node ->
            val id = node.config.id
            val link = links.firstOrNull { setOf(it.a, it.b) == setOf(id, routerId) }
            val fromRouter = link?.let { if (it.a == routerId) it.aToB else it.bToA }
            val toRouter = link?.let { if (it.a == routerId) it.bToA else it.aToB }
            StripSegment(
                link = link?.let {
                    StripLink(
                        downKbps = fromRouter?.expectedThroughputKbps,
                        upKbps = toRouter?.expectedThroughputKbps,
                        signal = listOfNotNull(fromRouter?.signalAvg, toRouter?.signalAvg).minOrNull(),
                    )
                },
                node = StripNode(id, node.config.label, node is NodeState.Online),
            )
        },
    )
}
