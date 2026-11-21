package wrthub.app.ui.sites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import wrthub.app.data.SiteConfig
import wrthub.app.data.SiteRepository

class SitesViewModel(private val repository: SiteRepository) : ViewModel() {
    val sites: StateFlow<List<SiteConfig>> =
        repository.sites.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun removeSite(id: String) {
        viewModelScope.launch { repository.removeSite(id) }
    }

    fun removeNode(siteId: String, nodeId: String) {
        viewModelScope.launch { repository.removeNode(siteId, nodeId) }
    }
}
