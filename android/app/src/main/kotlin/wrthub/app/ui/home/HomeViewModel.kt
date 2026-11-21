package wrthub.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
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
import wrthub.app.domain.CounterReading
import wrthub.app.domain.NodeState
import wrthub.app.domain.Rate
import wrthub.app.domain.RateMeter
import wrthub.app.domain.SiteLoading
import wrthub.core.model.wanDevice
import wrthub.core.ubus.UbusException

data class HomeUiState(
    val sites: List<SiteConfig> = emptyList(),
    val site: SiteConfig? = null,
    val nodes: List<NodeState> = emptyList(),
    val loading: Boolean = false,
    val rates: List<Rate> = emptyList(),
    val realtimeError: String? = null,
)

class HomeViewModel(
    private val repository: SiteRepository,
    private val loader: SiteLoading,
    private val counters: CounterReading,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutableState = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = mutableState.asStateFlow()

    private var meter = RateMeter()
    private var loadJob: Job? = null
    private var realtimeJob: Job? = null

    init {
        viewModelScope.launch {
            combine(repository.sites, repository.selectedSiteId) { sites, id -> sites to sites.firstOrNull { it.id == id } }
                .collect { (sites, site) ->
                    val previous = mutableState.value.site
                    mutableState.update { it.copy(sites = sites, site = site) }
                    if (site != previous) {
                        meter = RateMeter()
                        mutableState.update { it.copy(nodes = emptyList(), rates = emptyList(), realtimeError = null) }
                        refresh()
                    }
                }
        }
    }

    fun select(siteId: String) {
        viewModelScope.launch { repository.select(siteId) }
    }

    fun refresh() {
        val site = mutableState.value.site ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = true) }
            val nodes = loader.load(site)
            mutableState.update { it.copy(nodes = nodes, loading = false) }
        }
    }

    /** Polls the router's WAN counters every second while [active]; call with false when the screen is not visible. */
    fun setRealtime(active: Boolean) {
        realtimeJob?.cancel()
        realtimeJob = if (!active) {
            null
        } else {
            viewModelScope.launch {
                while (true) {
                    poll()
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
    }

    private suspend fun poll() {
        val router = mutableState.value.nodes.filterIsInstance<NodeState.Online>()
            .firstOrNull { it.snapshot.wanDevice() != null } ?: return
        val device = router.snapshot.wanDevice() ?: return
        try {
            val sample = counters.read(router.config)[device]
                ?: return mutableState.update { it.copy(realtimeError = "no counters for $device") }
            val rates = meter.add(clock(), sample)
            mutableState.update { it.copy(rates = rates, realtimeError = null) }
        } catch (e: IOException) {
            mutableState.update { it.copy(realtimeError = e.message ?: e.javaClass.simpleName) }
        } catch (e: UbusException) {
            mutableState.update { it.copy(realtimeError = e.message ?: "ubus status ${e.code}") }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1_000L
    }
}
