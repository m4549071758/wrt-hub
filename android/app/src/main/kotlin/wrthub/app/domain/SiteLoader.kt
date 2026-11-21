package wrthub.app.domain

import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import wrthub.app.data.NodeConfig
import wrthub.app.data.SiteConfig
import wrthub.core.direct.DirectCollector
import wrthub.core.snapshot.Snapshot
import wrthub.core.ubus.UbusCaller
import wrthub.core.ubus.UbusException

sealed interface NodeState {
    val config: NodeConfig

    data class Online(override val config: NodeConfig, val snapshot: Snapshot) : NodeState

    data class Unreachable(override val config: NodeConfig, val reason: String) : NodeState
}

fun interface SiteLoading {
    suspend fun load(site: SiteConfig): List<NodeState>
}

/** Collects every node of a site in parallel; a failing node becomes [NodeState.Unreachable], the rest still load. */
class SiteLoader(private val callerFor: (NodeConfig) -> UbusCaller) : SiteLoading {
    override suspend fun load(site: SiteConfig): List<NodeState> = coroutineScope {
        site.nodes.map { node -> async { load(node) } }.awaitAll()
    }

    private suspend fun load(node: NodeConfig): NodeState =
        try {
            NodeState.Online(node, DirectCollector(callerFor(node)).collect())
        } catch (e: IOException) {
            NodeState.Unreachable(node, e.message ?: e.javaClass.simpleName)
        } catch (e: UbusException) {
            NodeState.Unreachable(node, e.message ?: "ubus status ${e.code}")
        }
}
