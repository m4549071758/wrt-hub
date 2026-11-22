package wrthub.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import wrthub.app.data.SiteConfig
import wrthub.app.domain.CounterReading
import wrthub.app.domain.NodeState
import wrthub.app.domain.Rate
import wrthub.app.domain.RateMeter
import wrthub.app.domain.SiteSession
import wrthub.app.domain.routerOf
import wrthub.core.model.DeviceCounters
import wrthub.core.model.upstreamTrafficDevices
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
    private val session: SiteSession,
    private val counters: CounterReading,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private data class Realtime(val siteId: String? = null, val rates: List<Rate> = emptyList(), val error: String? = null)

    private val realtime = MutableStateFlow(Realtime())
    private var meter = RateMeter()
    private var realtimeJob: Job? = null

    /** For a hub site, the session's live updates (pushed snapshots plus a periodic reload) while the home shows. */
    private var liveJob: Job? = null
    private var liveSiteId: String? = null

    val state: StateFlow<HomeUiState> = combine(session.state, realtime) { site, live ->
        val current = live.siteId == site.site?.id
        HomeUiState(
            sites = site.sites,
            site = site.site,
            nodes = site.nodes,
            loading = site.loading,
            rates = if (current) live.rates else emptyList(),
            realtimeError = if (current) live.error else null,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())

    fun select(siteId: String) = session.select(siteId)

    fun refresh() = session.refresh()

    /** Polls the router's upstream counters every second while [active]; call with false when the screen is not visible. */
    fun setRealtime(active: Boolean) {
        realtimeJob?.cancel()
        liveJob?.cancel()
        liveJob = null
        liveSiteId = null
        realtimeJob = if (!active) {
            null
        } else {
            viewModelScope.launch {
                while (true) {
                    followHubSite()
                    poll()
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
    }

    /** Starts or stops the session's live updates as the selected site turns into a hub site or stops being one. */
    private fun followHubSite() {
        val site = session.state.value.site
        val wanted = site?.takeIf { it.hub != null }?.id
        if (wanted == liveSiteId) return
        liveJob?.cancel()
        liveSiteId = wanted
        liveJob = wanted?.let { session.keepFresh(HUB_RELOAD_MS) }
    }

    override fun onCleared() {
        liveJob?.cancel()
    }

    private suspend fun poll() {
        val current = session.state.value
        val siteId = current.site?.id ?: return
        if (realtime.value.siteId != siteId) {
            meter = RateMeter()
            realtime.value = Realtime(siteId)
        }
        val router = routerOf(current.nodes) ?: return
        val devices = router.snapshot.upstreamTrafficDevices().ifEmpty { return }
        try {
            val all = counters.read(router.config)
            val samples = devices.mapNotNull { all[it] }
            if (samples.isEmpty()) return realtime.update { it.copy(error = "no counters for ${devices.joinToString()}") }
            // Every upstream line together: the figure is the site's internet traffic, whichever line it took.
            val sum = DeviceCounters(samples.sumOf { it.rxBytes }, samples.sumOf { it.txBytes })
            val rates = meter.add(clock(), sum)
            realtime.update { it.copy(rates = rates, error = null) }
        } catch (e: IOException) {
            realtime.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
        } catch (e: UbusException) {
            realtime.update { it.copy(error = e.message ?: "ubus status ${e.code}") }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1_000L

        /** Pushed snapshots keep a hub site current; the reload only notices nodes that went silent. */
        const val HUB_RELOAD_MS = 30_000L
    }
}
