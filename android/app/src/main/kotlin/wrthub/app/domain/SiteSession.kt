package wrthub.app.domain

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import wrthub.app.data.SiteConfig
import wrthub.app.data.SiteRepository

data class SiteState(
    val sites: List<SiteConfig> = emptyList(),
    val site: SiteConfig? = null,
    val nodes: List<NodeState> = emptyList(),
    val loading: Boolean = false,
)

/** The selected site's latest node states, shared by every tab so that one load serves all of them. */
class SiteSession(
    private val repository: SiteRepository,
    private val loader: SiteLoading,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(SiteState())
    val state: StateFlow<SiteState> = mutableState.asStateFlow()
    private var loadJob: Job? = null

    init {
        scope.launch {
            combine(repository.sites, repository.selectedSiteId) { sites, id -> sites to sites.firstOrNull { it.id == id } }
                .collect { (sites, site) ->
                    val previous = mutableState.value.site
                    mutableState.update { it.copy(sites = sites, site = site) }
                    // Marking an AP edits the site too; only a different site or node list needs a reload. A hub
                    // site's node list is written by its own loads, so a change there needs none.
                    if (site?.id != previous?.id || (site?.hub == null && site?.nodes != previous?.nodes)) {
                        mutableState.update { it.copy(nodes = emptyList()) }
                        refresh()
                    }
                }
        }
    }

    fun select(siteId: String) {
        scope.launch { repository.select(siteId) }
    }

    /** Marks [mac] in the selected site as an access point that is not a registered node, or unmarks it. */
    fun setUnmanagedAp(mac: String, isAp: Boolean) {
        val siteId = mutableState.value.site?.id ?: return
        scope.launch { repository.setUnmanagedAp(siteId, mac, isAp) }
    }

    fun refresh() {
        val site = mutableState.value.site ?: return
        loadJob?.cancel()
        loadJob = scope.launch {
            mutableState.update { it.copy(loading = true) }
            val nodes = loader.load(site)
            mutableState.update { it.copy(nodes = nodes, loading = false) }
            if (site.hub != null) syncHubNodes(site, nodes)
        }
    }

    /** Keeps a hub site's stored node list in step with the server's, so the other tabs list the same nodes. */
    private suspend fun syncHubNodes(site: SiteConfig, nodes: List<NodeState>) {
        val configs = nodes.map { it.config }
        // An unreachable server yields the stored nodes, so only an answer from it can change the list.
        if (configs != site.nodes) repository.upsertSite(site.copy(nodes = configs))
    }

    /**
     * Refreshes now and every [intervalMs] until the returned job is cancelled. On a hub site it also takes each
     * snapshot the server pushes in between; when that stream breaks, the periodic refresh carries on alone and the
     * stream is opened again at the next one.
     */
    fun keepFresh(intervalMs: Long): Job = scope.launch {
        var stream: Job? = null
        try {
            while (true) {
                refresh()
                val site = mutableState.value.site
                if (stream?.isActive != true && site != null) {
                    stream = loader.updates(site)?.let { updates ->
                        launch {
                            try {
                                updates.collect { update -> apply(site.id, update) }
                            } catch (e: IOException) {
                                // Reopened at the next refresh.
                            }
                        }
                    }
                }
                delay(intervalMs)
            }
        } finally {
            stream?.cancel()
        }
    }

    private fun apply(siteId: String, update: NodeUpdate) = mutableState.update { state ->
        if (state.site?.id != siteId) return@update state
        state.copy(nodes = state.nodes.map { node -> if (node.config.id == update.nodeId) NodeState.Online(node.config, update.snapshot) else node })
    }
}
