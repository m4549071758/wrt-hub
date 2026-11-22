package wrthub.app.domain

import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
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

    /** Snapshots pushed as they arrive, for sites that offer them (hub sites); null for the others. */
    fun updates(site: SiteConfig): Flow<NodeUpdate>? = null
}

/**
 * Collects every node of a direct site in parallel; a failing node becomes [NodeState.Unreachable], the rest still
 * load. Hub sites load from their server through [hub]; when the server cannot be reached, every node of the site is
 * unreachable with that reason.
 */
class SiteLoader(private val hub: HubSiteLoader? = null, private val callerFor: (NodeConfig) -> UbusCaller) : SiteLoading {
    override suspend fun load(site: SiteConfig): List<NodeState> {
        if (site.hub != null) {
            val loader = requireNotNull(hub) { "hub mode is not available" }
            return try {
                loader.load(site)
            } catch (e: IOException) {
                site.nodes.map { NodeState.Unreachable(it, e.message ?: e.javaClass.simpleName) }
            }
        }
        return coroutineScope { site.nodes.map { node -> async { load(node) } }.awaitAll() }
    }

    override fun updates(site: SiteConfig): Flow<NodeUpdate>? = if (site.hub != null) hub?.updates(site) else null

    private suspend fun load(node: NodeConfig): NodeState =
        try {
            NodeState.Online(node, DirectCollector(callerFor(node)).collect())
        } catch (e: IOException) {
            NodeState.Unreachable(node, e.message ?: e.javaClass.simpleName)
        } catch (e: UbusException) {
            NodeState.Unreachable(node, e.message ?: "ubus status ${e.code}")
        }
}
