package wrthub.app.ui.nodes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import wrthub.app.data.SiteConfig
import wrthub.app.data.SiteRepository
import wrthub.app.domain.NodeState
import wrthub.app.domain.SiteSession

data class NodesUiState(
    val sites: List<SiteConfig> = emptyList(),
    /** Live state of the nodes of the loaded site; nodes of other sites are absent. */
    val states: Map<String, NodeState> = emptyMap(),
)

/** Every site with its nodes, for opening a node and for registering or removing sites and nodes. */
class NodesViewModel(private val repository: SiteRepository, private val session: SiteSession) : ViewModel() {
    val state: StateFlow<NodesUiState> = combine(repository.sites, session.state) { sites, site ->
        NodesUiState(sites, site.nodes.associateBy { it.config.id })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NodesUiState())

    /** Opening a node of another site loads that site first, so the node's pages have its state. */
    fun selectSiteOf(siteId: String) {
        if (session.state.value.site?.id != siteId) session.select(siteId)
    }

    fun removeSite(id: String) {
        viewModelScope.launch { repository.removeSite(id) }
    }

    fun removeNode(siteId: String, nodeId: String) {
        viewModelScope.launch { repository.removeNode(siteId, nodeId) }
    }
}
