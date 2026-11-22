package wrthub.app.ui.hub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import wrthub.app.data.HubLink
import wrthub.app.data.SiteConfig
import wrthub.app.data.SiteRepository
import wrthub.core.hub.HubClient
import wrthub.core.hub.HubEvent
import wrthub.core.hub.StarlinkData

/** One line of a site's history, with the node's label resolved. */
data class EventItem(val id: Long, val kind: String, val nodeLabel: String?, val newDevices: Int, val at: Long)

data class EventsUiState(
    val siteName: String = "",
    val events: List<EventItem> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

private suspend fun hubSite(repository: SiteRepository, siteId: String): Pair<SiteConfig, HubLink> {
    val site = repository.sites.first().firstOrNull { it.id == siteId } ?: throw IOException("the site was removed")
    return site to (site.hub ?: throw IOException("${site.name} is not a hub site"))
}

private fun newDevices(event: HubEvent): Int {
    val detail = event.detail as? JsonObject ?: return 0
    return (detail["macs"] as? JsonArray)?.size ?: 0
}

/** The server's recent events of a hub site: nodes going silent and back, WAN loss, new devices. */
class EventsViewModel(
    private val siteId: String,
    private val repository: SiteRepository,
    private val hubs: (HubLink) -> HubClient,
) : ViewModel() {
    private val mutableState = MutableStateFlow(EventsUiState())
    val state: StateFlow<EventsUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val (site, link) = hubSite(repository, siteId)
                val labels = site.nodes.associate { it.id to it.label }
                val events = hubs(link).events(link.siteId, EVENT_LIMIT).map { event ->
                    EventItem(event.id, event.kind, event.nodeId?.let { labels[it] ?: it }, newDevices(event), event.createdAt)
                }
                mutableState.update { it.copy(siteName = site.name, events = events, loading = false) }
            } catch (e: IOException) {
                mutableState.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private companion object {
        const val EVENT_LIMIT = 100
    }
}

enum class StarlinkRange(val query: String) { M15("15m"), H1("1h"), H6("6h"), H24("24h") }

data class StarlinkUiState(
    val siteName: String = "",
    val range: StarlinkRange = StarlinkRange.H1,
    val data: StarlinkData? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

/** The dish's history from the server (it reads the exporter's InfluxDB), over a chosen range. */
class StarlinkViewModel(
    private val siteId: String,
    private val repository: SiteRepository,
    private val hubs: (HubLink) -> HubClient,
) : ViewModel() {
    private val mutableState = MutableStateFlow(StarlinkUiState())
    val state: StateFlow<StarlinkUiState> = mutableState.asStateFlow()
    private var loadJob: Job? = null

    init {
        load()
    }

    fun select(range: StarlinkRange) {
        if (range == mutableState.value.range) return
        mutableState.update { it.copy(range = range) }
        load()
    }

    fun load() {
        val range = mutableState.value.range
        loadJob?.cancel()
        mutableState.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val (site, link) = hubSite(repository, siteId)
                val data = hubs(link).starlink(range.query)
                mutableState.update { it.copy(siteName = site.name, data = data, loading = false) }
            } catch (e: IOException) {
                mutableState.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }
}
