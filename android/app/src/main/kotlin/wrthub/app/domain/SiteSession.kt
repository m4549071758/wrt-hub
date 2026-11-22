package wrthub.app.domain

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
                    // Marking an AP edits the site too; only a different site or node list needs a reload.
                    if (site?.id != previous?.id || site?.nodes != previous?.nodes) {
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
        }
    }

    /** Refreshes now and every [intervalMs] until the returned job is cancelled. */
    fun keepFresh(intervalMs: Long): Job = scope.launch {
        while (true) {
            refresh()
            delay(intervalMs)
        }
    }
}
